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
        // Country code to (flag, displayName) mapping
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
            "UK" to ("🇬🇧" to "UK"),
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
            "CA" to ("🇨🇦" to "Canada"),
            "IN" to ("🇮🇳" to "India"),
            "IR" to ("🇮🇷" to "Iran"),
            "IE" to ("🇮🇪" to "Ireland"),
            "IT" to ("🇮🇹" to "Italy"),
            "ES" to ("🇪🇸" to "Spain"),
            "PT" to ("🇵🇹" to "Portugal"),
            "DK" to ("🇩🇰" to "Denmark"),
            "GR" to ("🇬🇷" to "Greece"),
            "RS" to ("🇷🇸" to "Serbia"),
            "HR" to ("🇭🇷" to "Croatia"),
            "SK" to ("🇸🇰" to "Slovakia"),
            "SI" to ("🇸🇮" to "Slovenia"),
            "TW" to ("🇹🇼" to "Taiwan"),
            "ID" to ("🇮🇩" to "Indonesia"),
            "MY" to ("🇲🇾" to "Malaysia"),
            "TH" to ("🇹🇭" to "Thailand"),
            "VN" to ("🇻🇳" to "Vietnam"),
            "PH" to ("🇵🇭" to "Philippines"),
            "AE" to ("🇦🇪" to "UAE"),
            "ZA" to ("🇿🇦" to "South Africa"),
            "MX" to ("🇲🇽" to "Mexico"),
            "AR" to ("🇦🇷" to "Argentina"),
            "CL" to ("🇨🇱" to "Chile"),
            "CO" to ("🇨🇴" to "Colombia"),
            "AM" to ("🇦🇲" to "Armenia"),
            "GE" to ("🇬🇪" to "Georgia"),
            "AZ" to ("🇦🇿" to "Azerbaijan"),
            "UZ" to ("🇺🇿" to "Uzbekistan"),
        )

        // Full country name → code lookup for names like "United States", "Germany", etc.
        val nameToCode = mapOf(
            "russia" to "RU", "germany" to "DE", "albania" to "AL", "austria" to "AT",
            "ukraine" to "UA", "belarus" to "BY", "bulgaria" to "BG", "australia" to "AU",
            "brazil" to "BR", "united states" to "US", "usa" to "US", "netherlands" to "NL",
            "holland" to "NL", "united kingdom" to "GB", "france" to "FR", "finland" to "FI",
            "sweden" to "SE", "norway" to "NO", "switzerland" to "CH", "poland" to "PL",
            "czech" to "CZ", "hungary" to "HU", "romania" to "RO", "moldova" to "MD",
            "lithuania" to "LT", "latvia" to "LV", "estonia" to "EE", "kazakhstan" to "KZ",
            "turkey" to "TR", "turkiye" to "TR", "israel" to "IL", "japan" to "JP",
            "korea" to "KR", "singapore" to "SG", "hong kong" to "HK", "canada" to "CA",
            "india" to "IN", "iran" to "IR", "ireland" to "IE", "italy" to "IT",
            "spain" to "ES", "portugal" to "PT", "denmark" to "DK", "greece" to "GR",
            "serbia" to "RS", "croatia" to "HR", "slovakia" to "SK", "slovenia" to "SI",
            "taiwan" to "TW", "indonesia" to "ID", "malaysia" to "MY", "thailand" to "TH",
            "vietnam" to "VN", "philippines" to "PH", "emirates" to "AE", "uae" to "AE",
            "south africa" to "ZA", "mexico" to "MX", "argentina" to "AR", "chile" to "CL",
            "colombia" to "CO", "armenia" to "AM", "georgia" to "GE", "azerbaijan" to "AZ",
            "uzbekistan" to "UZ",
        )

        val nameLower = name.lowercase()

        // 1. Check flag emoji at start (e.g. "🇩🇪 DE #12")
        for ((_, pair) in countryMap) {
            if (name.startsWith(pair.first)) {
                return pair.first to pair.second
            }
        }

        // 2. Check full country names (e.g. "United States #3", "Germany-vless-1")
        for ((fullName, code) in nameToCode) {
            if (nameLower.contains(fullName)) {
                val pair = countryMap[code] ?: continue
                return pair.first to pair.second
            }
        }

        // 3. Check 2-letter codes with word boundary (e.g. "DE-1", "US #3")
        // Match codes that appear as whole tokens, not substrings of other words
        for ((code, pair) in countryMap) {
            if (Regex("(?<![A-Za-z])${Regex.escape(code)}(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
                return pair.first to pair.second
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
