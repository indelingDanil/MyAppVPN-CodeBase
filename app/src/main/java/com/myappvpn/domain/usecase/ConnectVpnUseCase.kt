package com.myappvpn.domain.usecase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.service.MyVpnService
import com.myappvpn.util.XrayConfigBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ConnectVpnUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val preferences: AppPreferences,
) {
    companion object {
        private const val TAG = "ConnectVpnUseCase"
        private const val POOL_LIMIT = 50
        private const val SOCKS_PORT = 10808
    }

    /** Manual mode: connect to a single chosen server. */
    suspend operator fun invoke(server: ServerProfile) {
        Log.i(TAG, "Connecting (manual) to ${server.name} (${server.validationState})")

        val vlessConfig = VlessUriParser.parse(server.vlessUri)
            ?: throw IllegalArgumentException("Cannot parse VLESS URI for ${server.name}")

        val hotspotSharing = preferences.hotspotSharing.first()
        val xrayJson = XrayConfigBuilder.build(vlessConfig, socksPort = SOCKS_PORT, listenOnAllInterfaces = hotspotSharing)

        configRepository.selectServer(server.id)
        preferences.setSelectedServerId(server.id)

        startService(xrayJson, server.name, hotspotSharing, balancerMode = false)
    }

    /**
     * Auto mode: build a balancer over the country-filtered, reachability-pre-filtered
     * pool and let Xray pick/seamlessly switch the active server in-core.
     */
    suspend fun connectAuto() {
        val excluded = preferences.excludedCountries.first()
        val candidates = configRepository.getServersExcludingCountries(excluded)
        if (candidates.isEmpty()) {
            throw IllegalStateException("No servers available (check subscription / country filter)")
        }

        // Candidates already come ordered by latency (DB sort). Take the top N WITHOUT a
        // live TCP sweep — the in-core observatory does the real (Telegram) probing, so the
        // service can start immediately instead of blocking the user for 1-2 minutes first.
        val pool = candidates.take(POOL_LIMIT)

        val configs = pool.mapNotNull { VlessUriParser.parse(it.vlessUri) }
        if (configs.isEmpty()) {
            throw IllegalStateException("No parseable servers in pool")
        }

        Log.i(TAG, "Connecting (auto/balancer) with ${configs.size} servers")
        val hotspotSharing = preferences.hotspotSharing.first()
        val xrayJson = XrayConfigBuilder.buildBalancer(configs, socksPort = SOCKS_PORT, listenOnAllInterfaces = hotspotSharing)

        startService(xrayJson, "Авто • ${configs.size} серверов", hotspotSharing, balancerMode = true)
    }

    private fun startService(xrayJson: String, serverName: String, hotspotSharing: Boolean, balancerMode: Boolean) {
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_XRAY_CONFIG, xrayJson)
            putExtra(MyVpnService.EXTRA_SOCKS_PORT, SOCKS_PORT)
            putExtra(MyVpnService.EXTRA_SERVER_NAME, serverName)
            putExtra(MyVpnService.EXTRA_HOTSPOT_SHARING, hotspotSharing)
            putExtra(MyVpnService.EXTRA_BALANCER_MODE, balancerMode)
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
}
