package com.myappvpn.data.parser

import com.myappvpn.data.model.VlessConfig
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.util.IpLiteral
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
            percentDecode(uri.substring(fragmentIdx + 1))
        } else ""
        val uriWithoutFragment = if (fragmentIdx >= 0) uri.substring(0, fragmentIdx) else uri

        // Remove scheme
        val withoutScheme = uriWithoutFragment.removePrefix("vless://")

        // Split user@host:port?query
        val atIdx = withoutScheme.indexOf('@')
        if (atIdx <= 0) return null                      // no '@' or empty UUID
        val uuid = withoutScheme.substring(0, atIdx).trim()
        if (uuid.isEmpty()) return null
        val rest = withoutScheme.substring(atIdx + 1)

        // Split host:port from query. The authority ends at the first '/' or '?'
        // (e.g. "host:8443/?type=ws" → "host:8443").
        val queryIdx = rest.indexOf('?')
        val slashIdx = rest.indexOf('/')
        val authorityEnd = listOf(queryIdx, slashIdx).filter { it >= 0 }.minOrNull() ?: rest.length
        val hostPort = rest.substring(0, authorityEnd)
        val queryString = if (queryIdx >= 0) rest.substring(queryIdx + 1) else ""

        // Parse host:port — handle IPv6 [::1]:port. Null → malformed/invalid port → skip server.
        val (address, port) = parseHostPort(hostPort) ?: return null

        // Parse query params (values are percent-decoded exactly once here)
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
            wsPath = params["path"] ?: "/",
            grpcServiceName = params["serviceName"] ?: "",
            name = name,
            host = params["host"] ?: "",
            alpn = params["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList(),
            spiderX = params["spx"] ?: "",
            headerType = params["headerType"] ?: "",
            xhttpMode = params["mode"] ?: "",
            xhttpExtra = params["extra"] ?: "",
        ).let(::sanitizeForXray)
    }.getOrNull()

    // Xray-core (v25+) refuses to START on removed transports / unknown flow / reality without
    // a key — and in Auto mode ONE such server inside the balancer config kills the whole pool.
    // So unsupported servers are dropped here (parse → null), at the single entry point.
    private val SUPPORTED_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "httpupgrade", "xhttp", "splithttp", "kcp")
    private val SUPPORTED_SECURITY = setOf("none", "tls", "reality")
    private val SUPPORTED_FLOWS = setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
    private val KNOWN_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized", "randomizednoalpn",
    )

    private fun sanitizeForXray(c: VlessConfig): VlessConfig? {
        val transport = c.transport.lowercase()
        val security = c.security.lowercase()
        if (transport !in SUPPORTED_TRANSPORTS) return null
        if (security !in SUPPORTED_SECURITY) return null
        if (c.flow !in SUPPORTED_FLOWS) return null
        if (security == "reality" && c.publicKey.isBlank()) return null
        // Unknown fingerprint is not worth losing the server over — fall back to chrome.
        val fp = c.fingerprint.lowercase().takeIf { it in KNOWN_FINGERPRINTS } ?: "chrome"
        return c.copy(transport = transport, security = security, fingerprint = fp)
    }

    /**
     * Convert VlessConfig to ServerProfile.
     */
    fun toServerProfile(config: VlessConfig, rawUri: String): ServerProfile {
        val (flag, country) = extractFlagAndCountry(config.name)
        return ServerProfile(
            id = stableId(rawUri),
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
     * If the text contains no `vless://` lines, try decoding it as base64 first — many third-party
     * subscriptions ship the whole list as a single base64 blob.
     */
    fun parseSubscription(text: String): List<Pair<VlessConfig, String>> {
        val effective = if (text.contains("vless://")) text else (decodeBase64(text) ?: text)
        return effective.lines()
            .map { it.trim() }
            .filter { it.startsWith("vless://") }
            .mapNotNull { uri ->
                parse(uri)?.let { config -> config to uri }
            }
    }

    /** Best-effort base64 decode of a whole subscription body; null if it isn't valid base64. */
    private fun decodeBase64(text: String): String? {
        val cleaned = text.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        // Tolerate missing '=' padding (a remainder of 1 is never valid base64 — leave as is).
        val rem = cleaned.length % 4
        val padded = if (rem == 2 || rem == 3) cleaned + "=".repeat(4 - rem) else cleaned
        // Standard alphabet first, then URL-safe ('-' / '_') as a fallback.
        val flagsToTry = intArrayOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
        )
        for (flags in flagsToTry) {
            val decoded = runCatching { android.util.Base64.decode(padded, flags) }.getOrNull() ?: continue
            val s = String(decoded, Charsets.UTF_8)
            if (s.contains("vless://")) return s
        }
        return null
    }

    // ── Stable identity ──────────────────────────────────────────────────────────

    private val UUID_REGEX =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Stable server id: md5 of the normalized identity ([identityKey]) — independent of the
     * `#name` fragment, query-param order, percent-encoding and host case, so a source that renames
     * or reorders entries keeps the same id (stats + pin survive refreshes, no duplicates).
     * Falls back to md5(raw URI) only for URIs that can't be normalized.
     */
    fun stableId(rawUri: String): String = md5(identityKey(rawUri) ?: rawUri.trim())

    /**
     * `vless://<uuid>@<host>:<port>?<k=v sorted by key, decoded then re-encoded>` — fragment
     * excluded. uuid lowercased only when it is a real UUID (non-UUID ids are case-sensitive in
     * Xray); host lowercased; IPv6 literals canonicalised (RFC 5952) and bracketed; absent port → 443.
     */
    internal fun identityKey(uri: String): String? = runCatching {
        val trimmed = uri.trim()
        if (!trimmed.regionMatches(0, "vless://", 0, 8, ignoreCase = true)) return null
        val withoutScheme = trimmed.substringBefore('#').substring(8)

        val atIdx = withoutScheme.indexOf('@')
        if (atIdx <= 0) return null
        val rawUuid = withoutScheme.substring(0, atIdx).trim()
        if (rawUuid.isEmpty()) return null
        val uuid = if (UUID_REGEX.matches(rawUuid)) rawUuid.lowercase() else rawUuid
        val rest = withoutScheme.substring(atIdx + 1)

        val queryIdx = rest.indexOf('?')
        val slashIdx = rest.indexOf('/')
        val authorityEnd = listOf(queryIdx, slashIdx).filter { it >= 0 }.minOrNull() ?: rest.length
        val (host, port) = parseHostPort(rest.substring(0, authorityEnd)) ?: return null
        val queryString = if (queryIdx >= 0) rest.substring(queryIdx + 1) else ""

        val lowerHost = host.lowercase()
        val normHost = if (lowerHost.contains(':')) {
            "[" + (IpLiteral.canonicalIpv6(lowerHost) ?: lowerHost) + "]"
        } else lowerHost.trimEnd('.')

        val query = parseQueryList(queryString)
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        "vless://$uuid@$normHost:$port?$query"
    }.getOrNull()

    /** All params as decoded (key, value) pairs, keeping duplicates and dropping empty segments. */
    private fun parseQueryList(query: String): List<Pair<String, String>> {
        if (query.isEmpty()) return emptyList()
        return query.split("&").filter { it.isNotEmpty() }.map { param ->
            val eqIdx = param.indexOf('=')
            if (eqIdx >= 0) {
                percentDecode(param.substring(0, eqIdx)) to percentDecode(param.substring(eqIdx + 1))
            } else {
                percentDecode(param) to ""
            }
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Split an authority "host:port" / "[v6]:port" / "host" / "[v6]".
     * Port defaults to 443 ONLY when absent; a present-but-invalid port (non-numeric or outside
     * 1..65535) or an empty/malformed host → null (server is skipped instead of silently
     * connecting to the wrong port).
     */
    private fun parseHostPort(hostPort: String): Pair<String, Int>? {
        val host: String
        val portStr: String?
        if (hostPort.startsWith("[")) {
            // IPv6: [::1]:443 or [::1]
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket < 0) return null              // malformed (unclosed bracket)
            host = hostPort.substring(1, closeBracket)
            val after = hostPort.substring(closeBracket + 1)
            portStr = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return null                        // garbage after ']'
            }
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx >= 0) {
                host = hostPort.substring(0, colonIdx)
                portStr = hostPort.substring(colonIdx + 1)
            } else {
                host = hostPort
                portStr = null
            }
        }
        if (host.isBlank()) return null
        val port = if (portStr.isNullOrEmpty()) {
            443                                            // port genuinely absent
        } else {
            portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return host to port
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").associate { param ->
            val eqIdx = param.indexOf('=')
            if (eqIdx >= 0) {
                val key = param.substring(0, eqIdx)
                val value = percentDecode(param.substring(eqIdx + 1))
                key to value
            } else {
                param to ""
            }
        }
    }

    /**
     * Lenient percent-decoder: decodes valid %XX runs that form valid UTF-8, leaves a stray or
     * invalid '%' (e.g. "100% fast") untouched, and does NOT turn '+' into a space (URI, not
     * form-encoding). Never throws.
     */
    private fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && isHex(s, i + 1)) {
                // Collect a run of consecutive %XX escapes and decode them together (multi-byte UTF-8).
                val start = i
                val bytes = java.io.ByteArrayOutputStream()
                while (i < s.length && s[i] == '%' && isHex(s, i + 1)) {
                    bytes.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                val decoded = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                        .toString()
                }.getOrNull()
                out.append(decoded ?: s.substring(start, i))   // invalid UTF-8 → keep raw text
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    private fun isHex(s: String, idx: Int): Boolean =
        idx + 1 < s.length && s[idx].isHexDigitChar() && s[idx + 1].isHexDigitChar()

    private fun Char.isHexDigitChar(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    // Country code to (flag, displayName) mapping
    private val countryMap: Map<String, Pair<String, String>> = mapOf(
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
    private val nameToCode: Map<String, String> = mapOf(
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

    // Precompiled once: 2-letter code → whole-token matcher, in countryMap iteration order.
    private val countryCodeRegexes: List<Pair<Regex, Pair<String, String>>> =
        countryMap.map { (code, pair) ->
            Regex("(?<![A-Za-z])${Regex.escape(code)}(?![A-Za-z])", RegexOption.IGNORE_CASE) to pair
        }

    private fun extractFlagAndCountry(name: String): Pair<String, String> {
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
        for ((regex, pair) in countryCodeRegexes) {
            if (regex.containsMatchIn(name)) {
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
