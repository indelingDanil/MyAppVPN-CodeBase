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
        // Fetch all sources in parallel; ignore individual failures so one dead source
        // doesn't block the others.
        val igareckUrl = if (preferences.igareckFullSubscription.first())
            SubscriptionUrls.IGARECK_BLACK_FULL
        else
            SubscriptionUrls.IGARECK_BLACK_MOBILE

        val urls = listOf(
            SubscriptionUrls.VLESS_LITE,
            SubscriptionUrls.VLESS_LITE_MIRROR,
            igareckUrl,
        )
        // null = fetch failed; non-null (even empty) = source responded
        val fetched: List<List<ServerProfile>?> = coroutineScope {
            urls.map { url ->
                async {
                    runCatching { apiService.fetchRawFile(url) }
                        .getOrNull()
                        ?.let { text ->
                            VlessUriParser.parseSubscription(text)
                                .map { (config, uri) -> VlessUriParser.toServerProfile(config, uri) }
                        }
                }
            }.map { it.await() }
        }
        // If ALL sources failed → no network → throw so Worker retries with backoff
        // instead of silently reporting "0 servers updated" and waiting a full hour.
        if (fetched.all { it == null }) throw IOException("All sources unreachable")
        val newProfiles = fetched.filterNotNull().flatten()
            .filter { it.country !in BLOCKED_COUNTRIES }
        // De-duplicate by ID (same server may appear in multiple sources)
        val deduplicated = newProfiles.distinctBy { it.id }
        if (deduplicated.isNotEmpty()) {
            // Preserve learned data (ping latency, score counters, current selection)
            // for servers that already exist in the DB. New servers start with defaults.
            val existing = serverDao.getAllServers().first().associateBy { it.id }
            val merged = deduplicated.map { fresh ->
                val old = existing[fresh.id]
                if (old != null) fresh.copy(
                    latencyMs    = old.latencyMs,
                    successCount = old.successCount,
                    failCount    = old.failCount,
                    isSelected   = old.isSelected,
                    speedMbps    = old.speedMbps,
                ) else fresh
            }
            serverDao.insertAll(merged)                    // upsert (REPLACE strategy)
            serverDao.deleteNotIn(merged.map { it.id })   // remove stale servers
        }
        deduplicated.size
    }

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
     * Selects the best server using a composite score (see ServerProfile.score()).
     * Only considers servers that responded to ping (latencyMs > 0).
     * [excludeIds] lets the caller skip servers already tried in this failover round.
     */
    suspend fun selectBestServer(
        excludeIds: Set<String> = emptySet(),
        excludePorts: Set<Int> = emptySet(),
    ): ServerProfile? {
        val tested = serverDao.getAllServers().first()
            .filter { it.latencyMs > 0 && it.id !in excludeIds }
        // Prefer servers on non-blocked ports; fall back to any port if none left
        return tested.filter { it.port !in excludePorts }.minByOrNull { it.score() }
            ?: tested.minByOrNull { it.score() }
    }
}
