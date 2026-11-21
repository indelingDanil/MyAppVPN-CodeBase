package com.myappvpn.domain.usecase

import android.content.Context
import android.content.Intent
import android.util.Log
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.repository.ConfigRepository
import com.myappvpn.domain.OpenFluxEndpoint
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
        // Placeholder ports only: the service picks random free loopback ports per session and
        // rewrites the socks/api inbounds (and adds hotspot inbounds / auth) in its single
        // config chokepoint (MyVpnService.prepareConfig), so the config built here is a template.
        private const val TEMPLATE_SOCKS_PORT = 10808
    }

    /** Balancer config + metadata shared by connectAuto() and switchToAuto(). */
    private class BalancerSpec(
        val xrayJson: String,
        val serverName: String,
        val hotspotSharing: Boolean,
        val poolIds: ArrayList<String>,
    )

    /** Manual mode: connect to a single chosen server. */
    suspend operator fun invoke(server: ServerProfile) {
        if (com.myappvpn.BuildConfig.DEBUG) Log.i(TAG, "Connecting (manual) to ${server.name} (${server.validationState})")

        val vlessConfig = VlessUriParser.parse(server.vlessUri)
            ?: throw IllegalArgumentException("Cannot parse VLESS URI for ${server.name}")

        val hotspotSharing = preferences.hotspotSharing.first()
        val xrayJson = XrayConfigBuilder.build(vlessConfig, socksPort = TEMPLATE_SOCKS_PORT)

        configRepository.selectServer(server.id)

        startService(xrayJson, server.name, hotspotSharing, balancerMode = false)
    }

    /**
     * Auto mode: build a balancer over the country-filtered, reachability-pre-filtered
     * pool and let Xray pick/seamlessly switch the active server in-core.
     */
    suspend fun connectAuto() {
        val spec = buildBalancerSpec()
        if (com.myappvpn.BuildConfig.DEBUG) Log.i(TAG, "Connecting (auto/balancer) with ${spec.poolIds.size} servers")
        startService(spec.xrayJson, spec.serverName, spec.hotspotSharing, balancerMode = true, poolIds = spec.poolIds)
    }

    /**
     * OpenFlux (Beta): tunnel through the user's own PHP exit via the bundled bridge. Only the
     * URL/token are sent — the service builds the Xray→bridge chain config itself, because it
     * owns the random per-session ports (incl. the bridge port). Never touches the Room selection.
     * @throws IllegalStateException if the URL or token is not set.
     */
    suspend fun connectOpenFlux() {
        // Demo flag → demo constants; else the user's own URL/token (OpenFluxEndpoint decides).
        val endpoint = OpenFluxEndpoint.resolve(preferences)
        if (!endpoint.complete) {
            throw IllegalStateException(
                if (endpoint.channel == com.myappvpn.domain.OpenFluxChannel.DIRECT) "Укажите адрес и токен OpenFlux"
                else "Укажите ссылку для канала OpenFlux"
            )
        }
        val hotspotSharing = preferences.hotspotSharing.first()
        Log.i(TAG, "Connecting (OpenFlux, channel ${endpoint.channel.key})")   // never log the URL/token/link
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_OPENFLUX, true)
            putExtra(MyVpnService.EXTRA_OPENFLUX_URL, endpoint.url)
            putExtra(MyVpnService.EXTRA_OPENFLUX_TOKEN, endpoint.token)
            putExtra(MyVpnService.EXTRA_OPENFLUX_DEMO, endpoint.demo)
            putExtra(MyVpnService.EXTRA_OPENFLUX_TRANSPORT, endpoint.channel.transport)
            putExtra(MyVpnService.EXTRA_OPENFLUX_LINK, endpoint.link)
            putExtra(MyVpnService.EXTRA_OPENFLUX_SECRET, endpoint.secret)
            putExtra(MyVpnService.EXTRA_SERVER_NAME, "OpenFlux (Beta)")
            putExtra(MyVpnService.EXTRA_HOTSPOT_SHARING, hotspotSharing)
            putExtra(MyVpnService.EXTRA_BALANCER_MODE, false)
        }
        context.startForegroundService(intent)
    }

    /**
     * Live switch Manual → Auto under the running TUN: the service swaps the single outbound for
     * the balancer config without tearing the tunnel down. Same config as connectAuto().
     */
    suspend fun switchToAuto() {
        val spec = buildBalancerSpec()
        if (com.myappvpn.BuildConfig.DEBUG) Log.i(TAG, "Switching to auto/balancer with ${spec.poolIds.size} servers")
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_SWITCH_AUTO
            putExtra(MyVpnService.EXTRA_XRAY_CONFIG, spec.xrayJson)
            putExtra(MyVpnService.EXTRA_SERVER_NAME, spec.serverName)
            putExtra(MyVpnService.EXTRA_HOTSPOT_SHARING, spec.hotspotSharing)
            putStringArrayListExtra(MyVpnService.EXTRA_POOL_IDS, spec.poolIds)
        }
        context.startService(intent)
    }

    private suspend fun buildBalancerSpec(): BalancerSpec {
        val excluded = preferences.excludedCountries.first()
        val candidates = configRepository.getServersExcludingCountries(excluded)
        if (candidates.isEmpty()) {
            throw IllegalStateException("Нет доступных серверов (проверьте подписки / фильтр стран)")
        }

        // Candidates already come ordered by latency (DB sort). Take the top N WITHOUT a
        // live TCP sweep — the in-core observatory does the real (Telegram) probing, so the
        // service can start immediately instead of blocking the user for 1-2 minutes first.
        val pool = candidates.take(POOL_LIMIT)

        // Keep server↔config pairs so the proxy-N index in the balancer config maps back to a
        // real server id (mapNotNull alone would desync indices when a server fails to parse).
        val pairs = pool.mapNotNull { sp -> VlessUriParser.parse(sp.vlessUri)?.let { sp to it } }
        if (pairs.isEmpty()) {
            throw IllegalStateException("Нет корректных серверов в пуле")
        }
        val configs = pairs.map { it.second }
        val poolIds = ArrayList(pairs.map { it.first.id })   // index == proxy-N

        val hotspotSharing = preferences.hotspotSharing.first()
        val xrayJson = XrayConfigBuilder.buildBalancer(configs, socksPort = TEMPLATE_SOCKS_PORT)
        return BalancerSpec(xrayJson, "Авто • ${configs.size} серверов", hotspotSharing, poolIds)
    }

    private fun startService(
        xrayJson: String,
        serverName: String,
        hotspotSharing: Boolean,
        balancerMode: Boolean,
        poolIds: ArrayList<String>? = null,
    ) {
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_XRAY_CONFIG, xrayJson)
            putExtra(MyVpnService.EXTRA_SERVER_NAME, serverName)
            putExtra(MyVpnService.EXTRA_HOTSPOT_SHARING, hotspotSharing)
            putExtra(MyVpnService.EXTRA_BALANCER_MODE, balancerMode)
            if (poolIds != null) putStringArrayListExtra(MyVpnService.EXTRA_POOL_IDS, poolIds)
        }
        context.startForegroundService(intent)
    }

    /**
     * Hot-switch the active server while the tunnel is up (no disconnect→connect dance).
     * The running service swaps the Xray outbound and keeps the TUN. No-op if not running.
     */
    fun switchServer(serverId: String) {
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_SWITCH_SERVER
            putExtra(MyVpnService.EXTRA_SERVER_ID, serverId)
        }
        context.startService(intent)
    }

    fun disconnect() {
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_DISCONNECT
        }
        context.startService(intent)
    }
}
