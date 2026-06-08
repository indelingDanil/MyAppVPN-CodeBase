package com.myappvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myappvpn.BuildConfig
import com.myappvpn.R
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.ui.MainActivity
import com.myappvpn.util.HotspotHelper
import com.myappvpn.util.XrayConfigBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MyVpnService : VpnService() {

    companion object {
        const val TAG = "MyVpnService"
        const val ACTION_CONNECT = "com.myappvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.myappvpn.DISCONNECT"
        const val EXTRA_XRAY_CONFIG = "xray_config"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_HOTSPOT_SHARING = "hotspot_sharing"
        const val EXTRA_BALANCER_MODE = "balancer_mode"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "vpn_service"

        private val _vpnState = MutableStateFlow(VpnState.DISCONNECTED)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _hotspotProxyInfo = MutableStateFlow<String?>(null)
        val hotspotProxyInfo: StateFlow<String?> = _hotspotProxyInfo.asStateFlow()

        private val _connectedServer = MutableStateFlow<ServerProfile?>(null)
        val connectedServer: StateFlow<ServerProfile?> = _connectedServer.asStateFlow()
    }

    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var trafficMonitor: TrafficMonitor
    @Inject lateinit var configRepository: ConfigRepository
    @Inject lateinit var xrayMicroTest: com.myappvpn.domain.validation.XrayMicroTest

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var xrayManager: XrayManager
    private lateinit var tun2SocksManager: Tun2SocksManager

    private var tunFd: ParcelFileDescriptor? = null
    private var currentSocksPort: Int = 10808
    private var currentServerName: String = "Unknown"
    private var hotspotSharingEnabled = false
    // Balancer (auto) mode: Xray observatory picks/switches the active server in-core.
    private var balancerMode = false
    private var lastXrayConfig: String = ""   // authed config of the current session (for restart)
    private var watchdogJob: Job? = null
    private var splitTunnelObserverJob: Job? = null
    private var backgroundProbeJob: Job? = null

    // Per-session SOCKS5 auth credentials — prevents other apps from using our proxy
    // (CVE: unauthenticated local SOCKS proxy deanonymization, see habr.com/ru/articles/1020080)
    private var proxyUser: String = ""
    private var proxyPass: String = ""

    override fun onCreate() {
        super.onCreate()
        xrayManager = XrayManager(this)
        tun2SocksManager = Tun2SocksManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val xrayConfig = intent.getStringExtra(EXTRA_XRAY_CONFIG) ?: return START_NOT_STICKY
                val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 10808)
                val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Unknown"
                val hotspotSharing = intent.getBooleanExtra(EXTRA_HOTSPOT_SHARING, false)
                val balancer = intent.getBooleanExtra(EXTRA_BALANCER_MODE, false)
                serviceScope.launch { connect(xrayConfig, socksPort, serverName, hotspotSharing, balancer) }
            }
            ACTION_DISCONNECT -> serviceScope.launch { disconnect() }
        }
        return START_STICKY
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    private suspend fun connect(xrayConfig: String, socksPort: Int, serverName: String, hotspotSharing: Boolean = false, balancer: Boolean = false) {
        currentSocksPort = socksPort
        currentServerName = serverName
        hotspotSharingEnabled = hotspotSharing
        balancerMode = balancer
        try {
            _vpnState.value = VpnState.CONNECTING
            startForeground(NOTIFICATION_ID, buildNotification(serverName, VpnState.CONNECTING))

            // Generate per-session proxy auth to prevent other apps from using our SOCKS5 proxy
            generateProxyAuth()
            val authedConfig = injectProxyAuth(xrayConfig)
            lastXrayConfig = authedConfig
            installProxyAuthenticator()

            // 1. Start XRay
            if (!xrayManager.start(authedConfig)) {
                Log.e(TAG, "Failed to start XRay")
                _vpnState.value = VpnState.ERROR
                return
            }

            // 2. Establish TUN interface with current split-tunnel settings
            tunFd = buildTun() ?: run {
                Log.e(TAG, "Failed to establish TUN interface")
                xrayManager.stop()
                _vpnState.value = VpnState.ERROR
                return
            }

            // 3. Start tun2socks (native fork+exec for proper fd inheritance)
            if (!tun2SocksManager.start(tunFd = tunFd!!.fd, socksPort = socksPort, proxyUser = proxyUser, proxyPass = proxyPass)) {
                Log.e(TAG, "Failed to start tun2socks")
                xrayManager.stop()
                tunFd?.close(); tunFd = null
                _vpnState.value = VpnState.ERROR
                return
            }

            // 4. Start traffic monitoring
            trafficMonitor.start()

            // Balancer: stay in CONNECTING (UI shows "searching") until the observatory finds
            // a server that actually reaches Telegram, so CONNECTED is honest. Bounded wait.
            if (balancerMode) {
                awaitTelegramReachable(timeoutMs = 30_000L)
            }

            _vpnState.value = VpnState.CONNECTED
            _connectedServer.value = if (balancerMode) {
                ServerProfile(id = "__balancer__", name = serverName, flag = "⚖️",
                    country = "Auto", address = "", port = 0, vlessUri = "")
            } else {
                configRepository.getSelectedServer()
            }
            updateNotification(serverName, VpnState.CONNECTED)
            _hotspotProxyInfo.value = if (hotspotSharingEnabled) HotspotHelper.getHotspotIp()?.let { "$it:10808" } else null
            if (BuildConfig.DEBUG) Log.i(TAG, "VPN connected via $serverName")

            // 5. Observe split-tunnel changes → rebuild TUN seamlessly
            startSplitTunnelObserver()

            // 6. Watchdog — auto-failover if server dies
            startWatchdog()

        } catch (e: Exception) {
            Log.e(TAG, "Connect failed: ${e.message}")
            _vpnState.value = VpnState.ERROR
            cleanupProcesses()
        }
    }

    // ── Proxy auth (CVE mitigation) ──────────────────────────────────────────

    /** Generate random credentials for this VPN session. */
    private fun generateProxyAuth() {
        proxyUser = "u" + UUID.randomUUID().toString().replace("-", "").take(8)
        proxyPass = UUID.randomUUID().toString().replace("-", "")
    }

    /**
     * Inject SOCKS5/HTTP auth into an existing XRay config JSON.
     * This ensures no unauthenticated app can use our local proxy to discover the exit IP.
     */
    private fun injectProxyAuth(configJson: String): String {
        val root = JSONObject(configJson)
        val inbounds = root.getJSONArray("inbounds")
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(i)
            val settings = inbound.getJSONObject("settings")
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
        return root.toString(2)
    }

    /**
     * Set a global Java Authenticator so our own health-check HTTP requests
     * (which go through the SOCKS5 proxy) can authenticate automatically.
     */
    private fun installProxyAuthenticator() {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(proxyUser, proxyPass.toCharArray())
            }
        })
    }

    // ── TUN building ───────────────────────────────────────────────────────────

    /**
     * Creates (or recreates) the TUN interface with the current split-tunnel preferences.
     * Calling this while connected rebuilds the interface seamlessly — Android handles
     * the handover when establish() is called again from the same VpnService instance.
     */
    private suspend fun buildTun(): ParcelFileDescriptor? {
        val splitEnabled = preferences.splitTunnelEnabled.first()
        val excludedApps = preferences.excludedApps.first()

        val builder = Builder()
            .setSession("MyAppVPN")
            .addAddress("10.0.0.2", 24)
            .addAddress("fd00::2", 128)     // IPv6 — prevents leak outside tunnel
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            .addDnsServer("1.1.1.1")       // Cloudflare
            .addDnsServer("8.8.8.8")       // Google

        // Always exclude our own package to prevent XRay→TUN routing loop
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Could not exclude own package: ${e.message}")
        }

        // Exclude user-selected apps so they bypass VPN
        if (splitEnabled && excludedApps.isNotEmpty()) {
            var excludedCount = 0
            excludedApps.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                    excludedCount++
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Split tunnel: package not found — $pkg")
                }
            }
            Log.i(TAG, "Split tunnel: excluded $excludedCount apps")
        }

        return builder.establish()
    }

    // ── Split tunnel hot-reload ────────────────────────────────────────────────

    /**
     * Watches DataStore for split-tunnel changes while VPN is connected.
     * On change: rebuilds TUN + restarts tun2socks. XRay keeps running — no server reconnect needed.
     */
    private fun startSplitTunnelObserver() {
        splitTunnelObserverJob?.cancel()
        splitTunnelObserverJob = serviceScope.launch {
            combine(
                preferences.splitTunnelEnabled,
                preferences.excludedApps,
            ) { enabled, apps -> enabled to apps }
                .drop(1) // skip the initial emission (already applied at connect)
                .collect { reconnectTun() }
        }
    }

    /**
     * Rebuilds TUN and restarts tun2socks with new split-tunnel settings.
     * XRay is NOT touched — the VLESS connection to the server is preserved.
     */
    private suspend fun reconnectTun() {
        if (_vpnState.value != VpnState.CONNECTED) return
        Log.i(TAG, "Split tunnel settings changed — rebuilding TUN interface")

        // Stop tun2socks that holds the old TUN fd
        tun2SocksManager.stop()

        // Build new TUN (Android replaces the old one automatically)
        val newFd = buildTun()
        if (newFd == null) {
            Log.e(TAG, "Failed to rebuild TUN after split tunnel change")
            _vpnState.value = VpnState.ERROR
            cleanupProcesses()
            return
        }

        // The old fd is now invalid — close it and keep the new one
        tunFd?.close()
        tunFd = newFd

        // Restart tun2socks with the fresh fd
        if (!tun2SocksManager.start(tunFd = newFd.fd, socksPort = currentSocksPort, proxyUser = proxyUser, proxyPass = proxyPass)) {
            Log.e(TAG, "Failed to restart tun2socks after TUN rebuild")
            _vpnState.value = VpnState.ERROR
            cleanupProcesses()
            return
        }

        Log.i(TAG, "TUN rebuilt — split tunnel settings applied without reconnect")
    }

    // ── Watchdog ───────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay(30_000L + (Math.random() * 10_000L).toLong()) // jittered grace period
            var successRecorded = false
            var failStreak = 0
            while (isActive) {
                // Randomized intervals to avoid DPI fingerprinting fixed-cadence health checks
                val jitter = (Math.random() * 15_000L).toLong()
                delay(if (failStreak == 0) 30_000L + jitter else 8_000L + jitter)
                if (_vpnState.value != VpnState.CONNECTED) break

                // XRay process crash → restart (balancer) or switch (single)
                if (!xrayManager.isRunning) {
                    if (balancerMode) {
                        Log.w(TAG, "XRay died (balancer) — restarting same config")
                        if (!restartXray()) { _vpnState.value = VpnState.ERROR; cleanupProcesses(); break }
                        failStreak = 0
                        continue
                    }
                    Log.w(TAG, "XRay process died — switching immediately")
                    configRepository.getSelectedServer()?.let {
                        configRepository.incrementConsecutiveFailure(it.id)
                    }
                    switchToNextServer()
                    break
                }

                // If underlying network is gone (Doze / airplane) — skip, not a VPN failure
                if (!isUnderlyingNetworkAvailable()) {
                    Log.d(TAG, "No underlying network (Doze?) — skipping health check")
                    failStreak = 0
                    continue
                }

                // High XRay error rate = VLESS tunnel broken → count as fail without HTTP probe
                val xrayErrorsHigh = xrayManager.errorRate > 10
                val internetOk = when {
                    xrayErrorsHigh -> {
                        Log.w(TAG, "XRay errorRate=${xrayManager.errorRate} — treating as internet fail")
                        false
                    }
                    // Balancer mode: success means the active route actually reaches Telegram (HTTP 200).
                    balancerMode -> isTelegramReachableViaSocks(currentSocksPort)
                    else -> isInternetReachableViaSocks(currentSocksPort)
                }

                if (!internetOk) {
                    failStreak++
                    Log.w(TAG, "Health check failed #$failStreak (xrayErrors=${xrayManager.errorRate})")
                    if (balancerMode) {
                        // Observatory switches between servers in-core. Only act if the whole
                        // pool looks dead for a sustained period.
                        if (failStreak >= 4) {
                            Log.w(TAG, "Balancer: sustained failure — rebuilding pool")
                            if (!rebuildBalancer()) { _vpnState.value = VpnState.ERROR; cleanupProcesses(); break }
                            failStreak = 0
                        }
                    } else if (failStreak >= 2) {
                        Log.w(TAG, "2 consecutive failures — switching server")
                        configRepository.getSelectedServer()?.let {
                            configRepository.incrementConsecutiveFailure(it.id)
                            configRepository.recordFailure(it.id)
                        }
                        switchToNextServer()
                        break
                    }
                } else if (balancerMode) {
                    failStreak = 0   // balancer healthy — Xray manages per-server selection
                } else {
                    failStreak = 0
                    configRepository.getSelectedServer()?.let { server ->
                        // Mark as verified + reset consecutive failures on first success
                        if (!successRecorded) {
                            successRecorded = true
                            configRepository.recordSuccess(server.id)
                            configRepository.markVerified(server.id)
                            configRepository.resetConsecutiveFailures(server.id)
                            Log.d(TAG, "Server verified: ${server.name}")
                            // Start background probe to build failover reserve
                            startBackgroundProbe()
                        }

                        // ── Quick speed check: detect DPI throttling ──
                        val speed = withContext(Dispatchers.IO) {
                            measureQuickSpeedMbps(currentSocksPort)
                        }
                        if (speed > 0f) {
                            configRepository.updateSpeed(server.id, speed)
                            Log.d(TAG, "Speed: ${"%.0f".format(speed * 1024)} KB/s")
                        }
                        if (speed in 0.01f..0.09f) {
                            Log.w(TAG, "Throttled! ${"%.0f".format(speed * 1024)} KB/s — switching server")
                            configRepository.incrementConsecutiveFailure(server.id)
                            switchToNextServer()
                            return@launch
                        }
                    }
                }
            }
        }
    }

    // ── Background Probe ──────────────────────────────────────────────────────

    /**
     * Runs in background while VPN is connected.
     * Tests HANDSHAKE_OK servers that didn't make it to Stage 3 during initial validation.
     * Uses port 10903 (separate from main 10808 and micro-test 10900-10902).
     * Verified servers are saved to DB — available for watchdog failover.
     */
    private fun startBackgroundProbe() {
        backgroundProbeJob?.cancel()
        backgroundProbeJob = serviceScope.launch {
            delay(60_000L) // wait 1 min after connection stabilizes
            val currentId = configRepository.getSelectedServer()?.id ?: return@launch

            // Get HANDSHAKE_OK servers that aren't already VERIFIED
            val candidates = configRepository.servers.first()
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
                val result = xrayMicroTest.test(server, testPort = 10903)
                if (result.success) {
                    configRepository.markVerified(server.id)
                    verified++
                    Log.i(TAG, "Background verified: ${server.name} (${server.country})")
                }
                if (verified >= 5) break // enough reserves
            }
            Log.i(TAG, "Background probe done: $verified new verified servers")
        }
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
     * Strict Telegram reachability check through the tunnel: requires HTTP 200 from t.me.
     * Used in balancer mode — a route is only "good" if it actually unblocks Telegram,
     * which TCP ping / generic connectivity cannot guarantee.
     */
    /**
     * Poll Telegram reachability until success or timeout (balancer warm-up).
     * Returns true as soon as t.me responds 200 through the tunnel.
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

    private fun isTelegramReachableViaSocks(port: Int): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        return try {
            val conn = java.net.URL("https://t.me/")
                .openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

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

    /**
     * Downloads ~200 KB through the SOCKS5 proxy and measures throughput.
     * Returns MB/s, or 0f if the test could not complete (network error, timeout).
     * Test goes through the VLESS tunnel so it measures real-world usable speed.
     */
    private fun measureDownloadSpeedMbps(socksPort: Int): Float = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = java.net.URL("https://speed.cloudflare.com/__down?bytes=204800")
            .openConnection(proxy) as java.net.HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout    = 15_000
        conn.instanceFollowRedirects = true
        conn.connect()
        val start = System.currentTimeMillis()
        var bytes = 0L
        val buf = ByteArray(8192)
        conn.inputStream.use { stream ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) bytes += read
        }
        conn.disconnect()
        val elapsed = (System.currentTimeMillis() - start) / 1000.0
        if (elapsed > 0 && bytes > 10_000) (bytes / elapsed / (1024.0 * 1024.0)).toFloat() else 0f
    } catch (e: Exception) {
        Log.d(TAG, "Speed test failed: ${e.message}")
        0f
    }

    // ── Balancer recovery ───────────────────────────────────────────────────────

    /** Restart Xray with the current (balancer) config — used when the process dies. */
    private suspend fun restartXray(): Boolean {
        xrayManager.stop()
        return if (lastXrayConfig.isNotEmpty()) xrayManager.start(lastXrayConfig) else false
    }

    /**
     * Rebuild the balancer pool from scratch: best-effort re-fetch of the subscription,
     * re-filter by excluded countries, regenerate the balancer config and restart Xray.
     * Used when the entire pool appears dead.
     */
    private suspend fun rebuildBalancer(): Boolean = try {
        runCatching { configRepository.fetchAndSaveServers() }   // best-effort refresh
        val excluded = preferences.excludedCountries.first()
        val candidates = configRepository.getServersExcludingCountries(excluded)
        val configs = candidates.take(50).mapNotNull { VlessUriParser.parse(it.vlessUri) }
        if (configs.isEmpty()) {
            false
        } else {
            val json = injectProxyAuth(
                XrayConfigBuilder.buildBalancer(configs, currentSocksPort, hotspotSharingEnabled)
            )
            lastXrayConfig = json
            xrayManager.stop()
            xrayManager.start(json)
        }
    } catch (e: Exception) {
        Log.e(TAG, "rebuildBalancer failed: ${e.message}")
        false
    }

    // ── Server failover ────────────────────────────────────────────────────────

    private suspend fun switchToNextServer() {
        try {
            Log.i(TAG, "Switching to next server…")
            _vpnState.value = VpnState.CONNECTING

            val failedServer = configRepository.getSelectedServer()
            if (failedServer != null) {
                configRepository.recordFailure(failedServer.id)
                if (BuildConfig.DEBUG) Log.w(TAG, "Recorded failure for ${failedServer.name}")
            }

            val triedIds = mutableSetOf<String>()
            failedServer?.let { triedIds += it.id }
            val failedPorts = mutableSetOf<Int>()
            var consecutivePortFails = 0
            var lastFailedPort: Int? = failedServer?.port

            while (true) {
                val nextServer = configRepository.selectBestServer(
                    excludeIds = triedIds,
                    excludePorts = failedPorts,
                ) ?: run {
                    Log.e(TAG, "No reachable servers left — giving up")
                    _vpnState.value = VpnState.ERROR
                    cleanupProcesses()
                    return
                }

                val vlessConfig = VlessUriParser.parse(nextServer.vlessUri)
                if (vlessConfig == null) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "Cannot parse VLESS URI for ${nextServer.name} — skipping")
                    triedIds += nextServer.id
                    continue
                }

                xrayManager.stop()
                val failoverConfig = injectProxyAuth(XrayConfigBuilder.build(vlessConfig, currentSocksPort, hotspotSharingEnabled))
                if (!xrayManager.start(failoverConfig)) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "XRay failed to start with ${nextServer.name} — skipping")
                    configRepository.recordFailure(nextServer.id)
                    triedIds += nextServer.id
                    continue
                }

                // Give XRay 3s to stabilise, then speed test validates both connectivity and speed
                delay(3_000L)
                val speedMbps = withContext(Dispatchers.IO) { measureDownloadSpeedMbps(currentSocksPort) }
                if (speedMbps > 0f) {
                    configRepository.updateSpeed(nextServer.id, speedMbps)
                    if (BuildConfig.DEBUG) Log.d(TAG, "${nextServer.name} speed: ${"%.2f".format(speedMbps)} MB/s")
                }
                // 0f = tunnel dead (test failed), 0.01-0.09 = extremely slow — skip
                if (speedMbps < 0.1f) {
                    val reason = if (speedMbps == 0f) "unreachable" else "${"%.0f".format(speedMbps * 1024)} KB/s"
                    if (BuildConfig.DEBUG) Log.w(TAG, "${nextServer.name} failed ($reason) — trying next")
                    configRepository.recordFailure(nextServer.id)
                    configRepository.incrementConsecutiveFailure(nextServer.id)
                    triedIds += nextServer.id
                    // Track port failures
                    if (nextServer.port == lastFailedPort) {
                        consecutivePortFails++
                        if (consecutivePortFails >= 3) {
                            failedPorts += nextServer.port
                            consecutivePortFails = 0
                        }
                    } else {
                        lastFailedPort = nextServer.port
                        consecutivePortFails = 1
                    }
                    continue
                }

                // Server is confirmed working — mark as verified
                currentServerName = nextServer.name
                configRepository.selectServer(nextServer.id)
                configRepository.recordSuccess(nextServer.id)
                configRepository.markVerified(nextServer.id)
                configRepository.resetConsecutiveFailures(nextServer.id)
                _connectedServer.value = nextServer
                _vpnState.value = VpnState.CONNECTED
                updateNotification(nextServer.name, VpnState.CONNECTED)
                if (hotspotSharingEnabled) _hotspotProxyInfo.value = HotspotHelper.getHotspotIp()?.let { "$it:10808" }
                if (BuildConfig.DEBUG) Log.i(TAG, "Switched to ${nextServer.name} (${nextServer.address})")
                startWatchdog()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToNextServer failed: ${e.message}")
            _vpnState.value = VpnState.ERROR
            cleanupProcesses()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    private suspend fun disconnect() {
        _vpnState.value = VpnState.DISCONNECTING
        cleanupProcesses()
        Authenticator.setDefault(null)
        proxyUser = ""
        proxyPass = ""
        balancerMode = false
        lastXrayConfig = ""
        _vpnState.value = VpnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN disconnected")
    }

    private fun cleanupProcesses() {
        watchdogJob?.cancel(); watchdogJob = null
        splitTunnelObserverJob?.cancel(); splitTunnelObserverJob = null
        backgroundProbeJob?.cancel(); backgroundProbeJob = null
        trafficMonitor.stop()
        tun2SocksManager.stop()
        xrayManager.stop()
        tunFd?.close(); tunFd = null
        _hotspotProxyInfo.value = null
        _connectedServer.value = null
    }

    override fun onRevoke() {
        super.onRevoke()
        serviceScope.launch { disconnect() }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cleanupProcesses()
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
    DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
}
