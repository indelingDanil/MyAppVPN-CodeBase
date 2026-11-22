package com.myappvpn.data.repository

import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.model.ServerProfile
import com.myappvpn.data.parser.VlessUriParser
import com.myappvpn.data.remote.SubscriptionFetcher
import com.myappvpn.data.remote.SubscriptionUrls
import com.myappvpn.service.MyVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.net.URI
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepository @Inject constructor(
    private val fetcher: SubscriptionFetcher,
    private val serverDao: ServerDao,
    private val preferences: AppPreferences,
    private val subscriptionRepository: SubscriptionRepository
) {
    companion object {
        private val BLOCKED_COUNTRIES = setOf("Ukraine")
        /** Max Hysteria2 servers guaranteed a slot in the Auto pool (see [getBalancerPool]). */
        private const val HY2_POOL_RESERVE = 10
        /** Always-current mirror host; preferred over CDN mirrors that may serve stale copies. */
        private const val FRESH_HOST = "raw.githubusercontent.com"
        private const val FRESH_GRACE_MS = 5_000L
    }

    /** Serializes refreshes (worker / pull-to-refresh / in-tunnel refresh) — one at a time. */
    private val refreshMutex = Mutex()

    val servers: Flow<List<ServerProfile>> = serverDao.getAllServers()

    /** The pinned (manual-mode) server. Single source of truth for the pin: Room `isSelected`. */
    val pinnedServer: Flow<ServerProfile?> = serverDao.observeSelectedServer().distinctUntilChanged()

    suspend fun fetchAndSaveServers(): Result<Int> = refreshMutex.withLock { fetchAndSaveServersLocked() }

    private suspend fun fetchAndSaveServersLocked(): Result<Int> = runCatching {
        // Only enabled sources (built-in + custom) are fetched. Each source is a fallback chain
        // (identical mirrors) — take the first that works. Chains are fetched in parallel & merged.
        val chains = subscriptionRepository.getEnabledChains()
        // No enabled sources → do NOT prune the existing pool (don't brick the app when the user
        // toggles everything off). Just report 0; the current servers stay usable.
        if (chains.isEmpty()) return@runCatching 0

        val fetched: List<String?> = coroutineScope {
            chains.map { chain ->
                async { fetchFirstWorking(chain) }
            }.map { it.await() }
        }
        // Silent fallback: MyAppVPN enabled but none of its mirrors answered (whitelist-only
        // mobile networks block jsDelivr/githack/GitHub) → take the zieng2 list instead, whose RU
        // mirrors usually stay reachable. Never used while the MyAppVPN list itself works.
        val myAppVpnFailed = chains.indices.any { chains[it] == SubscriptionUrls.MYAPPVPN_CHAIN && fetched[it] == null }
        val texts = if (myAppVpnFailed) fetched + fetchFirstWorking(SubscriptionUrls.FALLBACK_CHAIN) else fetched
        // If ALL sources failed → no network → throw so Worker retries with backoff
        // instead of silently reporting "0 servers updated" and waiting a full hour.
        if (texts.all { it == null }) throw IOException("All subscription sources unreachable")

        // CPU-heavy parse + profile building off the caller's (often Main) thread.
        val deduplicated = withContext(Dispatchers.Default) {
            val newProfiles = texts.filterNotNull()
                .flatMap { text ->
                    VlessUriParser.parseSubscription(text)
                        .map { (config, uri) -> VlessUriParser.toServerProfile(config, uri) }
                }
                .filter { it.country !in BLOCKED_COUNTRIES }
            // De-duplicate by ID (same server may appear in multiple sources/mirrors)
            newProfiles.distinctBy { it.id }
        }
        if (deduplicated.isNotEmpty()) {
            // One transaction: existing rows get ONLY their static columns updated (learned
            // stats / pin / validation are never rewritten, so concurrent markVerified /
            // markFailed / updateLatency / selectServer writes can't be rolled back), new rows
            // are inserted (carrying stats over from a legacy-id row of the same server), and
            // rows no longer in any enabled source are deleted in chunks.
            serverDao.applyRefresh(deduplicated)
        }
        deduplicated.size
    }.onFailure { if (it is CancellationException) throw it }   // never swallow cancellation

    /**
     * RACE all mirrors of a chain in parallel (≤10s each); return the FIRST that responds with
     * vless:// links. Critical for RU mobile: blocked mirrors (github/codeberg/gitlab) just time
     * out in the background while a working experimental mirror (Mos.Hub / GitVerse) wins fast —
     * instead of sequentially waiting ~30s on each blocked one before reaching the good mirror.
     */
    private suspend fun fetchFirstWorking(chain: List<String>): String? = coroutineScope {
        val attempts = chain.map { url ->
            async {
                val text = withTimeoutOrNull(12_000) {
                    runCatching { fetchRaw(url) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                }
                // Validate AFTER decoding (base64 subscriptions have no literal "vless://" /
                // "hysteria2://"):
                // accept only if it yields ≥1 server. Parsing is CPU work → Default dispatcher.
                text?.takeIf {
                    withContext(Dispatchers.Default) { VlessUriParser.parseSubscription(it).isNotEmpty() }
                }
            }
        }
        // CDN mirrors (jsDelivr, githack) can keep serving a stale copy for hours even after a
        // purge — the server count "jumped" 301 ↔ 460 depending on which mirror answered first.
        // raw.githubusercontent.com is always current, so if the chain has it and it answers
        // within a short grace period, it wins; otherwise the race goes on as before.
        val freshIdx = chain.indexOfFirst { runCatching { URI(it).host }.getOrNull() == FRESH_HOST }
        val fresh = if (freshIdx >= 0) withTimeoutOrNull(FRESH_GRACE_MS) { attempts[freshIdx].await() } else null
        val result = fresh ?: run {
            val winner = CompletableDeferred<String?>()
            attempts.forEach { d -> launch { d.await()?.let { winner.complete(it) } } }
            launch { attempts.awaitAll(); winner.complete(null) }   // all failed → null
            winner.await()
        }
        attempts.forEach { it.cancel() }                            // stop the losers
        result
    }

    /**
     * Fetch a raw subscription file. When the VPN is up, route the request THROUGH the tunnel
     * (SOCKS 127.0.0.1:port) so blocked mirrors (github/gitlab/codeberg) become reachable via the
     * working proxy — the app is excluded from its own TUN, so a direct fetch would otherwise go
     * over the restricted operator network and only reach a couple of experimental mirrors.
     */
    private suspend fun fetchRaw(url: String): String {
        // OkHttp (cancellable, 8 MB cap, SSRF-checked redirects); SOCKS via 127.0.0.1 when the
        // tunnel is up — the service's global java.net.Authenticator supplies the SOCKS creds.
        return fetcher.fetch(url, MyVpnService.activeSocksPort)
    }

    /**
     * Servers eligible for the balancer pool / manual list: drops blocked countries
     * and any user-excluded countries (e.g. "Russia" by default). Ordered by latency
     * (DB already sorts by latencyMs) so callers can take the top N.
     */
    suspend fun getServersExcludingCountries(excluded: Set<String>): List<ServerProfile> =
        serverDao.getAllServers().first()
            .filter { it.country !in BLOCKED_COUNTRIES && it.country !in excluded }

    /**
     * Auto-balancer pool: the top [limit] of [getServersExcludingCountries] (latency order), but
     * with up to [HY2_POOL_RESERVE] Hysteria2 servers guaranteed a slot. Hysteria2 is never TCP-
     * pinged (QUIC/UDP), so it has no latency and would always sort behind every pinged VLESS
     * server and never reach the pool; the in-core observatory probes it for real once it's in.
     * Recently verified ones go first, the rest are appended (dead ones cost only probe traffic).
     */
    suspend fun getBalancerPool(excluded: Set<String>, limit: Int): List<ServerProfile> {
        val all = getServersExcludingCountries(excluded)
        val hy2 = all.filter { it.isHysteria2 && it.consecutiveFailures < 3 }
            .sortedBy { it.score() }
            .take(HY2_POOL_RESERVE)
        if (hy2.isEmpty()) return all.take(limit)
        val hy2Ids = hy2.mapTo(HashSet()) { it.id }
        val recent = System.currentTimeMillis() - 60 * 60_000L
        val (hy2Front, hy2Back) = hy2.partition { it.verifiedAt > recent }
        val rest = all.filter { it.id !in hy2Ids }.take((limit - hy2.size).coerceAtLeast(0))
        return (hy2Front + rest + hy2Back).take(limit)
    }

    suspend fun getSelectedServer(): ServerProfile? = serverDao.getSelectedServer()

    suspend fun getServerById(id: String): ServerProfile? = serverDao.getServerById(id)

    /** Atomic: one UPDATE statement, so observers never see a half-applied pin. */
    suspend fun selectServer(serverId: String) {
        serverDao.selectOnly(serverId)
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

    /** Clears a server's verified-working stamp so it leaves the 24h working pool. */
    suspend fun markFailed(serverId: String) {
        serverDao.markFailed(serverId)
    }

    suspend fun incrementConsecutiveFailure(serverId: String) {
        serverDao.incrementConsecutiveFailure(serverId)
    }

    suspend fun resetConsecutiveFailures(serverId: String) {
        serverDao.resetConsecutiveFailures(serverId)
    }
}
