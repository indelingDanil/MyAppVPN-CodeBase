package com.myappvpn.util

import java.net.URI
import java.util.Locale

/**
 * SSRF guard for user-supplied subscription URLs.
 *
 * A custom subscription URL is fetched by the app over the open internet, so an attacker who can
 * trick the user into adding a crafted URL could otherwise point it at internal/loopback endpoints
 * (e.g. `http://127.0.0.1/admin`, cloud metadata `169.254.169.254`, `file://`). We only allow plain
 * `https://` to public hosts. No DNS resolution / rebinding defense — overkill for this app; literal
 * host checks are enough.
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

    private fun isBlockedHost(host: String): Boolean {
        // Loopback / localhost / metadata by name
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "metadata" || host.endsWith(".internal") || host.contains("metadata.google")) return true

        // IPv6 literals (URI.host keeps the brackets, e.g. "[::1]")
        val v6 = host.removePrefix("[").removeSuffix("]")
        if (v6 == "::1") return true                       // loopback
        if (v6.startsWith("fe80:")) return true            // link-local
        if (v6.startsWith("fc") || v6.startsWith("fd")) return true  // unique-local

        // IPv4 literals — block loopback / private / link-local
        val octets = host.split(".")
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return when {
                a == 127 -> true                          // 127.0.0.0/8 loopback
                a == 10 -> true                           // 10.0.0.0/8
                a == 192 && b == 168 -> true              // 192.168.0.0/16
                a == 172 && b in 16..31 -> true           // 172.16.0.0/12
                a == 169 && b == 254 -> true              // 169.254.0.0/16 link-local + metadata
                a == 0 -> true                            // 0.0.0.0/8
                else -> false
            }
        }
        return false
    }
}
