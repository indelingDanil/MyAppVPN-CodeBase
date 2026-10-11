package com.myappvpn.util

import com.myappvpn.data.model.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fills transport/security details of an Xray `streamSettings` object from the optional VLESS URI
 * params (`host`, `alpn`, `spx`, `headerType`, `mode`, `extra`, `serviceName`, `path`).
 *
 * Designed as a drop-in post-processor for `XrayConfigBuilder.buildStreamSettings(config)`: it
 * only overrides/augments keys, so calling it on the existing output is safe and idempotent.
 * Intended call site (end of XrayConfigBuilder.buildVlessOutbound):
 *
 *     put("streamSettings", buildStreamSettings(config).also { TransportSettings.apply(it, config) })
 */
object TransportSettings {

    fun apply(stream: JSONObject, config: VlessConfig) {
        val network = config.transport.lowercase()
        val path = config.wsPath.ifEmpty { "/" }
        val host = config.host.ifEmpty { config.sni }

        when (network) {
            "ws" -> {
                stream.put("wsSettings", JSONObject().apply {
                    put("path", path)
                    // Xray ≥24.9: dedicated `host` field (headers.Host is deprecated).
                    if (host.isNotEmpty()) put("host", host)
                    remove("headers")
                })
            }
            "httpupgrade" -> {
                stream.put("httpupgradeSettings", JSONObject().apply {
                    put("path", path)
                    if (host.isNotEmpty()) put("host", host)
                })
            }
            "xhttp", "splithttp" -> {
                // Xray accepts "splithttp" as an alias; normalise to the current name.
                stream.put("network", "xhttp")
                stream.remove("splithttpSettings")
                stream.put("xhttpSettings", JSONObject().apply {
                    put("path", path)
                    if (host.isNotEmpty()) put("host", host)
                    put("mode", config.xhttpMode.ifEmpty { "auto" })
                    parseJsonObject(config.xhttpExtra)?.let(::sanitizeXhttpExtra)
                        ?.takeIf { it.length() > 0 }
                        ?.let { put("extra", it) }
                })
            }
            "grpc" -> {
                // Same semantics as the builder (serviceName, multiMode=false); `mode=multi` opts in.
                stream.put("grpcSettings", JSONObject().apply {
                    put("serviceName", config.grpcServiceName)
                    put("multiMode", config.xhttpMode.equals("multi", ignoreCase = true))
                })
            }
            "tcp", "raw" -> {
                if (config.headerType.equals("http", ignoreCase = true)) {
                    stream.put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("path", JSONArray().put(path))
                                if (host.isNotEmpty()) {
                                    put("headers", JSONObject().apply {
                                        put("Host", JSONArray().apply {
                                            host.split(',').map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .forEach { put(it) }
                                        })
                                    })
                                }
                            })
                        })
                    })
                } else if (!stream.has("tcpSettings")) {
                    stream.put("tcpSettings", JSONObject())
                }
            }
        }

        when (config.security) {
            "reality" -> {
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also {
                    stream.put("realitySettings", it)
                }
                reality.put("spiderX", config.spiderX.ifEmpty { "/" })
            }
            "tls" -> {
                if (config.alpn.isNotEmpty()) {
                    val tls = stream.optJSONObject("tlsSettings") ?: JSONObject().also {
                        stream.put("tlsSettings", it)
                    }
                    tls.put("alpn", JSONArray().apply { config.alpn.forEach { put(it) } })
                }
            }
        }
    }

    /** Non-blank string that parses as a JSON object → the object; anything else → null. */
    /**
     * `extra` comes verbatim from a share link and Xray unmarshals it strictly — a single
     * mistyped field (seen in the wild: "uplinkChunkSize": "…" as a string) makes Xray refuse
     * the WHOLE config, and in Auto that kills all 50 balancer outbounds. So only known fields
     * pass, coerced to the type Xray expects; anything else is dropped.
     */
    private fun sanitizeXhttpExtra(src: JSONObject): JSONObject {
        val out = JSONObject()
        src.optJSONObject("headers")?.let { h ->
            val clean = JSONObject()
            h.keys().forEach { k -> (h.opt(k) as? String)?.let { clean.put(k, it) } }
            if (clean.length() > 0) out.put("headers", clean)
        }
        for (k in listOf("xPaddingBytes", "scMaxEachPostBytes", "scMinPostsIntervalMs", "scStreamUpServerSecs")) {
            range(src.opt(k))?.let { out.put(k, it) }
        }
        for (k in listOf("scMaxBufferedPosts", "uplinkChunkSize")) {
            number(src.opt(k))?.let { out.put(k, it) }
        }
        for (k in listOf("noGRPCHeader", "noSSEHeader")) {
            bool(src.opt(k))?.let { out.put(k, it) }
        }
        src.optJSONObject("xmux")?.let { x ->
            val clean = JSONObject()
            for (k in listOf("maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs")) {
                range(x.opt(k))?.let { clean.put(k, it) }
            }
            number(x.opt("hKeepAlivePeriod"))?.let { clean.put("hKeepAlivePeriod", it) }
            if (clean.length() > 0) out.put("xmux", clean)
        }
        // downloadSettings (a nested streamSettings) is deliberately dropped — too easy to break.
        return out
    }

    private val RANGE = Regex("""^\d+(-\d+)?$""")

    /** Xray Int32Range: a non-negative number or a "min-max" string. */
    private fun range(v: Any?): Any? = when (v) {
        is Number -> v.toLong().takeIf { it >= 0 }
        is String -> v.trim().takeIf { RANGE.matches(it) }?.let { s -> s.toLongOrNull() ?: s }
        else -> null
    }

    /** Plain non-negative integer (accepts a numeric string). */
    private fun number(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }?.takeIf { it >= 0 }

    private fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }

    private fun parseJsonObject(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }
}
