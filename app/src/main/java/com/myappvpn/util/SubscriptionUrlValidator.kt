package com.myappvpn.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * SSRF guard for subscription URLs.
 *
 * A subscription URL is fetched by the app over the open internet, so an attacker who can trick
 * the user into adding a crafted URL could otherwise point it at internal/loopback endpoints
 * (e.g. `http://127.0.0.1/admin`, cloud metadata `169.254.169.254`, `file://`). We only allow plain
 * `https://` to public hosts.
 *
 * Layers:
 *  1. [validate] — static checks on the URL (scheme, host names, IP literals parsed WITHOUT DNS).
 *  2. [isNonPublicAddress] — used by the subscription OkHttp `Dns` (see
 *     `com.myappvpn.data.remote.PublicOnlyDns`) to reject hostnames that RESOLVE to private ranges
 *     (DNS rebinding / internal names).
 *  3. Every redirect hop is re-validated by `SubscriptionFetcher` before it is followed.
 */
object SubscriptionUrlValidator {

    /** Validation outcome with a human-readable reason on failure. */
    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun validate(rawUrl: String): Result {
        val url = rawUrl.trim()
        if (url.isEmpty()) return Result.Invalid("Пустой адрес")

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return Result.Invalid("Некорректный URL")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            return Result.Invalid("Разрешён только https:// (получено: ${scheme ?: "нет схемы"})")
        }

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return Result.Invalid("Не удалось определить хост")

        if (isBlockedHost(host)) {
            return Result.Invalid("Локальные и приватные адреса запрещены")
        }

        return Result.Valid
    }

    fun isValid(rawUrl: String): Boolean = validate(rawUrl) is Result.Valid

    private fun isBlockedHost(rawHost: String): Boolean {
        // Bracketed → IPv6 literal (URI.host keeps the brackets, e.g. "[::1]"). Parsed without DNS.
        if (rawHost.startsWith("[")) {
            if (!rawHost.endsWith("]")) return true
            val literal = rawHost.substring(1, rawHost.length - 1)
            if (literal.contains('%')) return true                 // zone ids → local by definition
            val bytes = IpLiteral.parseIpv6(literal) ?: return true // malformed literal → reject
            return isNonPublicAddress(InetAddress.getByAddress(bytes))
        }

        // "localhost." / "example.com." — a trailing dot is the same name for the resolver.
        val host = rawHost.trimEnd('.')
        if (host.isEmpty()) return true

        // Loopback / localhost / metadata / internal-only names
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "metadata" || host.endsWith(".internal") || host.contains("metadata.google")) return true
        if (host.endsWith(".local") || host.endsWith(".home.arpa")) return true

        // IPv4 literal (canonical dotted quad) — parsed without DNS.
        IpLiteral.parseIpv4(host)?.let { return isNonPublicAddress(InetAddress.getByAddress(it)) }

        // Anything whose last label is numeric / hex is an IP-literal attempt in a non-canonical
        // form ("2130706433", "0x7f.1", "127.1") that some resolvers would still map to an IP.
        // No real TLD is numeric, so legit domains are unaffected.
        val lastLabel = host.substringAfterLast('.')
        if (lastLabel.all { it.isDigit() } || lastLabel.startsWith("0x")) return true

        return false
    }

    /**
     * True for any address that must never be reached by a subscription fetch: unspecified,
     * loopback, private (RFC1918 / ULA), link-local, CGNAT, benchmark, multicast, broadcast,
     * reserved, and IPv6 forms embedding such an IPv4 (mapped / compatible / NAT64 / 6to4).
     */
    fun isNonPublicAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return true
        val b = addr.address
        return when (addr) {
            is Inet4Address -> isNonPublicV4(b)
            is Inet6Address -> isNonPublicV6(b)
            else -> true
        }
    }

    private fun isNonPublicV4(b: ByteArray): Boolean {
        if (b.size != 4) return true
        val a = b[0].toInt() and 0xff
        val c = b[1].toInt() and 0xff
        val d = b[2].toInt() and 0xff
        return when {
            a == 0 -> true                              // 0.0.0.0/8
            a == 10 -> true                             // 10.0.0.0/8
            a == 100 && c in 64..127 -> true            // 100.64.0.0/10 CGNAT
            a == 127 -> true                            // loopback
            a == 169 && c == 254 -> true                // link-local + metadata
            a == 172 && c in 16..31 -> true             // 172.16.0.0/12
            a == 192 && c == 0 && d == 0 -> true        // 192.0.0.0/24 IETF protocol assignments
            a == 192 && c == 168 -> true                // 192.168.0.0/16
            a == 198 && (c == 18 || c == 19) -> true    // 198.18.0.0/15 benchmark
            a >= 224 -> true                            // multicast, reserved 240/4, broadcast
            else -> false
        }
    }

    private fun isNonPublicV6(b: ByteArray): Boolean {
        if (b.size != 16) return true
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        if (first == 0xff) return true                                  // ff00::/8 multicast
        if (first == 0xfe && (second and 0xc0) == 0x80) return true     // fe80::/10 link-local
        if (first == 0xfe && (second and 0xc0) == 0xc0) return true     // fec0::/10 site-local
        if ((first and 0xfe) == 0xfc) return true                       // fc00::/7 ULA
        // First 10 bytes zero → ::, ::1, ::a.b.c.d (compatible), ::ffff:a.b.c.d (mapped)
        if ((0 until 10).all { b[it].toInt() == 0 }) {
            val b10 = b[10].toInt() and 0xff
            val b11 = b[11].toInt() and 0xff
            if ((b10 == 0 && b11 == 0) || (b10 == 0xff && b11 == 0xff)) {
                return isNonPublicV4(b.copyOfRange(12, 16)) ||
                    (b10 == 0 && (12 until 16).all { b[it].toInt() == 0 })
            }
        }
        // 64:ff9b::/96 NAT64 → judge the embedded IPv4
        if (first == 0x00 && second == 0x64 && (b[2].toInt() and 0xff) == 0xff &&
            (b[3].toInt() and 0xff) == 0x9b && (4 until 12).all { b[it].toInt() == 0 }
        ) return isNonPublicV4(b.copyOfRange(12, 16))
        // 2002::/16 6to4 → judge the embedded IPv4
        if (first == 0x20 && second == 0x02) return isNonPublicV4(b.copyOfRange(2, 6))
        return false
    }
}

