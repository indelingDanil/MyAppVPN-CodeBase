package com.myappvpn.util

import com.myappvpn.data.model.DnsPresets
import com.myappvpn.data.model.DnsType
import com.myappvpn.data.model.XrayDnsEntry

/**
 * Validates and normalizes a user-entered DNS server address for Xray's internal resolver.
 *
 * Pure Kotlin, NO network / DNS lookups (IP literals are parsed by hand — never
 * InetAddress.getByName, which would resolve hostnames outside the tunnel).
 *
 * Accepted inputs:
 *  - plain UDP: `1.2.3.4`, `1.2.3.4:5353`, `2001:db8::1`, `[2001:db8::1]:5353`, `udp://IP[:port]`
 *  - TCP:       `tcp://IP[:port]` (port default 53)
 *  - DoH:       `https://host-or-IP[:port][/path]` (path default `/dns-query`)
 *
 * Xray output format (see [XrayDnsEntry]):
 *  - UDP port 53  → plain string `"1.2.3.4"`
 *  - UDP other    → object `{"address":"1.2.3.4","port":5353}` (Xray's documented way to set a port
 *                   for plain DNS; the string form has no port syntax for UDP)
 *  - TCP          → `"tcp://1.2.3.4:53"` (port always explicit)
 *  - DoH          → the canonical https URL
 *
 * Rejected: `+local` schemes / localhost (queries would bypass the tunnel), tls:// / quic://,
 * http://, userinfo, private / loopback / link-local / CGNAT / multicast / reserved IPs,
 * bad ports, non-ASCII or malformed hostnames, inputs longer than 255 chars.
 */
object DnsValidator {

    const val MAX_LENGTH = 255

    const val MSG_LEAK = "DNS-запросы пойдут мимо туннеля — утечка"
    const val MSG_UNSUPPORTED = "Не поддерживается (DNS-over-TLS/QUIC). Используйте https:// (DoH)"
    const val MSG_HTTP = "Для DoH разрешён только https://"
    const val MSG_PRIVATE = "Локальный/частный адрес не подойдёт — DNS должен идти через VPN"
    const val MSG_USERINFO = "Логин/пароль в адресе не поддерживаются"
    const val MSG_PORT = "Порт должен быть от 1 до 65535"
    const val MSG_HOSTNAME = "Некорректное имя хоста (только латиница, цифры, точки, дефисы)"
    const val MSG_TOO_LONG = "Слишком длинный адрес"
    const val MSG_FORMAT =
        "Неверный формат. Примеры: 1.1.1.1, tcp://1.1.1.1:53, https://1.1.1.1/dns-query"
    const val MSG_NEED_IP =
        "Укажите IP-адрес (для DNS по имени используйте https://…/dns-query)"
    const val MSG_QUERY = "Параметры (?, #) в адресе DoH не поддерживаются"
    const val MSG_PATH = "Недопустимые символы в пути DoH"
    const val MSG_EMPTY = "Введите адрес DNS-сервера"

    const val WARN_PLAIN = "Обычный DNS виден выходному серверу — DoH безопаснее"
    const val WARN_DOH_HOSTNAME = "Для DoH по имени лучше указать IP-адрес"

    sealed class Result {
        data class Ok(
            /** Canonical text form — stored in prefs and used as the dedupe key. */
            val canonical: String,
            val type: DnsType,
            /** IP (no brackets) or lowercase hostname. */
            val host: String,
            val port: Int,
            val isIpLiteral: Boolean,
            /** Non-blocking advice for the UI, or null. */
            val warning: String?,
            /** What goes into Xray `dns.servers`. */
            val xrayEntry: XrayDnsEntry,
        ) : Result()

        data class Error(val message: String) : Result()
    }

