package com.myappvpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerProfile(
    @PrimaryKey val id: String,         // VlessUriParser.stableId: md5 of normalized identity (no #name)
    val name: String,                   // e.g. "🇩🇪 DE #12"
    val flag: String,                   // country flag emoji
    val country: String,                // "Germany", "Russia", etc.
    val address: String,                // IP or hostname
    val port: Int,
    val vlessUri: String,               // full raw URI
    val transport: String = "tcp",      // "tcp", "ws", "grpc"
    val security: String = "none",      // "none", "tls", "reality"
    val latencyMs: Int = -1,            // -1 = not tested, 0 = timeout
    val isSelected: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val successCount: Int = 0,          // watchdog-confirmed healthy connections
    val failCount: Int = 0,             // watchdog-detected VPN-level failures
    val speedMbps: Float = 0f,          // last measured download speed in MB/s, 0 = not tested
    // v2.0: multi-stage validation fields
    val validationState: String = "UNTESTED", // UNTESTED, REACHABLE, HANDSHAKE_OK, VERIFIED, STALE
    val lastValidatedAt: Long = 0L,
    val medianLatencyMs: Int = -1,      // median of 3 pings
    val pingCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val handshakeOk: Boolean = false,
    val verifiedAt: Long = 0L,
) {
    /** TCP+Reality bypasses carrier DPI — best for mobile internet. */
    val isTcpReality: Boolean get() = transport == "tcp" && security == "reality"

    val latencyCategory: LatencyCategory
        get() = when {
            latencyMs < 0 -> LatencyCategory.UNKNOWN
            latencyMs == 0 -> LatencyCategory.TIMEOUT
            latencyMs < 100 -> LatencyCategory.GOOD
            latencyMs < 300 -> LatencyCategory.MEDIUM
            else -> LatencyCategory.BAD
        }

    /**
     * Composite score: lower = better.
     *
     * v2.0: validation state is the dominant factor.
     * A VERIFIED server at 200ms beats an UNTESTED server at 50ms.
     */
    fun score(): Float {
        // Base latency: prefer median if available
        // RU servers get +500ms penalty — they can't unblock Telegram/YouTube etc.
        val geoPenalty = if (country.equals("Russia", ignoreCase = true)) 500f else 0f
        val effectiveLatency = geoPenalty + when {
            medianLatencyMs > 0 -> medianLatencyMs.toFloat()
            latencyMs > 0       -> latencyMs.toFloat()
            else                -> 9999f
        }

        // Validation: VERIFIED servers get massive advantage
        val now = System.currentTimeMillis()
        val validation = when (validationState) {
            "VERIFIED" -> {
                val age = now - verifiedAt
                when {
                    age < 15 * 60_000L      -> 0.3f  // <15min: huge bonus
                    age < 60 * 60_000L      -> 0.5f  // <1hr: strong bonus
                    age < 6 * 60 * 60_000L  -> 0.7f  // <6hr: moderate bonus
                    else                    -> 0.9f  // old: slight bonus
                }
            }
            "HANDSHAKE_OK" -> 0.8f
            "REACHABLE"    -> 1.0f
            "UNTESTED"     -> 1.5f
            "STALE"        -> 2.0f
            else           -> 1.5f
        }

        // Reliability with consecutive failure escalation
        val total = successCount + failCount
        val reliability = when {
            consecutiveFailures >= 5 -> 3.0f
            consecutiveFailures >= 3 -> 2.0f
            total == 0               -> 1.2f
            failCount == 0           -> 0.8f
            else                     -> 1f + failCount.toFloat() / total.toFloat()
        }

        val protocol = if (isTcpReality) 0.75f else 1.0f

        // Speed: softened penalties (was 8x for <0.5, now 2x)
        val speed = when {
            speedMbps <= 0f   -> 1.1f
            speedMbps < 0.1f  -> 4.0f
            speedMbps < 0.5f  -> 2.0f
            speedMbps < 1.0f  -> 1.3f
            speedMbps >= 5.0f -> 0.7f
            else              -> 0.9f
        }

        // Confidence: more pings = more reliable measurement
        val confidence = when {
            pingCount >= 3 -> 0.95f
            pingCount == 1 -> 1.1f
            else           -> 1.0f
        }

        return effectiveLatency * validation * reliability * protocol * speed * confidence
    }
}

enum class LatencyCategory { UNKNOWN, TIMEOUT, GOOD, MEDIUM, BAD }