/** DNS-free IP literal parsing / canonicalisation (InetAddress.getByName may hit DNS on bad input). */
object IpLiteral {

    /** Canonical dotted-quad IPv4 ("1.2.3.4") → 4 bytes, else null. Leading zeros rejected. */
    fun parseIpv4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            if (p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[i] = v.toByte()
        }
        return out
    }

    /** IPv6 literal (no brackets, no zone id; embedded trailing IPv4 allowed) → 16 bytes, else null. */
    fun parseIpv6(input: String): ByteArray? {
        var s = input
        if (s.isEmpty() || s.contains('%')) return null
        val groups = IntArray(8)
        // Embedded IPv4 tail ("::ffff:1.2.3.4") → rewrite as two hex groups.
        val lastColon = s.lastIndexOf(':')
        if (lastColon < 0) return null
        val tail = s.substring(lastColon + 1)
        if (tail.contains('.')) {
            val v4 = parseIpv4(tail) ?: return null
            val hi = ((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff)
            val lo = ((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff)
            s = s.substring(0, lastColon + 1) + Integer.toHexString(hi) + ":" + Integer.toHexString(lo)
        }
        fun parseGroups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.length !in 1..4 || !g.all { it.isHex() }) return null
                g.toInt(16)
            }
        }
        val dbl = s.indexOf("::")
        if (dbl >= 0) {
            if (s.indexOf("::", dbl + 1) >= 0) return null
            val head = parseGroups(s.substring(0, dbl)) ?: return null
            val rest = parseGroups(s.substring(dbl + 2)) ?: return null
            if (head.size + rest.size > 7) return null
            head.forEachIndexed { i, v -> groups[i] = v }
            rest.forEachIndexed { i, v -> groups[8 - rest.size + i] = v }
        } else {
            val all = parseGroups(s) ?: return null
            if (all.size != 8) return null
            all.forEachIndexed { i, v -> groups[i] = v }
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[2 * i] = (groups[i] shr 8).toByte()
            out[2 * i + 1] = (groups[i] and 0xff).toByte()
        }
        return out
    }

    /** RFC 5952-style canonical text for 16 IPv6 bytes (lowercase, no leading zeros, longest "::"). */
    fun formatIpv6(b: ByteArray): String {
        require(b.size == 16)
        val g = IntArray(8) { ((b[2 * it].toInt() and 0xff) shl 8) or (b[2 * it + 1].toInt() and 0xff) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen && j - i >= 2) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(g[i]))
            i++
        }
        return sb.toString()
    }

    /** Canonical IPv6 text if [s] is an IPv6 literal, else null. */
    fun canonicalIpv6(s: String): String? = parseIpv6(s)?.let { formatIpv6(it) }

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
