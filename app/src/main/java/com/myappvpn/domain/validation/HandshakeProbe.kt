package com.myappvpn.domain.validation

import com.myappvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SNIHostName

data class HandshakeResult(
    val serverId: String,
    val success: Boolean,
    val errorReason: String? = null,
)

class HandshakeProbe @Inject constructor() {

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 5000
    }

    /**
     * Attempts a TLS handshake to the server.
     * For security="reality" or "tls": validates that the port speaks TLS.
     * For security="none": auto-pass (no TLS expected).
     */
    suspend fun probe(server: ServerProfile): HandshakeResult = withContext(Dispatchers.IO) {
        // No TLS expected — skip handshake, auto-pass
        // (Hysteria2 = TLS over QUIC/UDP — a TCP handshake can't test it; leave it to micro-tests.)
        if (server.security == "none" || server.isHysteria2) {
            return@withContext HandshakeResult(server.id, true)
        }

        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // use{} closes the socket on EVERY path (timeouts / connect errors included).
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS

                // Set SNI from the VLESS config (required for Reality to respond correctly)
                val sni = extractSni(server.vlessUri)
                if (sni.isNotEmpty()) {
                    val params = SSLParameters()
                    params.serverNames = listOf(SNIHostName(sni))
                    socket.sslParameters = params
                }

                socket.connect(InetSocketAddress(server.address, server.port), HANDSHAKE_TIMEOUT_MS)

                try {
                    socket.startHandshake()
                    HandshakeResult(server.id, true)
                } catch (e: javax.net.ssl.SSLHandshakeException) {
                    // For Reality servers, certificate validation will fail — that's OK.
                    // The fact that we got a ServerHello means XRay is running.
                    if (server.security == "reality") {
                        HandshakeResult(server.id, true)
                    } else {
                        HandshakeResult(server.id, false, "tls_error: ${e.message}")
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            HandshakeResult(server.id, false, "timeout")
        } catch (e: java.net.ConnectException) {
            HandshakeResult(server.id, false, "connection_refused")
        } catch (e: Exception) {
            HandshakeResult(server.id, false, e.message)
        }
    }

    private fun extractSni(vlessUri: String): String {
        val queryStart = vlessUri.indexOf('?')
        if (queryStart < 0) return ""
        val fragment = vlessUri.indexOf('#')
        val queryStr = if (fragment > queryStart) vlessUri.substring(queryStart + 1, fragment)
        else vlessUri.substring(queryStart + 1)
        return queryStr.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == "sni" }
            ?.getOrNull(1)
            ?: ""
    }
}
