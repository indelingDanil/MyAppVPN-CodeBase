package com.myappvpn.data.model

import org.json.JSONObject

/**
 * A user-configured DNS server used by Xray's internal resolver (root `dns.servers`).
 *
 * [address] is the CANONICAL form produced by [com.myappvpn.util.DnsValidator]:
 *  - UDP:  `1.2.3.4`, `1.2.3.4:5353`, `2001:db8::1`, `[2001:db8::1]:5353`
 *  - TCP:  `tcp://1.2.3.4:53`, `tcp://[2001:db8::1]:53`
 *  - DoH:  `https://1.1.1.1/dns-query`, `https://dns.adguard-dns.com/dns-query`
 * [id] equals [address] (canonical → used for dedupe and as a stable list key).
 * Order in the list matters: Xray tries servers in order.
 */
data class DnsServer(
    val id: String,
    val address: String,
    val label: String,
    val enabled: Boolean,
) {
    val type: DnsType get() = DnsType.of(address)
}

enum class DnsType {
    UDP, TCP, DOH;

    companion object {
        fun of(address: String): DnsType {
            val a = address.trim().lowercase()
            return when {
                a.startsWith("https://") -> DOH
                a.startsWith("tcp://") -> TCP
                else -> UDP
            }
        }
    }
}

/**
 * One entry of Xray `dns.servers`.
 *  - [port] == null → emitted as a plain string ([address] is an IP, a `tcp://…` or an `https://…` URL).
 *  - [port] != null → emitted as an object `{"address": ip, "port": p}` (plain UDP on a non-53 port;
 *    Xray's string form for plain UDP has no port syntax, the object form is the documented way).
 * A data class (not JSONObject) so lists compare by value (usable with distinctUntilChanged).
 */
data class XrayDnsEntry(val address: String, val port: Int? = null) {
    fun toJson(): Any =
        if (port == null) address
        else JSONObject().apply {
            put("address", address)
            put("port", port)
        }
}

/** Everything the config injector needs; value-comparable so the service can react to changes. */
data class EffectiveDns(
    val servers: List<XrayDnsEntry>,
    val hosts: Map<String, List<String>>,
    val queryStrategy: String,
)
