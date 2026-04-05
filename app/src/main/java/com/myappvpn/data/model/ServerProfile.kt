package com.myappvpn.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerProfile(
    @PrimaryKey val id: String,         // hash of vlessUri
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
     *  latency × reliabilityMultiplier × protocolMultiplier
     *
     *  reliabilityMultiplier:
     *    no history   → 1.5  (unknown — slight caution)
     *    no failures  → 0.8  (perfect record — bonus)
     *    has failures → 1.0 + failRate  (range 1.0 – 2.0)
     *
     *  protocolMultiplier:
     *    TCP+Reality → 0.75  (DPI-resistant)
     *    others      → 1.0
     */
    fun score(): Float {
        val effectiveLatency = if (latencyMs > 0) latencyMs.toFloat() else 9999f
        val total = successCount + failCount
        val reliability = when {
            total == 0     -> 1.5f
            failCount == 0 -> 0.8f
            else           -> 1f + failCount.toFloat() / total.toFloat()
        }
        val protocol = if (isTcpReality) 0.75f else 1.0f
        // Speed factor: known-slow servers are heavily penalised; unknown (0) = slight penalty
        val speed = when {
            speedMbps <= 0f   -> 1.2f   // not yet tested
            speedMbps < 0.5f  -> 8.0f   // too slow — very unlikely to be picked
            speedMbps < 1.0f  -> 1.5f   // below average
            speedMbps >= 5.0f -> 0.7f   // fast — bonus
            else              -> 0.9f   // decent
        }
        return effectiveLatency * reliability * protocol * speed
    }
}

enum class LatencyCategory { UNKNOWN, TIMEOUT, GOOD, MEDIUM, BAD }
