package com.myappvpn.service

import android.content.Context
import android.net.TrafficStats
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

    private var monitorJob: Job? = null
    private var sessionStartMs = 0L

    // Baseline totals captured when VPN starts
    private var baselineTx = 0L
    private var baselineRx = 0L

    // Previous tick values for per-second speed calculation
    private var prevSessionTx = 0L
    private var prevSessionRx = 0L
    private var prevTimestamp = 0L

    fun start() {
        sessionStartMs = System.currentTimeMillis()
        baselineTx = totalTxBytes()
        baselineRx = totalRxBytes()
        prevSessionTx = 0L
        prevSessionRx = 0L
        prevTimestamp = 0L
        _statsFlow.value = TrafficStatsModel.Empty

        monitorJob = CoroutineScope(Dispatchers.IO).launch {
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

        val elapsed = if (prevTimestamp > 0) (now - prevTimestamp) / 1000.0 else 1.0
        val uploadSpeed = if (prevTimestamp > 0) ((sessionTx - prevSessionTx) / elapsed).toLong() else 0L
        val downloadSpeed = if (prevTimestamp > 0) ((sessionRx - prevSessionRx) / elapsed).toLong() else 0L

        prevSessionTx = sessionTx
        prevSessionRx = sessionRx
        prevTimestamp = now

        _statsFlow.value = TrafficStatsModel(
            uploadBytes = sessionTx,
            downloadBytes = sessionRx,
            uploadSpeed = uploadSpeed.coerceAtLeast(0L),
            downloadSpeed = downloadSpeed.coerceAtLeast(0L),
            sessionDurationMs = now - sessionStartMs,
        )
    }

    private fun totalTxBytes(): Long {
        val v = TrafficStats.getTotalTxBytes()
        return if (v == TrafficStats.UNSUPPORTED.toLong()) 0L else v
    }

    private fun totalRxBytes(): Long {
        val v = TrafficStats.getTotalRxBytes()
        return if (v == TrafficStats.UNSUPPORTED.toLong()) 0L else v
    }
}
