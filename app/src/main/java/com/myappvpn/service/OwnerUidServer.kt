package com.myappvpn.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Answers tun2socks-owner's "who owns this flow?" queries (GitHub issue #8).
 *
 * An app OUTSIDE the VPN can bind its socket to tun0 (SO_BINDTODEVICE); its packets then enter
 * the TUN and would be proxied, revealing the exit IP. tun2socks (started with
 * `-owner-socket <name>`) asks, for every new TCP connection / UDP session:
 *
 *     request:  "tcp|udp <srcIP>:<srcPort> <dstIP>:<dstPort>\n"   (IPv6 in brackets)
 *     response: "<uid>\n"  -1 → drop the flow, any other number → allow
 *               "-2\n"     API < 29, no lookup possible → allow (if [allowOnUnsupported])
 *               "err\n"    lookup failed → tun2socks allows unless started with -owner-strict
 *
 * The lookup is [ConnectivityManager.getConnectionOwnerUid] (API 29+, callable by the active
 * VpnService app). It returns [Process.INVALID_UID] for a socket whose UID isn't covered by our
 * VPN — exactly the "bound to tun0 from outside" case — in both INCLUDE and EXCLUDE mode.
 *
 * The socket lives in the abstract namespace under a random per-session name ([name]); only
 * peers running as our own UID (tun2socks is our child process) are served.
 *
 * @param allowedAppIds INCLUDE mode, optional defence in depth: app IDs ([appId], uid % 100000)
 *   of the packages routed through the VPN; an owner outside this set is answered -1 even if the
 *   system said otherwise. null = no extra restriction. See [appIdsForPackages].
 * @param allowOnUnsupported answer for API < 29: true → "-2" (allow), false → "-1" (drop all).
 */
