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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var xrayManager: XrayManager
    private lateinit var tun2SocksManager: Tun2SocksManager

    private var tunFd: ParcelFileDescriptor? = null
    private var currentSocksPort: Int = 10808
    private var currentServerName: String = "Unknown"
    private var hotspotSharingEnabled = false
    private var watchdogJob: Job? = null
    private var splitTunnelObserverJob: Job? = null

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
                serviceScope.launch { connect(xrayConfig, socksPort, serverName, hotspotSharing) }
            }
            ACTION_DISCONNECT -> serviceScope.launch { disconnect() }
        }
        return START_STICKY
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    private suspend fun connect(xrayConfig: String, socksPort: Int, serverName: String, hotspotSharing: Boolean = false) {
        currentSocksPort = socksPort
        currentServerName = serverName
        hotspotSharingEnabled = hotspotSharing
        try {
            _vpnState.value = VpnState.CONNECTING
            startForeground(NOTIFICATION_ID, buildNotification(serverName, VpnState.CONNECTING))

            // 1. Start XRay
            if (!xrayManager.start(xrayConfig)) {
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
            if (!tun2SocksManager.start(tunFd = tunFd!!.fd, socksPort = socksPort)) {
                Log.e(TAG, "Failed to start tun2socks")
                xrayManager.stop()
                tunFd?.close(); tunFd = null
                _vpnState.value = VpnState.ERROR
                return
            }

            // 4. Start traffic monitoring
            trafficMonitor.start()

            _vpnState.value = VpnState.CONNECTED
            _connectedServer.value = configRepository.getSelectedServer()
            updateNotification(serverName, VpnState.CONNECTED)
            _hotspotProxyInfo.value = if (hotspotSharingEnabled) HotspotHelper.getHotspotIp()?.let { "$it:10808" } else null
            Log.i(TAG, "VPN connected via $serverName")

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
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            .addDnsServer("1.1.1.1")       // Cloudflare
            .addDnsServer("8.8.8.8")       // Google
            .addDnsServer("9.9.9.9")       // Quad9
            .addDnsServer("208.67.222.222") // OpenDNS

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
        if (!tun2SocksManager.start(tunFd = newFd.fd, socksPort = currentSocksPort)) {
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
            delay(30_000L) // initial grace period
            var successRecorded = false
            var failStreak = 0
            while (isActive) {
                // After first failure: recheck in 10s. When healthy: check every 30s.
                delay(if (failStreak == 0) 30_000L else 10_000L)
                if (_vpnState.value != VpnState.CONNECTED) break

                // XRay process crash → switch immediately
                if (!xrayManager.isRunning) {
                    Log.w(TAG, "XRay process died — switching immediately")
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
                val internetOk = if (xrayErrorsHigh) {
                    Log.w(TAG, "XRay errorRate=${xrayManager.errorRate} — treating as internet fail")
                    false
                } else {
                    isTelegramReachableViaSocks(currentSocksPort)
                }

                if (!internetOk) {
                    failStreak++
                    Log.w(TAG, "Health check failed #$failStreak (xrayErrors=${xrayManager.errorRate})")
                    if (failStreak >= 2) {
                        // 2 consecutive failures (30s + 10s apart) → switch
                        Log.w(TAG, "2 consecutive failures — switching server")
                        switchToNextServer()
                        break
                    }
                } else {
                    failStreak = 0
                    if (!successRecorded) {
                        successRecorded = true
                        configRepository.getSelectedServer()?.let { server ->
                            configRepository.recordSuccess(server.id)
                            Log.d(TAG, "Recorded success for ${server.name}")
                        }
                    }

                    // ── Quick speed check: detect DPI throttling ──
                    val speed = withContext(Dispatchers.IO) {
                        measureQuickSpeedMbps(currentSocksPort)
                    }
                    if (speed > 0f) {
                        configRepository.getSelectedServer()?.let {
                            configRepository.updateSpeed(it.id, speed)
                        }
                        Log.d(TAG, "Speed: ${"%.0f".format(speed * 1024)} KB/s")
                    }
                    if (speed in 0.01f..0.09f) {
                        Log.w(TAG, "Throttled! ${"%.0f".format(speed * 1024)} KB/s — switching server")
                        switchToNextServer()
                        break
                    }
                }
            }
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
     * Telegram endpoints for health check — actual HTTPS requests, not just TCP connect.
     * TCP SYN/ACK can pass DPI even when Telegram traffic is blocked/throttled.
     * HTTPS request validates real data delivery through the tunnel.
     */
    private val TELEGRAM_CHECK_URLS = listOf(
        "https://t.me/",                    // primary — lightweight, blocked in RU
        "https://web.telegram.org/",        // fallback — WebSocket transport endpoint
    )

    /**
     * Checks that Telegram is reachable through the VLESS tunnel by making
     * an actual HTTPS request (not just TCP connect).
     * DPI in Russia can pass TCP handshake but block/throttle MTProto data —
     * an HTTP request catches this.
     */
    private fun isTelegramReachableViaSocks(port: Int): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        for (url in TELEGRAM_CHECK_URLS) {
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
        Log.d(TAG, "Telegram health check failed — all endpoints unreachable")
        return false
    }

    /**
     * Quick speed probe: downloads 50 KB through the tunnel.
     * Lighter than measureDownloadSpeedMbps (200 KB) — completes in ~2s even on throttled links.
     * Used by watchdog to detect DPI throttling every 30s.
     */
    private fun measureQuickSpeedMbps(socksPort: Int): Float = try {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = java.net.URL("https://speed.cloudflare.com/__down?bytes=51200")
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

    // ── Server failover ────────────────────────────────────────────────────────

    private suspend fun switchToNextServer() {
        try {
            Log.i(TAG, "Switching to next server…")
            _vpnState.value = VpnState.CONNECTING

            val failedServer = configRepository.getSelectedServer()
            if (failedServer != null) {
                configRepository.recordFailure(failedServer.id)
                Log.w(TAG, "Recorded failure for ${failedServer.name}")
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
                    Log.w(TAG, "Cannot parse VLESS URI for ${nextServer.name} — skipping")
                    triedIds += nextServer.id
                    continue
                }

                xrayManager.stop()
                if (!xrayManager.start(XrayConfigBuilder.build(vlessConfig, currentSocksPort, hotspotSharingEnabled))) {
                    Log.w(TAG, "XRay failed to start with ${nextServer.name} — skipping")
                    configRepository.recordFailure(nextServer.id)
                    triedIds += nextServer.id
                    continue
                }

                // Give XRay 3s to stabilise, then speed test validates both connectivity and speed
                delay(3_000L)
                val speedMbps = withContext(Dispatchers.IO) { measureDownloadSpeedMbps(currentSocksPort) }
                if (speedMbps > 0f) {
                    configRepository.updateSpeed(nextServer.id, speedMbps)
                    Log.d(TAG, "${nextServer.name} speed: ${"%.2f".format(speedMbps)} MB/s")
                }
                // 0f = tunnel dead (test failed), 0.01-0.49 = too slow — skip both
                if (speedMbps < 0.5f) {
                    val reason = if (speedMbps == 0f) "unreachable" else "${"%.0f".format(speedMbps * 1024)} KB/s"
                    Log.w(TAG, "${nextServer.name} failed ($reason) — trying next")
                    configRepository.recordFailure(nextServer.id)
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

                // Server is confirmed working
                currentServerName = nextServer.name
                configRepository.selectServer(nextServer.id)
                configRepository.recordSuccess(nextServer.id)
                _connectedServer.value = nextServer
                _vpnState.value = VpnState.CONNECTED
                updateNotification(nextServer.name, VpnState.CONNECTED)
                if (hotspotSharingEnabled) _hotspotProxyInfo.value = HotspotHelper.getHotspotIp()?.let { "$it:10808" }
                Log.i(TAG, "Switched to ${nextServer.name} (${nextServer.address})")
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
        _vpnState.value = VpnState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN disconnected")
    }

    private fun cleanupProcesses() {
        watchdogJob?.cancel(); watchdogJob = null
        splitTunnelObserverJob?.cancel(); splitTunnelObserverJob = null
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
        val channel = NotificationChannel(CHANNEL_ID, "VPN Service", NotificationManager.IMPORTANCE_LOW).apply {
            description = "VPN connection status"
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
        val statusText = when (state) {
            VpnState.CONNECTING -> "Connecting to $serverName..."
            VpnState.CONNECTED -> "Connected · $serverName"
            VpnState.DISCONNECTING -> "Disconnecting..."
            else -> "VPN"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MyAppVPN")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_close, "Disconnect", disconnectIntent)
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
