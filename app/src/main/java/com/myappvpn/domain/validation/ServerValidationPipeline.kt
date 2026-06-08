package com.myappvpn.domain.validation

import android.util.Log
import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

sealed class ValidationProgress {
    data class Stage1Progress(val fraction: Float, val reachableCount: Int) : ValidationProgress()
    data class Stage2Progress(val fraction: Float, val handshakeOkCount: Int) : ValidationProgress()
    data class Stage3Progress(val fraction: Float, val verifiedCount: Int) : ValidationProgress()
    data class Complete(val verifiedServerIds: List<String>, val bestServerId: String?) : ValidationProgress()
    data class Error(val message: String) : ValidationProgress()
}

class ServerValidationPipeline @Inject constructor(
    private val tcpProbe: TcpReachabilityProbe,
    private val handshakeProbe: HandshakeProbe,
    private val xrayMicroTest: XrayMicroTest,
    private val serverDao: ServerDao
) {
    companion object {
        private const val TAG = "ValidationPipeline"
        const val STAGE2_CANDIDATE_COUNT = 50  // best by TCP latency
        const val STAGE3_CANDIDATE_COUNT = 10
        const val TARGET_VERIFIED_COUNT = 3
        const val VERIFIED_CACHE_DURATION_MS = 15 * 60 * 1000L // 15 minutes
    }

    /**
     * Runs the full 3-stage validation pipeline.
     * Emits progress for each stage.
     * Returns verified server IDs once done.
     */
    fun validate(servers: List<ServerProfile>): Flow<ValidationProgress> = channelFlow {
        if (servers.isEmpty()) {
            send(ValidationProgress.Error("No servers to validate"))
            return@channelFlow
        }

        // Cache check: if we already have enough recently verified servers, skip pipeline
        val recentlyVerified = serverDao.getRecentlyVerifiedServers(
            since = System.currentTimeMillis() - VERIFIED_CACHE_DURATION_MS
        )
        if (recentlyVerified.size >= TARGET_VERIFIED_COUNT) {
            Log.i(TAG, "Cache hit: ${recentlyVerified.size} recently verified servers")
            val ids = recentlyVerified.map { it.id }
            val best = recentlyVerified.minByOrNull { it.score() }?.id
            send(ValidationProgress.Complete(ids, best))
            return@channelFlow
        }

        // ── Stage 1: TCP Reachability ──
        Log.i(TAG, "Stage 1: TCP pinging ${servers.size} servers (3x each)")
        var reachableCount = 0
        val tcpResults = tcpProbe.probeAll(servers) { completed, total, result ->
            if (result.medianLatencyMs > 0) reachableCount++
            send(ValidationProgress.Stage1Progress(completed.toFloat() / total, reachableCount))
        }

        val reachable = tcpResults.filter { it.medianLatencyMs > 0 }
        Log.i(TAG, "Stage 1 done: ${reachable.size}/${servers.size} reachable")

        if (reachable.isEmpty()) {
            send(ValidationProgress.Error("No servers reachable"))
            return@channelFlow
        }

        // ── Stage 2: TLS/Reality Handshake ──
        val stage2Candidates = reachable.take(STAGE2_CANDIDATE_COUNT)
        val serverMap = servers.associateBy { it.id }
        Log.i(TAG, "Stage 2: Handshake testing ${stage2Candidates.size} servers")

        var handshakeOkCount = 0
        val handshakeResults = coroutineScope {
            val total = stage2Candidates.size
            stage2Candidates.mapIndexed { index, tcpResult ->
                async(Dispatchers.IO) {
                    val server = serverMap[tcpResult.serverId] ?: return@async null
                    val result = handshakeProbe.probe(server)
                    serverDao.updateHandshakeResult(server.id, result.success)
                    if (result.success) handshakeOkCount++
                    send(ValidationProgress.Stage2Progress((index + 1).toFloat() / total, handshakeOkCount))
                    result
                }
            }.awaitAll().filterNotNull()
        }

        val handshakeOk = handshakeResults.filter { it.success }
        Log.i(TAG, "Stage 2 done: ${handshakeOk.size}/${stage2Candidates.size} handshake OK")

        if (handshakeOk.isEmpty()) {
            // Fallback: use reachable servers even without handshake (some may be security=none)
            Log.w(TAG, "No handshake passed — falling back to reachable servers for Stage 3")
        }

        // ── Stage 3: XRay Micro-Connection Test ──
        // Prefer handshake-ok servers, fall back to reachable
        val stage3CandidateIds = if (handshakeOk.isNotEmpty()) {
            handshakeOk.map { it.serverId }
        } else {
            reachable.map { it.serverId }
        }.take(STAGE3_CANDIDATE_COUNT)

        val stage3Servers = stage3CandidateIds.mapNotNull { serverMap[it] }
        Log.i(TAG, "Stage 3: XRay micro-testing ${stage3Servers.size} servers")

        val microResults = xrayMicroTest.testBatch(stage3Servers, TARGET_VERIFIED_COUNT)

        val now = System.currentTimeMillis()
        val verifiedIds = mutableListOf<String>()
        for (result in microResults) {
            send(ValidationProgress.Stage3Progress(
                (microResults.indexOf(result) + 1).toFloat() / stage3Servers.size,
                verifiedIds.size + if (result.success) 1 else 0
            ))
            if (result.success) {
                serverDao.markVerified(result.serverId, now)
                verifiedIds.add(result.serverId)
            }
        }

        Log.i(TAG, "Stage 3 done: ${verifiedIds.size}/${stage3Servers.size} verified")

        // Combine with any previously verified servers still valid
        val allVerified = (verifiedIds + recentlyVerified.map { it.id }).distinct()

        val bestId = if (allVerified.isNotEmpty()) {
            // Best among verified servers
            allVerified
                .mapNotNull { id -> serverMap[id] ?: serverDao.getServerById(id) }
                .minByOrNull { it.score() }?.id
        } else {
            // Fallback: no verified servers — pick best from handshake OK or reachable
            val fallbackPool = if (handshakeOk.isNotEmpty()) {
                handshakeOk.map { it.serverId }
            } else {
                reachable.map { it.serverId }
            }
            Log.w(TAG, "No verified servers — falling back to ${fallbackPool.size} candidates")
            fallbackPool
                .mapNotNull { id -> serverMap[id] }
                .minByOrNull { it.score() }?.id
        }

        send(ValidationProgress.Complete(allVerified, bestId))
    }
}
