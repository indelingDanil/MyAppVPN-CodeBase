package com.myappvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myappvpn.BuildConfig
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.EffectiveDns
import com.myappvpn.data.repository.DnsRepository
import com.myappvpn.util.DnsConfigInjector
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.usecase.ConnectVpnUseCase
import com.myappvpn.ui.MainActivity
import com.myappvpn.util.HotspotHelper
import com.myappvpn.util.XrayConfigBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@AndroidEntryPoint
class MyVpnService : VpnService() {

    companion object {
        private val BAD_OUTBOUND_RE = Regex("""outbound config with tag (proxy-\d+)""")
        const val TAG = "MyVpnService"
        const val ACTION_CONNECT = "com.myappvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.myappvpn.DISCONNECT"
        // Hot-switch to a specific server while the tunnel is up (swaps Xray outbound, keeps TUN).
        const val ACTION_SWITCH_SERVER = "com.myappvpn.SWITCH_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
        // Hot-switch back to Auto (balancer) while the tunnel is up. Extras: EXTRA_XRAY_CONFIG
        // (balancer config WITHOUT auth), EXTRA_SERVER_NAME, EXTRA_POOL_IDS, EXTRA_HOTSPOT_SHARING
        // (ignored — the session's hotspot setting is kept).
        const val ACTION_SWITCH_AUTO = "com.myappvpn.SWITCH_AUTO"
        const val EXTRA_XRAY_CONFIG = "xray_config"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HOTSPOT_SHARING = "hotspot_sharing"
        const val EXTRA_BALANCER_MODE = "balancer_mode"
        // Ordered server ids matching proxy-0..N in the balancer config (index = N). Lets the
        // service map the active/pinned `proxy-N` (from `xray api bi/bo`) back to a real server.
        const val EXTRA_POOL_IDS = "pool_ids"
        // OpenFlux (Beta) connect: ACTION_CONNECT with EXTRA_OPENFLUX=true + URL/token, and NO
        // EXTRA_XRAY_CONFIG — the service builds the Xray→bridge chain itself (it owns the random
        // session ports, incl. the bridge's). EXTRA_SERVER_NAME / EXTRA_HOTSPOT_SHARING as usual.
        const val EXTRA_OPENFLUX = "openflux"
        const val EXTRA_OPENFLUX_URL = "openflux_url"
        const val EXTRA_OPENFLUX_TOKEN = "openflux_token"
        /** true = the URL/token are the author's demo exit → never show/log its host. */
        const val EXTRA_OPENFLUX_DEMO = "openflux_demo"
        /** Bridge `-transport`: "v2" (default, URL/token) | "mailru" | "cups" | "session" (link). */
        const val EXTRA_OPENFLUX_TRANSPORT = "openflux_transport"
        /** Carrier channels: document link / room code / openflux:// share link. */
        const val EXTRA_OPENFLUX_LINK = "openflux_link"
        /** Share links: optional encryption key. */
        const val EXTRA_OPENFLUX_SECRET = "openflux_secret"
        /** [connectedServer] id of an OpenFlux session (synthetic profile, not in Room). */
        const val OPENFLUX_SERVER_ID = "__openflux__"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_service"
        // OpenFlux carrier asks for a browser check (captcha) → separate, audible notification.
        private const val CAPTCHA_NOTIFICATION_ID = 1002
        private const val CAPTCHA_CHANNEL_ID = "openflux_check"
        // Carrier channels: after "connecting", wait this long for "connected" before going CONNECTED.
        private const val OPENFLUX_CARRIER_CONNECT_WAIT_MS = 20_000L
        // Sticky balancer: only re-check the active route's Telegram reachability this often.
        private const val BALANCER_PROBE_INTERVAL_MS = 5 * 60_000L
        // "Too slow / dead" = couldn't FULLY load Telegram within this budget (one probe).
        private const val TELEGRAM_PROBE_TIMEOUT_MS = 18_000
        // OpenFlux connect: wait this long for the exit's first OFSTATUS before going CONNECTED.
        private const val OPENFLUX_FIRST_STATUS_TIMEOUT_MS = 8_000L

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        // Hotspot proxy endpoint of the live session: non-null only while CONNECTED with hotspot
        // sharing enabled and a hotspot IP present when the current Xray config was built.
        private val _hotspotInfo = MutableStateFlow<HotspotInfo?>(null)
        val hotspotInfo: StateFlow<HotspotInfo?> = _hotspotInfo.asStateFlow()

        private val _connectedServer = MutableStateFlow<ServerProfile?>(null)
        val connectedServer: StateFlow<ServerProfile?> = _connectedServer.asStateFlow()

        // AUTO while a balancer session exists, MANUAL while a single-server session exists,
        // OPENFLUX while an OpenFlux (Beta) session exists, null when DISCONNECTED/ERROR.
        private val _activeMode = MutableStateFlow<ActiveMode?>(null)
        val activeMode: StateFlow<ActiveMode?> = _activeMode.asStateFlow()

        // OpenFlux (Beta) refusal reported by the exit: "busy" (demo full), "expired" (this
        // install's one-time demo trial is used up), "ended" (demo campaign over), "quota" (demo
        // daily traffic budget spent), "noclient" (outdated app) or "badtoken" (wrong token/URL).
        // Each one STOPS the session (see stopOnOpenFluxRefusal) and the reason stays here after
        // the teardown so the UI can explain it; cleared when the next connect starts (or via
        // clearOpenFluxStatus()). null = no refusal.
        private val _openFluxStatus = MutableStateFlow<String?>(null)
        val openFluxStatus: StateFlow<String?> = _openFluxStatus.asStateFlow()
        val OPENFLUX_REFUSALS = setOf("busy", "expired", "ended", "quota", "noclient", "badtoken", "unsupported")

        /** Dismiss a stale refusal reason (e.g. the user left OpenFlux mode). */
        fun clearOpenFluxStatus() { _openFluxStatus.value = null }

        // Demo exit only: trial seconds / traffic MB left as last reported by the bridge (at each
        // session start). null when not connected via OpenFlux or the exit doesn't report it.
        private val _openFluxDemoLeftSec = MutableStateFlow<Int?>(null)
        val openFluxDemoLeftSec: StateFlow<Int?> = _openFluxDemoLeftSec.asStateFlow()
        /** SystemClock.elapsedRealtime() when [openFluxDemoLeftSec] was last received. */
        @Volatile var openFluxDemoLeftSecAt: Long = 0L
            private set
        private val _openFluxDemoLeftMb = MutableStateFlow<Int?>(null)
        val openFluxDemoLeftMb: StateFlow<Int?> = _openFluxDemoLeftMb.asStateFlow()

        // OpenFlux carrier channels: the carrier traffic currently rides ("mailru", "cupsonline",
        // "session", or a session link's active carrier like "yandex"). null = direct / no session.
        private val _openFluxCarrier = MutableStateFlow<String?>(null)
        val openFluxCarrier: StateFlow<String?> = _openFluxCarrier.asStateFlow()

        // A carrier waits for a browser check at this URL (CaptchaActivity). null = none.
        private val _openFluxCaptcha = MutableStateFlow<String?>(null)
        val openFluxCaptcha: StateFlow<String?> = _openFluxCaptcha.asStateFlow()

        // Bridge of the live service instance (for cookies from CaptchaActivity).
        @Volatile private var liveOpenFluxManager: OpenFluxManager? = null

        /**
         * Cookies ("a=1; b=2") from a solved browser check → the running bridge's stdin.
         * false = no OpenFlux bridge running / not delivered.
         */
        fun submitOpenFluxCookies(header: String): Boolean =
            liveOpenFluxManager?.sendCookies(header) == true

        // True while a hot-switch (to a server or back to Auto) is being applied.
        private val _switching = MutableStateFlow(false)
        val switching: StateFlow<Boolean> = _switching.asStateFlow()

        // Local SOCKS port of the live tunnel (0 = not connected; random per session). Lets the app route its OWN
        // requests (e.g. subscription refresh) THROUGH the tunnel — the app is excluded from its
        // TUN, so without this its traffic goes over the restricted operator network directly.
        @Volatile var activeSocksPort: Int = 0

        // Socket-owner server of the live TUN (GitHub issue #8) — read by diagnostics.
        @Volatile private var liveOwnerServer: OwnerUidServer? = null

        /**
         * One-line state of the tun2socks socket-owner check for diagnostics, e.g.
         * "on, queries=120 denied=3 errors=0, last denied: tcp 172.19.0.1:40000 1.2.3.4:443 (…)".
         * null = no TUN (not connected).
         */
        fun ownerCheckStats(): String? {
            val s = liveOwnerServer ?: return null
            return buildString {
                append("on, queries=${s.queries.get()} denied=${s.denied.get()} errors=${s.errors.get()}")
                s.lastDenied?.let { append(", last denied: ").append(it) }
            }
        }
    }

    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var trafficMonitor: TrafficMonitor
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var xrayMicroTest: com.myappvpn.domain.validation.XrayMicroTest
    @Inject lateinit var dnsRepository: DnsRepository
    // Always-on VPN / system restart: reconnect in the stored mode (see startAlwaysOn()).
    @Inject lateinit var connectVpnUseCase: ConnectVpnUseCase

    // User's custom DNS for this session (null = built-in defaults). Read at connect, updated by
    // startDnsObserver(); applied to every Xray config in prepareConfig().
    @Volatile private var currentDns: EffectiveDns? = null
    private var dnsObserverJob: Job? = null
    // OpenFlux: the DNS setting whose non-DoH:443 entries we last logged as dropped (log once).
    @Volatile private var openFluxDnsWarnedFor: EffectiveDns? = null