class OwnerUidServer(
    context: Context,
    private val allowedAppIds: Set<Int>? = null,
    private val allowOnUnsupported: Boolean = true,
) {
    companion object {
        private const val TAG = "OwnerUidServer"
        private const val MAX_CLIENTS = 32          // tun2socks-owner opens ≤ 16
        private const val MAX_LINE = 256
        const val ANSWER_DENY = "-1"
        const val ANSWER_UNKNOWN_ALLOW = "-2"
        const val ANSWER_ERROR = "err"

        /** App IDs (per-user-independent UIDs) of [packages]; missing packages are skipped. */
        fun appIdsForPackages(pm: PackageManager, packages: Collection<String>): Set<Int> =
            packages.mapNotNullTo(HashSet()) { pkg ->
                try {
                    appId(pm.getPackageUid(pkg, 0))
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }

        /** UserHandle.PER_USER_RANGE: uid = userId * 100000 + appId. */
        fun appId(uid: Int): Int = uid % 100_000

        private fun randomName(): String {
            val b = ByteArray(12)
            SecureRandom().nextBytes(b)
            return "myappvpn_owner_" + b.joinToString("") { "%02x".format(it) }
        }
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Abstract socket name to pass as `-owner-socket <name>`. */
    val name: String = randomName()

    @Volatile private var running = false
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = Collections.synchronizedSet(HashSet<LocalSocket>())
    private val activeClients = AtomicInteger()

    // Diagnostics.
    val queries = AtomicLong()
    val denied = AtomicLong()
    val errors = AtomicLong()
    @Volatile var lastDenied: String? = null
        private set

    /** Binds the socket and starts serving. Returns false if the socket can't be created. */
    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val ss = try {
            LocalServerSocket(name)   // abstract namespace
        } catch (e: Exception) {
            Log.e(TAG, "bind @$name failed: ${e.message}")
            return false
        }
        server = ss
        running = true
        acceptThread = Thread({ acceptLoop(ss) }, "OwnerUidServer").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "listening on @$name (api=${Build.VERSION.SDK_INT}, " +
            "allowedAppIds=${allowedAppIds?.size ?: "any"})")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        // LocalServerSocket.close() doesn't reliably unblock accept() on an abstract socket:
        // poke it with a throw-away connection first.
        try {
            LocalSocket().use { it.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)) }
        } catch (_: Exception) {
        }
        try { server?.close() } catch (_: Exception) {}
        server = null
        synchronized(clients) {
            clients.forEach { try { it.close() } catch (_: Exception) {} }
            clients.clear()
        }
        acceptThread = null
        Log.i(TAG, "stopped: queries=${queries.get()} denied=${denied.get()} errors=${errors.get()}")
    }

    private fun acceptLoop(ss: LocalServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                break
            }
            if (!running) { closeQuietly(s); break }
            val peerUid = try { s.peerCredentials.uid } catch (_: Exception) { -1 }
            if (peerUid != Process.myUid()) {
                Log.w(TAG, "rejected peer uid=$peerUid")
                closeQuietly(s)
                continue
            }
            if (activeClients.incrementAndGet() > MAX_CLIENTS) {
                activeClients.decrementAndGet()
                closeQuietly(s)
                continue
            }
            clients.add(s)
            Thread({ serve(s) }, "OwnerUidClient").apply { isDaemon = true; start() }
        }
    }

    private fun serve(s: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.US_ASCII), MAX_LINE)
            val out = s.outputStream
            while (running) {
                val line = reader.readLine() ?: break
                val resp = if (line.length > MAX_LINE) ANSWER_ERROR else answer(line)
                out.write((resp + "\n").toByteArray(Charsets.US_ASCII))
                out.flush()
            }
        } catch (_: Exception) {
            // peer closed / server stopping
        } finally {
            clients.remove(s)
            activeClients.decrementAndGet()
            closeQuietly(s)
        }
    }

    /** Visible for tests: computes the answer line (without '\n') for one request line. */
    internal fun answer(line: String): String {
        queries.incrementAndGet()
        val parts = line.trim().split(' ')
        if (parts.size != 3) return error("bad request: $line")
        val protocol = when (parts[0]) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return error("bad protocol: $line")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return if (allowOnUnsupported) ANSWER_UNKNOWN_ALLOW else deny(line, "api<29")
        }
        val uid = try {
            lookup(protocol, parts[1], parts[2]) ?: return error("bad address: $line")
        } catch (e: Exception) {
            // SecurityException: we're not (yet / anymore) the active VPN.
            return error("lookup failed for '$line': ${e.javaClass.simpleName}: ${e.message}")
        }
        if (uid == Process.INVALID_UID) return deny(line, "owner outside VPN")
        val allowed = allowedAppIds
        if (allowed != null && appId(uid) !in allowed) {
            return deny(line, "uid=$uid not in the include list")
        }
        return uid.toString()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun lookup(protocol: Int, src: String, dst: String): Int? {
        val local = parseAddr(src) ?: return null
        val remote = parseAddr(dst) ?: return null
        return cm.getConnectionOwnerUid(protocol, local, remote)
    }

    /** "1.2.3.4:80" / "[2001:db8::1]:443" → numeric address only, never DNS. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun parseAddr(s: String): InetSocketAddress? {
        val colon = s.lastIndexOf(':')
        if (colon <= 0) return null
        val port = s.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
        val host = s.substring(0, colon).removePrefix("[").removeSuffix("]")
        return try {
            InetSocketAddress(InetAddresses.parseNumericAddress(host), port)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun deny(line: String, why: String): String {
        val n = denied.incrementAndGet()
        lastDenied = "$line ($why)"
        if (n <= 20 || n % 100 == 0L) Log.w(TAG, "drop #$n: $line — $why")
        return ANSWER_DENY
    }

    private fun error(msg: String): String {
        val n = errors.incrementAndGet()
        if (n <= 20 || n % 100 == 0L) Log.w(TAG, msg)
        return ANSWER_ERROR
    }

    private fun closeQuietly(s: LocalSocket) {
        try { s.close() } catch (_: Exception) {}
    }
}
