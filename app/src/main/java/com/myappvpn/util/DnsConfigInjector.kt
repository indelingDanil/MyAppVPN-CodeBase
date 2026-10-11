package com.myappvpn.util

import com.myappvpn.data.model.DnsPresets
import com.myappvpn.data.model.EffectiveDns
import com.myappvpn.data.model.XrayDnsEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rewrites the DNS part of an Xray config produced by [XrayConfigBuilder] (single or balancer mode)
 * to use the user's DNS servers. Pure function — only depends on org.json.
 *
 * What it does:
 *  1. Replaces root `dns` with `{servers, hosts?, queryStrategy, tag:"dns-internal"}`.
 *  2. Inserts as the FIRST routing rule `{type:field, inboundTag:["dns-internal"], outboundTag:<vless>}`
 *     (single mode) or `{…, balancerTag:<balancer>}` (balancer mode — detected by a non-empty
 *     `routing.balancers`). Without it, the resolver's own plain UDP/TCP queries to :53 would match
 *     the port-53 hijack rule and loop back into `dns-out`. It also makes hostname-DoH work without
 *     local resolution: the request goes to the proxy with the domain, the exit resolves it.
 *  3. Sets `nonIPQuery: "reject"` on the `dns-out` outbound (non-A/AAAA queries get a fast REFUSED
 *     instead of a timeout).
 * Idempotent: a previous dns-internal rule is removed before inserting.
 */
object DnsConfigInjector {

    const val DNS_TAG = "dns-internal"
    const val DNS_OUT_TAG = "dns-out"
    const val STRATEGY_IPV4 = "UseIPv4"
    const val STRATEGY_IP = "UseIP"

    fun inject(configJson: String, dns: EffectiveDns): String =
        inject(configJson, dns.servers, dns.hosts, dns.queryStrategy)

    /**
     * @param servers entries of `dns.servers`: [XrayDnsEntry], String, or JSONObject (in order).
     *                Empty → the built-in default (Cloudflare + Google DoH).
     * @param hosts   `dns.hosts` (hostname → IPs); omitted when empty.
     * @param queryStrategy "UseIPv4" or "UseIP" (anything else → "UseIPv4").
     */
    fun inject(
        configJson: String,
        servers: List<Any>,
        hosts: Map<String, List<String>>,
        queryStrategy: String,
    ): String {
        val root = JSONObject(configJson)

        // 1. dns block
        val serverArr = JSONArray()
        servers.forEach { s ->
            when (s) {
                is XrayDnsEntry -> serverArr.put(s.toJson())
                is JSONObject -> serverArr.put(s)
                is String -> { if (s.isNotBlank()) serverArr.put(s) }
                else -> Unit
            }
        }
        if (serverArr.length() == 0) DnsPresets.DEFAULT_SERVERS.forEach { serverArr.put(it) }

        root.put("dns", JSONObject().apply {
            put("servers", serverArr)
            if (hosts.isNotEmpty()) {
                put("hosts", JSONObject().apply {
                    hosts.forEach { (name, ips) ->
                        if (ips.isEmpty()) return@forEach
                        if (ips.size == 1) put(name, ips[0])
                        else put(name, JSONArray().apply { ips.forEach { put(it) } })
                    }
                })
            }
            put(
                "queryStrategy",
                if (queryStrategy == STRATEGY_IP) STRATEGY_IP else STRATEGY_IPV4
            )
            put("tag", DNS_TAG)
        })

        // 2. routing: resolver's own traffic → proxy / balancer, FIRST rule
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val oldRules = routing.optJSONArray("rules") ?: JSONArray()
        val balancerTag = routing.optJSONArray("balancers")
            ?.let { arr -> (0 until arr.length()).firstNotNullOfOrNull { i ->
                arr.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotEmpty() }
            } }

        val dnsRule = JSONObject().apply {
            put("type", "field")
            put("inboundTag", JSONArray().apply { put(DNS_TAG) })
            if (balancerTag != null) put("balancerTag", balancerTag)
            else put("outboundTag", findProxyOutboundTag(root))
        }
        val newRules = JSONArray().apply { put(dnsRule) }
        for (i in 0 until oldRules.length()) {
            val r = oldRules.opt(i)
            if (r is JSONObject && isDnsInternalRule(r)) continue
            newRules.put(r)
        }
        routing.put("rules", newRules)

        // 3. dns-out: reject non-A/AAAA queries
        root.optJSONArray("outbounds")?.let { outs ->
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("tag") == DNS_OUT_TAG) {
                    val settings = o.optJSONObject("settings") ?: JSONObject().also { o.put("settings", it) }
                    settings.put("nonIPQuery", "reject")
                }
            }
        }

        return root.toString(2)
    }

    /** Tag of the first VLESS outbound ("proxy" in XrayConfigBuilder.build); fallback "proxy". */
    private fun findProxyOutboundTag(root: JSONObject): String {
        val outs = root.optJSONArray("outbounds") ?: return "proxy"
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") == "vless") return o.optString("tag").ifEmpty { "proxy" }
        }
        return "proxy"
    }

    private fun isDnsInternalRule(r: JSONObject): Boolean {
        val tags = r.optJSONArray("inboundTag") ?: return r.optString("inboundTag") == DNS_TAG
        return (0 until tags.length()).any { tags.optString(it) == DNS_TAG }
    }
}