    // An uncaught exception in any service coroutine must not crash the process (that would drop
    // the TUN and leak traffic): log it and, if a session is live, engage the kill-switch.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> onUncaughtCoroutineError(e) }
    )
    private lateinit var xrayManager: XrayManager
    private lateinit var tun2SocksManager: Tun2SocksManager
    // Answers tun2socks' per-flow "who owns this socket?" queries; one per established TUN
    // (recreated in buildTun, stopped in cleanupProcesses). null → tun2socks runs without the check.
    @Volatile private var ownerUidServer: OwnerUidServer? = null
    private lateinit var openFluxManager: OpenFluxManager

    /** OpenFlux (Beta) session parameters (URL/token from the connect intent). */
    private class OpenFluxTarget(
        val url: String, val token: String, val demo: Boolean,
        val transport: String = "v2", val link: String = "", val secret: String = "",
    ) {
        val direct: Boolean get() = transport == "v2"
        override fun toString(): String = "OpenFluxTarget(demo=$demo, transport=$transport)"   // never leak URL/token/link
    }

    // OpenFlux session: Xray's "proxy" outbound → local bridge (libofbridge) → user's PHP exit.
    // Read on the main thread (switch filtering) → volatile.
    @Volatile private var openFluxMode = false
    // Session generation: incremented at the start of every connect() (under opMutex). An async
    // refusal stop captures it and only tears down if it still matches (else it's stale).
    @Volatile private var sessionGen = 0L
    private var openFlux: OpenFluxTarget? = null
    // Bridge listen port + SOCKS5 credentials (random per session; kept across bridge restarts).
    private var bridgePort = 0
    private var bridgeUser = ""
    private var bridgePass = ""
    // AppPreferences.installId() (sent to the exit as -client), cached after the first read.
    @Volatile private var installIdCache: String? = null

    private var tunFd: ParcelFileDescriptor? = null

    /** Random loopback ports of the current session (picked at connect, kept until teardown). */
    private data class LocalPorts(val socks: Int)
    private var ports = LocalPorts(10808)
    private val currentSocksPort: Int get() = ports.socks
    private var currentServerName: String = "Unknown"
    private var hotspotSharingEnabled = false
    // Stable hotspot SOCKS credentials (from preferences) — set at connect when sharing is on.
    private var hotspotCreds: Pair<String, String>? = null
    // Stable per-install hotspot ports (http to socks, from preferences) — set together with creds.
    private var hotspotPorts: Pair<Int, Int>? = null
    // Set when Xray couldn't start with the control-API Unix socket: from then on configs are
    // started without the API (Auto mode works, just without balancer pinning).
    @Volatile private var apiDisabled = false
    // Hotspot endpoint baked into the most recent prepared config (null = no hotspot inbounds).
    @Volatile private var configHotspot: HotspotInfo? = null

    // Default-network tracking (underlying network for the VPN + handover detection).
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastDefaultNetwork: Network? = null
    // Set when the default network changes (Wi-Fi ↔ mobile); the watchdog consumes it and resets
    // its failure streak so a handover isn't treated as a server failure.
    @Volatile private var networkSwitched = false
    // Always-on / system-restart connect that is resolving the stored mode (main thread only).
    private var alwaysOnJob: Job? = null
    @Volatile private var lastUncaughtBlackholeAt = 0L
    // Balancer (auto) mode: Xray observatory picks/switches the active server in-core.
    private var balancerMode = false
    private var lastXrayConfig: String = ""   // authed config of the current session (for restart)
    // Balancer pool: ordered server ids where index == proxy-N in the Xray config (set from
    // EXTRA_POOL_IDS). Lets us map the active/pinned proxy back to a real server.
    private var balancerPoolIds: List<String> = emptyList()
    // Current balancer override (e.g. "proxy-33") that we pinned via `xray api bo`, or null.
    private var pinnedTag: String? = null
    private var watchdogJob: Job? = null
    private var splitTunnelObserverJob: Job? = null
    private var backgroundProbeJob: Job? = null
    private var poolReverifyJob: Job? = null
    // Kill-switch: keep TUN up + block traffic on unexpected failure, then auto-recover.
    private var killSwitchEnabled = true
    // "Обход DPI" (TLS ClientHello fragmentation), read once per connect; see prepareConfig.
    @Volatile private var tlsFragmentEnabled = false
    private var recoveryJob: Job? = null

    // Latest startId seen by onStartCommand. disconnect() stops via stopSelf(lastStartId) (never the
    // unconditional stopSelf()), so a start request AMS delivered after it isn't killed.
    // Main thread only.
    private var lastStartId: Int = 0
    // In-flight connect / disconnect coroutines (touched on the main thread only).
    private var connectJob: Job? = null
    private var disconnectJob: Job? = null
    // The disconnectJob the current connectJob waits for (null = it didn't wait). Main thread.
    private var connectQueuedBehind: Job? = null

    // Serializes EVERY state-mutating operation (connect body, hot-switches, watchdog actions,
    // recovery attempts, split-tunnel TUN rebuild, disconnect teardown) so they never race on
    // xrayManager / tunFd / session fields. NOT reentrant: only top-level entry points take it;
    // everything they call is lock-free ("*Locked" = caller must hold opMutex).
    private val opMutex = Mutex()

    /** A user hot-switch request. Latest wins (see [pendingSwitch]). */
    private sealed class SwitchRequest {
        data class Server(val serverId: String) : SwitchRequest()
        data class Auto(val config: String, val name: String, val poolIds: List<String>) : SwitchRequest()
    }

    // Latest not-yet-applied user switch. Requests that arrive while the initial connect is in
    // flight wait here until connect() finishes (dropped if it fails); later ones overwrite it.
    private val pendingSwitch = AtomicReference<SwitchRequest?>(null)

    // Parent of all switch coroutines — disconnect cancels its children so a running/queued switch
    // releases opMutex promptly and can't resurrect the session behind the teardown.
    private val switchParent = SupervisorJob(serviceScope.coroutineContext[Job])

    // Per-session SOCKS5 auth credentials — prevents other apps from using our proxy
    // (CVE: unauthenticated local SOCKS proxy deanonymization, see habr.com/ru/articles/1020080)
    @Volatile private var proxyUser: String = ""
    @Volatile private var proxyPass: String = ""

    override fun onCreate() {
        super.onCreate()
        xrayManager = XrayManager(this)
        tun2SocksManager = Tun2SocksManager(this)
        openFluxManager = OpenFluxManager(this)
        liveOpenFluxManager = openFluxManager
        createNotificationChannel()
        // Bridge exit status while in OpenFlux mode: a refusal (busy / expired / ended / quota /
        // noclient / badtoken) is published and ends the session. Outside OpenFlux mode the
        // published reason is left alone — it must survive the teardown for the UI.
        serviceScope.launch {
            openFluxManager.status.collect { s ->
                if (!openFluxMode) return@collect
                when {
                    s in OPENFLUX_REFUSALS -> {
                        val gen = sessionGen   // the session this refusal belongs to
                        _openFluxStatus.value = s
                        stopOnOpenFluxRefusal(s, gen)
                    }
                    // "" = bridge (re)started / stopped by the kill-switch — nothing new.
                    s.isEmpty() -> Unit
                    else -> _openFluxStatus.value = null   // "ok" (or "error": transient, not surfaced)
                }
            }
        }
        serviceScope.launch {
            openFluxManager.leftSec.collect {
                openFluxDemoLeftSecAt = android.os.SystemClock.elapsedRealtime()
                _openFluxDemoLeftSec.value = if (openFluxMode) it else null
            }
        }
        serviceScope.launch {
            openFluxManager.leftMb.collect { _openFluxDemoLeftMb.value = if (openFluxMode) it else null }
        }
        serviceScope.launch {
            openFluxManager.carrier.collect { _openFluxCarrier.value = if (openFluxMode) it else null }
        }
        // Carrier captcha: publish for the in-app prompt + a notification opening CaptchaActivity.
        serviceScope.launch {
            openFluxManager.captchaUrl.collect { url ->
                val shown = if (openFluxMode) url else null
                _openFluxCaptcha.value = shown
                if (shown != null) showCaptchaNotification(shown) else cancelCaptchaNotification()
            }
        }
    }

    private fun showCaptchaNotification(url: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CAPTCHA_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CAPTCHA_CHANNEL_ID, getString(R.string.openflux_captcha_channel),
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val open = PendingIntent.getActivity(
                this, 2,
                com.myappvpn.ui.CaptchaActivity.intent(this, url),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, CAPTCHA_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(getString(R.string.openflux_captcha_title))
                .setContentText(getString(R.string.openflux_captcha_notif_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(CAPTCHA_NOTIFICATION_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "Captcha notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun cancelCaptchaNotification() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(CAPTCHA_NOTIFICATION_ID) }
    }

    /**
     * The OpenFlux exit refused us (see [OPENFLUX_REFUSALS]): end the session like a normal
     * disconnect — no kill-switch, no retries — keeping [openFluxStatus] = [reason] for the UI.
     * A connect still in flight handles a refusal itself (it waits for the first answer), so we
     * only act after it finished and only if the SAME OpenFlux session ([gen] = [sessionGen] when
     * the refusal was observed) is still there — checked under opMutex, so a stale refusal can't
     * kill a newer session. Any thread.
     */
    private fun stopOnOpenFluxRefusal(reason: String, gen: Long) {
        Handler(Looper.getMainLooper()).post {
            if (!openFluxMode || gen != sessionGen || disconnectJob?.isActive == true) return@post
            val inFlightConnect = connectJob?.takeIf { it.isActive }
            Log.w(TAG, "OpenFlux exit refused the session ('$reason') — stopping")
            disconnectJob = serviceScope.launch {
                inFlightConnect?.join()
                val stopped = opMutex.withLock {
                    // The connect already stopped / a teardown ran / a newer session took over.
                    if (!openFluxMode || gen != sessionGen) {
                        Log.i(TAG, "Stale OpenFlux refusal ('$reason') dropped")
                        return@withLock false
                    }
                    _vpnState.value = VpnState.DISCONNECTING
                    pendingSwitch.set(null)
                    teardownSessionLocked()   // also cancels watchdog / recovery / observers
                    // The reason outlives the session so the UI can explain it.
                    _openFluxStatus.value = reason
                    _vpnState.value = VpnState.DISCONNECTED
                    true
                }
                if (!stopped) return@launch
                Log.i(TAG, "VPN disconnected (OpenFlux refusal)")
                withContext(Dispatchers.Main) {
                    val newerConnect = connectJob?.let { it !== inFlightConnect && it.isActive } == true
                    if (!newerConnect) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // We are started via startForegroundService → startForeground MUST happen promptly for
        // EVERY start, synchronously, before any early return (else ForegroundServiceDidNotStart-
        // InTimeException). Later code updates the notification text.
        enterForegroundNow(if (intent?.action == ACTION_DISCONNECT) VpnState.DISCONNECTING else null)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val ofTarget = if (intent.getBooleanExtra(EXTRA_OPENFLUX, false)) {
                    val url = intent.getStringExtra(EXTRA_OPENFLUX_URL)?.trim().orEmpty()
                    val token = intent.getStringExtra(EXTRA_OPENFLUX_TOKEN)?.trim().orEmpty()
                    val transport = intent.getStringExtra(EXTRA_OPENFLUX_TRANSPORT)?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "v2"
                    val link = intent.getStringExtra(EXTRA_OPENFLUX_LINK)?.trim().orEmpty()
                    val secret = intent.getStringExtra(EXTRA_OPENFLUX_SECRET)?.trim().orEmpty()
                    val usable = if (transport == "v2") url.isNotEmpty() && token.isNotEmpty() else link.isNotEmpty()
                    if (!usable) {
                        Log.w(TAG, "OpenFlux CONNECT without URL/token/link — ignored")
                        stopIfIdle(startId)
                        return START_NOT_STICKY
                    }
                    OpenFluxTarget(
                        url, token, intent.getBooleanExtra(EXTRA_OPENFLUX_DEMO, false),
                        transport = transport, link = link, secret = secret,
                    )
                } else null
                // OpenFlux: the service builds the chain config itself (needs the session ports).
                val xrayConfig = if (ofTarget != null) "" else intent.getStringExtra(EXTRA_XRAY_CONFIG)
                if (xrayConfig == null) {
                    Log.w(TAG, "CONNECT without config — ignored")
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }
                val st = _vpnState.value
                val disconnectInFlight = disconnectJob?.takeIf { it.isActive }
                if (disconnectInFlight != null) {
                    // A disconnect is tearing the current session down (its state may not even
                    // be published yet, e.g. CONNECT→DISCONNECT→CONNECT back-to-back): don't drop
                    // this CONNECT — queue it behind the teardown. Only ignore it if a connect is
                    // already queued behind this very disconnect.
                    if (connectJob?.isActive == true && connectQueuedBehind === disconnectInFlight) {
                        Log.w(TAG, "CONNECT ignored — a connect is already queued behind the disconnect")
                        return START_NOT_STICKY
                    }
                } else if (st == VpnState.CONNECTED || st == VpnState.CONNECTING || connectJob?.isActive == true) {
                    // A second connect would build a new TUN and leak the old fd / processes.
                    Log.w(TAG, "CONNECT ignored — session already $st")
                    return START_NOT_STICKY
                }
                // EXTRA_SOCKS_PORT is ignored: local ports are random per session (see connect()).
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Unknown"
                val hotspotSharing = intent.getBooleanExtra(EXTRA_HOTSPOT_SHARING, false)
                val balancer = intent.getBooleanExtra(EXTRA_BALANCER_MODE, false)
                // Authoritative proxy-N → server id mapping (index = N), as built into the config.
                val poolIds = intent.getStringArrayListExtra(EXTRA_POOL_IDS) ?: emptyList<String>()
                // If a disconnect is still tearing down, let it finish first (it would otherwise
                // kill the processes this connect starts).
                val pendingDisconnect = disconnectInFlight
                connectQueuedBehind = pendingDisconnect
                connectJob = serviceScope.launch {
                    pendingDisconnect?.join()
                    connect(xrayConfig, serverName, hotspotSharing, balancer && ofTarget == null, poolIds, ofTarget)
                }
            }
            ACTION_SWITCH_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                if (serverId == null) {
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }
                requestSwitch(SwitchRequest.Server(serverId), startId)
            }
            ACTION_SWITCH_AUTO -> {
                val config = intent.getStringExtra(EXTRA_XRAY_CONFIG)
                if (config == null) {
                    Log.w(TAG, "SWITCH_AUTO without config — ignored")
                    stopIfIdle(startId)
                    return START_NOT_STICKY
                }
                val name = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Auto"
                val poolIds = intent.getStringArrayListExtra(EXTRA_POOL_IDS)?.toList() ?: emptyList()
                // EXTRA_HOTSPOT_SHARING is intentionally ignored: the session's value is kept.
                requestSwitch(SwitchRequest.Auto(config, name, poolIds), startId)
            }
            ACTION_DISCONNECT -> {
                val inFlightConnect = connectJob
                disconnectJob = serviceScope.launch { disconnect(inFlightConnect) }
            }
            null, VpnService.SERVICE_INTERFACE -> {
                // Always-on VPN / system (re)start: null intent or android.net.VpnService action.
                startAlwaysOn(startId)
            }
            else -> {
                Log.w(TAG, "Unknown start intent (${intent?.action}) — ignoring")
                stopIfIdle(startId)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Always-on VPN: connect in the stored mode via [ConnectVpnUseCase] (which sends ACTION_CONNECT
     * back to us): Auto → balancer; Manual → the pinned server (Auto if none). No-op when a session
     * already exists. If building the connect fails, the session goes to ERROR. Main thread.
     */
    private fun startAlwaysOn(startId: Int) {
        val st = _vpnState.value
        val busy = st == VpnState.CONNECTED || st == VpnState.CONNECTING || st == VpnState.BLOCKED ||
            connectJob?.isActive == true || alwaysOnJob?.isActive == true
        if (busy) {
            Log.i(TAG, "Always-on start ignored — session already $st")
            return
        }
        Log.i(TAG, "Always-on / system start — connecting in the stored mode")
        val pendingDisconnect = disconnectJob
        alwaysOnJob = serviceScope.launch {
            pendingDisconnect?.join()
            try {
                if (preferences.openfluxEnabled.first()) {
                    connectVpnUseCase.connectOpenFlux()   // throws if URL/token are missing
                } else {
                    val auto = preferences.autoMode.first()
                    val pinned = if (auto) null else configRepository.getSelectedServer()
                    if (pinned != null) connectVpnUseCase(pinned) else connectVpnUseCase.connectAuto()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Always-on connect failed: ${e.message}")
                opMutex.withLock {
                    // Only if no connect took over meanwhile.
                    if (_vpnState.value != VpnState.CONNECTED && _vpnState.value != VpnState.CONNECTING) failSession()
                }
            }
        }
    }

    /** Synchronous startForeground for the current start request. */
    private fun enterForegroundNow(stateOverride: VpnState?) {
        val st = stateOverride ?: when (_vpnState.value) {
            VpnState.CONNECTED, VpnState.BLOCKED -> _vpnState.value   // keep live session text
            else -> VpnState.CONNECTING                               // "Подключение…"
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(currentServerName, st))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    /** For a start request we won't act on: drop foreground + stop, unless a session is live. */
    private fun stopIfIdle(startId: Int) {
        val st = _vpnState.value
        val busy = st == VpnState.CONNECTED || st == VpnState.CONNECTING ||
            st == VpnState.BLOCKED || st == VpnState.DISCONNECTING ||
            connectJob?.isActive == true || disconnectJob?.isActive == true ||
            alwaysOnJob?.isActive == true
        if (busy) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    // ── Hot-switch requests (main thread) ───────────────────────────────────────

    /**
     * Queue a user switch (latest wins) and launch a coroutine that applies it. If the initial
     * connect is still in flight (e.g. long balancer warm-up), the request waits for it to finish
     * and is applied right after a successful connect (dropped if connect fails).
     */
    private fun requestSwitch(req: SwitchRequest, startId: Int) {
        val st = _vpnState.value
        val inFlightConnect = connectJob?.takeIf { it.isActive }
        val live = st == VpnState.CONNECTED || st == VpnState.BLOCKED || st == VpnState.CONNECTING
        if (inFlightConnect == null && (!live || disconnectJob?.isActive == true)) {
            Log.w(TAG, "Switch ignored — no live session ($st)")
            stopIfIdle(startId)
            return
        }
        if (openFluxMode && inFlightConnect == null) {
            // Don't even preempt the watchdog: leaving OpenFlux is a controller-driven reconnect.
            Log.i(TAG, "Switch ignored — OpenFlux session (controller reconnects instead)")
            return
        }
        pendingSwitch.set(req)   // latest wins
        if (inFlightConnect != null) Log.i(TAG, "Switch queued until connect completes")
        serviceScope.launch(switchParent) { applyPendingSwitch(inFlightConnect) }
    }

    private suspend fun applyPendingSwitch(inFlightConnect: Job?) {
        inFlightConnect?.join()
        // A sibling switch coroutine may already have consumed the latest request.
        if (pendingSwitch.get() == null) return
        // Preempt background ops BEFORE taking the lock — cancellation makes them release it.
        watchdogJob?.cancel()
        recoveryJob?.cancel()
        opMutex.withLock {
            val req = pendingSwitch.getAndSet(null)
            val st = _vpnState.value
            if (req == null || tunFd == null || (st != VpnState.CONNECTED && st != VpnState.BLOCKED)) {
                if (req != null) Log.w(TAG, "Switch dropped — no live tunnel ($st)")
                resumeMonitoringLocked()
                return
            }
            if (openFluxMode) {
                // Leaving OpenFlux is a full reconnect driven by ConnectionController.
                Log.i(TAG, "Switch ignored — OpenFlux session (controller reconnects instead)")
                resumeMonitoringLocked()
                return
            }
            when (req) {
                is SwitchRequest.Server -> switchToServerLocked(req.serverId)
                is SwitchRequest.Auto -> switchToAutoLocked(req.config, req.name, req.poolIds)
            }
        }
    }

    /** Restart whichever background monitor matches the current state (after a no-op switch). */
    private fun resumeMonitoringLocked() {
        when (_vpnState.value) {
            VpnState.CONNECTED -> if (watchdogJob?.isActive != true) startWatchdog()
            VpnState.BLOCKED -> if (recoveryJob?.isActive != true) scheduleRecovery()
            else -> Unit
        }
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    private suspend fun connect(
        xrayConfig: String,
        serverName: String,
        hotspotSharing: Boolean = false,
        balancer: Boolean = false,
        poolIds: List<String> = emptyList(),
        openFluxTarget: OpenFluxTarget? = null,
    ) { opMutex.withLock {
        sessionGen++   // only written under opMutex
        // Leftovers of a previous session (e.g. BLOCKED kill-switch TUN, ERROR) — release them
        // so we never overwrite (leak) a live TUN fd / processes.
        if (tunFd != null || xrayManager.isRunning || openFluxManager.isRunning) cleanupProcesses()
        // A refusal reason of the previous OpenFlux session is kept past its teardown for the
        // UI — a new connect (any mode) starts clean.
        _openFluxStatus.value = null
        currentServerName = serverName
        hotspotSharingEnabled = hotspotSharing
        openFluxMode = openFluxTarget != null
        openFlux = openFluxTarget
        balancerMode = balancer && !openFluxMode
        balancerPoolIds = if (balancerMode) poolIds else emptyList()
        _activeMode.value = when {
            openFluxMode -> ActiveMode.OPENFLUX
            balancerMode -> ActiveMode.AUTO
            else -> ActiveMode.MANUAL
        }
        try {
            killSwitchEnabled = preferences.killSwitchEnabled.first()
            tlsFragmentEnabled = preferences.tlsFragmentEnabled.first()
            if (tlsFragmentEnabled) Log.i(TAG, "DPI bypass: TLS ClientHello fragmentation ON")
            currentDns = try {
                dnsRepository.effectiveConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Custom DNS load failed, using defaults: ${e.message}")
                null
            }
            _vpnState.value = VpnState.CONNECTING
            startForeground(NOTIFICATION_ID, buildNotification(serverName, VpnState.CONNECTING))

            // Random loopback SOCKS port for this session. Fixed well-known ports
            // could be pre-bound by another app whenever Xray isn't listening.
            // OpenFlux: one more port for the local bridge + its own random SOCKS5 credentials.
            // (No loopback HTTP inbound any more; the control API is a private Unix socket.)
            val picked = LocalProxyAuth.pickFreeLoopbackPorts(if (openFluxMode) 2 else 1)
            ports = LocalPorts(socks = picked[0])
            if (openFluxMode) {
                bridgePort = picked[1]
                val (bu, bp) = LocalProxyAuth.randomCredentials()
                bridgeUser = bu
                bridgePass = bp
            }

            // Hotspot sharing: stable credentials + per-install random ports for the hotspot inbounds.
            hotspotCreds = if (!hotspotSharing) null else try {
                hotspotPorts = preferences.ensureHotspotPorts()
                preferences.ensureHotspotCredentials()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Hotspot credentials unavailable — sharing disabled for this session: ${e.message}")
                null
            }

            // Generate per-session proxy auth to prevent other apps from using our SOCKS5 proxy
            generateProxyAuth()
            // OpenFlux: the bridge must be listening BEFORE Xray (its "proxy" outbound dials it).
            if (openFluxMode && !startOpenFluxBridge()) {
                failConnect("Failed to start OpenFlux bridge")
                return
            }
            val authedConfig = prepareConfig(if (openFluxMode) openFluxChainTemplate() else xrayConfig)
            lastXrayConfig = authedConfig
            LocalProxyAuth.setSession(proxyUser, proxyPass)

            // 1. Start XRay
            if (!startXray(authedConfig)) {
                failConnect("Failed to start XRay")
                return
            }

            // OpenFlux: don't go CONNECTED before the exit's first session answer. A refusal
            // (busy / expired / …) ends the connect cleanly; "ok"/"error"/timeout → go on (the
            // watchdog handles a silent exit).
            if (openFluxMode) {
                val first = withTimeoutOrNull(OPENFLUX_FIRST_STATUS_TIMEOUT_MS) {
                    openFluxManager.status.first { it.isNotEmpty() }
                }
                if (first in OPENFLUX_REFUSALS) {
                    stopForOpenFluxRefusalLocked(first!!)
                    return
                }
                if (first == null) Log.w(TAG, "OpenFlux: no exit answer within ${OPENFLUX_FIRST_STATUS_TIMEOUT_MS / 1000} s — continuing")
                // Carrier channels report "connecting" at once: give the carrier a moment to join
                // its document/room (or the node's handshake). A captcha or "error" → go on (the
                // in-app prompt / watchdog take over); so does a timeout.
                if (first == "connecting") {
                    val joined = withTimeoutOrNull(OPENFLUX_CARRIER_CONNECT_WAIT_MS) {
                        combine(openFluxManager.status, openFluxManager.captchaUrl) { s, c -> s != "connecting" || c != null }
                            .first { it }
                    }
                    Log.i(TAG, "OpenFlux carrier: ${if (joined == null) "still connecting — continuing" else openFluxManager.status.value}")
                }
            }
            activeSocksPort = currentSocksPort   // expose for in-tunnel subscription refresh

            // 2. Establish TUN interface with current split-tunnel settings
            tunFd = buildTun() ?: run {
                failConnect("Failed to establish TUN interface")
                return
            }
            registerNetworkCallback()

            // 3. Start tun2socks (native fork+exec for proper fd inheritance)
            if (!tun2SocksManager.start(tunFd = tunFd!!.fd, socksPort = currentSocksPort, proxyUser = proxyUser, proxyPass = proxyPass, ownerSocket = ownerUidServer?.name)) {
                failConnect("Failed to start tun2socks")
                return
            }

            // 4. Start traffic monitoring
            trafficMonitor.start()

            // Balancer: stay in CONNECTING (UI shows "searching") until the observatory finds
            // a server that actually reaches Telegram, so CONNECTED is honest. Then PIN that pick
            // (xray api bo) so the route stays put instead of drifting on ping jitter.
            if (balancerMode) {
                pinnedTag = null
                // balancerPoolIds already set from EXTRA_POOL_IDS (authoritative proxy-N mapping).
                // Walk the pool and pin the first FAST-responding server (tested directly via the
                // tunnel) — picks a fast route, not just any alive one.
                warmUpPinFast()
            }

            _vpnState.value = VpnState.CONNECTED
            _connectedServer.value = if (openFluxMode) {
                openFluxProfile()   // synthetic — never touches the Room selection
            } else if (balancerMode) {
                connectedServerForTag(pinnedTag) ?: balancerPlaceholder(serverName)
            } else {
                safeDb("selected server") { configRepository.getSelectedServer() }
            }
            updateNotification(serverName, VpnState.CONNECTED)
            publishHotspotInfo()
            if (BuildConfig.DEBUG) Log.i(TAG, "VPN connected via $serverName")

            // 5. Observe split-tunnel changes → rebuild TUN seamlessly
            startSplitTunnelObserver()
            startDnsObserver()

            // 6. Watchdog — sticky-pin health + seamless re-pin on real failure
            startWatchdog()

            // 7. Background re-verify of the 24h working pool — drop entries that went dead
            startWorkingPoolReverify()

        } catch (e: CancellationException) {
            throw e   // cancelled by disconnect()/onDestroy — they own the teardown
        } catch (e: Exception) {
            failConnect("Connect failed: ${e.message}")
        }
    } }

    // ── OpenFlux (Beta) ──────────────────────────────────────────────────────────

    /**
     * UI model of an OpenFlux session: "OpenFlux · <host of the phpbox URL>" (no path/query) for
     * the user's own exit, "OpenFlux · демо" for the author's demo exit (its host is never shown).
     */
    private fun openFluxProfile(): ServerProfile {
        val target = openFlux
        val label = if (target?.demo == true) {
            "демо"
        } else if (target != null && !target.direct) {
            // Carrier channels: no exit host in the settings — name the channel (never the link).
            when (target.transport) {
                "mailru" -> "Mail.ru"
                "cups" -> "Cups"
                else -> "ссылка"
            }
        } else {
            target?.url?.let { u -> runCatching { java.net.URI(u).host }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: "?"
        }
        return ServerProfile(
            id = OPENFLUX_SERVER_ID, name = "OpenFlux · $label", flag = "🧪",
            country = "OpenFlux", address = "", port = 0, vlessUri = "",
        )
    }

    /** Xray → bridge chain template for the current session ports (prepareConfig adds auth etc.). */
    private fun openFluxChainTemplate(): String =
        XrayConfigBuilder.buildSocksChain(ports.socks, bridgePort, bridgeUser, bridgePass)

    /** (Re)start the bridge on the session's bridge port/credentials. Caller holds opMutex. */
    private suspend fun startOpenFluxBridge(): Boolean {
        val target = openFlux ?: return false
        // Stable per install → cached; the first call happens inside connect()'s try.
        val clientId = installIdCache ?: preferences.installId().also { installIdCache = it }
        return openFluxManager.start(
            target.url, target.token, clientId, bridgePort, bridgeUser, bridgePass, target.demo,
            transport = target.transport, link = target.link, secret = target.secret,
        )
    }

    /**
     * Connect-time refusal by the OpenFlux exit: tear down like a normal disconnect (no ERROR,
     * no kill-switch), keep [openFluxStatus] = [reason], then leave the foreground once the
     * connect coroutine has returned. Caller (connect) holds opMutex.
     */
    private fun stopForOpenFluxRefusalLocked(reason: String) {
        Log.w(TAG, "OpenFlux exit refused the session ('$reason') — stopping")
        pendingSwitch.set(null)
        teardownSessionLocked()
        _openFluxStatus.value = reason
        _vpnState.value = VpnState.DISCONNECTED
        serviceScope.launch(Dispatchers.Main) {
            // connectJob is main-thread state: read it here. It is the calling connect (CONNECT
            // is ignored while that is active) or, if it already finished, possibly a newer one.
            val job = connectJob
            job?.join()
            // Skip if a newer connect / a user disconnect took over meanwhile.
            if (_vpnState.value == VpnState.DISCONNECTED && connectJob === job) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            }
        }
    }

    /**
     * Rebuild the whole OpenFlux chain under the live TUN: restart the bridge (same port/creds;
     * a fresh port if that one got taken meanwhile), then start Xray with a freshly built chain
     * config. Used by the watchdog's one-shot restart and by kill-switch recovery (replaces the
     * blackhole Xray). Caller holds opMutex.
     */
    private suspend fun recoverOpenFluxLocked(): Boolean {
        openFluxManager.stop()
        if (!startOpenFluxBridge()) {
            Log.w(TAG, "OpenFlux bridge restart failed — retrying on a fresh port")
            bridgePort = LocalProxyAuth.pickFreeLoopbackPorts(1)[0]
            if (!startOpenFluxBridge()) return false
        }
        val cfg = prepareConfig(openFluxChainTemplate())
        if (!startXray(cfg)) return false   // start() replaces the old / blackhole process
        lastXrayConfig = cfg
        return true
    }

    /** UI model for the Auto session before/without a resolved pinned server. */
    private fun balancerPlaceholder(name: String) = ServerProfile(
        id = "__balancer__", name = name, flag = "⚖️",
        country = "Auto", address = "", port = 0, vlessUri = "",
    )

    /**
     * Initial connect failed: tear everything down, publish ERROR and leave the foreground
     * state so the service doesn't linger with a stale "connecting" notification.
     * Caller holds opMutex.
     */
    private fun failConnect(reason: String) {
        Log.e(TAG, reason)
        pendingSwitch.set(null)   // a switch queued behind a failed connect is dropped
        failSession()
    }

    /**
     * Session is unrecoverable (or kill switch is off): tear down, publish ERROR and leave the
     * foreground so no stale "active" notification lingers. Caller holds opMutex.
     */
    private fun failSession() {
        cleanupProcesses()
        activeSocksPort = 0
        LocalProxyAuth.clearSession()
        _vpnState.value = VpnState.ERROR
        Handler(Looper.getMainLooper()).post {
            // Skip if a new connect already started meanwhile (it owns the foreground now).
            if (_vpnState.value == VpnState.ERROR && connectJob?.isActive != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            }
        }
    }

    // ── Proxy auth (CVE mitigation) ──────────────────────────────────────────

    /** Generate random credentials for this VPN session. */
    private fun generateProxyAuth() {
        val (u, p) = LocalProxyAuth.randomCredentials()
        proxyUser = u
        proxyPass = p
    }

    /**
     * THE config chokepoint — every Xray start (connect, hot-switch, auto-switch, recovery,
     * rebuild, restart, kill-switch blackhole) goes through here. Idempotent. It:
     *  1. rewrites the local inbounds to the session's random loopback ports and forces them to
     *     listen on 127.0.0.1 only ([rewriteLocalPorts]);
     *  2. adds the hotspot inbounds (bound to the CURRENT hotspot IP only) when sharing is on;
     *  3. injects the per-session SOCKS/HTTP auth into the loopback inbounds (never hotspot-*);
     *  4. inserts the loopback/TUN-subnet (and hotspot→private) block rules;
     *  5. applies the user's custom DNS ([withDns] = false for the blackhole config, which has no
     *     proxy outbound for the resolver rule to point at).
     * Also records the hotspot endpoint baked into this config in [configHotspot].
     */
    private fun prepareConfig(configJson: String, withDns: Boolean = true): String {
        val root = JSONObject(configJson)

        // Hotspot endpoint for this config: re-resolved every time so a restart never tries to
        // bind an IP that disappeared (hotspot turned off) — and picks it up once it appears.
        val creds = hotspotCreds
        val hPorts = hotspotPorts
        val hotspotIp = if (hotspotSharingEnabled && creds != null && hPorts != null) HotspotHelper.getHotspotIp(this) else null
        val hotspot = if (hotspotIp != null && creds != null && hPorts != null) {
            HotspotInfo(hotspotIp, hPorts.first, hPorts.second, creds.first, creds.second)
        } else null

        rewriteLocalPorts(root, ports, hotspot)
        injectProxyAuth(root)
        // DPI bypass: VPN-server outbounds dial via a ClientHello-fragmenting freedom outbound.
        // Never for OpenFlux (its chain goes to the local bridge); `false` strips it (idempotent).
        XrayConfigBuilder.applyTlsFragment(root, tlsFragmentEnabled && !openFluxMode)
        // Hotspot inbounds: only real hotspot clients may use them — connections from the phone
        // itself (its hotspot IP, loopback, or any address outside the hotspot subnet) are
        // blackholed, so apps outside the tunnel can't CONNECT through the no-auth HTTP inbound
        // to learn the exit IP (issue #8).
        XrayConfigBuilder.applyLocalBlockRules(
            root,
            if (hotspot != null) listOf(HotspotInfo.TAG_HTTP, HotspotInfo.TAG_SOCKS) else emptyList(),
            hotspotIp = hotspot?.ip,
            hotspotPrefix = hotspot?.ip?.let { HotspotHelper.getPrefixLength(it) } ?: 24,
        )
        configHotspot = hotspot

        val rendered = root.toString(2)
        // The bundled Xray once refused the API Unix socket → keep the API off (see startXray).
        val authed = if (apiDisabled) stripApi(rendered) ?: rendered else rendered
        if (!withDns) return authed
        val userDns = currentDns ?: return authed
        // OpenFlux: the resolver's traffic goes to the bridge, which only does TCP CONNECT to
        // 80/443 — plain UDP/TCP DNS and DoH on other ports would silently fail (CONNECTED but
        // nothing resolves). Keep DoH:443 only (default DoH if none is left).
        val dns = if (openFluxMode) {
            DnsConfigInjector.restrictToDoh443(userDns).also { filtered ->
                if (filtered !== userDns && openFluxDnsWarnedFor != userDns) {
                    openFluxDnsWarnedFor = userDns   // log once per DNS setting, not per restart
                    val kept = userDns.servers.count { it in filtered.servers }
                    val fallback = if (kept == 0) " — using default DoH" else ""
                    Log.w(TAG, "OpenFlux: dropped ${userDns.servers.size - kept} non-DoH:443 DNS " +
                        "server(s) (bridge only reaches 80/443)$fallback")
                }
            }
        } else userDns
        return try {
            DnsConfigInjector.inject(authed, dns)
        } catch (e: Exception) {
            Log.e(TAG, "DNS inject failed, keeping default DNS: ${e.message}")
            authed
        }
    }

    /**
     * Local inbounds → session SOCKS port, loopback-only; the loopback HTTP inbound is dropped and
     * the API inbound moves to the private Unix socket. Old hotspot-* inbounds are dropped and, if
     * [hotspot] is non-null, re-added bound to the hotspot IP (never 0.0.0.0):
     * hotspot-http (no auth — Android's Wi-Fi proxy can't authenticate; the hotspot password is
     * the gate) and hotspot-socks (stable user/password, UDP).
     */
    private fun rewriteLocalPorts(root: JSONObject, p: LocalPorts, hotspot: HotspotInfo?) {
        val old = root.optJSONArray("inbounds") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val inbound = old.optJSONObject(i) ?: continue
            val tag = inbound.optString("tag")
            if (tag.startsWith("hotspot-")) continue
            // No loopback HTTP inbound: nothing in the app uses it (tun2socks, in-tunnel refresh
            // and micro-tests are SOCKS-only) — one less local listener to probe.
            if (tag == "http") continue
            if (tag == "api") {
                // Control API → private Unix socket (never a TCP port other apps could reach).
                out.put(XrayConfigBuilder.apiInbound(xrayManager.apiSocketPath))
                continue
            }
            val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
            if (tag == "socks") {
                inbound.put("port", p.socks)
                settings.put("ip", "127.0.0.1")
            }
            inbound.put("listen", "127.0.0.1")   // main inbounds are ALWAYS loopback-only
            out.put(inbound)
        }
        // ObservatoryService is never queried by the app; only RoutingService (balancer bo/bi).
        root.optJSONObject("api")?.put("services", JSONArray().apply { put("RoutingService") })
        if (hotspot != null) {
            out.put(JSONObject().apply {
                put("tag", HotspotInfo.TAG_HTTP)
                put("port", hotspot.httpPort)
                put("listen", hotspot.ip)
                put("protocol", "http")
                put("settings", JSONObject().apply { put("allowTransparent", false) })
            })
            out.put(JSONObject().apply {
                put("tag", HotspotInfo.TAG_SOCKS)
                put("port", hotspot.socksPort)
                put("listen", hotspot.ip)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "password")
                    put("accounts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("user", hotspot.user)
                            put("pass", hotspot.pass)
                        })
                    })
                    put("udp", true)
                    put("ip", hotspot.ip)
                })
            })
        }
        root.put("inbounds", out)
    }

    /**
     * Inject the per-session SOCKS5/HTTP auth into the LOOPBACK inbounds so no unauthenticated
     * local app can use our proxy to discover the exit IP. hotspot-* inbounds keep their own
     * (stable / no) auth.
     */
    private fun injectProxyAuth(root: JSONObject) {
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("tag").startsWith("hotspot-")) continue
            val settings = inbound.optJSONObject("settings") ?: JSONObject().also { inbound.put("settings", it) }
            val accounts = JSONArray().apply {
                put(JSONObject().apply {
                    put("user", proxyUser)
                    put("pass", proxyPass)
                })
            }
            when (inbound.optString("protocol")) {
                "socks" -> {
                    settings.put("auth", "password")
                    settings.put("accounts", accounts)
                }
                "http" -> {
                    settings.put("accounts", accounts)
                }
            }
        }
    }

    /** Publish the hotspot endpoint of the running config (call on every → CONNECTED). */
    private fun publishHotspotInfo() {
        _hotspotInfo.value = if (_vpnState.value == VpnState.CONNECTED) configHotspot else null
    }

    /**
     * Custom DNS changed while connected → re-inject into the live config and restart Xray
     * (no API for hot DNS reload). TUN and tun2socks stay as they are.
     */
    @OptIn(FlowPreview::class)
    private fun startDnsObserver() {
        dnsObserverJob?.cancel()
        dnsObserverJob = serviceScope.launch {
            dnsRepository.effective
                .drop(1)          // initial value was applied at connect
                .debounce(800)    // several quick edits on the DNS screen → one restart
                .collect { dns ->
                    opMutex.withLock {
                        currentDns = dns
                        if (_vpnState.value != VpnState.CONNECTED || lastXrayConfig.isEmpty()) return@withLock
                        Log.i(TAG, "DNS settings changed — restarting Xray")
                        // restartXray() re-runs prepareConfig → re-injects the new currentDns.
                        if (!restartXray()) {
                            enterBlackhole("xray restart after DNS change failed")
                            return@withLock
                        }
                        publishHotspotInfo()
                        if (balancerMode) {
                            pinnedTag = null   // restart drops the balancer override
                            warmUpPinFast()
                            connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
                        }
                    }
                }
        }
    }

    // ── TUN building ───────────────────────────────────────────────────────────

    /**
     * Creates (or recreates) the TUN interface with the current split-tunnel preferences.
     * Calling this while connected rebuilds the interface seamlessly — Android handles
     * the handover when establish() is called again from the same VpnService instance.
     */
    private suspend fun buildTun(): ParcelFileDescriptor? {
        val splitEnabled = preferences.splitTunnelEnabled.first()
        val splitMode = preferences.splitTunnelMode.first()
        // "Selected apps": bypass VPN in EXCLUDE mode, the only apps via VPN in INCLUDE mode.
        val selectedApps = preferences.excludedApps.first()

        fun newBaseBuilder(): Builder = Builder()
            .setSession("MyAppVPN")
            // Small, rarely-used private subnets: 10.0.0.x/24 collided with common home LANs.
            .addAddress("172.19.0.1", 30)
            .addAddress("fdfe:dcba:9876::1", 126)   // IPv6 — prevents leak outside tunnel
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            // Placeholder resolver INSIDE the TUN subnet: every :53 query is hijacked to Xray's DNS
            // anyway. NOT 1.1.1.1/8.8.8.8 — Chrome-based browsers see a known DoH provider and
            // auto-upgrade to their own DoH, bypassing our DNS (custom servers, IPv4-only answers);
            // in OpenFlux mode that handed browsers IPv6 addresses the exit can't dial.
            .addDnsServer("172.19.0.2")
            .addDnsServer("fdfe:dcba:9876::2")
            // Follow the system default network; the network callback pins the actual one.
            .setUnderlyingNetworks(null)
            .apply {
                // VPN itself isn't metered — metering is inherited from the underlying network.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setMetered(false)
            }

        var builder = newBaseBuilder()

        // NOTE: VpnService.Builder forbids mixing addAllowedApplication and
        // addDisallowedApplication (UnsupportedOperationException). INCLUDE mode therefore uses
        // ONLY the allow-list; our own package stays outside the tunnel simply by not being in it
        // (prevents the XRay→TUN routing loop just like the disallow does in EXCLUDE mode).
        val includeSelection = selectedApps.filter { it != packageName }
        var allowedCount = 0
        if (splitEnabled && splitMode == AppPreferences.SPLIT_MODE_INCLUDE && includeSelection.isNotEmpty()) {
            includeSelection.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                    allowedCount++
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Split tunnel (include): package not found — $pkg")
                }
            }
            if (allowedCount > 0) {
                Log.i(TAG, "Split tunnel (include): $allowedCount apps routed via VPN")
            } else {
                // Every selected package is gone → with no allowed apps the builder would route
                // EVERYTHING (incl. our own Xray) into the TUN. Rebuild as full VPN instead.
                Log.w(TAG, "Split tunnel (include): no selected app is installed — falling back to full VPN")
                builder = newBaseBuilder()
            }
        }

        if (allowedCount == 0) {
            if (splitEnabled && splitMode == AppPreferences.SPLIT_MODE_INCLUDE && includeSelection.isEmpty()) {
                Log.w(TAG, "Split tunnel (include): selection is empty — full VPN for all apps")
            }

            // Always exclude our own package to prevent XRay→TUN routing loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Could not exclude own package: ${e.message}")
            }

            // EXCLUDE mode: user-selected apps bypass VPN
            if (splitEnabled && splitMode != AppPreferences.SPLIT_MODE_INCLUDE && selectedApps.isNotEmpty()) {
                var excludedCount = 0
                selectedApps.forEach { pkg ->
                    if (pkg == packageName) return@forEach
                    try {
                        builder.addDisallowedApplication(pkg)
                        excludedCount++
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Split tunnel: package not found — $pkg")
                    }
                }
                Log.i(TAG, "Split tunnel: excluded $excludedCount apps")
            }
        }

        val fd = builder.establish() ?: return null
        // INCLUDE mode: also restrict the owner check to the routed apps (defence in depth).
        val ownerAllowedAppIds = if (allowedCount > 0) {
            OwnerUidServer.appIdsForPackages(packageManager, includeSelection)
        } else null
        restartOwnerUidServer(ownerAllowedAppIds)
        return fd
    }

    /**
     * Replaces the socket-owner server for a freshly established TUN (issue #8: apps OUTSIDE the
     * VPN binding to tun0 must not reach the exit). Fail-open: if the socket can't be bound,
     * tun2socks simply runs without -owner-socket.
     */
    private fun restartOwnerUidServer(allowedAppIds: Set<Int>?) {
        ownerUidServer?.stop()
        val server = OwnerUidServer(this, allowedAppIds)
        if (server.start()) {
            ownerUidServer = server
            liveOwnerServer = server
            Log.i(TAG, "Owner check: on (include-list ${allowedAppIds?.size ?: "off"})")
        } else {
            ownerUidServer = null
            liveOwnerServer = null
            Log.w(TAG, "Owner check: OwnerUidServer failed to start — tun2socks runs without it")
        }
    }

    private fun stopOwnerUidServer() {
        ownerUidServer?.stop()
        ownerUidServer = null
        liveOwnerServer = null
    }

    // ── Underlying network tracking ────────────────────────────────────────────

    /**
     * Track the default (physical) network while a session exists: our own app is excluded from
     * the TUN, so this callback sees the real Wi-Fi / mobile network. Point the VPN's underlying
     * network at it (correct metering/caps for apps) and flag handovers for the watchdog.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        // The first onAvailable after registering is the CURRENT network, not a handover —
        // a value left over from the previous session made every connect look like one.
        lastDefaultNetwork = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = lastDefaultNetwork
                lastDefaultNetwork = network
                if (prev != null && prev != network) {
                    Log.i(TAG, "Default network changed — handover")
                    networkSwitched = true
                }
                try { setUnderlyingNetworks(arrayOf(network)) } catch (e: Exception) {
                    Log.w(TAG, "setUnderlyingNetworks failed: ${e.message}")
                }
            }

            override fun onLost(network: Network) {
                if (lastDefaultNetwork == network) {
                    try { setUnderlyingNetworks(null) } catch (_: Exception) {}
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        lastDefaultNetwork = null
        networkSwitched = false
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
    }

    // ── Split tunnel hot-reload ────────────────────────────────────────────────

    /**
     * Watches DataStore for split-tunnel changes while VPN is connected.
     * On change: rebuilds TUN + restarts tun2socks. XRay keeps running — no server reconnect needed.
     */
    @OptIn(FlowPreview::class)
    private fun startSplitTunnelObserver() {
        splitTunnelObserverJob?.cancel()
        splitTunnelObserverJob = serviceScope.launch {
            combine(
                preferences.splitTunnelEnabled,
                preferences.splitTunnelMode,
                preferences.excludedApps,
            ) { enabled, mode, apps -> Triple(enabled, mode, apps) }
                // Each pref flow re-emits on ANY DataStore write — ignore unrelated keys.
                .distinctUntilChanged()
                .drop(1) // skip the initial emission (already applied at connect)
                // Bulk ops / rapid toggles → rebuild the TUN once after things settle.
                .debounce(600)
                .collect { reconnectTun() }
        }
    }

    /**
     * Rebuilds TUN and restarts tun2socks with new split-tunnel settings.
     * XRay is NOT touched — the VLESS connection to the server is preserved.
     */
    private suspend fun reconnectTun() = opMutex.withLock { reconnectTunLocked() }

    private suspend fun reconnectTunLocked() {
        if (_vpnState.value != VpnState.CONNECTED || tunFd == null) return
        Log.i(TAG, "Split tunnel settings changed — rebuilding TUN interface")

        // Stop tun2socks that holds the old TUN fd
        tun2SocksManager.stop()

        // Build new TUN (Android replaces the old one automatically)
        val newFd = buildTun()
        if (newFd == null) {
            Log.e(TAG, "Failed to rebuild TUN after split tunnel change")
            enterBlackhole("TUN rebuild failed")
            return
        }

        // The old fd is now invalid — close it and keep the new one
        tunFd?.close()
        tunFd = newFd
        // The fresh interface starts with underlying=null — re-pin the tracked default network.
        lastDefaultNetwork?.let { net ->
            try { setUnderlyingNetworks(arrayOf(net)) } catch (_: Exception) {}
        }

        // Restart tun2socks with the fresh fd
        if (!tun2SocksManager.start(tunFd = newFd.fd, socksPort = currentSocksPort, proxyUser = proxyUser, proxyPass = proxyPass, ownerSocket = ownerUidServer?.name)) {
            Log.e(TAG, "Failed to restart tun2socks after TUN rebuild")
            enterBlackhole("tun2socks restart after TUN rebuild failed")
            return
        }

        Log.i(TAG, "TUN rebuilt — split tunnel settings applied without reconnect")
    }

    // ── Watchdog ───────────────────────────────────────────────────────────────

    /**
     * Health monitor. Probes run WITHOUT the lock; every action that touches xray / tun2socks /
     * the pin runs under opMutex and re-checks that this watchdog is still current first (a user
     * switch / disconnect cancels it and may already have changed the session).
     *
     * Auto (balancer): sticky pin, seamless re-pin, full rebuild when the pool is dead.
     * Manual (single server): NO silent failover — the user's pinned server is restarted once with
     * the same config; if it still fails, the kill-switch blocks traffic and recovery keeps retrying
     * that same server.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        if (openFluxMode) {
            startOpenFluxWatchdog()
            return
        }
        watchdogJob = serviceScope.launch {
            // Still the live watchdog of a CONNECTED session? (checked under the lock before acting)
            fun stillCurrent() = isActive && _vpnState.value == VpnState.CONNECTED

            delay(30_000L + (Math.random() * 10_000L).toLong()) // jittered grace period
            var successRecorded = false
            var failStreak = 0
            var lastTgProbeAt = 0L   // balancer: timestamp of last Telegram reachability probe
            // Manual mode: one same-config restart per failure episode (reset by a good check).
            var manualRestartUsed = false
            while (isActive) {
                // Randomized intervals to avoid DPI fingerprinting fixed-cadence health checks
                val jitter = (Math.random() * 15_000L).toLong()
                delay(if (failStreak == 0) 30_000L + jitter else 8_000L + jitter)
                if (_vpnState.value != VpnState.CONNECTED) break

                // Wi-Fi ↔ mobile handover since the last tick: probes failing around it say
                // nothing about the server — start a fresh failure episode.
                if (networkSwitched) {
                    networkSwitched = false
                    if (failStreak > 0 || manualRestartUsed) Log.i(TAG, "Network handover — resetting failure streak")
                    failStreak = 0
                    manualRestartUsed = false
                    continue
                }

                // XRay process crash → restart the same config (both modes; never switch server)
                if (!xrayManager.isRunning) {
                    if (balancerMode) {
                        Log.w(TAG, "XRay died (balancer) — restarting same config")
                        val restarted = opMutex.withLock {
                            if (!stillCurrent()) return@withLock null
                            if (!restartXray()) {
                                enterBlackhole("balancer xray restart failed")
                                return@withLock false
                            }
                            pinnedTag = null              // restart drops the override
                            warmUpPinFast()               // re-pin onto a fast alive server (avoid cold-start)
                            connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
                            true
                        }
                        if (restarted != true) break
                        lastTgProbeAt = System.currentTimeMillis()
                        failStreak = 0
                        continue
                    }
                    Log.w(TAG, "XRay died (manual) — restarting the pinned server's config")
                    safeDb("xray-died stats") {
                        configRepository.getSelectedServer()?.let {
                            configRepository.incrementConsecutiveFailure(it.id)
                        }
                    }
                    val restarted = opMutex.withLock {
                        if (!stillCurrent()) return@withLock null
                        if (manualRestartUsed || !restartXray()) {
                            enterBlackhole("manual xray died, restart failed")
                            return@withLock false
                        }
                        true
                    }
                    if (restarted != true) break
                    manualRestartUsed = true
                    failStreak = 0
                    continue
                }

                // tun2socks crash → restart it on the existing TUN fd (Xray + TUN stay as-is).
                if (!tun2SocksManager.isRunning) {
                    Log.w(TAG, "tun2socks died while CONNECTED — restarting on existing TUN")
                    val restarted = opMutex.withLock {
                        if (!stillCurrent()) return@withLock null
                        if (tun2SocksManager.isRunning) return@withLock true
                        val fd = tunFd
                        val ok = fd != null && tun2SocksManager.start(
                            tunFd = fd.fd, socksPort = currentSocksPort, proxyUser = proxyUser, proxyPass = proxyPass,
                            ownerSocket = ownerUidServer?.name,
                        )
                        if (!ok) {
                            Log.e(TAG, "tun2socks restart failed")
                            enterBlackhole("tun2socks restart failed")
                        }
                        ok
                    }
                    if (restarted != true) break
                    Log.i(TAG, "tun2socks restarted")
                }

                // If underlying network is gone (Doze / airplane) — skip, not a VPN failure
                if (!isUnderlyingNetworkAvailable()) {
                    Log.d(TAG, "No underlying network (Doze?) — skipping health check")
                    failStreak = 0
                    continue
                }

                // High XRay error rate = VLESS tunnel broken → treat as failure without HTTP probe
                val xrayErrorsHigh = xrayManager.errorRate > 10

                if (balancerMode) {
                    // ── STICKY balancer health (pin orchestrator) ──
                    // Re-evaluate only every ~5 min. The route is PINNED (xray api bo), so it never
                    // drifts on ping jitter. The only signal is real Telegram reachability: pinned
                    // route loads Telegram within ~18s → stay. On failure we ask the balancer for a
                    // new best-alive pick and move the override to it — seamless, NO Xray restart.
                    val now = System.currentTimeMillis()
                    if (!xrayErrorsHigh && now - lastTgProbeAt < BALANCER_PROBE_INTERVAL_MS) continue
                    lastTgProbeAt = now

                    var ok = !xrayErrorsHigh &&
                        withContext(Dispatchers.IO) { isTelegramReachableViaSocks(currentSocksPort) }
                    if (!ok && !xrayErrorsHigh) {
                        // One immediate confirm re-probe so a single transient blip doesn't churn.
                        ok = withContext(Dispatchers.IO) { isTelegramReachableViaSocks(currentSocksPort) }
                    }
                    if (!stillCurrent()) break

                    if (ok) {
                        failStreak = 0   // pinned route works → stay (sticky)
                        // Refresh the pinned server's 24h "verified working" stamp.
                        tagToServerId(pinnedTag)?.let { safeDb("markVerified") { configRepository.markVerified(it) } }
                        if (pinnedTag == null) {
                            // lost pin → re-establish
                            opMutex.withLock {
                                if (stillCurrent() && pinnedTag == null && pinBestAlive()) {
                                    connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
                                }
                            }
                        }
                    } else {
                        failStreak++
                        Log.w(TAG, "Balancer: pinned route unreachable ~${TELEGRAM_PROBE_TIMEOUT_MS / 1000}s #$failStreak (xrayErrors=${xrayManager.errorRate})")
                        // The pinned route actually failed → drop it from the 24h working pool.
                        tagToServerId(pinnedTag)?.let { safeDb("markFailed") { configRepository.markFailed(it) } }
                        // Seamless re-pin: balancer's `Selects` excludes the just-died server.
                        val repinned: String? = opMutex.withLock {
                            if (!stillCurrent()) return@withLock null
                            val (_, selects) = balancerInfo()
                            if (selects != null && selects != pinnedTag && pinBalancer(selects)) {
                                pinnedTag = selects
                                connectedServerForTag(selects)?.let { _connectedServer.value = it }
                                selects
                            } else {
                                null
                            }
                        }
                        if (!stillCurrent()) break
                        if (repinned != null) {
                            Log.i(TAG, "Balancer re-pinned to $repinned — confirming…")
                            // CONFIRM the new pin actually reaches Telegram before trusting it
                            // (probe runs outside the lock). Only a confirmed route enters the 24h pool.
                            delay(2_000L)
                            val confirmed = withContext(Dispatchers.IO) { isTelegramReachableViaSocks(currentSocksPort) }
                            val sid = tagToServerId(repinned)
                            if (confirmed) {
                                sid?.let { safeDb("markVerified") { configRepository.markVerified(it) } }
                                Log.i(TAG, "Balancer re-pin to $repinned confirmed working")
                                failStreak = 0
                            } else {
                                sid?.let { safeDb("markFailed") { configRepository.markFailed(it) } }
                                Log.w(TAG, "Balancer re-pin to $repinned still unreachable — will retry/rebuild")
                                // leave failStreak growing → escalates to rebuild below on next cycles
                            }
                        } else if (failStreak >= 3) {
                            // No alternative alive pick for ~minutes → whole pool likely bad → re-fetch,
                            // then warm-up-pin onto a confirmed-alive server (avoid cold-start blind routing).
                            Log.w(TAG, "Balancer: no alive pick — full rebuild (re-fetch)")
                            val rebuilt = opMutex.withLock {
                                if (!stillCurrent()) return@withLock null
                                if (!rebuildBalancer()) {
                                    // Kill switch: keep the TUN up and block traffic; recovery
                                    // retries the rebuild. (Was ERROR + TUN teardown = leak.)
                                    enterBlackhole("balancer rebuild failed")
                                    return@withLock false
                                }
                                warmUpPinFast()
                                connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
                                true
                            }
                            if (rebuilt != true) break
                            failStreak = 0
                        }
                        lastTgProbeAt = System.currentTimeMillis()
                    }
                } else {
                    // ── Single-server (manual) mode: probe every tick, never switch server ──
                    val internetOk = if (xrayErrorsHigh) {
                        Log.w(TAG, "XRay errorRate=${xrayManager.errorRate} — treating as internet fail")
                        false
                    } else {
                        isInternetReachableViaSocks(currentSocksPort)
                    }
                    if (!stillCurrent()) break

                    if (!internetOk) {
                        failStreak++
                        Log.w(TAG, "Health check failed #$failStreak (xrayErrors=${xrayManager.errorRate})")
                        if (failStreak >= 2) {
                            // Stats only — the user's pin is NOT changed.
                            safeDb("failure stats") {
                                configRepository.getSelectedServer()?.let {
                                    configRepository.incrementConsecutiveFailure(it.id)
                                    configRepository.recordFailure(it.id)
                                    configRepository.markFailed(it.id)   // drop from 24h working pool
                                }
                            }
                            if (manualRestartUsed) {
                                Log.w(TAG, "Manual server still unreachable after restart — kill-switch")
                                opMutex.withLock {
                                    if (stillCurrent()) enterBlackhole("manual server unreachable")
                                }
                                break
                            }
                            Log.w(TAG, "2 consecutive failures — restarting the pinned server once")
                            val restarted = opMutex.withLock {
                                if (!stillCurrent()) return@withLock null
                                if (!restartXray()) {
                                    enterBlackhole("manual xray restart failed")
                                    return@withLock false
                                }
                                true
                            }
                            if (restarted != true) break
                            manualRestartUsed = true
                            failStreak = 0
                        }
                        continue
                    }

                    failStreak = 0
                    manualRestartUsed = false
                    safeDb("selected server") { configRepository.getSelectedServer() }?.let { server ->
                        // Mark as verified + reset consecutive failures on first success
                        if (!successRecorded) {
                            successRecorded = true
                            safeDb("success stats") {
                                configRepository.recordSuccess(server.id)
                                configRepository.markVerified(server.id)
                                configRepository.resetConsecutiveFailures(server.id)
                            }
                            Log.d(TAG, "Server verified: ${server.name}")
                            // Start background probe to build the verified reserve (manual list)
                            startBackgroundProbe()
                        }

                        // ── Quick speed check: stats only (manual mode never switches) ──
                        val speed = withContext(Dispatchers.IO) {
                            measureQuickSpeedMbps(currentSocksPort)
                        }
                        if (speed > 0f) {
                            safeDb("updateSpeed") { configRepository.updateSpeed(server.id, speed) }
                            Log.d(TAG, "Speed: ${"%.0f".format(speed * 1024)} KB/s")
                        }
                        if (speed in 0.01f..0.09f) {
                            Log.w(TAG, "Throttled? ${"%.0f".format(speed * 1024)} KB/s — staying on the pinned server")
                        }
                    }
                }
            }
        }
    }

    /**
     * OpenFlux (Beta) watchdog: bridge alive (restart on the same port/creds if it died), Xray
     * alive, tun2socks alive, then the generic internet probe through the session SOCKS. Two
     * failed probes → rebuild bridge + Xray once; still failing → kill-switch (recovery keeps
     * rebuilding the chain). No throttling/speed checks, background probe or pool re-verify —
     * there is no server list in this mode. Same locking discipline as [startWatchdog].
     */
    private fun startOpenFluxWatchdog() {
        watchdogJob = serviceScope.launch {
            // A refusal (stop pending) is never "current": no restart / kill-switch over it.
            fun stillCurrent() = isActive && _vpnState.value == VpnState.CONNECTED && openFluxMode &&
                openFluxManager.status.value !in OPENFLUX_REFUSALS

            delay(20_000L + (Math.random() * 10_000L).toLong()) // jittered grace period
            var failStreak = 0
            var restartUsed = false   // one bridge+xray rebuild per failure episode
            while (isActive) {
                val jitter = (Math.random() * 15_000L).toLong()
                delay(if (failStreak == 0) 30_000L + jitter else 8_000L + jitter)
                if (!stillCurrent()) break

                if (networkSwitched) {
                    networkSwitched = false
                    if (failStreak > 0 || restartUsed) Log.i(TAG, "Network handover — resetting failure streak")
                    failStreak = 0
                    restartUsed = false
                    continue
                }

                // Bridge died → restart it on the same port/credentials (Xray keeps dialing it).
                if (!openFluxManager.isRunning) {
                    Log.w(TAG, "OpenFlux bridge died — restarting")
                    val ok = opMutex.withLock {
                        if (!stillCurrent()) return@withLock null
                        if (openFluxManager.isRunning) return@withLock true
                        if (startOpenFluxBridge()) return@withLock true
                        // Port taken meanwhile? Full chain rebuild moves the bridge to a fresh port.
                        if (recoverOpenFluxLocked()) {
                            publishHotspotInfo()
                            return@withLock true
                        }
                        enterBlackhole("openflux bridge restart failed")
                        false
                    }
                    if (ok != true) break
                }

                // Xray crash → restart the same chain config.
                if (!xrayManager.isRunning) {
                    Log.w(TAG, "XRay died (OpenFlux) — restarting the chain config")
                    val ok = opMutex.withLock {
                        if (!stillCurrent()) return@withLock null
                        if (restartUsed || !restartXray()) {
                            enterBlackhole("openflux xray died, restart failed")
                            return@withLock false
                        }
                        true
                    }
                    if (ok != true) break
                    restartUsed = true
                    failStreak = 0
                    continue
                }

                // tun2socks crash → restart it on the existing TUN fd.
                if (!tun2SocksManager.isRunning) {
                    Log.w(TAG, "tun2socks died while CONNECTED (OpenFlux) — restarting on existing TUN")
                    val ok = opMutex.withLock {
                        if (!stillCurrent()) return@withLock null
                        if (ensureTun2SocksLocked()) return@withLock true
                        enterBlackhole("tun2socks restart failed")
                        false
                    }
                    if (ok != true) break
                }

                if (!isUnderlyingNetworkAvailable()) {
                    Log.d(TAG, "No underlying network (Doze?) — skipping health check")
                    failStreak = 0
                    continue
                }

                // Exit refused us (busy / expired / ended / quota / noclient / badtoken): the
                // session is being stopped by stopOnOpenFluxRefusal — never restart/blackhole.
                val bridgeState = openFluxManager.status.value
                if (bridgeState in OPENFLUX_REFUSALS) {
                    Log.i(TAG, "OpenFlux exit reports '$bridgeState' — session stop pending")
                    break
                }

                // A carrier waits for the user's browser check: a restart would only lose it.
                if (openFluxManager.captchaUrl.value != null) {
                    Log.i(TAG, "OpenFlux carrier waits for a browser check — skipping health check")
                    failStreak = 0
                    continue
                }

                // Carrier channels say "error" when the carrier stays disconnected for a minute:
                // a failed check without probing (→ the usual one-shot restart, then kill-switch).
                val internetOk = if (bridgeState == "error" && openFlux?.direct == false) {
                    false
                } else withContext(Dispatchers.IO) { isInternetReachableViaSocks(currentSocksPort) }
                if (!stillCurrent()) break
                if (internetOk) {
                    failStreak = 0
                    restartUsed = false
                    continue
                }

                failStreak++
                Log.w(TAG, "OpenFlux health check failed #$failStreak")
                if (failStreak < 2) continue
                if (restartUsed) {
                    Log.w(TAG, "OpenFlux still unreachable after restart — kill-switch")
                    opMutex.withLock {
                        if (stillCurrent()) enterBlackhole("openflux unreachable")
                    }
                    break
                }
                Log.w(TAG, "2 consecutive failures — restarting OpenFlux bridge + Xray once")
                val ok = opMutex.withLock {
                    if (!stillCurrent()) return@withLock null
                    if (!recoverOpenFluxLocked()) {
                        enterBlackhole("openflux chain restart failed")
                        return@withLock false
                    }
                    publishHotspotInfo()   // prepareConfig re-resolved the hotspot IP
                    true
                }
                if (ok != true) break
                restartUsed = true
                failStreak = 0
            }
        }
    }

    // ── 24h working-pool re-verify ───────────────────────────────────────────────

    /**
     * Periodically re-tests servers in the "Рабочt за 24ч" pool (verified within 24h) that aren't
     * the currently-pinned one, and clears the stamp of any that went dead — so the manual-fallback
     * list stays trustworthy. Uses the single-server micro-test (real reachability) on a spare port.
     */
    private fun startWorkingPoolReverify() {
        poolReverifyJob?.cancel()
        poolReverifyJob = null
        if (openFluxMode) return   // no server pool in OpenFlux mode
        poolReverifyJob = serviceScope.launch {
            while (isActive && _vpnState.value == VpnState.CONNECTED) {
                delay(10 * 60_000L)   // every ~10 min
                if (_vpnState.value != VpnState.CONNECTED) break
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                val pinnedId = tagToServerId(pinnedTag)
                val pool = (safeDb("servers") { configRepository.servers.first() } ?: emptyList())
                    .filter { it.verifiedAt > cutoff && it.id != pinnedId }
                    .sortedBy { it.verifiedAt }   // oldest stamp first (most likely stale)
                    .take(5)
                for (s in pool) {
                    if (!isActive || _vpnState.value != VpnState.CONNECTED) break
                    delay(20_000L)   // slow pace — don't drain battery/data
                    val ok = withContext(Dispatchers.IO) { xrayMicroTest.test(s).success }
                    if (!ok) {
                        safeDb("markFailed") { configRepository.markFailed(s.id) }
                        Log.i(TAG, "Pool re-verify: ${s.name} no longer working — removed from 24h pool")
                    }
                }
            }
        }
    }

    // ── Background Probe ──────────────────────────────────────────────────────

    /**
     * Runs in background while VPN is connected.
     * Tests HANDSHAKE_OK servers that didn't make it to Stage 3 during initial validation.
     * Each micro-test uses its own random loopback port + credentials (see XrayMicroTest).
     * Verified servers are saved to DB — available for watchdog failover.
     */
    private fun startBackgroundProbe() {
        backgroundProbeJob?.cancel()
        backgroundProbeJob = null
        if (openFluxMode) return   // no server list to probe in OpenFlux mode
        backgroundProbeJob = serviceScope.launch {
            delay(60_000L) // wait 1 min after connection stabilizes
            val currentId = safeDb("selected server") { configRepository.getSelectedServer() }?.id ?: return@launch

            // Get HANDSHAKE_OK servers that aren't already VERIFIED
            val candidates = (safeDb("servers") { configRepository.servers.first() } ?: emptyList())
                .filter {
                    it.id != currentId &&
                    it.handshakeOk &&
                    it.validationState != "VERIFIED" &&
                    it.country != "Russia" // respect geo preference
                }
                .sortedBy { it.medianLatencyMs.let { ms -> if (ms <= 0) Int.MAX_VALUE else ms } }
                .take(20)

            if (candidates.isEmpty()) {
                Log.d(TAG, "Background probe: no candidates")
                return@launch
            }

            Log.i(TAG, "Background probe: testing ${candidates.size} servers")
            var verified = 0
            for (server in candidates) {
                if (!isActive || _vpnState.value != VpnState.CONNECTED) break
                // Slow pace: 1 test every 30s to avoid draining battery/data
                delay(30_000L)
                val result = xrayMicroTest.test(server)   // own random port + credentials
                if (result.success) {
                    safeDb("markVerified") { configRepository.markVerified(server.id) }
                    verified++
                    Log.i(TAG, "Background verified: ${server.name} (${server.country})")
                }
                if (verified >= 5) break // enough reserves
            }
            Log.i(TAG, "Background probe done: $verified new verified servers")
        }
    }

    /**
     * Run a DB/repository call from a background loop: a DB error is logged and yields null
     * instead of killing the watchdog / probe loop. Cancellation is rethrown.
     */
    private suspend fun <T> safeDb(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "DB call failed ($what): ${e.message}")
        null
    }

    /**
     * Uncaught exception in a service coroutine (CoroutineExceptionHandler of [serviceScope]).
     * Never crash the process: log, and if a session is live engage the kill-switch (under the
     * lock, from a fresh coroutine) so traffic stays blocked instead of leaking.
     */
    private fun onUncaughtCoroutineError(e: Throwable) {
        Log.e(TAG, "Uncaught coroutine error: ${e.javaClass.simpleName}: ${e.message}", e)
        val st = _vpnState.value
        // CONNECTING is owned by connect()/switch code, which handles its own failures.
        if (st != VpnState.CONNECTED && st != VpnState.BLOCKED) return
        // Re-entrancy guard: if the blackhole itself throws deterministically, the handler would
        // relaunch it forever (one Xray start per round). Engage at most once per 30 s; a repeat
        // failure within that window tears the session down instead.
        val now = System.currentTimeMillis()
        val repeat = now - lastUncaughtBlackholeAt < 30_000L
        lastUncaughtBlackholeAt = now
        try {
            serviceScope.launch {
                opMutex.withLock {
                    val cur = _vpnState.value
                    if (cur != VpnState.CONNECTED && cur != VpnState.BLOCKED) return@withLock
                    if (repeat) {
                        Log.e(TAG, "Repeated uncaught errors — stopping the session")
                        failSession()
                        return@withLock
                    }
                    try {
                        enterBlackhole("uncaught error: ${e.javaClass.simpleName}")
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e2: Exception) {
                        Log.e(TAG, "Blackhole failed after uncaught error: ${e2.message}")
                        failSession()
                    }
                }
            }
        } catch (_: Exception) { /* scope already cancelled (onDestroy) */ }
    }

    /**
     * Returns true if the physical network (WiFi / mobile) is available.
     * Returns false during Doze mode or airplane mode — callers should skip VPN health checks.
     */
    private fun isUnderlyingNetworkAvailable(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Standard connectivity check endpoints — look like normal Android/Chrome traffic to DPI.
     * Randomly rotated to avoid a fixed request pattern.
     */
    private val HEALTH_CHECK_URLS = listOf(
        "https://www.google.com/generate_204",              // Android default captive portal check
        "https://cp.cloudflare.com/",                       // Cloudflare connectivity
        "https://connectivitycheck.gstatic.com/generate_204", // Google captive portal
        "https://www.gstatic.com/generate_204",             // Google static
        "https://t.me/",                                    // Telegram — also validates RU-blocking bypass
    )

    /**
     * Checks internet reachability through the VLESS tunnel.
     * Uses standard connectivity check endpoints (indistinguishable from normal Android traffic).
     * Randomly picks 2 from the pool to avoid a fixed pattern.
     */
    private fun isInternetReachableViaSocks(port: Int): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val urls = HEALTH_CHECK_URLS.shuffled().take(2)
        for (url in urls) {
            try {
                val conn = java.net.URL(url)
                    .openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout    = 5_000
                conn.requestMethod  = "HEAD"
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) return true
            } catch (_: Exception) { /* try next URL */ }
        }
        return false
    }

    /**
     * Poll Telegram reachability until success or timeout (balancer warm-up).
     * Returns true as soon as Telegram responds through the tunnel.
     */
    private suspend fun awaitTelegramReachable(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_vpnState.value != VpnState.CONNECTING) return false
            if (withContext(Dispatchers.IO) { isTelegramReachableViaSocks(currentSocksPort) }) return true
            delay(1_500L)
        }
        return false
    }

    /**
     * Full Telegram-API probe through the tunnel: GET https://api.telegram.org/ and READ THE WHOLE
     * RESPONSE BODY (not just the status line) within [timeoutMs]. Returns elapsed ms on success, or
     * -1 on timeout/error. Reading the body to completion is what catches a "mega slow" proxy: it
     * may return headers instantly but then crawl — such a route won't finish the read in budget (or
     * will, but slowly → caller can reject by elapsed time).
     */
    private fun probeTelegramElapsedMs(port: Int, timeoutMs: Int = TELEGRAM_PROBE_TIMEOUT_MS): Int {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        return try {
            val start = System.currentTimeMillis()
            val conn = java.net.URL("https://api.telegram.org/")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            // Drain the body fully within the budget (404 body is in errorStream).
            (runCatching { conn.inputStream }.getOrNull() ?: conn.errorStream)?.use { it.readBytes() }
            conn.disconnect()
            if (code > 0) (System.currentTimeMillis() - start).toInt() else -1
        } catch (_: Exception) {
            -1
        }
    }

    /** Reachable = a full Telegram response arrived within the budget. */
    private fun isTelegramReachableViaSocks(port: Int): Boolean = probeTelegramElapsedMs(port) >= 0

    /**
     * Quick speed probe: downloads 30-70 KB through the tunnel (randomized to avoid DPI fingerprinting).
     * Used by watchdog to detect DPI throttling.
     */
    private fun measureQuickSpeedMbps(socksPort: Int): Float = try {
        val downloadBytes = 30_000 + (Math.random() * 40_000).toInt() // 30-70 KB
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = java.net.URL("https://speed.cloudflare.com/__down?bytes=$downloadBytes")
            .openConnection(proxy) as java.net.HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout    = 8_000
        conn.instanceFollowRedirects = true
        conn.connect()
        val start = System.currentTimeMillis()
        var bytes = 0L
        val buf = ByteArray(4096)
        conn.inputStream.use { stream ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) bytes += read
        }
        conn.disconnect()
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        if (elapsed > 0 && bytes > 5_000) (bytes / elapsed / (1024.0 * 1024.0)).toFloat() else 0f
    } catch (e: Exception) {
        Log.d(TAG, "Quick speed test failed: ${e.message}")
        0f
    }

    // ── Balancer control API (xray api bo/bi) ───────────────────────────────────
    // bo/bi confirmed working on the bundled Xray 26.2.6 (over the old loopback TCP API):
    //   bo --server=unix://<apiSocket> -b balancer <proxy-N>    → seamless override (no restart)
    //   bi --server=unix://<apiSocket> balancer                 → text with "Selecting Override" + "Selects"
    // The API listens on a Unix socket in the app-private files dir (not a loopback port), so
    // other apps on the phone can't drive RoutingService (AddRule → "direct", override, …).

    private fun apiServerArg() = xrayManager.apiServerArg

    /**
     * Reads `xray api bi`. Returns (overrideTag, selectsTag): the current override target and the
     * balancer's own best-alive pick (leastPing "Selects"). Either may be null.
     */
    private suspend fun balancerInfo(): Pair<String?, String?> {
        val out = xrayManager.xrayApi("bi", apiServerArg(), "balancer") ?: return null to null
        var section = ""
        var override: String? = null
        var selects: String? = null
        for (line in out.lines()) {
            val t = line.trim()
            when {
                t.startsWith("- Selecting Override") -> section = "override"
                t.startsWith("- Selects") -> section = "selects"
                else -> Regex("proxy-\\d+").find(t)?.value?.let { tag ->
                    when (section) {
                        "override" -> if (override == null) override = tag
                        "selects" -> if (selects == null) selects = tag
                    }
                }
            }
        }
        return override to selects
    }

    /** Pin the balancer to a proxy tag via `bo` (seamless, no restart). Empty output = success. */
    private suspend fun pinBalancer(tag: String): Boolean {
        val res = xrayManager.xrayApi("bo", apiServerArg(), "-b", "balancer", tag) ?: return false
        return !(res.contains("error", true) || res.contains("not defined") || res.contains("usage:"))
    }

    /** Map a proxy-N tag → real server id via the ordered pool (index == N). */
    private fun tagToServerId(tag: String?): String? {
        val idx = tag?.removePrefix("proxy-")?.toIntOrNull() ?: return null
        return balancerPoolIds.getOrNull(idx)
    }

    /** Connected-server UI model for a pinned tag (real flag/country), or null. */
    private suspend fun connectedServerForTag(tag: String?): ServerProfile? {
        val id = tagToServerId(tag) ?: return null
        return safeDb("servers") { configRepository.servers.first() }?.firstOrNull { it.id == id }
    }

    /**
     * Pin the balancer's best-alive pick (its leastPing "Selects") so the route stays put instead
     * of drifting between similar-ping servers. Marks that server verified (feeds the 24h pool).
     * No-op (returns false) if the API/observatory has no pick yet.
     */
    private suspend fun pinBestAlive(): Boolean {
        val (_, selects) = balancerInfo()
        val target = selects ?: return false
        if (pinBalancer(target)) {
            pinnedTag = target
            tagToServerId(target)?.let { safeDb("markVerified") { configRepository.markVerified(it) } }
            Log.i(TAG, "Balancer pinned to $target (server=${tagToServerId(target)})")
            return true
        }
        return false
    }

    /** Record the active pin: update UI server + stamp it into the 24h working pool. */
    private suspend fun recordPin(tag: String) {
        pinnedTag = tag
        connectedServerForTag(tag)?.let { _connectedServer.value = it }
        tagToServerId(tag)?.let { safeDb("markVerified") { configRepository.markVerified(it) } }
    }

    /**
     * Warm-up / cold-start pin — fully automatic, no live-route disruption. Tests the top pool
     * candidates with SIDE micro-tests (separate temporary xray instances, real reachability +
     * latency), then pins the FASTEST one in a single `bo` call. Because testing happens off to the
     * side, the user's traffic isn't dragged through slow/dead candidates during warm-up. Falls back
     * to the balancer's own best-alive pick if no candidate passes.
     */
    private suspend fun warmUpPinFast() {
        val byId = (safeDb("servers") { configRepository.servers.first() } ?: emptyList()).associateBy { it.id }
        val candidates = balancerPoolIds.take(12).mapNotNull { byId[it] }
        if (candidates.isEmpty()) { pinBestAlive(); return }

        // First server to pass wins (≈ fastest) — don't wait for slow/dead ones to time out.
        val best = xrayMicroTest.firstSuccess(candidates)
        if (best != null) {
            val idx = balancerPoolIds.indexOf(best.serverId)
            if (idx >= 0 && pinBalancer("proxy-$idx")) {
                recordPin("proxy-$idx")
                Log.i(TAG, "Warm-up: pinned FASTEST proxy-$idx (${best.latencyMs}ms, server=${best.serverId})")
                return
            }
        }
        pinBestAlive()   // nothing passed the side-test → let the balancer choose
    }

    // ── Balancer recovery ───────────────────────────────────────────────────────

    /**
     * Restart Xray with the session's current config — used when the process dies / DNS changes.
     * Re-runs the chokepoint (current hotspot IP, DNS). start() stops the old process itself, so
     * the port is released only for the spawn time of the new one.
     */
    /**
     * Start Xray with an already-prepared config. If it fails and the config carried hotspot
     * inbounds (their ports on the hotspot IP may be taken by another app), turn sharing off for
     * this session and retry once — the VPN itself must still come up. Last resort: retry
     * without the control API (Unix-socket listener) — Auto mode then runs on the balancer's
     * own leastPing choice without pinning, and the API stays off for this service instance.
     */
    private suspend fun startXray(prepared: String): Boolean {
        if (startXrayInner(prepared)) return true
        val current = lastTriedConfig ?: prepared   // incl. any pruned bad outbounds
        if (apiDisabled || !hasApi(current)) return false
        Log.w(TAG, "Xray failed to start with the control API socket — retrying without it " +
            "(balancer pinning off): ${xrayManager.lastStartError ?: "no error line"}")
        val stripped = stripApi(prepareConfig(current)) ?: return false
        if (!xrayManager.start(stripped)) return false
        apiDisabled = true
        if (lastXrayConfig == current || lastXrayConfig == prepared) lastXrayConfig = stripped
        return true
    }

    /** True if [configJson] has the control-API inbound. */
    private fun hasApi(configJson: String): Boolean = try {
        val ins = JSONObject(configJson).optJSONArray("inbounds") ?: JSONArray()
        (0 until ins.length()).any { ins.optJSONObject(it)?.optString("tag") == "api" }
    } catch (_: Exception) { false }

    /** [configJson] without the control API (section, inbound, routing rule); null on parse error. */
    private fun stripApi(configJson: String): String? = try {
        val root = JSONObject(configJson)
        root.remove("api")
        root.optJSONArray("inbounds")?.let { ins ->
            root.put("inbounds", JSONArray().apply {
                for (i in 0 until ins.length()) {
                    val ib = ins.optJSONObject(i) ?: continue
                    if (ib.optString("tag") != "api") put(ib)
                }
            })
        }
        root.optJSONObject("routing")?.let { routing ->
            val rules = routing.optJSONArray("rules") ?: JSONArray()
            routing.put("rules", JSONArray().apply {
                for (i in 0 until rules.length()) {
                    val r = rules.opt(i)
                    if (r is JSONObject && r.optString("outboundTag") == "api") continue
                    put(r)
                }
            })
        }
        root.toString(2)
    } catch (e: Exception) {
        Log.w(TAG, "stripApi failed: ${e.message}")
        null
    }

    /** Config of the last failed [startXrayInner] attempt chain (after outbound pruning). */
    private var lastTriedConfig: String? = null

    private suspend fun startXrayInner(prepared: String): Boolean {
        var cfg = prepared
        lastTriedConfig = null
        if (xrayManager.start(cfg)) return true
        // Balancer: Xray names the outbound it couldn't build ("…outbound config with tag
        // proxy-10 > …"). One malformed server must not kill the whole pool — drop it and
        // retry. Selectors are prefix-based ("proxy-"), and the remaining tags keep their
        // index → server-id mapping, so nothing else needs to change.
        repeat(5) {
            val badTag = xrayManager.lastStartError?.let { BAD_OUTBOUND_RE.find(it)?.groupValues?.get(1) }
                ?: return@repeat
            val pruned = removeOutbound(cfg, badTag) ?: return@repeat
            Log.w(TAG, "Xray rejected $badTag — dropping it from the pool and retrying")
            tagToServerId(badTag)?.let { id -> safeDb("markFailed bad outbound") { configRepository.markFailed(id) } }
            // Keep restarts on the pruned config (callers store the config before starting).
            if (lastXrayConfig == cfg) lastXrayConfig = pruned
            cfg = pruned
            if (xrayManager.start(cfg)) return true
        }
        lastTriedConfig = cfg
        if (configHotspot == null) return false
        Log.w(TAG, "Xray failed with hotspot inbounds (port busy?) — sharing disabled for this session")
        hotspotSharingEnabled = false
        return xrayManager.start(prepareConfig(cfg))
    }

    /** Removes outbound [tag] from a config; null if it isn't there or it's the last proxy. */
    private fun removeOutbound(configJson: String, tag: String): String? = try {
        val root = JSONObject(configJson)
        val outs = root.getJSONArray("outbounds")
        val kept = JSONArray()
        var removed = false
        var proxies = 0
        for (i in 0 until outs.length()) {
            val o = outs.getJSONObject(i)
            val t = o.optString("tag")
            if (t == tag) { removed = true; continue }
            if (t.startsWith("proxy-")) proxies++
            kept.put(o)
        }
        if (!removed || proxies == 0) null
        else { root.put("outbounds", kept); root.toString(2) }
    } catch (e: Exception) {
        null
    }

    private suspend fun restartXray(): Boolean {
        if (lastXrayConfig.isEmpty()) return false
        lastXrayConfig = prepareConfig(lastXrayConfig)
        val ok = startXray(lastXrayConfig)
        // prepareConfig re-resolves the hotspot IP — keep the Home banner in sync with it.
        if (ok && _vpnState.value == VpnState.CONNECTED) publishHotspotInfo()
        return ok
    }

    /**
     * Rebuild the balancer pool from scratch: best-effort re-fetch of the subscription,
     * re-filter by excluded countries, regenerate the balancer config and restart Xray.
     * Used when the entire pool appears dead.
     */
    private suspend fun rebuildBalancer(): Boolean = try {
        try {
            configRepository.fetchAndSaveServers()   // best-effort refresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "rebuildBalancer: re-fetch failed: ${e.message}")
        }
        val excluded = preferences.excludedCountries.first()
        val candidates = configRepository.getBalancerPool(excluded, 50)
        // Keep server↔config pairs so proxy-N still maps to a real server after the rebuild.
        val pairs = candidates.mapNotNull { sp -> VlessUriParser.parse(sp.vlessUri)?.let { sp to it } }
        if (pairs.isEmpty()) {
            false
        } else {
            balancerPoolIds = pairs.map { it.first.id }   // refresh proxy-N mapping
            pinnedTag = null                              // restart drops any override
            val json = prepareConfig(
                XrayConfigBuilder.buildBalancer(pairs.map { it.second }, ports.socks, xrayManager.apiSocketPath)
            )
            lastXrayConfig = json
            startXray(json)   // stops the old (or blackhole) process first
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "rebuildBalancer failed: ${e.message}")
        false
    }

    // ── Hot-switch (user picks a server / Auto while connected) ──────────────────

    /**
     * Switch the active server to [serverId] WITHOUT tearing down the tunnel: only the Xray
     * outbound is swapped (stop→start) while TUN + tun2socks stay up on the same SOCKS port.
     * Lets the Servers screen change server on tap during an active session (no disconnect dance).
     * Caller holds opMutex and has verified a live TUN (CONNECTED/BLOCKED).
     */
    private suspend fun switchToServerLocked(serverId: String) {
        val server = try {
            configRepository.getServerById(serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Hot-switch: lookup failed: ${e.message}")
            null
        }
        val vlessConfig = server?.let { VlessUriParser.parse(it.vlessUri) }
        if (server == null || vlessConfig == null) {
            Log.w(TAG, "Hot-switch: server $serverId missing/unparseable — ignored")
            resumeMonitoringLocked()
            return
        }
        if (!balancerMode && _activeMode.value == ActiveMode.MANUAL &&
            _vpnState.value == VpnState.CONNECTED && _connectedServer.value?.id == serverId
        ) {
            Log.i(TAG, "Hot-switch: already on ${server.name} — no-op")
            resumeMonitoringLocked()
            return
        }
        recoveryJob?.cancel(); recoveryJob = null
        watchdogJob?.cancel(); watchdogJob = null
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        _switching.value = true
        try {
            balancerMode = false
            pinnedTag = null
            balancerPoolIds = emptyList()
            _activeMode.value = ActiveMode.MANUAL
            _vpnState.value = VpnState.CONNECTING
            updateNotification(server.name, VpnState.CONNECTING)

            // The user's own pin — written BEFORE the start so a kill-switch recovery after a
            // failed start retries THIS server (recoverSingle reads the selected server).
            configRepository.selectServer(serverId)
            currentServerName = server.name
            val cfg = prepareConfig(XrayConfigBuilder.build(vlessConfig, ports.socks))
            lastXrayConfig = cfg
            // start() stops the old process itself → the port is free only for the spawn time.
            if (!startXray(cfg)) {
                enterBlackhole("hot-switch xray start failed")
                return
            }
            currentCoroutineContext().ensureActive()   // disconnect may have preempted us
            if (!ensureTun2SocksLocked()) { enterBlackhole("tun2socks restart failed"); return }
            activeSocksPort = currentSocksPort
            _connectedServer.value = server
            _vpnState.value = VpnState.CONNECTED
            updateNotification(server.name, VpnState.CONNECTED)
            publishHotspotInfo()
            if (BuildConfig.DEBUG) Log.i(TAG, "Hot-switched to ${server.name}")
            startWatchdog()
            startWorkingPoolReverify()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "switchToServer failed: ${e.message}")
            enterBlackhole("hot-switch error")
        } finally {
            _switching.value = false
        }
    }

    /**
     * Go back to Auto (balancer) under the live TUN: swap Xray to the balancer [config] (auth is
     * injected here), keep TUN + tun2socks, then warm-up-pin a fast server. Does NOT touch the
     * Room selection. Caller holds opMutex and has verified a live TUN (CONNECTED/BLOCKED).
     */
    private suspend fun switchToAutoLocked(config: String, name: String, poolIds: List<String>) {
        recoveryJob?.cancel(); recoveryJob = null
        watchdogJob?.cancel(); watchdogJob = null
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        poolReverifyJob?.cancel(); poolReverifyJob = null
        _switching.value = true
        try {
            // Session is Auto from here on — a kill-switch recovery after a failed start rebuilds
            // the balancer rather than reviving the old manual server.
            balancerMode = true
            balancerPoolIds = poolIds
            pinnedTag = null
            currentServerName = name
            _activeMode.value = ActiveMode.AUTO
            _vpnState.value = VpnState.CONNECTING
            updateNotification(name, VpnState.CONNECTING)

            // The use case built [config] with placeholder ports — the chokepoint rewrites them.
            val authed = prepareConfig(config)
            lastXrayConfig = authed
            // start() stops the old process itself → the port is free only for the spawn time.
            if (!startXray(authed)) {
                enterBlackhole("switch-to-auto xray start failed")
                return
            }
            currentCoroutineContext().ensureActive()   // disconnect may have preempted us
            if (!ensureTun2SocksLocked()) { enterBlackhole("tun2socks restart failed"); return }
            activeSocksPort = currentSocksPort
            _connectedServer.value = balancerPlaceholder(name)
            _vpnState.value = VpnState.CONNECTED
            updateNotification(name, VpnState.CONNECTED)
            publishHotspotInfo()
            warmUpPinFast()
            connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
            if (BuildConfig.DEBUG) Log.i(TAG, "Hot-switched to Auto (pinned=$pinnedTag)")
            startWatchdog()
            startWorkingPoolReverify()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "switchToAuto failed: ${e.message}")
            enterBlackhole("switch-to-auto error")
        } finally {
            _switching.value = false
        }
    }

    // ── Kill-switch ──────────────────────────────────────────────────────────────

    /**
     * Kill-switch: on unexpected tunnel loss keep the TUN interface UP and swap Xray to a
     * BLACKHOLE config (same loopback inbounds/ports/auth, single blackhole outbound), so app
     * traffic hits the TUN → tun2socks → our Xray and is DROPPED instead of leaking over the
     * operator network — and the session's local ports stay bound by US the whole time (a
     * released port could be grabbed by another app, which would then receive every tunneled
     * connection plus our credentials). If even the blackhole Xray can't start, tun2socks is
     * stopped too: nothing dials the released port, and the unread TUN drops all packets.
     * Then auto-recover. If the kill-switch is disabled or there is no TUN to hold, fall back to
     * the old behavior (tear down → ERROR). Caller holds opMutex.
     */
    private suspend fun enterBlackhole(reason: String) {
        val st = _vpnState.value
        if (st == VpnState.DISCONNECTING || st == VpnState.DISCONNECTED) {
            Log.i(TAG, "enterBlackhole($reason) skipped — session is $st")
            return
        }
        if (!killSwitchEnabled || tunFd == null) {
            failSession()
            return
        }
        watchdogJob?.cancel(); watchdogJob = null
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        poolReverifyJob?.cancel(); poolReverifyJob = null
        activeSocksPort = 0
        _connectedServer.value = null
        _hotspotInfo.value = null
        _vpnState.value = VpnState.BLOCKED
        // NonCancellable: the caller may be the watchdog we just cancelled above — the blackhole
        // must still come up (else the port would be left unbound).
        withContext(NonCancellable) { startBlackholeXray() }
        // OpenFlux: nothing dials the bridge while blackholed — stop its upstream polling;
        // recovery restarts it (same port/creds) together with the chain config.
        if (openFluxMode) openFluxManager.stop()
        updateNotification(currentServerName, VpnState.BLOCKED)
        Log.w(TAG, "Kill-switch active — traffic blocked ($reason)")
        scheduleRecovery()
    }

    /**
     * Swap the running Xray for the blackhole config (session ports + auth, hotspot inbounds if
     * the hotspot is up — so those ports stay ours too). Fallbacks: retry without hotspot
     * inbounds; if that also fails, stop tun2socks so nothing dials the now-free local port.
     * Caller holds opMutex.
     */
    private suspend fun startBlackholeXray() {
        val template = XrayConfigBuilder.buildBlackhole(ports.socks)
        val savedHotspot = configHotspot
        var ok = xrayManager.start(prepareConfig(template, withDns = false))
        if (!ok && configHotspot != null) {
            Log.w(TAG, "Blackhole Xray failed with hotspot inbounds — retrying without")
            val sharing = hotspotSharingEnabled
            hotspotSharingEnabled = false
            try {
                ok = xrayManager.start(prepareConfig(template, withDns = false))
            } finally {
                hotspotSharingEnabled = sharing
            }
        }
        // configHotspot describes the SESSION config for publishHotspotInfo — restore it.
        configHotspot = savedHotspot
        if (!ok) {
            Log.e(TAG, "Blackhole Xray failed to start — stopping tun2socks (unread TUN drops traffic)")
            xrayManager.stop()
            tun2SocksManager.stop()
        }
    }

    /** Make sure tun2socks runs on the current TUN (it is stopped if the blackhole Xray failed). */
    private suspend fun ensureTun2SocksLocked(): Boolean {
        if (tun2SocksManager.isRunning) return true
        val fd = tunFd ?: return false
        Log.i(TAG, "Restarting tun2socks on the existing TUN")
        return tun2SocksManager.start(tunFd = fd.fd, socksPort = currentSocksPort, proxyUser = proxyUser, proxyPass = proxyPass, ownerSocket = ownerUidServer?.name)
    }

    /**
     * While BLOCKED, keep retrying (with backoff) to bring the session back up under the live TUN:
     * Auto → rebuild the balancer; Manual → the user's PINNED server only. Each attempt runs
     * under opMutex; the backoff delay does not hold it.
     */
    private fun scheduleRecovery() {
        recoveryJob?.cancel()
        recoveryJob = serviceScope.launch {
            var backoff = 5_000L
            while (isActive && _vpnState.value == VpnState.BLOCKED) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
                if (tunFd == null) break
                if (!isUnderlyingNetworkAvailable()) continue
                val recovered: Boolean? = opMutex.withLock {
                    // Someone else (user switch / disconnect) already changed the session.
                    if (!isActive || _vpnState.value != VpnState.BLOCKED || tunFd == null) return@withLock null
                    val ok = try {
                        when {
                            openFluxMode -> recoverOpenFluxLocked()
                            balancerMode -> rebuildBalancer()
                            else -> recoverSingle()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Recovery attempt failed: ${e.message}")
                        false
                    }
                    if (!ok) {
                        // The attempt replaced/stopped the blackhole Xray — put it back so the
                        // session ports stay bound while we wait for the next attempt.
                        if (!xrayManager.isRunning) withContext(NonCancellable) { startBlackholeXray() }
                        if (openFluxMode) openFluxManager.stop()   // restarted on the next attempt
                        return@withLock false
                    }
                    currentCoroutineContext().ensureActive()
                    if (!ensureTun2SocksLocked()) {
                        Log.w(TAG, "Recovery: tun2socks restart failed")
                        withContext(NonCancellable) { startBlackholeXray() }
                        if (openFluxMode) openFluxManager.stop()
                        return@withLock false
                    }
                    activeSocksPort = currentSocksPort
                    _connectedServer.value = when {
                        openFluxMode -> openFluxProfile()
                        balancerMode -> connectedServerForTag(pinnedTag) ?: balancerPlaceholder(currentServerName)
                        else -> safeDb("selected server") { configRepository.getSelectedServer() }
                    }
                    _vpnState.value = VpnState.CONNECTED
                    updateNotification(currentServerName, VpnState.CONNECTED)
                    publishHotspotInfo()
                    if (balancerMode) {
                        warmUpPinFast()
                        connectedServerForTag(pinnedTag)?.let { _connectedServer.value = it }
                    }
                    startWatchdog()
                    startWorkingPoolReverify()
                    true
                }
                if (recovered == null) break
                if (recovered) {
                    Log.i(TAG, "Kill-switch recovered — tunnel restored")
                    break
                }
            }
        }
    }

    /**
     * Manual recovery: bring the user's PINNED server (Room selection) back up under the existing
     * TUN. Never picks another server and never writes the selection. Caller holds opMutex.
     */
    private suspend fun recoverSingle(): Boolean {
        val server = configRepository.getSelectedServer() ?: return false
        val vless = VlessUriParser.parse(server.vlessUri) ?: return false
        val cfg = prepareConfig(XrayConfigBuilder.build(vless, ports.socks))
        if (!startXray(cfg)) return false   // start() replaces the blackhole process
        lastXrayConfig = cfg
        currentServerName = server.name
        return true
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * [inFlightConnect] = the connect coroutine running when Disconnect was requested — cancelled
     * and awaited first so it can't bring processes back up behind our teardown.
     * The final stop uses stopSelf(lastStartId) on the main thread (serialized with
     * onStartCommand) and is skipped if a newer Connect is already queued, so a quick
     * Disconnect→Connect doesn't kill the freshly started service.
     */
    private suspend fun disconnect(inFlightConnect: Job? = null, refusal: String? = null) {
        _vpnState.value = VpnState.DISCONNECTING
        // Preempt everything that may hold (or queue for) opMutex BEFORE taking it: cancellation
        // makes a holder release the lock at its next suspension point.
        pendingSwitch.set(null)
        // An always-on start still resolving the mode would otherwise send ACTION_CONNECT after
        // this disconnect and bring the VPN back against the user's wish.
        alwaysOnJob?.cancel()
        switchParent.cancelChildren()
        watchdogJob?.cancel()
        recoveryJob?.cancel()
        splitTunnelObserverJob?.cancel()
        dnsObserverJob?.cancel()
        inFlightConnect?.cancelAndJoin()
        opMutex.withLock {
            teardownSessionLocked()
            // OpenFlux refusal: the reason outlives the session so the UI can explain it.
            if (refusal != null) _openFluxStatus.value = refusal
            // Publish DISCONNECTED only after teardown is complete.
            _vpnState.value = VpnState.DISCONNECTED
        }
        Log.i(TAG, "VPN disconnected")
        withContext(Dispatchers.Main) {
            val newerConnect = connectJob?.let { it !== inFlightConnect && it.isActive } == true
            if (!newerConnect) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                // No-op if AMS already has a newer start request; that one handles itself.
                stopSelf(lastStartId)
            }
        }
    }

    /** Full teardown of a session (processes + per-session state). Caller holds opMutex. */
    private fun teardownSessionLocked() {
        cleanupProcesses()
        LocalProxyAuth.clearSession()
        proxyUser = ""
        proxyPass = ""
        hotspotCreds = null
        hotspotPorts = null
        balancerMode = false
        lastXrayConfig = ""
        balancerPoolIds = emptyList()
        pinnedTag = null
    }

    private fun cleanupProcesses() {
        watchdogJob?.cancel(); watchdogJob = null
        splitTunnelObserverJob?.cancel(); splitTunnelObserverJob = null
        dnsObserverJob?.cancel(); dnsObserverJob = null
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        poolReverifyJob?.cancel(); poolReverifyJob = null
        recoveryJob?.cancel(); recoveryJob = null
        activeSocksPort = 0   // stop routing app requests through a dead tunnel
        unregisterNetworkCallback()
        trafficMonitor.stop()
        tun2SocksManager.stop()
        stopOwnerUidServer()          // after tun2socks: nothing queries it any more
        xrayManager.stop()
        xrayManager.deleteConfigs()   // no session credentials / server secrets left on disk
        openFluxManager.stop()        // after Xray: nothing dials the bridge any more
        openFluxMode = false
        _openFluxCarrier.value = null
        _openFluxCaptcha.value = null
        // _openFluxStatus is NOT cleared here: a refusal reason must outlive the teardown (the
        // next connect clears it).
        _openFluxDemoLeftSec.value = null
        _openFluxDemoLeftMb.value = null
        openFlux = null               // drop the token from memory
        bridgeUser = ""
        bridgePass = ""
        tunFd?.close(); tunFd = null
        configHotspot = null
        _hotspotInfo.value = null
        _connectedServer.value = null
        // Session is gone (teardown / ERROR): no mode, no switch in progress.
        _activeMode.value = null
        _switching.value = false
    }

    override fun onRevoke() {
        super.onRevoke()
        val inFlightConnect = connectJob
        disconnectJob = serviceScope.launch { disconnect(inFlightConnect) }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // cleanupProcesses() blocks (tun2socks reap up to ~1s, xray stop up to ~3s) — run it off
        // the main thread, waiting only a bounded time so onDestroy can't ANR. Usually a no-op
        // here because disconnect() already tore everything down.
        val cleanup = Thread({
            try { cleanupProcesses() } catch (e: Exception) { Log.w(TAG, "onDestroy cleanup: ${e.message}") }
        }, "vpn-destroy-cleanup")
        cleanup.start()
        try { cleanup.join(1_500L) } catch (_: InterruptedException) {}
        _connectedServer.value = null
        _activeMode.value = null
        _switching.value = false
        // _openFluxStatus is kept: a refusal stop ends in stopSelf → onDestroy, and the UI still
        // has to explain why. The next connect clears it.
        _openFluxDemoLeftSec.value = null
        _openFluxDemoLeftMb.value = null
        _openFluxCarrier.value = null
        _openFluxCaptcha.value = null
        cancelCaptchaNotification()
        if (liveOpenFluxManager === openFluxManager) liveOpenFluxManager = null
        _vpnState.value = VpnState.DISCONNECTED
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Connection Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Secure connection status"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(serverName: String, state: VpnState): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnectIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MyVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )
        // Generic status text — no server names or VPN keywords to prevent detection
        val statusText = when (state) {
            VpnState.CONNECTING -> "Establishing..."
            VpnState.CONNECTED -> "Active"
            VpnState.DISCONNECTING -> "Closing..."
            VpnState.BLOCKED -> "Reconnecting — traffic blocked"
            else -> "Idle"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure Connection")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_close, "Stop", disconnectIntent)
            .build()
    }

    private fun updateNotification(serverName: String, state: VpnState) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(serverName, state))
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)
}

enum class VpnState {
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR,
    // Kill-switch active: tunnel lost, all traffic BLOCKED (not leaked) while recovering.
    BLOCKED
}
