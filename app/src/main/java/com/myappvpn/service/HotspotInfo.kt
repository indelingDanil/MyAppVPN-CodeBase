package com.myappvpn.service

/**
 * Hotspot proxy sharing endpoint of the live session (published by [MyVpnService.hotspotInfo]).
 * Both inbounds are bound to the hotspot interface IP only (never 0.0.0.0), on stable random
 * per-install ports (AppPreferences.ensureHotspotPorts, 20000–60000):
 *  - HTTP  [ip]:[httpPort]  — no auth (Android's Wi-Fi proxy setting can't do auth; the hotspot
 *    Wi-Fi password is the gate). Connections coming from the phone itself (hotspot IP,
 *    loopback) are blackholed by a routing rule, so on-device apps outside the tunnel can't use
 *    it to learn the VPN exit IP;
 *  - SOCKS [ip]:[socksPort] — user/password auth with the stable [user]/[pass] from preferences
 *    (same on-device source block).
 */
data class HotspotInfo(
    val ip: String,
    val httpPort: Int,
    val socksPort: Int,
    val user: String,
    val pass: String,
) {
    companion object {
        const val TAG_HTTP = "hotspot-http"
        const val TAG_SOCKS = "hotspot-socks"
    }
}
