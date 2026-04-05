package com.myappvpn.domain.usecase

import android.content.Context
import android.content.Intent
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
    private val preferences: AppPreferences
) {
    suspend operator fun invoke(server: ServerProfile) {
        val vlessConfig = VlessUriParser.parse(server.vlessUri)
            ?: throw IllegalArgumentException("Cannot parse VLESS URI for ${server.name}")

        val hotspotSharing = preferences.hotspotSharing.first()
        val xrayJson = XrayConfigBuilder.build(vlessConfig, socksPort = 10808, listenOnAllInterfaces = hotspotSharing)

        // Save selected server
        configRepository.selectServer(server.id)
        preferences.setSelectedServerId(server.id)

        // Start VPN service
        val intent = Intent(context, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_CONNECT
            putExtra(MyVpnService.EXTRA_XRAY_CONFIG, xrayJson)
            putExtra(MyVpnService.EXTRA_SOCKS_PORT, 10808)
            putExtra(MyVpnService.EXTRA_SERVER_NAME, server.name)
            putExtra(MyVpnService.EXTRA_HOTSPOT_SHARING, hotspotSharing)
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
