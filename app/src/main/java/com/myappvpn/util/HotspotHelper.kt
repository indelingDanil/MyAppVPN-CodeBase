package com.myappvpn.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotHelper {

    /**
     * Name prefixes of interfaces that are (almost) always a Wi-Fi soft-AP interface.
     * USB tethering (rndis0, ncm0, …) is intentionally NOT included: hotspot sharing is a Wi-Fi
     * feature, and binding the no-auth HTTP proxy to a USB-tether address was never intended.
     */
    private val AP_PREFIXES = listOf("ap", "swlan", "softap", "wlan1", "wigig")

    /**
     * Returns the phone's private IPv4 address on the hotspot (soft-AP) interface,
     * e.g. "192.168.43.1", or null if no hotspot is active.
     *
     * Dedicated AP interfaces (ap0, swlan0, softap*, wlan1) are preferred. wlan0 is used
     * only when it is NOT the interface of a Wi-Fi client connection — otherwise we would return
     * the phone's address in the home Wi-Fi network (with no [context] we can't tell, so wlan0
     * is then skipped entirely).
     */
    fun getHotspotIp(context: Context?): String? {
        val ifaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
        }.getOrNull() ?: return null
        // null = couldn't determine the Wi-Fi client interface → never treat wlanN as the AP
        // (else the no-auth HTTP proxy could bind to e.g. café Wi-Fi).
        val clientIfaces = wifiClientInterfaces(context)
        val canUseWlan = context != null && clientIfaces != null

        fun ipOf(iface: NetworkInterface): String? = runCatching {
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
                .firstOrNull { isPrivateIpv4(it) }
        }.getOrNull()

        val up = ifaces.filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .filter { clientIfaces == null || it.name !in clientIfaces }

        // 1) Dedicated AP / tethering interfaces.
        up.filter { iface -> AP_PREFIXES.any { iface.name.startsWith(it) } }
            .firstNotNullOfOrNull { ipOf(it) }
            ?.let { return it }

        // 2) wlan0 (or other wlanN) — only when we positively know it's not the Wi-Fi client.
        if (!canUseWlan) return null
        return up.filter { it.name.startsWith("wlan") }
            .firstNotNullOfOrNull { ipOf(it) }
    }

    /** Network prefix length of the local IPv4 address [ip] (e.g. 24), or null if unknown. */
    fun getPrefixLength(ip: String): Int? = runCatching {
        val addr = java.net.InetAddress.getByName(ip)
        NetworkInterface.getByInetAddress(addr)?.interfaceAddresses
            ?.firstOrNull { it.address == addr }
            ?.networkPrefixLength?.toInt()
    }.getOrNull()

    /** Compatibility overload: without a Context wlan0 is never considered. */
    fun getHotspotIp(): String? = getHotspotIp(null)

    /** Names of interfaces currently carrying a Wi-Fi CLIENT (station) connection. */
    private fun wifiClientInterfaces(context: Context?): Set<String>? {
        if (context == null) return null
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                cm.getLinkProperties(network)?.interfaceName
            }.toSet()
        }.getOrNull()
    }

    /** 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16. */
    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            else -> false
        }
    }
}
