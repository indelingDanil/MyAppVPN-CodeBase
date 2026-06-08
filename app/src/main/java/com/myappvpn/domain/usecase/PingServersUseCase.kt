package com.myappvpn.domain.usecase

import android.util.Log
import com.myappvpn.data.db.ServerDao
import com.myappvpn.domain.validation.ServerValidationPipeline
import com.myappvpn.domain.validation.ValidationProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PingServersUseCase @Inject constructor(
    private val pipeline: ServerValidationPipeline,
    private val serverDao: ServerDao
) {
    companion object {
        private const val TAG = "PingServersUseCase"
    }

    /**
     * Runs the full 3-stage validation pipeline (TCP ping → TLS handshake → XRay micro-test).
     * Progress is mapped: Stage1=0-40%, Stage2=40-70%, Stage3=70-100%.
     */
    fun invoke(): Flow<PingProgress> = channelFlow {
        val allServers = serverDao.getAllServers().first()
        if (allServers.isEmpty()) {
            send(PingProgress.Done(null))
            return@channelFlow
        }

        // Pre-filter: skip servers known to be dead — saves minutes of TCP probing
        val now = System.currentTimeMillis()
        val servers = allServers.filter { server ->
            // Skip STALE servers with 3+ consecutive failures
            if (server.validationState == "STALE" && server.consecutiveFailures >= 3) return@filter false
            // Skip servers that failed TCP ping recently (<1 hour ago)
            if (server.medianLatencyMs == 0 && server.lastValidatedAt > 0 &&
                (now - server.lastValidatedAt) < 60 * 60_000L) return@filter false
            true
        }
        Log.i(TAG, "Pre-filter: ${allServers.size} → ${servers.size} candidates")

        send(PingProgress.Started(servers.size))

        pipeline.validate(servers).collect { progress ->
            when (progress) {
                is ValidationProgress.Stage1Progress ->
                    send(PingProgress.StageProgress(
                        fraction = progress.fraction * 0.4f,
                        stage = 1,
                        stageDetail = "Checking reachability... (${progress.reachableCount} found)"
                    ))
                is ValidationProgress.Stage2Progress ->
                    send(PingProgress.StageProgress(
                        fraction = 0.4f + progress.fraction * 0.3f,
                        stage = 2,
                        stageDetail = "Testing handshakes... (${progress.handshakeOkCount} OK)"
                    ))
                is ValidationProgress.Stage3Progress ->
                    send(PingProgress.StageProgress(
                        fraction = 0.7f + progress.fraction * 0.3f,
                        stage = 3,
                        stageDetail = "Verifying servers... (${progress.verifiedCount} verified)"
                    ))
                is ValidationProgress.Complete ->
                    send(PingProgress.Done(progress.bestServerId))
                is ValidationProgress.Error ->
                    send(PingProgress.Done(null))
            }
        }
    }
}

sealed class PingProgress {
    data class Started(val total: Int) : PingProgress()
    /** Legacy: kept for backward compat with existing UI collectors. */
    data class Progress(val fraction: Float, val serverId: String, val latencyMs: Int) : PingProgress()
    /** v2.0: multi-stage progress with description. */
    data class StageProgress(val fraction: Float, val stage: Int, val stageDetail: String) : PingProgress()
    data class Done(val bestServerId: String?) : PingProgress()
}
