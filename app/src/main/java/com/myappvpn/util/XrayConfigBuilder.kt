package com.myappvpn.util

import com.myappvpn.data.model.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    /** Observatory probe target — server must reach Telegram (HTTP 200) to be "alive". */
    const val TELEGRAM_PROBE_URL = "https://t.me/"

    /**
     * Builds a minimal but complete xray JSON config for VLESS with various transport types.
     * Inbound: SOCKS5 on listenAddr:socksPort
     * Outbound: VLESS to remote server
     * [listenOnAllInterfaces] — bind to 0.0.0.0 so hotspot clients can reach the proxy.
     */
    fun build(config: VlessConfig, socksPort: Int = 10808, listenOnAllInterfaces: Boolean = false): String {
        val listenAddr = if (listenOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
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
                    put("auth", "noauth")
                    put("udp", true)
                    put("ip", listenAddr)
                })
            })
            // HTTP inbound (optional, for apps that prefer HTTP proxy).
            // Always loopback-only: hotspot clients use SOCKS (auth'd); we never expose
            // an HTTP proxy to the LAN even when listenOnAllInterfaces is set.
            put(JSONObject().apply {
                put("tag", "http")
                put("port", socksPort + 1)
                put("listen", "127.0.0.1")
                put("protocol", "http")
                put("settings", JSONObject().apply {
                    put("allowTransparent", false)
                })
            })
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
        listenOnAllInterfaces: Boolean = false,
    ): String {
        val listenAddr = if (listenOnAllInterfaces) "0.0.0.0" else "127.0.0.1"
        val root = JSONObject()

        root.put("log", JSONObject().apply { put("loglevel", "warning") })

        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("https://1.1.1.1/dns-query")
                put("https://8.8.8.8/dns-query")
            })
        })

        // Local control API (RoutingService = balancer override `bo`/info `bi`,
        // ObservatoryService = per-outbound alive/delay). Bound to loopback only, on socksPort+2.
        // Lets the app know which proxy is alive/active and pin a chosen one (sticky) without
        // restarting Xray — IF this build honors it (verified by an on-device spike).
        val apiPort = socksPort + 2
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
                put("port", socksPort + 1)
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

        return root.toString(2)
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
            put("streamSettings", buildStreamSettings(config))
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
                        put("allowInsecure", false)
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