    fun validate(raw: String): Result {
        val input = raw.trim()
        if (input.isEmpty()) return Result.Error(MSG_EMPTY)
        if (input.length > MAX_LENGTH) return Result.Error(MSG_TOO_LONG)
        if (input.any { it.isWhitespace() || it.code < 0x21 || it.code == 0x7F }) {
            return Result.Error(MSG_FORMAT)
        }

        val schemeSep = input.indexOf("://")
        if (schemeSep < 0) {
            val lower = input.lowercase()
            if (lower == "localhost" || lower == "localdns" || lower.endsWith(".localhost")) {
                return Result.Error(MSG_LEAK)
            }
            if (lower == "fakedns") return Result.Error(MSG_UNSUPPORTED)
            return parsePlain(input, DnsType.UDP)
        }

        val scheme = input.substring(0, schemeSep).lowercase()
        val rest = input.substring(schemeSep + 3)
        if (scheme.contains("+local")) return Result.Error(MSG_LEAK)
        return when (scheme) {
            "https" -> parseDoh(rest)
            "tcp" -> parsePlain(rest, DnsType.TCP)
            "udp" -> parsePlain(rest, DnsType.UDP)
            "http" -> Result.Error(MSG_HTTP)
            "tls", "quic", "h2c", "dot", "doq", "sdns", "doh" -> Result.Error(MSG_UNSUPPORTED)
            else -> Result.Error(MSG_FORMAT)
        }
    }

    fun isValid(raw: String): Boolean = validate(raw) is Result.Ok

    /** Warning for an already-stored (canonical) address, or null. */
    fun warningFor(address: String): String? = (validate(address) as? Result.Ok)?.warning

    // ── UDP / TCP: IP literal with optional port ────────────────────────────────

    private fun parsePlain(s: String, type: DnsType): Result {
        if (s.contains('@')) return Result.Error(MSG_USERINFO)
        if (s.contains('/') || s.contains('?') || s.contains('#')) return Result.Error(MSG_FORMAT)
        val hp = splitHostPort(s) ?: return Result.Error(MSG_FORMAT)
        val port = if (hp.port == null) 53 else (parsePort(hp.port) ?: return Result.Error(MSG_PORT))

        val ip = parseIp(hp.host)
        if (ip == null) {
            if (hp.bracketed) return Result.Error(MSG_FORMAT)
            val lower = hp.host.lowercase()
            if (lower == "localhost" || lower.endsWith(".localhost")) return Result.Error(MSG_LEAK)
            return Result.Error(MSG_NEED_IP)
        }
        if (hp.bracketed && !ip.isV6) return Result.Error(MSG_FORMAT)
        publicIpError(ip)?.let { return Result.Error(it) }

        val ipText = ip.format()
        val hostForUrl = if (ip.isV6) "[$ipText]" else ipText
        return when (type) {
            DnsType.TCP -> {
                val canonical = "tcp://$hostForUrl:$port"
                Result.Ok(canonical, DnsType.TCP, ipText, port, true, WARN_PLAIN, XrayDnsEntry(canonical))
            }
            else -> {
                val canonical = if (port == 53) ipText else "$hostForUrl:$port"
                val entry = if (port == 53) XrayDnsEntry(ipText) else XrayDnsEntry(ipText, port)
                Result.Ok(canonical, DnsType.UDP, ipText, port, true, WARN_PLAIN, entry)
            }
        }
    }

    // ── DoH ─────────────────────────────────────────────────────────────────────

    private fun parseDoh(rest: String): Result {
        if (rest.contains('?') || rest.contains('#')) return Result.Error(MSG_QUERY)
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        var path = if (slash < 0) "" else rest.substring(slash)
        if (authority.isEmpty()) return Result.Error(MSG_FORMAT)
        if (authority.contains('@')) return Result.Error(MSG_USERINFO)

        val hp = splitHostPort(authority) ?: return Result.Error(MSG_FORMAT)
        val port = if (hp.port == null) 443 else (parsePort(hp.port) ?: return Result.Error(MSG_PORT))

        if (path.isEmpty() || path == "/") path = "/dns-query"
        if (!path.all { it.isLetterOrDigit() && it.code < 128 || it in "-._~/%" } || path.contains("//")) {
            return Result.Error(MSG_PATH)
        }

        val ip = parseIp(hp.host)
        val host: String
        val hostForUrl: String
        val isIp: Boolean
        if (ip != null) {
            if (hp.bracketed != ip.isV6) return Result.Error(MSG_FORMAT) // bare IPv6 in URL / bracketed v4
            publicIpError(ip)?.let { return Result.Error(it) }
            host = ip.format()
            hostForUrl = if (ip.isV6) "[$host]" else host
            isIp = true
        } else {
            if (hp.bracketed) return Result.Error(MSG_FORMAT)
            host = when (val h = normalizeHostname(hp.host)) {
                is HostCheck.Bad -> return Result.Error(h.message)
                is HostCheck.Good -> h.host
            }
            hostForUrl = host
            isIp = false
        }

        val canonical = "https://$hostForUrl" + (if (port != 443) ":$port" else "") + path
        if (canonical.length > MAX_LENGTH) return Result.Error(MSG_TOO_LONG)
        val warning = if (!isIp && host !in DnsPresets.BOOTSTRAP_HOSTS) WARN_DOH_HOSTNAME else null
        return Result.Ok(canonical, DnsType.DOH, host, port, isIp, warning, XrayDnsEntry(canonical))
    }

