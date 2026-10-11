package com.myappvpn.service

/**
 * Hotspot proxy sharing endpoint of the live session (published by [MyVpnService.hotspotInfo]).
 * Both inbounds are bound to the hotspot interface IP only (never 0.0.0.0):
 *  - HTTP  [ip]:[httpPort]  — no auth (Android's Wi-Fi proxy setting can't do auth; the hotspot
 *    Wi-Fi password is the gate);
 *  - SOCKS [ip]:[socksPort] — user/password auth with the stable [user]/[pass] from preferences.
 */
data class HotspotInfo(
    val ip: String,
    val httpPort: Int,
    val socksPort: Int,
    val user: String,
    val pass: String,
) {
    companion object {
        const val HTTP_PORT = 10809
        const val SOCKS_PORT = 10808
        const val TAG_HTTP = "hotspot-http"
        const val TAG_SOCKS = "hotspot-socks"
    }
}
