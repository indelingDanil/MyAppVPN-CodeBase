package com.myappvpn.service

import android.content.Context
import android.util.Log
import com.myappvpn.BuildConfig
import com.myappvpn.util.BinaryInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * OpenFlux (Beta) bridge process: the bundled `libofbridge.so` — a local SOCKS5 server (CONNECT
 * only, TCP to ports 80/443, username/password auth, loopback-only) that tunnels every stream
 * through the user's own PHP exit (phpbox.php) on ordinary web hosting. It re-establishes its
 * upstream sessions by itself; this class only owns the process lifetime.
 *
 * Privacy: the token (and the URL, whose query may carry secrets) is NEVER logged. The bridge's
 * own output is forwarded to logcat in debug builds only, with the token redacted.
 */
class OpenFluxManager(private val context: Context) {

    companion object {
        private const val TAG = "OpenFluxManager"
        // The bridge listens within ~100 ms; this only has to catch an immediate exit
        // (bad flags, port already taken, missing binary).
        private const val STARTUP_WAIT_MS = 300L
        private const val STATUS_PREFIX = "OFSTATUS "
        private const val LEFT_PREFIX = "OFLEFT "
        private const val LEFT_MB_PREFIX = "OFLEFTMB "
        private const val NOTE_PREFIX = "OFNOTE "
        private const val IPCACHE_FILE = "of-ipcache.json"

        /** Exit-host IP cache shared by the running bridge and the diagnostics run. */
        fun ipCachePath(context: Context): String =
            java.io.File(context.filesDir, IPCACHE_FILE).absolutePath
    }

    @Volatile private var process: Process? = null

    val isRunning: Boolean get() = process?.isAlive == true

    // Last "OFSTATUS <state>" the bridge printed (printed only on change): "ok" | "busy"
    // (demo exit full) | "quota" (demo daily traffic budget spent) | "expired" (this install's
    // one-time demo trial is used up) | "ended" (demo campaign over) | "noclient" (exit wants a
    // client id — outdated app) | "badtoken" (404/403) | "error". "" = no status yet / not running.
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    // Demo exits only: trial seconds / traffic MB left, printed by the bridge ("OFLEFT n" /
    // "OFLEFTMB n") on each successful session start. null = not reported / not running.
    private val _leftSec = MutableStateFlow<Int?>(null)
    val leftSec: StateFlow<Int?> = _leftSec.asStateFlow()
    private val _leftMb = MutableStateFlow<Int?>(null)
    val leftMb: StateFlow<Int?> = _leftMb.asStateFlow()

    /**
     * Start the bridge listening on 127.0.0.1:[port] with SOCKS5 auth [user]/[pass]; [clientId]
     * (the install id) is sent to the exit — demo exits require it (one-time trial per install).
     * Stops a previous instance first. Returns true if the process is alive after a short wait.
     */
    suspend fun start(
        url: String, token: String, clientId: String, port: Int, user: String, pass: String,
        demo: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            stop()   // ensure clean state
            // Debug-log redaction: the token always; for the author's demo exit also its URL and
            // host (never shown in the UI either).
            val secrets = buildList {
                if (token.isNotEmpty()) add(token)
                if (demo) {
                    if (url.isNotEmpty()) add(url)
                    runCatching { java.net.URI(url).host }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.sortedByDescending { it.length }   // full URL before its host substring
            try {
                val bin = BinaryInstaller.getOpenFluxBridgePath(context)
                val cmd = mutableListOf(
                    bin,
                    "-url", url,
                    "-token", token,
                    "-client", clientId,
                    "-listen", "127.0.0.1:$port",
                    "-user", user,
                    "-pass", pass,
                    // Remembered exit-host IPs: keeps working when the carrier's DNS doesn't.
                    "-ipcache", ipCachePath(context),
                    // Short polls when a carrier proxy buffers long responses (bridge decides).
                    "-shortpoll", "auto",
                )
                // -debug logs every destination — debug builds only.
                if (BuildConfig.DEBUG) cmd.add("-debug")

                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process = proc
                _status.value = ""
                _leftSec.value = null
                _leftMb.value = null

                // Always drain the output (a full pipe would block the bridge). OFSTATUS lines
                // carry no secrets → parsed in release too; everything else is logged in debug only.
                proc.inputStream.bufferedReader().let { reader ->
                    Thread {
                        try {
                            reader.forEachLine { line ->
                                val t = line.trim()
                                if (t.startsWith(STATUS_PREFIX)) {
                                    val state = t.removePrefix(STATUS_PREFIX).trim()
                                    // Ignore a late line from a process that was already replaced.
                                    if (process === proc && state.isNotEmpty()) {
                                        _status.value = state
                                        Log.i(TAG, "OpenFlux status: $state")
                                    }
                                } else if (t.startsWith(LEFT_MB_PREFIX)) {   // before LEFT_PREFIX (shared stem)
                                    val n = t.removePrefix(LEFT_MB_PREFIX).trim().toDoubleOrNull()?.toInt()
                                    if (process === proc && n != null) _leftMb.value = n
                                } else if (t.startsWith(LEFT_PREFIX)) {
                                    val n = t.removePrefix(LEFT_PREFIX).trim().toDoubleOrNull()?.toInt()
                                    if (process === proc && n != null) _leftSec.value = n
                                } else if (t.startsWith(NOTE_PREFIX)) {
                                    // e.g. "OFNOTE shortpoll": the bridge switched to short polls
                                    // (carrier proxy buffers long responses). Informational only.
                                    if (BuildConfig.DEBUG) Log.d(TAG, "OpenFlux note: ${t.removePrefix(NOTE_PREFIX).trim()}")
                                } else if (BuildConfig.DEBUG) {
                                    var safe = line
                                    for (s in secrets) safe = safe.replace(s, "***")
                                    Log.d(TAG, safe)
                                }
                            }
                        } catch (_: Exception) { /* process ended */ }
                    }.also { it.isDaemon = true; it.name = "ofbridge-log" }.start()
                }

                delay(STARTUP_WAIT_MS)

                val alive = proc.isAlive
                if (alive) Log.i(TAG, "OpenFlux bridge started")
                else Log.e(TAG, "OpenFlux bridge exited immediately (code=${runCatching { proc.exitValue() }.getOrNull()})")
                alive
            } catch (e: CancellationException) {
                stop()   // cancelled mid-start — don't leave an orphan bridge behind
                throw e
            } catch (e: Exception) {
                // Exception messages from ProcessBuilder contain the command line → no message.
                Log.e(TAG, "Failed to start OpenFlux bridge: ${e.javaClass.simpleName}")
                stop()
                false
            }
        }

    /** SIGTERM → wait 2 s → SIGKILL. Blocking; safe to call when not running. */
    fun stop() {
        val proc = process ?: return
        process = null
        _status.value = ""
        _leftSec.value = null
        _leftMb.value = null
        try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "OpenFlux bridge did not exit after SIGTERM — killing")
                proc.destroyForcibly()
                proc.waitFor(1, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping OpenFlux bridge: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "OpenFlux bridge stopped")
    }
}
