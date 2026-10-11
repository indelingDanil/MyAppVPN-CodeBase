package com.myappvpn.service

import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide credentials for OUR local Xray SOCKS inbounds (all of them require user/pass).
 *
 * java.net's SOCKS5 client (used by HttpURLConnection over a SOCKS Proxy) asks the default
 * [Authenticator] for credentials. This single authenticator answers ONLY for loopback proxies:
 *  - a port registered by a micro-test instance → that test's random credentials;
 *  - otherwise → the live VPN session credentials (if any).
 * It never hands anything to a remote host/proxy.
 */
object LocalProxyAuth {

    @Volatile private var sessionUser: String = ""
    @Volatile private var sessionPass: String = ""
    private val testCreds = ConcurrentHashMap<Int, Pair<String, String>>()
    @Volatile private var installed = false

    private val authenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            // NB: java.net SOCKS5 auth (SocksSocketImpl) asks with protocol "SOCKS5" but
            // RequestorType.SERVER, so accept that case too — still loopback-only below.
            val isProxyAsk = requestorType == RequestorType.PROXY ||
                requestingProtocol.equals("SOCKS5", ignoreCase = true)
            if (!isProxyAsk) return null
            val host = requestingHost
            val addr = requestingSite?.hostAddress
            val isLocal = host == "127.0.0.1" || host == "localhost" || addr == "127.0.0.1"
            if (!isLocal) return null
            testCreds[requestingPort]?.let { (u, p) -> return PasswordAuthentication(u, p.toCharArray()) }
            val u = sessionUser
            val p = sessionPass
            if (u.isEmpty() || p.isEmpty()) return null
            return PasswordAuthentication(u, p.toCharArray())
        }
    }

    /** Idempotent: installs our authenticator as the JVM default. */
    @Synchronized
    fun install() {
        if (installed) return
        Authenticator.setDefault(authenticator)
        installed = true
    }

    fun setSession(user: String, pass: String) {
        sessionUser = user
        sessionPass = pass
        install()
    }

    fun clearSession() {
        sessionUser = ""
        sessionPass = ""
    }

    fun registerTestPort(port: Int, user: String, pass: String) {
        testCreds[port] = user to pass
        install()
    }

    fun unregisterTestPort(port: Int) {
        testCreds.remove(port)
    }

    /** Random user/password pair for a local inbound. */
    fun randomCredentials(): Pair<String, String> =
        ("u" + UUID.randomUUID().toString().replace("-", "").take(8)) to
            UUID.randomUUID().toString().replace("-", "")

    /**
     * Picks [count] distinct free TCP ports on 127.0.0.1 (kernel-assigned ephemeral ports).
     * All sockets are held open until every port is chosen (guarantees distinct values), then
     * closed so Xray can bind them. Tiny TOCTOU window — acceptable vs. fixed, guessable ports.
     */
    fun pickFreeLoopbackPorts(count: Int): List<Int> {
        val loopback = InetAddress.getByName("127.0.0.1")
        val sockets = ArrayList<ServerSocket>(count)
        try {
            repeat(count) { sockets.add(ServerSocket(0, 1, loopback)) }
            return sockets.map { it.localPort }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }
}
