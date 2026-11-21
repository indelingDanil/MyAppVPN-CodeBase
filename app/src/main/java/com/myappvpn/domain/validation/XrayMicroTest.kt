package com.myappvpn.domain.validation

import android.content.Context
import android.util.Log
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.service.LocalProxyAuth
import com.myappvpn.util.BinaryInstaller
import com.myappvpn.util.XrayConfigBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class MicroTestResult(
    val serverId: String,
    val success: Boolean,
    val latencyMs: Int = 0,
    val errorReason: String? = null,
)

@Singleton   // one shared semaphore for ALL callers (batch, background probe, re-verify)
class XrayMicroTest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    companion object {
        private const val TAG = "XrayMicroTest"
        const val TEST_TIMEOUT_MS = 8000L
        const val MAX_PARALLEL_TESTS = 4
        const val XRAY_STARTUP_WAIT_MS = 1200L
        private const val TEST_URL = "https://www.google.com/generate_204"
    }

    private val semaphore = Semaphore(MAX_PARALLEL_TESTS)

    /**
     * Starts a temporary XRay instance, routes a single HTTP HEAD through it.
     * Returns true if the request succeeds (2xx/3xx).
     * Always cleans up the XRay process. Each test gets a random free loopback port (no fixed,
     * guessable port another app could pre-bind) and random SOCKS credentials, registered with
     * [LocalProxyAuth] so the java.net SOCKS client authenticates for that port only.
     */
    suspend fun test(server: ServerProfile): MicroTestResult = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            val testPort = try {
                LocalProxyAuth.pickFreeLoopbackPorts(1).first()
            } catch (e: Exception) {
                return@withContext MicroTestResult(server.id, false, errorReason = "no_free_port")
            }
            val (testUser, testPass) = LocalProxyAuth.randomCredentials()
            var process: Process? = null
            val configFile = File(context.filesDir, "xray_test_${testPort}_${UUID.randomUUID()}.json")
            try {
                val vlessConfig = VlessUriParser.parse(server.vlessUri)
                    ?: return@withContext MicroTestResult(server.id, false, errorReason = "parse_failed")

                val baseJson = XrayConfigBuilder.build(
                    vlessConfig, socksPort = testPort,
                    socksAccount = testUser to testPass,
                )
                // Test the server the way the tunnel will dial it: with "Обход DPI" on, a server
                // reachable only via a fragmented ClientHello must pass (and vice versa).
                val fragment = preferences.tlsFragmentEnabled.first()   // flow already catches IO errors
                val xrayJson = if (fragment) XrayConfigBuilder.applyTlsFragment(baseJson, true) else baseJson
                configFile.writeText(xrayJson)
                LocalProxyAuth.registerTestPort(testPort, testUser, testPass)

                val xrayBin = BinaryInstaller.getXrayBinaryPath(context)
                val pb = ProcessBuilder(xrayBin, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                pb.environment()["xray.location.asset"] = context.filesDir.absolutePath
                val proc = pb.start()
                process = proc

                // Drain stdout in background to prevent buffer deadlock
                Thread {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { /* discard */ }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; start() }

                delay(XRAY_STARTUP_WAIT_MS)

                if (!proc.isAlive) {
                    return@withContext MicroTestResult(server.id, false, errorReason = "xray_crashed")
                }

                // Route HTTP HEAD through the temporary SOCKS proxy
                val start = System.currentTimeMillis()
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", testPort))
                val conn = URL(TEST_URL).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = TEST_TIMEOUT_MS.toInt()
                conn.readTimeout = TEST_TIMEOUT_MS.toInt()
                conn.requestMethod = "HEAD"
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                conn.disconnect()
                val elapsed = (System.currentTimeMillis() - start).toInt()

                if (code in 200..399) {
                    Log.d(TAG, "Micro-test OK for ${server.name}: ${elapsed}ms, HTTP $code")
                    MicroTestResult(server.id, true, elapsed)
                } else {
                    Log.w(TAG, "Micro-test failed for ${server.name}: HTTP $code")
                    MicroTestResult(server.id, false, elapsed, "http_$code")
                }
            } catch (e: CancellationException) {
                throw e   // process is destroyed in finally
            } catch (e: Exception) {
                Log.w(TAG, "Micro-test error for ${server.name}: ${e.message}")
                MicroTestResult(server.id, false, errorReason = e.message)
            } finally {
                process?.let {
                    try {
                        it.destroy()
                        if (!it.waitFor(2, TimeUnit.SECONDS)) {
                            it.destroyForcibly()
                            it.waitFor(1, TimeUnit.SECONDS)
                        }
                    } catch (_: Exception) {}
                }
                configFile.delete()
                LocalProxyAuth.unregisterTestPort(testPort)
            }
        }
    }

    /**
     * Tests multiple servers in parallel (up to MAX_PARALLEL_TESTS).
     * Runs in groups of MAX_PARALLEL_TESTS and awaits the WHOLE group before the next one;
     * stops launching new groups once [targetCount] servers pass.
     */
    // Losing tests of firstSuccess() finish here in the background: their blocking HTTP call
    // can't be interrupted, and waiting for them is exactly what made Auto connect take ~30 s.
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    /**
     * Tests [servers] (up to [MAX_PARALLEL_TESTS] at a time, in order) and returns the FIRST
     * success as soon as it arrives — the first to answer is effectively the fastest. Remaining
     * tests keep running in the background (each cleans up its own Xray) but don't delay us.
     * Null if none succeeded.
     */
    suspend fun firstSuccess(servers: List<ServerProfile>): MicroTestResult? {
        if (servers.isEmpty()) return null
        val winner = kotlinx.coroutines.CompletableDeferred<MicroTestResult?>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(servers.size)
        val jobs = servers.map { server ->
            backgroundScope.launch {
                try {
                    if (winner.isCompleted) return@launch
                    val r = test(server)   // test() is itself limited by [semaphore]
                    if (r.success) winner.complete(r)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "firstSuccess: test error ${e.message}")
                } finally {
                    if (remaining.decrementAndGet() == 0) winner.complete(null)
                }
            }
        }
        return try {
            winner.await().also {
                // Tests still queued on the semaphore are pointless now — cancel them. Ones
                // already running finish their blocking request, then clean up in finally.
                jobs.forEach { j -> j.cancel() }
            }
        } catch (e: CancellationException) {
            jobs.forEach { it.cancel() }   // queued ones won't start; running ones clean up
            throw e
        }
    }

    suspend fun testBatch(
        servers: List<ServerProfile>,
        targetCount: Int = 3
    ): List<MicroTestResult> = withContext(Dispatchers.IO) {
        val results = java.util.concurrent.CopyOnWriteArrayList<MicroTestResult>()
        val verified = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            for (group in servers.chunked(MAX_PARALLEL_TESTS)) {
                if (verified.get() >= targetCount) break
                group.map { server ->
                    async {
                        if (verified.get() >= targetCount) return@async null
                        val result = test(server)
                        results.add(result)
                        if (result.success) verified.incrementAndGet()
                        result
                    }
                }.awaitAll()
            }
        }

        results.toList()
    }
}
