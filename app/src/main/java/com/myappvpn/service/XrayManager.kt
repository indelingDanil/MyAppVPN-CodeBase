package com.myappvpn.service

import android.content.Context
import android.util.Log
import com.myappvpn.util.BinaryInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class XrayManager(private val context: Context) {

    companion object {
        private const val TAG = "XrayManager"
        private const val CONFIG_FILE = "xray_config.json"
        private const val STARTUP_WAIT_MS = 800L
        private const val STALE_TEST_CONFIG_MS = 60_000L
        // XRay log keywords that indicate VLESS outbound failures
        private val ERROR_KEYWORDS = listOf("failed", "timeout", "rejected", "connection refused")
    }

    @Volatile private var process: Process? = null
    private val _recentErrorCount = AtomicInteger(0)
    // Single error-rate reset thread for the current process; interrupted on stop() so
    // repeated start()s don't pile up sleeping threads.
    @Volatile private var errorResetThread: Thread? = null

    val isRunning: Boolean get() = process?.isAlive == true

    /** Number of VLESS-level errors logged by XRay in the last ~60s. */
    val errorRate: Int get() = _recentErrorCount.get()

    /**
     * Start xray with given JSON config.
     * Returns true if started successfully.
     */
    /** Config-load error line from the last start() (null if none) — lets the caller find a bad outbound. */
    @Volatile var lastStartError: String? = null
        private set

    suspend fun start(xrayConfigJson: String): Boolean = withContext(Dispatchers.IO) {
        stop() // ensure clean state
        lastStartError = null

        try {
            val xrayBin = BinaryInstaller.getXrayBinaryPath(context)
            val configFile = writeConfig(xrayConfigJson)

            val pb = ProcessBuilder(xrayBin, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)

            // Set env so xray can find asset files
            pb.environment()["xray.location.asset"] = context.filesDir.absolutePath

            val proc = pb.start()
            process = proc

            // Read xray logs in background; count VLESS error lines.
            // Never forward raw xray output to logcat in release — the lines contain the
            // exit server address/port and would leak connection details via adb/bugreport.
            _recentErrorCount.set(0)
            proc.inputStream.bufferedReader().let { reader ->
                Thread {
                    try {
                        reader.forEachLine { line ->
                            val lower = line.lowercase()
                            if (lower.contains("failed to load config") || lower.contains("failed to build")) {
                                lastStartError = line
                            }
                            val isError = ERROR_KEYWORDS.any { lower.contains(it) }
                            if (isError) _recentErrorCount.incrementAndGet()
                            if (com.myappvpn.BuildConfig.DEBUG) {
                                if (isError) Log.w(TAG, line) else Log.d(TAG, line)
                            }
                        }
                    } catch (e: Exception) { /* process ended */ }
                }.also { it.isDaemon = true }.start()
            }

            // Reset error counter every 60s so transient errors don't linger.
            // Bound to THIS process; stop() interrupts it (one reset thread at a time).
            errorResetThread?.interrupt()
            errorResetThread = Thread {
                try {
                    while (proc.isAlive) {
                        Thread.sleep(60_000L)
                        _recentErrorCount.set(0)
                    }
                } catch (_: InterruptedException) {}
            }.also { it.isDaemon = true; it.start() }

            // Wait a bit for xray to start listening
            delay(STARTUP_WAIT_MS)

            val alive = proc.isAlive
            if (alive) Log.i(TAG, "XRay started successfully")
            else Log.e(TAG, "XRay process died immediately")
            alive
        } catch (e: CancellationException) {
            // Cancelled mid-start (e.g. user hit Disconnect) — don't leave an orphan xray behind.
            stop()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start XRay: ${e.message}")
            false
        }
    }

    fun stop() {
        errorResetThread?.interrupt()
        errorResetThread = null
        process?.let {
            try {
                it.destroy()
                if (!it.waitFor(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "XRay did not exit after SIGTERM — killing")
                    it.destroyForcibly()
                    it.waitFor(1, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping xray: ${e.message}")
            }
        }
        process = null
        // The config holds the session proxy credentials + server secrets — don't leave it on
        // disk once the process is gone (start() rewrites it on the next run).
        try { File(context.filesDir, CONFIG_FILE).delete() } catch (_: Exception) {}
        Log.i(TAG, "XRay stopped")
    }

    /**
     * Session teardown: delete every leftover xray_*.json in filesDir (main config + stale
     * micro-test configs). Test configs younger than [STALE_TEST_CONFIG_MS] are kept — a
     * concurrent micro-test may be about to start Xray with it (it deletes its own file).
     */
    fun deleteConfigs() {
        try {
            val now = System.currentTimeMillis()
            context.filesDir.listFiles { f -> f.isFile && f.name.startsWith("xray_") && f.name.endsWith(".json") }
                ?.forEach { f ->
                    val isTest = f.name.startsWith("xray_test_")
                    if (!isTest || now - f.lastModified() > STALE_TEST_CONFIG_MS) f.delete()
                }
        } catch (e: Exception) {
            Log.w(TAG, "deleteConfigs: ${e.message}")
        }
    }

    /**
     * Runs a short-lived `xray api <args>` command and returns combined stdout/stderr (or null on
     * failure). Used to probe balancer override (`bo`) / info (`bi`) support against the local API
     * inbound. Cheap and synchronous — the process exits immediately.
     */
    suspend fun xrayApi(vararg args: String): String? = withContext(Dispatchers.IO) {
        try {
            val xrayBin = BinaryInstaller.getXrayBinaryPath(context)
            val pb = ProcessBuilder(listOf(xrayBin, "api") + args).redirectErrorStream(true)
            pb.environment()["xray.location.asset"] = context.filesDir.absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out.trim()
        } catch (e: Exception) {
            Log.w(TAG, "xray api ${args.joinToString(" ")} failed: ${e.message}")
            null
        }
    }

    private fun writeConfig(json: String): File {
        val configFile = File(context.filesDir, CONFIG_FILE)
        configFile.writeText(json)
        return configFile
    }
}
