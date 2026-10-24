package com.myappvpn.domain

import com.myappvpn.data.datastore.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single place that decides WHICH OpenFlux exit is used:
 *  - `openfluxDemo` pref on  → the author's public demo exit (constants below, never stored);
 *  - otherwise               → the user's own exit (`openfluxUrl` / `openfluxToken` prefs).
 *
 * The demo URL/token are never shown in the UI, never written to the prefs and never logged.
 */
object OpenFluxEndpoint {

    /**
     * Effective OpenFlux config. [complete] = usable for a connect.
     * [channel] = how the bridge reaches the exit: DIRECT uses [url]/[token] (our v2 exit); the
     * other channels use [link] (Mail.ru document / cups room / openflux:// share link) and, for
     * LINK, an optional [secret]. The demo is always DIRECT.
     */
    class Resolved(
        val url: String,
        val token: String,
        val demo: Boolean,
        val channel: OpenFluxChannel = OpenFluxChannel.DIRECT,
        val link: String = "",
        val secret: String = "",
    ) {
        val complete: Boolean get() = when (channel) {
            OpenFluxChannel.DIRECT -> url.isNotBlank() && token.isNotBlank()
            else -> link.isNotBlank()
        }
        override fun toString(): String =
            "OpenFluxEndpoint.Resolved(demo=$demo, channel=${channel.key}, complete=$complete)"
    }

    // ── Demo exit constants (obfuscated) ─────────────────────────────────────────────────────
    // XOR-encoded so the demo host/token aren't plain greppable strings in the APK. This ONLY
    // deters casual inspection: the APK can be decompiled and the traffic to the demo exit is
    // plain HTTP, so treat these values as public. Re-encode with the same [KEY] if they change.
    private val KEY = intArrayOf(0x5A, 0x13, 0xC7, 0x2E, 0x91, 0x48, 0xB6, 0x0D, 0x73, 0xE4, 0x29, 0x8F)

    // v2 (Node, resumable sessions).
    private val ENC_URL = intArrayOf(
        50, 103, 179, 94, 171, 103, 153, 98, 3, 129, 71, 233, 54, 102, 191, 0, 245, 41, 216, 100,
        31, 221, 83, 229, 116, 113, 162, 73, 244, 60, 152, 121, 22, 135, 65, 160, 52, 60,
    )
    // v1 (PHP) — still recognised as the demo when migrating old prefs.
    private val ENC_LEGACY_URL = intArrayOf(
        50, 103, 179, 94, 171, 103, 153, 98, 3, 129, 71, 233, 54, 102, 191, 0, 245, 41, 216, 100,
        31, 221, 83, 229, 116, 113, 162, 73, 244, 60, 152, 121, 22, 135, 65, 160, 42, 123, 183, 76,
        254, 48, 152, 125, 27, 148,
    )
    private val ENC_TOKEN = intArrayOf(
        62, 118, 170, 65, 188, 28, 241, 64, 62, 174, 70, 206, 50, 106, 165, 124, 226, 3, 226, 64,
        31, 161, 111, 191, 12, 32, 172, 31, 219,
    )

    private fun decode(enc: IntArray): String =
        String(ByteArray(enc.size) { i -> (enc[i] xor KEY[i % KEY.size]).toByte() }, Charsets.UTF_8)

    private val demoUrl: String by lazy { decode(ENC_URL) }
    private val legacyDemoUrl: String by lazy { decode(ENC_LEGACY_URL) }
    private val demoToken: String by lazy { decode(ENC_TOKEN) }

    /** The URL is (one of) the demo exit's URLs. */
    fun isDemoUrl(url: String?): Boolean {
        val u = url?.trim() ?: return false
        return u == demoUrl || u == legacyDemoUrl
    }

    /** The token is the demo exit's token. */
    fun isDemoToken(token: String?): Boolean = token?.trim() == demoToken

    /** URL + token are the demo pair (as written into the prefs by older versions). */
    fun isDemoPair(url: String?, token: String?): Boolean = isDemoUrl(url) && isDemoToken(token)

    // ── One-time migration ───────────────────────────────────────────────────────────────────
    private val migrateMutex = Mutex()
    @Volatile private var migrated = false

    /**
     * Older versions stored the demo URL/token in `openfluxUrl`/`openfluxToken`: move that to the
     * `openfluxDemo` flag (clearing the stored pair). Idempotent; runs at most once per process.
     */
    suspend fun migrateOnce(preferences: AppPreferences) {
        if (migrated) return
        migrateMutex.withLock {
            if (migrated) return
            try {
                preferences.migrateOpenfluxDemo { u, t -> isDemoPair(u, t) }
                migrated = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // not done — the next caller retries
            } catch (_: Exception) {
                migrated = true   // DataStore I/O error: don't retry on every connect
            }
        }
    }

    /** Effective OpenFlux config (demo flag → demo constants; else the user's own prefs). */
    suspend fun resolve(preferences: AppPreferences): Resolved {
        migrateOnce(preferences)
        return if (preferences.openfluxDemo.first()) {
            Resolved(demoUrl, demoToken, demo = true)
        } else {
            val channel = OpenFluxChannel.of(preferences.openfluxChannel.first())
            val link = when (channel) {
                OpenFluxChannel.DIRECT -> ""
                OpenFluxChannel.MAILRU -> preferences.openfluxLinkMailru.first()
                OpenFluxChannel.CUPS -> preferences.openfluxLinkCups.first()
                OpenFluxChannel.LINK -> preferences.openfluxLinkShare.first()
            }.trim()
            Resolved(
                preferences.openfluxUrl.first().trim(),
                preferences.openfluxToken.first().trim(),
                demo = false,
                channel = channel,
                link = link,
                secret = if (channel == OpenFluxChannel.LINK) preferences.openfluxSecret.first().trim() else "",
            )
        }
    }
}

/**
 * Channel from the bridge to the user's own exit ("Канал до выхода").
 * [key] = stored pref value; [transport] = the bridge's `-transport` flag.
 */
enum class OpenFluxChannel(val key: String, val transport: String) {
    /** Our v2 exit over plain HTTP(S) (URL + token). */
    DIRECT("direct", "v2"),
    /** Stream mode through a Mail.ru document (link = the document's public link). */
    MAILRU("mailru", "mailru"),
    /** Stream mode through cups.online rooms (link = the exit's room code). */
    CUPS("cups", "cups"),
    /** An openflux://v1/… share link to any OpenFlux node (+ optional key). */
    LINK("link", "session");

    companion object {
        fun of(key: String?): OpenFluxChannel = entries.firstOrNull { it.key == key } ?: DIRECT
        fun ofTransport(t: String?): OpenFluxChannel = entries.firstOrNull { it.transport == t } ?: DIRECT
    }
}
