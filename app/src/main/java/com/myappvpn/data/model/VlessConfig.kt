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
    val name: String = ""
)
