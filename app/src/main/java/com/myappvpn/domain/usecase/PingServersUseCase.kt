package com.myappvpn.domain.usecase

import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.repository.ConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class PingServersUseCase @Inject constructor(
    private val configRepository: ConfigRepository,
    private val serverDao: ServerDao
) {
    companion object {
        /** Servers slower than this (ms) are deleted after ping sweep. */
        const val MAX_ACCEPTABLE_LATENCY = 50
    }
    /**
     * Pings all servers in parallel and emits real-time progress as each result arrives.
     * Uses channelFlow so concurrent coroutines can emit to the flow simultaneously.
     */
    fun invoke(): Flow<PingProgress> = channelFlow {
        val servers = serverDao.getAllServers().first()
        if (servers.isEmpty()) {
            send(PingProgress.Done(null))
            return@channelFlow
        }

        val total = servers.size
        send(PingProgress.Started(total))

        val completed = AtomicInteger(0)
        // thread-safe map: serverId → latencyMs
        val results = ConcurrentHashMap<String, Int>()

        // Ping all servers in parallel — each async emits progress immediately on completion
        servers.map { server ->
            async(Dispatchers.IO) {
                val latency = tcpPing(server.address, server.port)
                serverDao.updateLatency(server.id, latency)
                results[server.id] = latency
                val count = completed.incrementAndGet()
                send(PingProgress.Progress(count.toFloat() / total, server.id, latency))
            }
        }.awaitAll()

        // Remove servers with ping > 80ms or timeout — keep only fast ones
        serverDao.deleteSlowServers(MAX_ACCEPTABLE_LATENCY)

        val best = results.entries
            .filter { it.value in 1..MAX_ACCEPTABLE_LATENCY }
            .minByOrNull { it.value }
        send(PingProgress.Done(best?.key))
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            0 // timeout / unreachable
        }
    }
}

sealed class PingProgress {
    data class Started(val total: Int) : PingProgress()
    data class Progress(val fraction: Float, val serverId: String, val latencyMs: Int) : PingProgress()
    data class Done(val bestServerId: String?) : PingProgress()
}
