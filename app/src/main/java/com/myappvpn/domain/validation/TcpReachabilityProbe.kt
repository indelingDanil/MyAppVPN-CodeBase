package com.myappvpn.domain.validation

import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class TcpResult(
    val serverId: String,
    val medianLatencyMs: Int,  // 0 = all pings failed
    val samples: List<Int>,
)

class TcpReachabilityProbe @Inject constructor(
    private val serverDao: ServerDao
) {
    companion object {
        const val PING_COUNT = 3
        const val PING_TIMEOUT_MS = 3000
        const val PARALLEL_LIMIT = 30
    }

    /**
     * Pings all servers 3 times each, stores median latency.
     * Returns sorted list by median latency. Servers NEVER deleted.
     */
    suspend fun probeAll(
        servers: List<ServerProfile>,
        onProgress: suspend (completed: Int, total: Int, result: TcpResult) -> Unit = { _, _, _ -> }
    ): List<TcpResult> = coroutineScope {
        val semaphore = Semaphore(PARALLEL_LIMIT)
        val total = servers.size
        val results = java.util.concurrent.ConcurrentHashMap<String, TcpResult>()
        val completed = java.util.concurrent.atomic.AtomicInteger(0)

        servers.map { server ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val samples = (1..PING_COUNT).map {
                        tcpPing(server.address, server.port)
                    }
                    val successful = samples.filter { it > 0 }.sorted()
                    val median = if (successful.isNotEmpty()) {
                        successful[successful.size / 2]
                    } else 0

                    val result = TcpResult(server.id, median, samples)
                    results[server.id] = result

                    // Update DB: store median, keep latencyMs in sync for UI
                    serverDao.updatePingResult(server.id, median, PING_COUNT)

                    val count = completed.incrementAndGet()
                    onProgress(count, total, result)
                }
            }
        }.awaitAll()

        results.values.sortedBy { it.medianLatencyMs.let { ms -> if (ms == 0) Int.MAX_VALUE else ms } }
    }

    private fun tcpPing(host: String, port: Int): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PING_TIMEOUT_MS)
            }
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            0
        }
    }
}
