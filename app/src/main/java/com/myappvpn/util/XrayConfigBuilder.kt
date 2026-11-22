package com.myappvpn.util

import com.myappvpn.data.model.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    /** Observatory probe target — server must reach Telegram (HTTP 200) to be "alive". */
    const val TELEGRAM_PROBE_URL = "https://t.me/"

    /** Loopback + TUN subnet destinations that proxy clients must never reach through Xray. */
    val LOCAL_BLOCK_IPS = listOf("127.0.0.0/8", "::1/128", "172.19.0.0/30", "fdfe:dcba:9876::/126")
    const val RULE_TAG_LOCAL_BLOCK = "local-block"
    const val RULE_TAG_LOCAL_BLOCK_DOMAIN = "local-block-domain"
    const val RULE_TAG_HOTSPOT_PRIVATE_BLOCK = "hotspot-private-block"
    const val RULE_TAG_HOTSPOT_SELF_BLOCK = "hotspot-self-block"
    const val RULE_TAG_HOTSPOT_OFFNET_BLOCK = "hotspot-offnet-block"

    /**
     * Placeholder listen path of the API inbound in config TEMPLATES. The VPN service always
     * rewrites it to the real socket in the app's private files dir (MyVpnService.prepareConfig).
     */
    const val API_SOCKET_PLACEHOLDER = "/xray-api.sock"

    /**
     * Local control API inbound (dokodemo-door → the "api" handler), listening on a Unix domain
     * socket at [socketPath] instead of a loopback TCP port: a socket file inside the app's
     * private data dir can't be opened by other apps (Linux DAC + per-app SELinux), while any
     * app could connect to a 127.0.0.1 port and drive RoutingService (AddRule / balancer
     * override — e.g. route the tunnel to "direct"). `network: unix` makes Xray create the UDS
     * worker; a fixed `port` keeps dokodemo-door from parsing a port out of the socket address.
     */
    fun apiInbound(socketPath: String): JSONObject = JSONObject().apply {
        put("tag", "api")
        put("listen", socketPath)
        put("protocol", "dokodemo-door")
        put("settings", JSONObject().apply {
            put("address", "127.0.0.1")
            put("port", 1)
            put("network", "unix")
        })
    }

    /**
     * Builds a minimal but complete xray JSON config for VLESS with various transport types.
     * Inbound: SOCKS5 on 127.0.0.1:socksPort (ALWAYS loopback — hotspot sharing adds its own
     * dedicated inbounds bound to the hotspot IP, see MyVpnService.prepareConfig).
     * Outbound: VLESS to remote server
     * No loopback HTTP inbound: nothing in the app uses it (tun2socks, in-tunnel refresh and
     * micro-tests all go through SOCKS), and every extra local listener is one more probe target.
     * [socksAccount] — optional user/password for the SOCKS inbound (micro-tests); the VPN
     * service injects its session credentials itself.
     */
    fun build(
        config: VlessConfig,
        socksPort: Int = 10808,
        socksAccount: Pair<String, String>? = null,
    ): String {
        val listenAddr = "127.0.0.1"
        val root = JSONObject()

        // Log — access log disabled: it would record every destination the user visits.
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
        })

        // DNS — DoH resolver (encrypted; IP-based URLs avoid the bootstrap problem).
        // App DNS on :53 is hijacked to this resolver via the dns-out outbound + the
        // port-53 routing rule below, so the untrusted exit never sees plaintext DNS
        // (and can't spoof it). The DoH request itself egresses through the proxy.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")   // Cloudflare DoH
                put("https://8.8.8.8/dns-query")   // Google DoH
            })
        })

        // Inbounds
        root.put("inbounds", JSONArray().apply {
            // SOCKS5 inbound
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", socksPort)
                put("listen", listenAddr)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    if (socksAccount != null) {
                        put("auth", "password")
                        put("accounts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("user", socksAccount.first)
                                put("pass", socksAccount.second)
                            })
                        })
                    } else {
                        put("auth", "noauth")
                    }
                    put("udp", true)
                    put("ip", listenAddr)
                })
            })
        })

        // Outbounds
        root.put("outbounds", JSONArray().apply {
            // VLESS outbound
            put(buildProxyOutbound(config))
            // DNS outbound — answers hijacked :53 queries via the DoH resolver (dns{} above)
            put(JSONObject().apply {
                put("tag", "dns-out")
                put("protocol", "dns")
            })
            // Direct outbound (for bypassed traffic)
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
            // Block outbound
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply { put("type", "none") })
                })
            })
        })

        // Routing
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                // Hijack ALL DNS (:53, udp+tcp) to the DoH resolver — the untrusted exit
                // must never see or be able to spoof plaintext DNS. MUST precede catch-alls.
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("network", "tcp,udp")
                    put("outboundTag", "dns-out")
                })
                // Block private IPs from going through proxy
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                    })
                })
            })
        })

        applyLocalBlockRules(root)
        return root.toString(2)
    }

    /**
     * Builds a balancer config: one VLESS outbound per server (tags "proxy-0"…"proxy-N"),
     * an observatory that probes each via a real HTTP request, and routing through a
     * balancerTag with the `leastPing` strategy. Xray picks the best alive server and
     * switches seamlessly in-core when one dies — no tunnel restart.
     */
    fun buildBalancer(
        configs: List<VlessConfig>,
        socksPort: Int = 10808,
        apiSocketPath: String = API_SOCKET_PLACEHOLDER,
    ): String {
        val listenAddr = "127.0.0.1"   // always loopback (hotspot inbounds are added separately)
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
        })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://8.8.8.8/dns-query")
            })
        })

        // Local control API — RoutingService only (balancer override `bo` / info `bi`: the app
        // pins the balancer to a chosen proxy without restarting Xray). ObservatoryService was
        // never queried by the app, so it's not exposed. Reachable only via the Unix socket at
        // [apiSocketPath] in the app's private dir (see [apiInbound]), never a TCP port.
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply {
                put("RoutingService")
            })
        })

        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", socksPort)
                put("listen", listenAddr)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("ip", listenAddr)
                })
            })
            // API inbound (dokodemo-door on a private Unix socket); carries `xray api` gRPC calls.
            put(apiInbound(apiSocketPath))
        })

        // One VLESS outbound per server, tagged proxy-0 … proxy-N
        root.put("outbounds", JSONArray().apply {
            configs.forEachIndexed { index, cfg ->
                put(buildProxyOutbound(cfg, "proxy-$index"))
            }
            // DNS outbound — answers hijacked :53 queries via the DoH resolver (dns{} above)
            put(JSONObject().apply {
                put("tag", "dns-out")
                put("protocol", "dns")
            })
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply { put("type", "none") })
                })
            })
        })

        // Observatory — periodic real HTTP probe of every proxy-* outbound.
        // Probe Telegram specifically: a server is only considered "alive" if it can
        // actually reach t.me (HTTP 200), so the balancer never routes through servers
        // that ping fast but can't unblock Telegram. TCP ping alone is insufficient.
        root.put("observatory", JSONObject().apply {
            put("subjectSelector", JSONArray().apply { put("proxy-") })
            put("probeURL", TELEGRAM_PROBE_URL)
            put("probeInterval", "2m")
            put("enableConcurrency", true)
        })

        // Routing: private → direct; everything else → balancer (leastPing)
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("balancers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "balancer")
                    put("selector", JSONArray().apply { put("proxy-") })
                    put("strategy", JSONObject().apply { put("type", "leastPing") })
                })
            })
            put("rules", JSONArray().apply {
                // API calls (loopback dokodemo-door) → api handler. MUST be first.
                put(JSONObject().apply {
                    put("type", "field")
                    put("inboundTag", JSONArray().apply { put("api") })
                    put("outboundTag", "api")
                })
                // Hijack ALL DNS (:53, udp+tcp) → DoH resolver. Before private/balancer catch-alls.
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("network", "tcp,udp")
                    put("outboundTag", "dns-out")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("geoip:private") })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("balancerTag", "balancer")
                })
            })
        })

        applyLocalBlockRules(root)
        return root.toString(2)
    }

    /**
     * OpenFlux (Beta) chain: the same loopback inbound as [build] (socks; the service
     * injects auth / rewrites ports), but the "proxy" outbound is a SOCKS5 client to the local
     * OpenFlux bridge (127.0.0.1:[bridgePort], user/pass auth), which tunnels TCP through the
     * user's PHP exit.
     *
     * The bridge carries TCP CONNECT only, so UDP (QUIC, plain DNS to non-53 ports…) is blocked
     * right away → apps fall back to TCP fast instead of timing out. App DNS on :53 is still
     * hijacked to the DoH resolver (dns-out), whose HTTPS requests egress via "proxy" (the tag
     * [DnsConfigInjector] routes the dns-internal resolver to). The outbound tag MUST stay "proxy".
     */
    fun buildSocksChain(
        socksPort: Int,
        bridgePort: Int,
        bridgeUser: String,
        bridgePass: String,
    ): String {
        val listenAddr = "127.0.0.1"
        val root = JSONObject()

        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
        })

        // DoH only (TCP 443 → through the bridge). Plain UDP resolvers would be dead here.
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://8.8.8.8/dns-query")
            })
        })

        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", socksPort)
                put("listen", listenAddr)
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")   // the service injects the session credentials
                    put("udp", true)        // tun2socks needs UDP ASSOCIATE (DNS :53 → dns-out)
                    put("ip", listenAddr)
                })
            })
        })

        root.put("outbounds", JSONArray().apply {
            // First outbound = default route.
            put(JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", "127.0.0.1")
                            put("port", bridgePort)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("user", bridgeUser)
                                    put("pass", bridgePass)
                                })
                            })
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("tag", "dns-out")
                put("protocol", "dns")
            })
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply { put("type", "none") })
                })
            })
        })

        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                // Hijack ALL DNS (:53) → DoH resolver. MUST precede the UDP block below.
                put(JSONObject().apply {
                    put("type", "field")
                    put("port", 53)
                    put("network", "tcp,udp")
                    put("outboundTag", "dns-out")
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("geoip:private") })
                })
                // The bridge is TCP-only: drop UDP immediately (QUIC → TCP fallback).
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "udp")
                    put("outboundTag", "block")
                })
            })
        })

        applyLocalBlockRules(root)
        return root.toString(2)
    }

    /**
     * Kill-switch config: the SAME loopback SOCKS inbound (session port — the service injects
     * auth) but a single blackhole outbound. Keeps our local port bound while the session is
     * BLOCKED, so no other local app can grab it and receive tun2socks traffic (with our
     * credentials); every connection is simply dropped. No control API here: nothing pins a
     * balancer while BLOCKED, and RoutingService would only be an extra attack surface.
     */
    fun buildBlackhole(socksPort: Int): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
        })
        root.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("ip", "127.0.0.1")
                })
            })
        })
        root.put("outbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply { put("type", "none") })
                })
            })
        })
        root.put("routing", JSONObject().apply {
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("outboundTag", "block")
                })
            })
        })
        return root.toString(2)
    }

    /**
     * Idempotently inserts the "never reach the phone's loopback / TUN subnet" block rules,
     * right AFTER the api inboundTag rule (if any) and therefore before DNS hijack and the
     * geoip:private→direct rule. With [hotspotInboundTags] non-empty, also blocks every private
     * destination (phone LAN/loopback) for those inbounds. Ensures a "block" outbound exists.
     *
     * With [hotspotIp] set (and hotspot tags), the FIRST of our rules blackholes every hotspot
     * connection that doesn't come from a hotspot client: sources = the phone's own hotspot
     * address, loopback, and everything outside the hotspot subnet ([hotspotIp]/[hotspotPrefix]
     * — this covers the phone's other addresses: mobile data, Wi-Fi client, TUN). The hotspot
     * HTTP inbound has no auth (Android's Wi-Fi proxy can't do it), so without this any app on
     * the phone outside the tunnel (split-tunnel INCLUDE mode) could CONNECT through it and learn
     * the VPN exit IP. Real hotspot clients connect from other subnet addresses and keep working.
     */
    fun applyLocalBlockRules(
        root: JSONObject,
        hotspotInboundTags: List<String> = emptyList(),
        hotspotIp: String? = null,
        hotspotPrefix: Int = 24,
    ) {
        val ourTags = setOf(
            RULE_TAG_LOCAL_BLOCK, RULE_TAG_LOCAL_BLOCK_DOMAIN, RULE_TAG_HOTSPOT_PRIVATE_BLOCK,
            RULE_TAG_HOTSPOT_SELF_BLOCK, RULE_TAG_HOTSPOT_OFFNET_BLOCK,
        )
        val routing = root.optJSONObject("routing") ?: JSONObject().also { root.put("routing", it) }
        val old = routing.optJSONArray("rules") ?: JSONArray()
        val kept = ArrayList<Any>()
        for (i in 0 until old.length()) {
            val r = old.opt(i)
            if (r is JSONObject && r.optString("ruleTag") in ourTags) continue
            kept.add(r)
        }
        // Insert after the last rule routing the api inbound (keeps the API working).
        var insertAt = 0
        kept.forEachIndexed { idx, r ->
            if (r is JSONObject && r.optString("outboundTag") == "api") insertAt = idx + 1
        }
        val ours = ArrayList<JSONObject>()
        if (hotspotInboundTags.isNotEmpty() && hotspotIp != null) {
            val tags = JSONArray().apply { hotspotInboundTags.forEach { put(it) } }
            // 1) The phone itself: its hotspot address + loopback.
            ours.add(JSONObject().apply {
                put("type", "field")
                put("ruleTag", RULE_TAG_HOTSPOT_SELF_BLOCK)
                put("inboundTag", tags)
                put("sourceIP", JSONArray().apply {
                    put("$hotspotIp/32")
                    put("127.0.0.0/8")
                    put("::1/128")
                })
                put("outboundTag", "block")
            })
            // 2) Anything outside the hotspot subnet (the phone's other interfaces, IPv6).
            val offNet = ipv4SubnetComplement(hotspotIp, hotspotPrefix)
            if (offNet != null) {
                ours.add(JSONObject().apply {
                    put("type", "field")
                    put("ruleTag", RULE_TAG_HOTSPOT_OFFNET_BLOCK)
                    put("inboundTag", JSONArray().apply { hotspotInboundTags.forEach { put(it) } })
                    put("sourceIP", JSONArray().apply {
                        offNet.forEach { put(it) }
                        put("::/0")
                    })
                    put("outboundTag", "block")
                })
            }
        }
        ours.add(JSONObject().apply {
            put("type", "field")
            put("ruleTag", RULE_TAG_LOCAL_BLOCK)
            put("ip", JSONArray().apply { LOCAL_BLOCK_IPS.forEach { put(it) } })
            // Except :53 — the TUN's placeholder resolver (172.19.0.2 / fdfe:dcba:9876::2) lives in
            // the blocked subnet; DNS to it must fall through to the port-53 → dns-out hijack.
            put("port", "0-52,54-65535")
            put("outboundTag", "block")
        })
        ours.add(JSONObject().apply {
            put("type", "field")
            put("ruleTag", RULE_TAG_LOCAL_BLOCK_DOMAIN)
            put("domain", JSONArray().apply { put("full:localhost") })
            put("outboundTag", "block")
        })
        if (hotspotInboundTags.isNotEmpty()) {
            ours.add(JSONObject().apply {
                put("type", "field")
                put("ruleTag", RULE_TAG_HOTSPOT_PRIVATE_BLOCK)
                put("inboundTag", JSONArray().apply { hotspotInboundTags.forEach { put(it) } })
                put("ip", JSONArray().apply { put("geoip:private") })
                put("outboundTag", "block")
            })
        }
        kept.addAll(insertAt, ours)
        routing.put("rules", JSONArray().apply { kept.forEach { put(it) } })

        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val hasBlock = (0 until outs.length()).any { outs.optJSONObject(it)?.optString("tag") == "block" }
        if (!hasBlock) {
            outs.put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().apply { put("type", "none") })
                })
            })
        }
    }

    /**
     * CIDR list covering all of IPv4 EXCEPT the subnet [ip]/[prefix] (prefix outside 8..30 → 24).
     * [prefix] CIDRs: for every bit position i < prefix, the block that shares the first i bits
     * with the subnet but differs at bit i. null if [ip] isn't a dotted IPv4 address.
     */
    internal fun ipv4SubnetComplement(ip: String, prefix: Int): List<String>? {
        val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        val p = if (prefix in 8..30) prefix else 24
        val addr = parts.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        return (0 until p).map { i ->
            val bit = 1L shl (31 - i)
            val keepMask = if (i == 0) 0L else (0xFFFFFFFFL shl (32 - i)) and 0xFFFFFFFFL
            val net = ((addr and keepMask) or ((addr and bit) xor bit)) and 0xFFFFFFFFL
            val dotted = (3 downTo 0).joinToString(".") { ((net shr (it * 8)) and 0xFF).toString() }
            "$dotted/${i + 1}"
        }
    }

    /** Tag of the helper freedom outbound that fragments the TLS ClientHello (see [applyTlsFragment]). */
    const val TLS_FRAGMENT_TAG = "tls-fragment"
    // Freedom "fragment" defaults: split the ClientHello into 100–200-byte TCP writes with a
    // 10–20 ms pause between them — enough to hide the SNI from simple per-packet DPI while
    // adding only ~50–150 ms to the handshake (a ~500-byte chrome/REALITY hello → 3–5 pieces).
    const val TLS_FRAGMENT_LENGTH = "100-200"
    const val TLS_FRAGMENT_INTERVAL = "10-20"
    private val FRAGMENT_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")
    // UDP-based transports can't be dialed through a TCP freedom fragmenter.
    private val FRAGMENT_SKIP_NETWORKS = setOf("kcp", "mkcp", "quic", "hysteria")

    /**
     * "Обход DPI": the standard Xray way of fragmenting the TLS ClientHello. Every outbound that
     * dials a VPN server (vless/vmess/trojan/ss with TLS or REALITY, TCP-based transport) gets
     * `streamSettings.sockopt.dialerProxy = "tls-fragment"`, and a helper freedom outbound
     *
     *     {"tag":"tls-fragment","protocol":"freedom","settings":{"fragment":
     *         {"packets":"tlshello","length":"100-200","interval":"10-20"}}}
     *
     * is added, so the proxy's TCP connection to the server goes out through freedom, which
     * splits the first TLS record (the ClientHello carrying the SNI) into small pieces.
     * direct / block / dns-out / the OpenFlux socks chain are never touched. Other sockopt
     * fields are preserved; an outbound already using dialerProxy / proxySettings is left alone.
     *
     * Idempotent in both directions: [enabled] = false removes the helper and every
     * dialerProxy pointing at it, so re-preparing an old config after the user turned the
     * option off yields a clean config. Returns the number of outbounds routed via the fragmenter.
     */
    fun applyTlsFragment(root: JSONObject, enabled: Boolean): Int {
        val outs = root.optJSONArray("outbounds") ?: return 0
        val kept = JSONArray()
        var routed = 0
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == TLS_FRAGMENT_TAG) continue   // re-added below if needed
            val stream = o.optJSONObject("streamSettings")
            val sockopt = stream?.optJSONObject("sockopt")
            if (sockopt != null && sockopt.optString("dialerProxy") == TLS_FRAGMENT_TAG) {
                sockopt.remove("dialerProxy")
                if (sockopt.length() == 0) stream.remove("sockopt")
            }
            if (enabled && stream != null && wantsTlsFragment(o, stream)) {
                val so = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                so.put("dialerProxy", TLS_FRAGMENT_TAG)
                routed++
            }
            kept.put(o)
        }
        if (routed > 0) {
            kept.put(JSONObject().apply {
                put("tag", TLS_FRAGMENT_TAG)
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", "tlshello")
                        put("length", TLS_FRAGMENT_LENGTH)
                        put("interval", TLS_FRAGMENT_INTERVAL)
                    })
                })
            })
        }
        root.put("outbounds", kept)
        return routed
    }

    /** Convenience for string configs (micro-tests). */
    fun applyTlsFragment(configJson: String, enabled: Boolean): String {
        val root = JSONObject(configJson)
        applyTlsFragment(root, enabled)
        return root.toString(2)
    }

    private fun wantsTlsFragment(o: JSONObject, stream: JSONObject): Boolean {
        if (o.optString("protocol") !in FRAGMENT_PROTOCOLS) return false
        if (o.has("proxySettings")) return false   // conflicts with sockopt.dialerProxy
        if (stream.optString("security") !in setOf("tls", "reality")) return false  // no ClientHello
        if (stream.optString("network").lowercase() in FRAGMENT_SKIP_NETWORKS) return false
        val existing = stream.optJSONObject("sockopt")?.optString("dialerProxy").orEmpty()
        return existing.isEmpty()
    }

    /** The server outbound for [config] — VLESS or Hysteria2 — tagged [tag]. */
    private fun buildProxyOutbound(config: VlessConfig, tag: String = "proxy"): JSONObject =
        if (config.isHysteria2) buildHysteria2Outbound(config, tag) else buildVlessOutbound(config, tag)

    /**
     * Hysteria2 outbound for Xray v26.2.6 (infra/conf: HysteriaClientConfig, HysteriaConfig,
     * TLSConfig, FinalMask/Salamander):
     *
     *     {"tag":T,"protocol":"hysteria","settings":{"version":2,"address":H,"port":P},
     *      "streamSettings":{"network":"hysteria","security":"tls",
     *        "tlsSettings":{"serverName":SNI,"alpn":["h3"],"pinnedPeerCertSha256":"hex"?},
     *        "hysteriaSettings":{"version":2,"auth":A,"congestion"?,"maxIdleTimeout"?,"keepAlivePeriod"?},
     *        "finalmask":{"udp":[{"type":"salamander","settings":{"password":PW}}]}?}}
     *
     * Deliberately absent: flow / reality / mux / tcpSettings / sockopt (VLESS/TCP-only), the
     * TLS-fragment dialerProxy (applyTlsFragment skips network "hysteria" and protocol "hysteria"),
     * `allowInsecure` (26.x refuses the whole config with it after 2026-06-01), up/down bandwidth
     * and udphop (port hopping) — not in the share links we support. 26.2.6 has no
     * finalmask.quicParams: those knobs live in hysteriaSettings.
     */
    private fun buildHysteria2Outbound(config: VlessConfig, tag: String): JSONObject = JSONObject().apply {
        put("tag", tag)
        put("protocol", "hysteria")
        put("settings", JSONObject().apply {
            put("version", 2)
            put("address", config.address)
            put("port", config.port)
        })
        put("streamSettings", JSONObject().apply {
            put("network", "hysteria")
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", config.sni.ifEmpty { config.address })
                put("alpn", JSONArray().apply { config.alpn.ifEmpty { listOf("h3") }.forEach { put(it) } })
                if (config.hyPinSha256.isNotEmpty()) put("pinnedPeerCertSha256", config.hyPinSha256)
            })
            put("hysteriaSettings", JSONObject().apply {
                put("version", 2)
                put("auth", config.hyAuth)
                if (config.hyCongestion.isNotEmpty()) put("congestion", config.hyCongestion)
                if (config.hyMaxIdleTimeout > 0) put("maxIdleTimeout", config.hyMaxIdleTimeout)
                if (config.hyKeepAlivePeriod > 0) put("keepAlivePeriod", config.hyKeepAlivePeriod)
            })
            if (config.hyObfsPassword.isNotEmpty()) {
                put("finalmask", JSONObject().apply {
                    put("udp", JSONArray().put(JSONObject().apply {
                        put("type", "salamander")
                        put("settings", JSONObject().apply { put("password", config.hyObfsPassword) })
                    }))
                })
            }
        })
    }

    private fun buildVlessOutbound(config: VlessConfig, tag: String = "proxy"): JSONObject {
        return JSONObject().apply {
            put("tag", tag)
            put("protocol", "vless")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply {
                    put(JSONObject().apply {
                        put("address", config.address)
                        put("port", config.port)
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", "none")
                                if (config.flow.isNotEmpty()) {
                                    put("flow", config.flow)
                                }
                            })
                        })
                    })
                })
            })
            // TransportSettings adds per-transport details the URI carries (ws/httpupgrade host,
            // xhttp path/mode/extra, alpn, reality spiderX, tcp http header).
            put("streamSettings", buildStreamSettings(config).also { TransportSettings.apply(it, config) })
        }
    }

    private fun buildStreamSettings(config: VlessConfig): JSONObject {
        return JSONObject().apply {
            put("network", config.transport)

            when (config.security) {
                "reality" -> {
                    put("security", "reality")
                    put("realitySettings", JSONObject().apply {
                        put("serverName", config.sni)
                        put("fingerprint", config.fingerprint.ifEmpty { "chrome" })
                        put("publicKey", config.publicKey)
                        put("shortId", config.shortId)
                        put("spiderX", "/")
                    })
                }
                "tls" -> {
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", config.sni)
                        if (config.fingerprint.isNotEmpty()) {
                            put("fingerprint", config.fingerprint)
                        }
                    })
                }
                else -> put("security", "none")
            }

            // Transport-specific settings
            when (config.transport) {
                "ws" -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", config.wsPath.ifEmpty { "/" })
                        put("headers", JSONObject().apply {
                            if (config.sni.isNotEmpty()) put("Host", config.sni)
                        })
                    })
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", config.grpcServiceName)
                        put("multiMode", false)
                    })
                }
                "tcp" -> {
                    put("tcpSettings", JSONObject())
                }
            }
        }
    }
}
