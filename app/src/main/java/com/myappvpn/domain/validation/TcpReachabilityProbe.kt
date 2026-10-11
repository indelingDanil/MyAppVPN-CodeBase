package com.myappvpn.domain.validation

import com.myappvpn.data.db.ServerDao
import com.myappvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.resume

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
        const val DNS_TIMEOUT_MS = 3000L

        /** Daemon threads, idle ones die after 30s; unbounded so a stuck lookup never queues others. */
        private val dnsExecutor: ExecutorService = ThreadPoolExecutor(
            0, Int.MAX_VALUE, 30L, TimeUnit.SECONDS, SynchronousQueue()
        ) { r -> Thread(r, "tcp-probe-dns").apply { isDaemon = true } }
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
                    // Resolve ONCE, bounded, before timing: latency excludes DNS and a slow
                    // resolver can't hold the permit for longer than DNS_TIMEOUT_MS.
                    val addr = resolve(server.address)
                    val samples = if (addr == null) List(PING_COUNT) { 0 } else (1..PING_COUNT).map {
                        tcpPing(addr, server.port)
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

    /**
     * DNS lookup on a separate pool, abandoned after [DNS_TIMEOUT_MS] (getaddrinfo itself is not
     * interruptible, so the lookup thread may linger, but the caller — and its permit — moves on).
     */
    private suspend fun resolve(host: String): InetAddress? = withTimeoutOrNull(DNS_TIMEOUT_MS) {
        suspendCancellableCoroutine<InetAddress?> { cont ->
            val future = dnsExecutor.submit(Runnable {
                val addr = try {
                    InetAddress.getByName(host)
                } catch (_: Exception) {
                    null
                }
                cont.resume(addr)          // ignored if the wait was already cancelled
            })
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    private fun tcpPing(addr: InetAddress, port: Int): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(addr, port), PING_TIMEOUT_MS)
            }
            (System.currentTimeMillis() - start).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            0
        }
    }
}
