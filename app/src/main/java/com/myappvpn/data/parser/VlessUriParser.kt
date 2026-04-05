package com.myappvpn.data.parser

import com.myappvpn.data.model.VlessConfig
import com.myappvpn.data.model.ServerProfile
import java.net.URLDecoder
import java.security.MessageDigest

object VlessUriParser {

    /**
     * Parse a VLESS URI into VlessConfig.
     * Format: vless://UUID@host:port?params#fragment
     */
    fun parse(uri: String): VlessConfig? = runCatching {
        if (!uri.startsWith("vless://")) return null

        // Split off the fragment (#name) first
        val fragmentIdx = uri.indexOf('#')
        val name = if (fragmentIdx >= 0) {
            URLDecoder.decode(uri.substring(fragmentIdx + 1), "UTF-8")
        } else ""
        val uriWithoutFragment = if (fragmentIdx >= 0) uri.substring(0, fragmentIdx) else uri

        // Remove scheme
        val withoutScheme = uriWithoutFragment.removePrefix("vless://")

        // Split user@host:port?query
        val atIdx = withoutScheme.indexOf('@')
        val uuid = withoutScheme.substring(0, atIdx)
        val rest = withoutScheme.substring(atIdx + 1)

        // Split host:port from query
        val queryIdx = rest.indexOf('?')
        val hostPort = if (queryIdx >= 0) rest.substring(0, queryIdx) else rest
        val queryString = if (queryIdx >= 0) rest.substring(queryIdx + 1) else ""

        // Parse host:port — handle IPv6 [::1]:port
        val (address, port) = parseHostPort(hostPort)

        // Parse query params
        val params = parseQueryParams(queryString)

        VlessConfig(
            uuid = uuid,
            address = address,
            port = port,
            security = params["security"] ?: "none",
            sni = params["sni"] ?: "",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            flow = params["flow"] ?: "",
            transport = params["type"] ?: "tcp",
            fingerprint = params["fp"] ?: "chrome",
            wsPath = URLDecoder.decode(params["path"] ?: "/", "UTF-8"),
            grpcServiceName = params["serviceName"] ?: "",
            name = name
        )
    }.getOrNull()

    /**
     * Convert VlessConfig to ServerProfile.
     */
    fun toServerProfile(config: VlessConfig, rawUri: String): ServerProfile {
        val (flag, country) = extractFlagAndCountry(config.name)
        return ServerProfile(
            id = md5(rawUri),
            name = config.name.ifEmpty { "${config.address}:${config.port}" },
            flag = flag,
            country = country,
            address = config.address,
            port = config.port,
            vlessUri = rawUri,
            transport = config.transport,
            security = config.security
        )
    }

    /**
     * Parse list of VLESS URIs from subscription text (one per line).
     */
    fun parseSubscription(text: String): List<Pair<VlessConfig, String>> {
        return text.lines()
            .map { it.trim() }
            .filter { it.startsWith("vless://") }
            .mapNotNull { uri ->
                parse(uri)?.let { config -> config to uri }
            }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        return if (hostPort.startsWith("[")) {
            // IPv6: [::1]:443
            val closeBracket = hostPort.indexOf(']')
            val host = hostPort.substring(1, closeBracket)
            val port = hostPort.substring(closeBracket + 2).toIntOrNull() ?: 443
            host to port
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx >= 0) {
                val host = hostPort.substring(0, colonIdx)
                val port = hostPort.substring(colonIdx + 1).toIntOrNull() ?: 443
                host to port
            } else {
                hostPort to 443
            }
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { param ->
            val eqIdx = param.indexOf('=')
            if (eqIdx >= 0) {
                val key = param.substring(0, eqIdx)
                val value = URLDecoder.decode(param.substring(eqIdx + 1), "UTF-8")
                key to value
            } else {
                param to ""
            }
        }
    }

    private fun extractFlagAndCountry(name: String): Pair<String, String> {
        // Country code to country name mapping
        val countryMap = mapOf(
            "RU" to ("🇷🇺" to "Russia"),
            "DE" to ("🇩🇪" to "Germany"),
            "AL" to ("🇦🇱" to "Albania"),
            "AT" to ("🇦🇹" to "Austria"),
            "UA" to ("🇺🇦" to "Ukraine"),
            "BY" to ("🇧🇾" to "Belarus"),
            "BG" to ("🇧🇬" to "Bulgaria"),
            "AU" to ("🇦🇺" to "Australia"),
            "BR" to ("🇧🇷" to "Brazil"),
            "US" to ("🇺🇸" to "USA"),
            "NL" to ("🇳🇱" to "Netherlands"),
            "GB" to ("🇬🇧" to "UK"),
            "FR" to ("🇫🇷" to "France"),
            "FI" to ("🇫🇮" to "Finland"),
            "SE" to ("🇸🇪" to "Sweden"),
            "NO" to ("🇳🇴" to "Norway"),
            "CH" to ("🇨🇭" to "Switzerland"),
            "PL" to ("🇵🇱" to "Poland"),
            "CZ" to ("🇨🇿" to "Czech Rep."),
            "HU" to ("🇭🇺" to "Hungary"),
            "RO" to ("🇷🇴" to "Romania"),
            "MD" to ("🇲🇩" to "Moldova"),
            "LT" to ("🇱🇹" to "Lithuania"),
            "LV" to ("🇱🇻" to "Latvia"),
            "EE" to ("🇪🇪" to "Estonia"),
            "KZ" to ("🇰🇿" to "Kazakhstan"),
            "TR" to ("🇹🇷" to "Turkey"),
            "IL" to ("🇮🇱" to "Israel"),
            "JP" to ("🇯🇵" to "Japan"),
            "KR" to ("🇰🇷" to "South Korea"),
            "SG" to ("🇸🇬" to "Singapore"),
            "HK" to ("🇭🇰" to "Hong Kong"),
        )

        // Try to extract flag from name string (first 2 chars might be emoji flag)
        // Flag emojis are stored in the name like "🇩🇪 DE #12"
        for ((code, pair) in countryMap) {
            if (name.contains(code, ignoreCase = true) || name.contains(pair.first)) {
                return pair.first to pair.second
            }
        }

        // Check for flag emoji at start of name
        val flagEmojis = countryMap.values.map { it.first }
        for (flag in flagEmojis) {
            if (name.startsWith(flag)) {
                val country = countryMap.values.find { it.first == flag }?.second ?: "Unknown"
                return flag to country
            }
        }

        return "🌐" to "Unknown"
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
