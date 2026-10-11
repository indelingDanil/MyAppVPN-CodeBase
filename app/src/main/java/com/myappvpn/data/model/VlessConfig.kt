package com.myappvpn.data.model

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
)
