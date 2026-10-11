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

    /**
     * Builds a minimal but complete xray JSON config for VLESS with various transport types.
     * Inbound: SOCKS5 on 127.0.0.1:socksPort (ALWAYS loopback — hotspot sharing adds its own
     * dedicated inbounds bound to the hotspot IP, see MyVpnService.prepareConfig).
     * Outbound: VLESS to remote server
     * [includeHttpInbound] — add the loopback HTTP inbound on [httpPort]. Micro-tests pass
     * false so parallel test instances don't fight over neighbouring ports.
     * [socksAccount] — optional user/password for the SOCKS inbound (micro-tests); the VPN
     * service injects its session credentials itself.
     */
    fun build(
        config: VlessConfig,
        socksPort: Int = 10808,
        includeHttpInbound: Boolean = true,
        httpPort: Int = socksPort + 1,
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
            // HTTP inbound (optional, for apps that prefer HTTP proxy). Loopback-only.
            if (includeHttpInbound) {
                put(JSONObject().apply {
                    put("tag", "http")
                    put("port", httpPort)
                    put("listen", "127.0.0.1")
                    put("protocol", "http")
                    put("settings", JSONObject().apply {
                        put("allowTransparent", false)
                    })
                })
            }
        })

        // Outbounds
        root.put("outbounds", JSONArray().apply {
            // VLESS outbound
            put(buildVlessOutbound(config))
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
        httpPort: Int = socksPort + 1,
        apiPort: Int = socksPort + 2,
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

        // Local control API (RoutingService = balancer override `bo`/info `bi`,
        // ObservatoryService = per-outbound alive/delay). Bound to loopback only, on [apiPort]
        // (the VPN service picks a random free port per session).
        // Lets the app know which proxy is alive/active and pin a chosen one (sticky) without
        // restarting Xray — IF this build honors it (verified by an on-device spike).
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply {
                put("RoutingService")
                put("ObservatoryService")
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
            put(JSONObject().apply {
                put("tag", "http")
                put("port", httpPort)
                put("listen", "127.0.0.1")   // loopback-only; never expose HTTP proxy to LAN
                put("protocol", "http")
                put("settings", JSONObject().apply { put("allowTransparent", false) })
            })
            // API inbound (dokodemo-door) — loopback only; carries `xray api` gRPC calls.
            put(JSONObject().apply {
                put("tag", "api")
                put("port", apiPort)
                put("listen", "127.0.0.1")
                put("protocol", "dokodemo-door")
                put("settings", JSONObject().apply { put("address", "127.0.0.1") })
            })
        })

        // One VLESS outbound per server, tagged proxy-0 … proxy-N
        root.put("outbounds", JSONArray().apply {
            configs.forEachIndexed { index, cfg ->
                put(buildVlessOutbound(cfg, "proxy-$index"))
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
     * Kill-switch config: the SAME loopback inbounds (socks/http/api on the session ports — the
     * service injects auth) but a single blackhole outbound. Keeps our local ports bound while
     * the session is BLOCKED, so no other local app can grab them and receive tun2socks traffic
     * (with our credentials); every connection is simply dropped.
     */
    fun buildBlackhole(socksPort: Int, httpPort: Int, apiPort: Int): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
            put("access", "none")
        })
        root.put("api", JSONObject().apply {
            put("tag", "api")
            put("services", JSONArray().apply {
                // NOT ObservatoryService: it hard-requires an `observatory` section, which this
                // config doesn't have — Xray would refuse to start and the port would go unbound.
                put("RoutingService")
            })
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
            put(JSONObject().apply {
                put("tag", "http")
                put("port", httpPort)
                put("listen", "127.0.0.1")
                put("protocol", "http")
                put("settings", JSONObject().apply { put("allowTransparent", false) })
            })
            put(JSONObject().apply {
                put("tag", "api")
                put("port", apiPort)
                put("listen", "127.0.0.1")
                put("protocol", "dokodemo-door")
                put("settings", JSONObject().apply { put("address", "127.0.0.1") })
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
                    put("inboundTag", JSONArray().apply { put("api") })
                    put("outboundTag", "api")
                })
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
     */
    fun applyLocalBlockRules(root: JSONObject, hotspotInboundTags: List<String> = emptyList()) {
        val ourTags = setOf(RULE_TAG_LOCAL_BLOCK, RULE_TAG_LOCAL_BLOCK_DOMAIN, RULE_TAG_HOTSPOT_PRIVATE_BLOCK)
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
        ours.add(JSONObject().apply {
            put("type", "field")
            put("ruleTag", RULE_TAG_LOCAL_BLOCK)
            put("ip", JSONArray().apply { LOCAL_BLOCK_IPS.forEach { put(it) } })
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
