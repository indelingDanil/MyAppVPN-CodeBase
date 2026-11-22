package com.myappvpn.data.model

/**
 * One parsed share link. Historically VLESS-only (hence the name); [protocol] = "hysteria2" reuses
 * the common fields (address / port / sni / alpn / name; security is always "tls", transport
 * "hysteria") plus the `hy*` fields below. VLESS-only fields (uuid, flow, reality, transport
 * extras) stay at their defaults for Hysteria2 and XrayConfigBuilder never emits them for it.
 */
data class VlessConfig(
    val uuid: String,
    val address: String,
    val port: Int,
    val security: String = "none",       // "reality", "tls", "none"
    val sni: String = "",
    val publicKey: String = "",           // pbk — for Reality
    val shortId: String = "",             // sid — for Reality
    val flow: String = "",                // "xtls-rprx-vision" or ""
    val transport: String = "tcp",        // "tcp", "ws", "grpc", "xhttp"
    val fingerprint: String = "chrome",   // TLS fingerprint
    val wsPath: String = "/",
    val grpcServiceName: String = "",
    val name: String = "",
    // Transport extras (all optional; empty = not set in the URI)
    val host: String = "",                // `host` — ws/httpupgrade/xhttp Host header, tcp-http host
    val alpn: List<String> = emptyList(), // `alpn` — comma list, e.g. ["h2", "http/1.1"] (TLS only)
    val spiderX: String = "",             // `spx` — Reality spiderX
    val headerType: String = "",          // `headerType` — "http" for tcp HTTP obfuscation, "none"
    val xhttpMode: String = "",           // `mode` — xhttp: "auto" / "packet-up" / "stream-up" / "stream-one"
    val xhttpExtra: String = "",          // `extra` — xhttp raw JSON object (applied only if valid JSON)
    // ── Protocol + Hysteria2-only fields ──
    val protocol: String = PROTOCOL_VLESS, // "vless" | "hysteria2"
    val hyAuth: String = "",              // userinfo of hysteria2://AUTH@… (URL-decoded; "user:pass" kept whole)
    val hyPinSha256: String = "",         // `pinSHA256` — lowercase hex, colons stripped (comma list allowed)
    val hyObfsPassword: String = "",      // `obfs=salamander` + `obfs-password` → finalmask udp salamander
    val hyCongestion: String = "",        // `fm` quicParams.congestion: "bbr" / "reno" / "brutal" ("" = Xray default)
    val hyMaxIdleTimeout: Int = 0,        // `fm` quicParams.maxIdleTimeout, s (0 = Xray default 30)
    val hyKeepAlivePeriod: Int = 0,       // `fm` quicParams.keepAlivePeriod, s (0 = Xray default)
) {
    val isHysteria2: Boolean get() = protocol == PROTOCOL_HYSTERIA2

    companion object {
        const val PROTOCOL_VLESS = "vless"
        const val PROTOCOL_HYSTERIA2 = "hysteria2"
    }
}
