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
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * OpenFlux (Beta) bridge process: the bundled `libofbridge.so` — a local SOCKS5 server (CONNECT
 * only, username/password auth, loopback-only) that tunnels every stream to the user's exit,
 * either directly over HTTP(S) (`-transport v2`, our Node exit) or through a carrier of the
 * OpenFlux core (`-transport mailru|cups|session`: Mail.ru document, cups.online rooms, an
 * openflux:// share link). It re-establishes its upstream by itself; this class owns the process
 * lifetime, parses its status lines and keeps its stdin open for "OFCOOKIES" answers to captchas.
 *
 * Privacy: the token, link and key (and the URL, whose query may carry secrets) are NEVER logged.
 * The bridge's own output is forwarded to logcat in debug builds only, with those redacted.
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
        private const val COOKIES_FILE = "of-cookies.json"

        /** Exit-host IP cache shared by the running bridge and the diagnostics run. */
        fun ipCachePath(context: Context): String =
            java.io.File(context.filesDir, IPCACHE_FILE).absolutePath

        /** Cookies passed for carrier captchas (the bridge replays them on restart). */
        fun cookiesPath(context: Context): String =
            java.io.File(context.filesDir, COOKIES_FILE).absolutePath
    }

    @Volatile private var process: Process? = null
    @Volatile private var stdin: Writer? = null

    val isRunning: Boolean get() = process?.isAlive == true

    // Last "OFSTATUS <state>" the bridge printed (printed only on change): "ok" | "busy"
    // (demo exit full) | "quota" (demo daily traffic budget spent) | "expired" (this install's
    // one-time demo trial is used up) | "ended" (demo campaign over) | "noclient" (exit wants a
    // client id — outdated app) | "badtoken" (404/403) | "error" | carrier channels:
    // "connecting" | "connected" | "error" (still not connected after a minute; keeps trying).
    // "" = no status yet / not running.
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    // Demo exits only: trial seconds / traffic MB left, printed by the bridge ("OFLEFT n" /
    // "OFLEFTMB n") on each successful session start. null = not reported / not running.
    private val _leftSec = MutableStateFlow<Int?>(null)
    val leftSec: StateFlow<Int?> = _leftSec.asStateFlow()
    private val _leftMb = MutableStateFlow<Int?>(null)
    val leftMb: StateFlow<Int?> = _leftMb.asStateFlow()

    // Carrier channels: the carrier traffic rides ("OFNOTE carrier <type>", session links) or,
    // until one is reported, the transport ("OFNOTE transport <name>": mailru / cupsonline /
    // session). null = direct v2 / not running.
    private val _carrier = MutableStateFlow<String?>(null)
    val carrier: StateFlow<String?> = _carrier.asStateFlow()

    // A carrier asks for a browser check ("OFNOTE captcha <url>"); cleared once connected again.
    private val _captchaUrl = MutableStateFlow<String?>(null)
    val captchaUrl: StateFlow<String?> = _captchaUrl.asStateFlow()

    /**
     * Start the bridge listening on 127.0.0.1:[port] with SOCKS5 auth [user]/[pass].
     * [transport] = "v2" (direct: [url]/[token]; [clientId] — the install id — is sent to the
     * exit, demo exits require it) or a carrier ("mailru" / "cups" / "session") using [link]
     * (+ [secret] for share links). Stops a previous instance first. Returns true if the process
     * is alive after a short wait.
     */
    suspend fun start(
        url: String, token: String, clientId: String, port: Int, user: String, pass: String,
        demo: Boolean = false,
        transport: String = "v2",
        link: String = "",
        secret: String = "",
    ): Boolean =
        withContext(Dispatchers.IO) {
            stop()   // ensure clean state
            val direct = transport == "v2"
            // Debug-log redaction: the token, link and key always; for the author's demo exit
            // also its URL and host (never shown in the UI either).
            val secrets = buildList {
                if (token.isNotEmpty()) add(token)
                if (link.isNotEmpty()) add(link)
                if (secret.isNotEmpty()) add(secret)
                if (demo) {
                    if (url.isNotEmpty()) add(url)
                    runCatching { java.net.URI(url).host }.getOrNull()
                        ?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.sortedByDescending { it.length }   // full URL before its host substring
            try {
                val bin = BinaryInstaller.getOpenFluxBridgePath(context)
                val cmd = mutableListOf(bin)
                if (direct) {
                    cmd += listOf(
                        "-url", url,
                        "-token", token,
                        "-client", clientId,
                        // Remembered exit-host IPs: keeps working when the carrier's DNS doesn't.
                        "-ipcache", ipCachePath(context),
                        // Short polls when a carrier proxy buffers long responses (bridge decides).
                        "-shortpoll", "auto",
                    )
                } else {
                    cmd += listOf(
                        "-transport", transport,
                        "-link", link,
                        "-cookies", cookiesPath(context),
                    )
                    if (secret.isNotEmpty()) cmd += listOf("-secret", secret)
                }
                cmd += listOf(
                    "-listen", "127.0.0.1:$port",
                    "-user", user,
                    "-pass", pass,
                )
                // -debug logs every destination — debug builds only.
                if (BuildConfig.DEBUG) cmd.add("-debug")

                // stdin stays a pipe (kept open) for "OFCOOKIES" lines.
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process = proc
                stdin = proc.outputStream.bufferedWriter()
                _status.value = ""
                _leftSec.value = null
                _leftMb.value = null
                _carrier.value = null
                _captchaUrl.value = null

                // Always drain the output (a full pipe would block the bridge). OFSTATUS / OFNOTE
                // lines carry no secrets → parsed in release too; the rest is logged in debug only.
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
                                        if (state == "connected") _captchaUrl.value = null
                                        Log.i(TAG, "OpenFlux status: $state")
                                    }
                                } else if (t.startsWith(LEFT_MB_PREFIX)) {   // before LEFT_PREFIX (shared stem)
                                    val n = t.removePrefix(LEFT_MB_PREFIX).trim().toDoubleOrNull()?.toInt()
                                    if (process === proc && n != null) _leftMb.value = n
                                } else if (t.startsWith(LEFT_PREFIX)) {
                                    val n = t.removePrefix(LEFT_PREFIX).trim().toDoubleOrNull()?.toInt()
                                    if (process === proc && n != null) _leftSec.value = n
                                } else if (t.startsWith(NOTE_PREFIX)) {
                                    if (process === proc) handleNote(t.removePrefix(NOTE_PREFIX).trim())
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
                if (alive) Log.i(TAG, "OpenFlux bridge started (${if (direct) "direct" else transport})")
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

    /** "OFNOTE …": shortpoll (info) | transport <name> | carrier <type> | captcha <url>. */
    private fun handleNote(note: String) {
        val key = note.substringBefore(' ')
        val arg = note.substringAfter(' ', "").trim()
        when (key) {
            "transport" -> if (arg.isNotEmpty() && arg != "v2") {
                if (_carrier.value == null) _carrier.value = arg
            }
            "carrier" -> if (arg.isNotEmpty()) _carrier.value = arg
            "captcha" -> {
                // "-" = a check page the bridge can't hand over (script carriers): nothing to open.
                if (arg.startsWith("http://") || arg.startsWith("https://")) {
                    _captchaUrl.value = arg
                    Log.i(TAG, "OpenFlux: carrier asks for a browser check")
                }
            }
        }
        if (BuildConfig.DEBUG && key != "captcha") Log.d(TAG, "OpenFlux note: $note")
    }

    /**
     * Hand cookies from a solved browser check to the bridge ("OFCOOKIES a=1; b=2" on stdin).
     * Returns false when no bridge is running or the write failed.
     */
    fun sendCookies(header: String): Boolean {
        val h = header.replace('\n', ' ').replace('\r', ' ').trim()
        if (h.isEmpty()) return false
        val w = stdin ?: return false
        if (process?.isAlive != true) return false
        return try {
            synchronized(w) {
                w.write("OFCOOKIES $h\n")
                w.flush()
            }
            Log.i(TAG, "OpenFlux: cookies handed to the bridge")
            true
        } catch (e: Exception) {
            Log.w(TAG, "OpenFlux: cookies not delivered: ${e.javaClass.simpleName}")
            false
        }
    }

    /** SIGTERM → wait 2 s → SIGKILL. Blocking; safe to call when not running. */
    fun stop() {
        val proc = process ?: return
        process = null
        val w = stdin
        stdin = null
        _status.value = ""
        _leftSec.value = null
        _leftMb.value = null
        _carrier.value = null
        _captchaUrl.value = null
        runCatching { w?.close() }
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