    // ── Host / port helpers ─────────────────────────────────────────────────────

    private data class HostPort(val host: String, val port: String?, val bracketed: Boolean)

    private fun splitHostPort(s: String): HostPort? {
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            val host = s.substring(1, end)
            val after = s.substring(end + 1)
            return when {
                after.isEmpty() -> HostPort(host, null, true)
                after.startsWith(":") -> HostPort(host, after.substring(1), true)
                else -> null
            }
        }
        if (s.contains(']')) return null
        return when (s.count { it == ':' }) {
            0 -> HostPort(s, null, false)
            1 -> HostPort(s.substringBefore(':'), s.substringAfter(':'), false)
            else -> HostPort(s, null, false) // bare IPv6 literal (no port possible)
        }
    }

    private fun parsePort(s: String): Int? {
        if (s.isEmpty() || s.length > 5 || !s.all { it in '0'..'9' }) return null
        val p = s.toInt()
        return if (p in 1..65535) p else null
    }

    private sealed class HostCheck {
        data class Good(val host: String) : HostCheck()
        data class Bad(val message: String) : HostCheck()
    }

    private fun normalizeHostname(raw: String): HostCheck {
        if (raw.any { it.code > 127 }) {
            return HostCheck.Bad("Используйте имя в ASCII (punycode, xn--…)")
        }
        val host = raw.lowercase().removeSuffix(".")
        if (host == "localhost" || host.endsWith(".localhost")) return HostCheck.Bad(MSG_LEAK)
        if (host.length !in 1..253) return HostCheck.Bad(MSG_HOSTNAME)
        val labels = host.split('.')
        if (labels.size < 2) return HostCheck.Bad(MSG_HOSTNAME)
        val labelRe = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        if (!labels.all { labelRe.matches(it) }) return HostCheck.Bad(MSG_HOSTNAME)
        if (labels.last().all { it in '0'..'9' }) return HostCheck.Bad(MSG_HOSTNAME)
        val privateSuffixes = listOf("local", "lan", "internal", "intranet", "home", "corp", "arpa")
        if (labels.last() in privateSuffixes || host.endsWith(".home.arpa")) {
            return HostCheck.Bad(MSG_PRIVATE)
        }
        return HostCheck.Good(host)
    }

    // ── IP literal parsing (no lookups) ─────────────────────────────────────────

    /** Parsed IP: 4 bytes (v4) or 16 bytes (v6), each 0..255. */
    internal class Ip(val bytes: IntArray) {
        val isV6: Boolean get() = bytes.size == 16

        fun format(): String = if (!isV6) bytes.joinToString(".") else formatV6(bytes)
    }

    internal fun parseIp(s: String): Ip? =
        parseIpv4(s)?.let { Ip(it) } ?: parseIpv6(s)?.let { Ip(it) }

    internal fun parseIpv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.length !in 1..3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null // no octal-looking leading zeros
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v
        }
        return out
    }

    internal fun parseIpv6(input: String): IntArray? {
        if (input.length !in 2..45 || input.contains('%')) return null
        var s = input
        var v4Tail: IntArray? = null
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0) return null
        val tail = s.substring(lastColon + 1)
        if (tail.contains('.')) {
            v4Tail = parseIpv4(tail) ?: return null
            s = s.substring(0, lastColon + 1) + "0:0"
        }

        fun groupsOf(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            for (g in part.split(':')) {
                if (g.length !in 1..4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    return null
                }
                out.add(g.toInt(16))
            }
            return out
        }

        val dbl = s.indexOf("::")
        val groups: List<Int> = if (dbl >= 0) {
            if (s.indexOf("::", dbl + 1) >= 0) return null
            val head = groupsOf(s.substring(0, dbl)) ?: return null
            val tailGroups = groupsOf(s.substring(dbl + 2)) ?: return null
            if (head.size + tailGroups.size > 7) return null
            head + List(8 - head.size - tailGroups.size) { 0 } + tailGroups
        } else {
            val all = groupsOf(s) ?: return null
            if (all.size != 8) return null
            all
        }

        val bytes = IntArray(16)
        for (i in 0 until 8) {
            bytes[i * 2] = (groups[i] shr 8) and 0xFF
            bytes[i * 2 + 1] = groups[i] and 0xFF
        }
        if (v4Tail != null) for (i in 0 until 4) bytes[12 + i] = v4Tail[i]
        return bytes
    }

    /** RFC 5952 text form: lowercase, no leading zeros, longest (>=2) zero run compressed. */
    private fun formatV6(b: IntArray): String {
        val g = IntArray(8) { (b[it * 2] shl 8) or b[it * 2 + 1] }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { Integer.toHexString(it) }
        val head = (0 until bestStart).joinToString(":") { Integer.toHexString(g[it]) }
        val tail = (bestStart + bestLen until 8).joinToString(":") { Integer.toHexString(g[it]) }
        return "$head::$tail"
    }

    /** Error message if the IP is not a public unicast address, else null. */
    internal fun publicIpError(ip: Ip): String? {
        val b = ip.bytes
        if (!ip.isV6) return if (isNonPublicV4(b)) MSG_PRIVATE else null

        val allZeroPrefix10 = (0 until 10).all { b[it] == 0 }
        // IPv4-mapped (::ffff:a.b.c.d) / IPv4-compatible (::a.b.c.d) → judge the embedded IPv4
        if (allZeroPrefix10 && b[10] == 0xFF && b[11] == 0xFF) {
            return if (isNonPublicV4(b.copyOfRange(12, 16))) MSG_PRIVATE else null
        }
        if (b.all { it == 0 }) return MSG_PRIVATE                                   // :: unspecified
        if ((0 until 15).all { b[it] == 0 } && b[15] == 1) return MSG_PRIVATE       // ::1 loopback
        if (allZeroPrefix10 && b[10] == 0 && b[11] == 0) return MSG_PRIVATE         // ::/96 deprecated
        if ((b[0] and 0xFE) == 0xFC) return MSG_PRIVATE                             // fc00::/7 ULA
        if (b[0] == 0xFE && (b[1] and 0xC0) == 0x80) return MSG_PRIVATE             // fe80::/10 link-local
        if (b[0] == 0xFE && (b[1] and 0xC0) == 0xC0) return MSG_PRIVATE             // fec0::/10 site-local
        if (b[0] == 0xFF) return MSG_PRIVATE                                        // ff00::/8 multicast
        // NAT64 64:ff9b::/96 → judge embedded IPv4
        if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B &&
            (4 until 12).all { b[it] == 0 }
        ) {
            return if (isNonPublicV4(b.copyOfRange(12, 16))) MSG_PRIVATE else null
        }
        return null
    }

    private fun isNonPublicV4(b: IntArray): Boolean {
        val a = b[0]
        val c = b[1]
        return a == 0 ||                                  // 0.0.0.0/8 (incl. unspecified)
            a == 10 ||                                    // 10/8
            (a == 100 && (c and 0xC0) == 64) ||           // 100.64/10 CGNAT
            a == 127 ||                                   // loopback
            (a == 169 && c == 254) ||                     // link-local
            (a == 172 && (c and 0xF0) == 16) ||           // 172.16/12
            (a == 192 && c == 0 && b[2] == 0) ||          // 192.0.0/24 IETF
            (a == 192 && c == 168) ||                     // 192.168/16
            (a == 198 && (c == 18 || c == 19)) ||         // 198.18/15 benchmarking
            a >= 224                                      // multicast 224/4 + reserved 240/4 + broadcast
    }
}
