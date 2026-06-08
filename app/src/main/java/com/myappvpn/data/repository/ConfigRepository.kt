package com.myappvpn.data.repository

import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.remote.GithubApiService
import com.myappvpn.data.remote.SubscriptionUrls
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository @Inject constructor(
    private val apiService: GithubApiService,
    private val serverDao: ServerDao,
    private val preferences: AppPreferences
) {
    companion object {
        private val BLOCKED_COUNTRIES = setOf("Ukraine")
    }

    val servers: Flow<List<ServerProfile>> = serverDao.getAllServers()

    suspend fun fetchAndSaveServers(): Result<Int> = runCatching {
        // Each source is a fallback chain (identical mirrors) — take the first that works.
        // Chains are fetched in parallel; their results are merged.
        val texts: List<String?> = coroutineScope {
            SubscriptionUrls.ALL_CHAINS.map { chain ->
                async { fetchFirstWorking(chain) }
            }.map { it.await() }
        }
        // If ALL sources failed → no network → throw so Worker retries with backoff
        // instead of silently reporting "0 servers updated" and waiting a full hour.
        if (texts.all { it == null }) throw IOException("All subscription sources unreachable")

        val newProfiles = texts.filterNotNull()
            .flatMap { text ->
                VlessUriParser.parseSubscription(text)
                    .map { (config, uri) -> VlessUriParser.toServerProfile(config, uri) }
            }
            .filter { it.country !in BLOCKED_COUNTRIES }
        // De-duplicate by ID (same server may appear in multiple sources/mirrors)
        val deduplicated = newProfiles.distinctBy { it.id }
        if (deduplicated.isNotEmpty()) {
            // Preserve learned data (ping latency, score counters, current selection)
            // for servers that already exist in the DB. New servers start with defaults.
            val existing = serverDao.getAllServers().first().associateBy { it.id }
            val merged = deduplicated.map { fresh ->
                val old = existing[fresh.id]
                if (old != null) fresh.copy(
                    latencyMs            = old.latencyMs,
                    successCount         = old.successCount,
                    failCount            = old.failCount,
                    isSelected           = old.isSelected,
                    speedMbps            = old.speedMbps,
                    validationState      = old.validationState,
                    lastValidatedAt      = old.lastValidatedAt,
                    medianLatencyMs      = old.medianLatencyMs,
                    pingCount            = old.pingCount,
                    consecutiveFailures  = old.consecutiveFailures,
                    handshakeOk          = old.handshakeOk,
                    verifiedAt           = old.verifiedAt,
                ) else fresh
            }
            serverDao.insertAll(merged)                    // upsert (REPLACE strategy)
            serverDao.deleteNotIn(merged.map { it.id })   // remove stale servers
        }
        deduplicated.size
    }

    /** Try mirrors in order; return the first response that contains vless:// links. */
    private suspend fun fetchFirstWorking(chain: List<String>): String? {
        for (url in chain) {
            val text = runCatching { apiService.fetchRawFile(url) }.getOrNull()
            if (text != null && text.contains("vless://")) return text
        }
        return null
    }

    /**
     * Servers eligible for the balancer pool / manual list: drops blocked countries
     * and any user-excluded countries (e.g. "Russia" by default). Ordered by latency
     * (DB already sorts by latencyMs) so callers can take the top N.
     */
    suspend fun getServersExcludingCountries(excluded: Set<String>): List<ServerProfile> =
        serverDao.getAllServers().first()
            .filter { it.country !in BLOCKED_COUNTRIES && it.country !in excluded }

    suspend fun getSelectedServer(): ServerProfile? = serverDao.getSelectedServer()

    suspend fun selectServer(serverId: String) {
        serverDao.clearSelection()
        serverDao.selectServer(serverId)
    }

    suspend fun updateLatency(serverId: String, latencyMs: Int) {
        serverDao.updateLatency(serverId, latencyMs)
    }

    suspend fun recordSuccess(serverId: String) {
        serverDao.incrementSuccess(serverId)
    }

    suspend fun recordFailure(serverId: String) {
        serverDao.incrementFail(serverId)
    }

    suspend fun updateSpeed(serverId: String, speedMbps: Float) {
        serverDao.updateSpeed(serverId, speedMbps)
    }

    /**
     * Selects the best server using composite score (see ServerProfile.score()).
     * Prefers VERIFIED servers, then falls back to any tested server.
     */
    suspend fun selectBestServer(
        excludeIds: Set<String> = emptySet(),
        excludePorts: Set<Int> = emptySet(),
    ): ServerProfile? {
        val all = serverDao.getAllServers().first()
            .filter { it.id !in excludeIds }

        val eligible = all.filter { it.port !in excludePorts }.ifEmpty { all }

        // First: recently verified servers (last hour)
        val verified = eligible.filter {
            it.validationState == "VERIFIED" &&
            (System.currentTimeMillis() - it.verifiedAt) < 60 * 60_000L
        }
        if (verified.isNotEmpty()) return verified.minByOrNull { it.score() }

        // Fallback: any server with latency data
        return eligible.filter { it.latencyMs > 0 || it.medianLatencyMs > 0 }
            .minByOrNull { it.score() }
            ?: eligible.minByOrNull { it.score() }
    }

    /** Best verified server — for pre-connect validation fallback. */
    suspend fun selectBestVerifiedServer(excludeIds: Set<String> = emptySet()): ServerProfile? {
        val verified = serverDao.getRecentlyVerifiedServers(
            since = System.currentTimeMillis() - 60 * 60_000L
        )
        return verified.filter { it.id !in excludeIds }.minByOrNull { it.score() }
    }

    suspend fun markVerified(serverId: String) {
        serverDao.markVerified(serverId, System.currentTimeMillis())
    }

    suspend fun incrementConsecutiveFailure(serverId: String) {
        serverDao.incrementConsecutiveFailure(serverId)
    }

    suspend fun resetConsecutiveFailures(serverId: String) {
        serverDao.resetConsecutiveFailures(serverId)
    }
}
