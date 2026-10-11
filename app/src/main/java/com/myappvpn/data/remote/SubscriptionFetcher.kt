package com.myappvpn.data.remote

import com.myappvpn.util.SubscriptionUrlValidator
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches subscription bodies with:
 *  - an 8 MB body cap (bigger → IOException, treated as a failed mirror);
 *  - real cancellation: coroutine cancel → `Call.cancel()` aborts the socket immediately, so a
 *    mirror race no longer waits for the slowest blocking connection;
 *  - SSRF protection: [PublicOnlyDns] on the client, redirects followed MANUALLY (client has
 *    followRedirects=false) and every hop re-validated with [SubscriptionUrlValidator] (https-only).
 *
 * When [socksPort] > 0 the request goes through the local Xray SOCKS inbound (127.0.0.1). OkHttp
 * uses a plain `java.net.Socket(proxy)` for SOCKS, so the global `java.net.Authenticator` installed
 * by MyVpnService still supplies the per-session SOCKS credentials. NB: with a SOCKS proxy the
 * hostname is resolved remotely (by the proxy), so [PublicOnlyDns] is not consulted on that path —
 * the static URL validation still applies.
 */
@Singleton
class SubscriptionFetcher @Inject constructor(
    @Named(SUBSCRIPTION_CLIENT) private val client: OkHttpClient,
) {
    companion object {
        const val SUBSCRIPTION_CLIENT = "subscription"
        const val MAX_BODY_BYTES = 8L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
    }

    suspend fun fetch(url: String, socksPort: Int): String {
        val effectiveClient = if (socksPort > 0) {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .build()
        } else client

        if (!SubscriptionUrlValidator.isValid(url)) throw IOException("Blocked subscription URL")
        var current = url.toHttpUrlOrNull() ?: throw IOException("Malformed subscription URL")
        repeat(MAX_REDIRECTS + 1) {
            when (val hop = fetchOnce(effectiveClient, current)) {
                is Hop.Body -> return hop.text
                is Hop.Redirect -> {
                    val next = current.resolve(hop.location)
                        ?: throw IOException("Malformed redirect location")
                    if (!SubscriptionUrlValidator.isValid(next.toString())) {
                        throw IOException("Blocked redirect target")
                    }
                    current = next
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private sealed class Hop {
        class Body(val text: String) : Hop()
        class Redirect(val location: String) : Hop()
    }

    private suspend fun fetchOnce(client: OkHttpClient, url: okhttp3.HttpUrl): Hop =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(Request.Builder().url(url).get().build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)       // ignored if already cancelled
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { handle(it) } }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
            })
        }

    private fun handle(response: Response): Hop {
        if (response.isRedirect) {
            val location = response.header("Location")
                ?: throw IOException("Redirect without Location (HTTP ${response.code})")
            return Hop.Redirect(location)
        }
        // NB: never put the URL into the message — custom subscription URLs may carry tokens.
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        return Hop.Body(readBounded(response.body ?: throw IOException("Empty body")))
    }

    private fun readBounded(body: ResponseBody): String {
        val declared = body.contentLength()
        if (declared > MAX_BODY_BYTES) throw IOException("Subscription body too large ($declared bytes)")
        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val read = source.read(buffer, 64 * 1024L)
            if (read == -1L) break
            if (buffer.size > MAX_BODY_BYTES) throw IOException("Subscription body exceeds ${MAX_BODY_BYTES} bytes")
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(charset)
    }
}

/**
 * OkHttp [Dns] for subscription fetches: resolves normally, then drops every non-public address
 * (loopback, any-local, private, link-local, CGNAT, multicast, mapped/embedded forms…). If nothing
 * public is left → [UnknownHostException]. Defends against DNS rebinding / internal hostnames.
 */
object PublicOnlyDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val public = all.filterNot { SubscriptionUrlValidator.isNonPublicAddress(it) }
        if (public.isEmpty()) throw UnknownHostException("$hostname resolves only to non-public addresses")
        return public
    }
}
