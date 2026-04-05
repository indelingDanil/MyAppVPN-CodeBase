package com.myappvpn.util

import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotHelper {
    /**
     * Returns the phone's IPv4 address on the hotspot interface (e.g. "192.168.43.1"),
     * or null if hotspot is not active.
     * Checks wlan*, ap*, swlan* interfaces for a 192.168.x.x address.
     */
    fun getHotspotIp(): String? {
        val ifaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
        }.getOrNull() ?: return null
        return ifaces
            .filter { iface ->
                iface.isUp && !iface.isLoopback &&
                    (iface.name.startsWith("wlan") ||
                        iface.name.startsWith("ap") ||
                        iface.name.startsWith("swlan"))
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { it.startsWith("192.168.") }
    }
}
