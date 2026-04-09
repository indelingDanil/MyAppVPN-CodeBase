package com.myappvpn.service

import android.content.Context
import android.util.Log
import com.myappvpn.util.BinaryInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class XrayManager(private val context: Context) {

    companion object {
        private const val TAG = "XrayManager"
        private const val CONFIG_FILE = "xray_config.json"
        private const val STARTUP_WAIT_MS = 800L
        // XRay log keywords that indicate VLESS outbound failures
        private val ERROR_KEYWORDS = listOf("failed", "timeout", "rejected", "connection refused")
    }

    private var process: Process? = null
    private val _recentErrorCount = AtomicInteger(0)

    val isRunning: Boolean get() = process?.isAlive == true

    /** Number of VLESS-level errors logged by XRay in the last ~60s. */
    val errorRate: Int get() = _recentErrorCount.get()

    /**
     * Start xray with given JSON config.
     * Returns true if started successfully.
     */
    suspend fun start(xrayConfigJson: String): Boolean = withContext(Dispatchers.IO) {
        stop() // ensure clean state

        try {
            val xrayBin = BinaryInstaller.getXrayBinaryPath(context)
            val configFile = writeConfig(xrayConfigJson)

            val pb = ProcessBuilder(xrayBin, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true)

            // Set env so xray can find asset files
            pb.environment()["xray.location.asset"] = context.filesDir.absolutePath

            process = pb.start()

            // Read xray logs in background; count VLESS error lines
            // Only forward errors to logcat in release — full output leaks connection details
            _recentErrorCount.set(0)
            process?.inputStream?.bufferedReader()?.let { reader ->
                Thread {
                    try {
                        reader.forEachLine { line ->
                            val lower = line.lowercase()
                            val isError = ERROR_KEYWORDS.any { lower.contains(it) }
                            if (isError) {
                                _recentErrorCount.incrementAndGet()
                                Log.w(TAG, line)
                            } else if (com.myappvpn.BuildConfig.DEBUG) {
                                Log.d(TAG, line)
                            }
                        }
                    } catch (e: Exception) { /* process ended */ }
                }.also { it.isDaemon = true }.start()
            }

            // Reset error counter every 60s so transient errors don't linger
            Thread {
                try {
                    while (process?.isAlive == true) {
                        Thread.sleep(60_000L)
                        _recentErrorCount.set(0)
                    }
                } catch (_: InterruptedException) {}
            }.also { it.isDaemon = true }.start()

            // Wait a bit for xray to start listening
            delay(STARTUP_WAIT_MS)

            val alive = process?.isAlive == true
            if (alive) Log.i(TAG, "XRay started successfully")
            else Log.e(TAG, "XRay process died immediately")
            alive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start XRay: ${e.message}")
            false
        }
    }

    fun stop() {
        process?.let {
            try {
                it.destroy()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping xray: ${e.message}")
            }
        }
        process = null
        Log.i(TAG, "XRay stopped")
    }

    private fun writeConfig(json: String): File {
        val configFile = File(context.filesDir, CONFIG_FILE)
        configFile.writeText(json)
        return configFile
    }
}
