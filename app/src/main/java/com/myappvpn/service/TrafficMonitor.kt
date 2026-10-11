package com.myappvpn.service

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.myappvpn.data.model.TrafficStats as TrafficStatsModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures VPN session traffic using android.net.TrafficStats API.
 * Works on all Android versions including 10+ (unlike /proc/net/dev which is SELinux-blocked).
 * Measures delta from baseline at VPN start so values represent session-only traffic.
 */
@Singleton
class TrafficMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _statsFlow = MutableStateFlow(TrafficStatsModel.Empty)
    val statsFlow: StateFlow<TrafficStatsModel> = _statsFlow.asStateFlow()

    // Single long-lived scope so repeated start()/stop() never leak competing monitors.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var sessionStartMs = 0L

    // Baseline totals captured when VPN starts
    private var baselineTx = 0L
    private var baselineRx = 0L

    // Previous tick values for per-second speed calculation
    private var prevSessionTx = 0L
    private var prevSessionRx = 0L
    private var prevTimestamp = 0L

    // EMA smoothing to prevent 0 B/s flickering on bursty VPN traffic
    private var smoothedUpload = 0.0
    private var smoothedDownload = 0.0
    private companion object {
        const val EMA_ALPHA = 0.3 // 0.3 = responsive but smooth (lower = smoother)
    }

    fun start() {
        // Cancel any previous monitor first so we never run two concurrently
        // (repeated CONNECT intents / reconnects would otherwise stack competing
        // monitors that fight over statsFlow and make UP/DOWN jump or freeze).
        monitorJob?.cancel()

        sessionStartMs = System.currentTimeMillis()
        baselineTx = totalTxBytes()
        baselineRx = totalRxBytes()
        prevSessionTx = 0L
        prevSessionRx = 0L
        prevTimestamp = 0L
        smoothedUpload = 0.0
        smoothedDownload = 0.0
        _statsFlow.value = TrafficStatsModel.Empty

        monitorJob = scope.launch {
            while (isActive) {
                updateStats()
                delay(1000L)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _statsFlow.value = TrafficStatsModel.Empty
    }

    private fun updateStats() {
        val sessionTx = (totalTxBytes() - baselineTx).coerceAtLeast(0L)
        val sessionRx = (totalRxBytes() - baselineRx).coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        if (prevTimestamp > 0) {
            val elapsed = (now - prevTimestamp) / 1000.0
            val rawUpload = ((sessionTx - prevSessionTx) / elapsed)
            val rawDownload = ((sessionRx - prevSessionRx) / elapsed)

            // EMA smoothing: prevents 0 B/s flicker on bursty VPN traffic
            smoothedUpload = EMA_ALPHA * rawUpload + (1 - EMA_ALPHA) * smoothedUpload
            smoothedDownload = EMA_ALPHA * rawDownload + (1 - EMA_ALPHA) * smoothedDownload
        }

        prevSessionTx = sessionTx
        prevSessionRx = sessionRx
        prevTimestamp = now

        _statsFlow.value = TrafficStatsModel(
            uploadBytes = sessionTx,
            downloadBytes = sessionRx,
            uploadSpeed = smoothedUpload.toLong().coerceAtLeast(0L),
            downloadSpeed = smoothedDownload.toLong().coerceAtLeast(0L),
            sessionDurationMs = now - sessionStartMs,
        )
    }

    // Our UID only: Xray and tun2socks run as child processes under the app UID and carry the
    // tunneled traffic exactly once on the upstream side. Device totals (getTotal*) counted both
    // the TUN and the physical interface (~2x) plus traffic of apps excluded from the tunnel.
    private val myUid = Process.myUid()

    private fun totalTxBytes(): Long {
        val v = TrafficStats.getUidTxBytes(myUid)
        return if (v == TrafficStats.UNSUPPORTED.toLong() || v < 0) 0L else v
    }

    private fun totalRxBytes(): Long {
        val v = TrafficStats.getUidRxBytes(myUid)
        return if (v == TrafficStats.UNSUPPORTED.toLong() || v < 0) 0L else v
    }
}
