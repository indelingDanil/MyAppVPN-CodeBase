package com.myappvpn.domain.validation

import android.content.Context
import android.util.Log
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.util.BinaryInstaller
import com.myappvpn.util.XrayConfigBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.inject.Inject

data class MicroTestResult(
    val serverId: String,
    val success: Boolean,
    val latencyMs: Int = 0,
    val errorReason: String? = null,
)

class XrayMicroTest @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "XrayMicroTest"
        const val TEST_SOCKS_PORT_BASE = 10900
        const val TEST_TIMEOUT_MS = 8000L
        const val MAX_PARALLEL_TESTS = 4
        const val XRAY_STARTUP_WAIT_MS = 1200L
        private const val TEST_URL = "https://www.google.com/generate_204"
    }

    private val semaphore = Semaphore(MAX_PARALLEL_TESTS)

    /**
     * Starts a temporary XRay instance, routes a single HTTP HEAD through it.
     * Returns true if the request succeeds (2xx/3xx).
     * Always cleans up the XRay process.
     */
    suspend fun test(server: ServerProfile, testPort: Int): MicroTestResult = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            var process: Process? = null
            val configFile = File(context.filesDir, "xray_test_${testPort}.json")
            try {
                val vlessConfig = VlessUriParser.parse(server.vlessUri)
                    ?: return@withContext MicroTestResult(server.id, false, errorReason = "parse_failed")

                val xrayJson = XrayConfigBuilder.build(vlessConfig, socksPort = testPort, listenOnAllInterfaces = false)
                configFile.writeText(xrayJson)

                val xrayBin = BinaryInstaller.getXrayBinaryPath(context)
                val pb = ProcessBuilder(xrayBin, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                pb.environment()["xray.location.asset"] = context.filesDir.absolutePath
                process = pb.start()

                // Drain stdout in background to prevent buffer deadlock
                val logThread = Thread {
                    try {
                        process?.inputStream?.bufferedReader()?.forEachLine { /* discard */ }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true; start() }

                delay(XRAY_STARTUP_WAIT_MS)

                if (process?.isAlive != true) {
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
            } catch (e: Exception) {
                Log.w(TAG, "Micro-test error for ${server.name}: ${e.message}")
                MicroTestResult(server.id, false, errorReason = e.message)
            } finally {
                process?.let {
                    try { it.destroy(); it.waitFor() } catch (_: Exception) {}
                }
                configFile.delete()
            }
        }
    }

    /**
     * Tests multiple servers in parallel (up to MAX_PARALLEL_TESTS).
     * Stops launching new tests once [targetCount] servers pass.
     */
    suspend fun testBatch(
        servers: List<ServerProfile>,
        targetCount: Int = 3
    ): List<MicroTestResult> = withContext(Dispatchers.IO) {
        val results = java.util.concurrent.CopyOnWriteArrayList<MicroTestResult>()
        val verified = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            for ((index, server) in servers.withIndex()) {
                if (verified.get() >= targetCount) break

                val port = TEST_SOCKS_PORT_BASE + (index % MAX_PARALLEL_TESTS)
                val deferred = async {
                    if (verified.get() >= targetCount) return@async null
                    val result = test(server, port)
                    results.add(result)
                    if (result.success) verified.incrementAndGet()
                    result
                }
                // Launch in batches of MAX_PARALLEL_TESTS
                if ((index + 1) % MAX_PARALLEL_TESTS == 0 || index == servers.lastIndex) {
                    deferred.await()
                }
            }
        }

        results.toList()
    }
}
