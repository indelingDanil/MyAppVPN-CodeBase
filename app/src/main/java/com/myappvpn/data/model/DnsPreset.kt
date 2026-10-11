package com.myappvpn.data.model

/** A known public DNS resolver offered as a one-tap "add" in the DNS screen. */
data class DnsPreset(
    /** Provider group shown on the chip ("Cloudflare", "Яндекс", …). */
    val group: String,
    /** Label stored with the server when added. */
    val label: String,
    /** Canonical address (must already be in [com.myappvpn.util.DnsValidator] canonical form). */
    val address: String,
)

object DnsPresets {

    val ALL: List<DnsPreset> = listOf(
        DnsPreset("Cloudflare", "Cloudflare (DoH)", "https://1.1.1.1/dns-query"),
        DnsPreset("Cloudflare", "Cloudflare", "1.1.1.1"),
        DnsPreset("Google", "Google (DoH)", "https://8.8.8.8/dns-query"),
        DnsPreset("Google", "Google", "8.8.8.8"),
        DnsPreset("Quad9", "Quad9 (DoH)", "https://9.9.9.9/dns-query"),
        DnsPreset("Quad9", "Quad9", "9.9.9.9"),
        DnsPreset("AdGuard", "AdGuard (DoH)", "https://dns.adguard-dns.com/dns-query"),
        DnsPreset("AdGuard", "AdGuard", "94.140.14.14"),
        DnsPreset("Яндекс", "Яндекс", "77.88.8.8"),
    )

    /** Distinct groups in display order. */
    val GROUPS: List<String> = ALL.map { it.group }.distinct()

    /**
     * Bootstrap `dns.hosts` entries for hostname-based DoH presets, so Xray never has to resolve
     * the DoH server name itself. Key = DoH hostname (lowercase).
     */
    val BOOTSTRAP_HOSTS: Map<String, List<String>> = mapOf(
        "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
    )

    /** Used when the user list is empty / everything is disabled — identical to the pre-3b behaviour. */
    val DEFAULT_SERVERS: List<String> = listOf(
        "https://1.1.1.1/dns-query",
        "https://8.8.8.8/dns-query",
    )

    const val DEFAULT_SUMMARY = "По умолчанию (Cloudflare, Google DoH)"

    fun byAddress(address: String): DnsPreset? = ALL.firstOrNull { it.address == address }
}
