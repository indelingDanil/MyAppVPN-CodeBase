package com.myappvpn.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.myappvpn.BuildConfig
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.service.OpenFluxManager
import com.myappvpn.util.BinaryInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.EOFException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenFlux connection diagnostics: Android network facts + the bridge's own network tests
 * (`libofbridge.so -diag`, one `OFDIAG <json>` line) → a plain-language report the user can copy
 * or share. The token is never included; for the demo exit its URL/host/IPs are masked.
 */
object OpenFluxDiagnostics {

    private const val DIAG_PREFIX = "OFDIAG "
    private const val DIAG_TIMEOUT_MS = 95_000L

    /** Facts about one network (types/flags only — no IP addresses). */
    private class NetFacts(
        val transport: String,
        val isCellular: Boolean,
        val isVpn: Boolean,
        val metered: Boolean,
        val validated: Boolean,
        val privateDnsActive: Boolean?,
        val privateDnsServer: String?,
        val dnsCount: Int,
        val dnsV4: Boolean,
        val dnsV6: Boolean,
        val addrV4: Boolean,
        val addrV6: Boolean,
    )

    private class BridgeResult(val json: JSONObject?, val rawLine: String?, val error: String?)

    suspend fun run(context: Context, prefs: AppPreferences): String = withContext(Dispatchers.IO) {
        val ru = isRussian(context)
        fun L(r: String, e: String) = if (ru) r else e

        val endpoint = OpenFluxEndpoint.resolve(prefs)
        val clientId = runCatching { prefs.installId() }.getOrDefault("")

        // ── Android facts ──
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active: Network? = runCatching { cm?.activeNetwork }.getOrNull()
        val activeFacts = active?.let { facts(cm!!, it) }
        val all = runCatching { cm?.allNetworks?.toList() }.getOrNull().orEmpty()
        val vpnUp = all.any { n ->
            runCatching { cm!!.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
                .getOrNull() == true
        }
        // The real (non-VPN) network carrying traffic: the active one, or — when the active one
        // is the VPN — a cellular/Wi-Fi network with INTERNET (validated preferred).
        val underlying: Pair<Network, NetFacts>? = if (active != null && activeFacts != null && !activeFacts.isVpn) {
            active to activeFacts
        } else {
            all.mapNotNull { n ->
                val caps = runCatching { cm!!.getNetworkCapabilities(n) }.getOrNull() ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) return@mapNotNull null
                facts(cm!!, n)?.let { n to it }
            }.sortedByDescending { it.second.validated }.firstOrNull()
        }
        val realFacts = underlying?.second

        val restrictStatus = runCatching { cm?.restrictBackgroundStatus }.getOrNull()
        val dataSaverOn = restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED ||
            restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED
        val whitelisted = restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED

        val direct = endpoint.channel == OpenFluxChannel.DIRECT
        val host = if (!direct) null
        else runCatching { URI(endpoint.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
        val demoLabel = L("демо-сервер", "demo server")
        val channelLabel = when (endpoint.channel) {
            OpenFluxChannel.DIRECT -> L("напрямую", "direct")
            OpenFluxChannel.MAILRU -> L("через Mail.ru", "via Mail.ru")
            OpenFluxChannel.CUPS -> L("через Cups", "via Cups")
            OpenFluxChannel.LINK -> L("ссылка OpenFlux", "OpenFlux link")
        }

        // ── Bridge network tests + service reachability probes, concurrently ──
        // Probes go OUTSIDE the tunnel: our own package is always excluded from the VPN
        // (MyVpnService: addDisallowedApplication(packageName), or simply not in the INCLUDE allow-list),
        // and additionally every probe socket + DNS lookup is bound to the underlying non-VPN network.
        val exitProbe: Probe? = if (endpoint.complete && direct) {
            runCatching {
                val u = URI(endpoint.url)
                val scheme = (u.scheme ?: "https").lowercase()
                val port = if (u.port > 0) ":${u.port}" else ""
                // Cleartext http:// is blocked for the app's own OkHttp (targetSdk ≥ 28 default), so an
                // http exit is judged by the bridge's own probe below instead.
                if (u.host.isNullOrBlank() || scheme != "https") null
                else Probe(if (endpoint.demo) demoLabel else u.host, "$scheme://${u.host}$port/", G_EXIT)
            }.getOrNull()
        } else null
        val (bridge, probes) = coroutineScope {
            val b = async { if (endpoint.complete) runBridge(context, endpoint, clientId) else null }
            val p = async { runProbes(underlying?.first, PROBES + listOfNotNull(exitProbe)) }
            b.await() to p.await()
        }

        // ── Masking ──
        fun sanitize(s: String): String {
            var out = s
            // Longest first: a key may be a substring of the link carrying it.
            listOf(endpoint.token, endpoint.link, endpoint.secret)
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
                .forEach { out = out.replace(it, "***") }
            if (endpoint.demo) {
                if (endpoint.url.isNotEmpty()) out = out.replace(endpoint.url, demoLabel)
                if (host != null) out = out.replace(host, demoLabel)
                // Resolved/cached IPs of the demo host.
                out = out.replace(Regex("""\b\d{1,3}(\.\d{1,3}){3}\b"""), "x.x.x.x")
            }
            return out
        }

        val summary = bridge?.json?.optString("summary")?.takeIf { it.isNotBlank() }
        val tcpText = bridge?.json?.opt("tcp")?.toString()?.lowercase().orEmpty()

        // ── Verdict ──
        val verdict = when {
            !endpoint.complete -> L(
                "Выход OpenFlux не настроен: выберите демо-сервер или укажите адрес и токен своего выхода.",
                "No OpenFlux exit configured: pick the demo server or enter your own exit URL and token."
            )
            bridge?.json == null -> L(
                "Не удалось выполнить проверку сети (${bridge?.error ?: "нет ответа"}). Отправьте этот отчёт разработчику.",
                "Couldn't run the network test (${bridge?.error ?: "no response"}). Please send this report to the developer."
            )
            // Carrier channels (Mail.ru / Cups / openflux:// link): the bridge tested the carrier.
            !direct -> {
                val svc = bridge?.json?.optString("host").orEmpty().ifBlank { L("сервис канала", "the channel's service") }
                when (summary) {
                    "ok" -> L(
                        "Канал ($channelLabel) подключается. Если VPN всё равно не работает — проверьте, что выход на той стороне запущен, и отправьте этот отчёт разработчику.",
                        "The channel ($channelLabel) connects. If the VPN still doesn't work, check that the exit on the other side is running and send this report to the developer."
                    )
                    "bad_link" -> L(
                        "Ссылка канала не подходит: ${bridge?.json?.optJSONObject("carrier")?.optString("err").orEmpty()}. Проверьте её и ключ.",
                        "The channel link is not valid: ${bridge?.json?.optJSONObject("carrier")?.optString("err").orEmpty()}. Check it and the key."
                    )
                    "dns_failed" -> L(
                        "Не удаётся узнать адрес $svc через DNS — канал не заработает в этой сети.",
                        "Can't resolve $svc via DNS — the channel won't work on this network."
                    )
                    "tcp_failed" -> L(
                        "$svc не принимает соединения — оператор, похоже, его блокирует. Попробуйте другой канал.",
                        "$svc doesn't accept connections — the carrier seems to block it. Try another channel."
                    )
                    "captcha_required" -> L(
                        "Сервис канала просит пройти проверку «я не робот». Подключитесь — приложение предложит открыть проверку.",
                        "The channel's service asks for a \"not a robot\" check. Connect — the app will offer to open it."
                    )
                    "carrier_failed" -> L(
                        "Канал не запустился. Проверьте ссылку; если она верна — отправьте этот отчёт разработчику.",
                        "The channel failed to start. Check the link; if it is right, send this report to the developer."
                    )
                    "carrier_timeout" -> L(
                        "$svc доступен, но канал не подключился за 25 с. Проверьте ссылку и что выход на той стороне запущен.",
                        "$svc is reachable, but the channel didn't connect within 25 s. Check the link and that the exit on the other side is running."
                    )
                    else -> L(
                        "Обнаружена проблема: $summary. Отправьте этот отчёт разработчику.",
                        "Problem detected: $summary. Please send this report to the developer."
                    )
                }
            }
            summary == "ok" -> L(
                "С сетью всё в порядке: сервер доступен, соединение проходит. Если VPN всё равно не работает — отправьте этот отчёт разработчику.",
                "The network looks fine: the server is reachable and the connection works. If the VPN still doesn't work, send this report to the developer."
            )
            summary == "dns_failed" && (realFacts?.privateDnsActive == true || activeFacts?.privateDnsActive == true) -> {
                val name = realFacts?.privateDnsServer ?: activeFacts?.privateDnsServer
                val shown = name?.let { " ($it)" } ?: ""
                L(
                    "Включён «Частный DNS»$shown — на мобильной сети он, похоже, заблокирован. Выключите: Настройки → Подключения → Частный DNS → Выкл / Автоматически.",
                    "\"Private DNS\" is on$shown — it seems to be blocked on the mobile network. Turn it off: Settings → Connections → Private DNS → Off / Automatic."
                )
            }
            summary == "dns_failed" -> L(
                "Не удаётся узнать адрес сервера через DNS оператора. Подключитесь один раз по Wi-Fi — приложение запомнит адрес и дальше будет работать без DNS.",
                "The server address can't be resolved via the carrier's DNS. Connect once over Wi-Fi — the app will remember the address and work without DNS afterwards."
            )
            summary == "ok_via_ipcache" -> L(
                "DNS оператора не находит сервер, но сохранённый адрес работает — приложение подключается через него. Если VPN всё равно не работает — отправьте этот отчёт разработчику.",
                "The carrier's DNS can't find the server, but the saved address works — the app connects through it. If the VPN still doesn't work, send this report to the developer."
            )
            summary == "tcp_blocked_permission" ||
                ((tcpText.contains("permission denied") || tcpText.contains("network is unreachable")) &&
                    realFacts?.isCellular == true) -> L(
                "Похоже, приложению запрещены мобильные данные. realme/OPPO: Настройки → Мобильная сеть → Использование данных → MyAppVPN → разрешить мобильные данные (и фоновые).",
                "Mobile data seems to be blocked for the app. realme/OPPO: Settings → Mobile network → Data usage → MyAppVPN → allow mobile data (and background data)."
            )
            summary == "tcp_failed" -> L(
                "Сервер не отвечает на соединение — оператор или сеть, вероятно, блокирует его адрес. Попробуйте другую сеть или свой выход на другом хостинге.",
                "The server doesn't accept connections — the carrier or network probably blocks its address. Try another network or your own exit on different hosting."
            )
            summary == "proxy_buffering_use_shortpoll" -> L(
                "Оператор пропускает соединение через свой прокси, который задерживает ответы. Приложение автоматически перейдёт в режим коротких запросов.",
                "The carrier routes the connection through its own proxy, which delays responses. The app will switch to short-request mode automatically."
            )
            summary == "http_unexpected" -> L(
                "Оператор подменяет ответы сервера (страница блокировки или свой прокси). Отправьте этот отчёт разработчику.",
                "The carrier replaces the server's responses (a block page or its own proxy). Please send this report to the developer."
            )
            summary == "token_rejected" -> L(
                "Сервер доступен, но отклонил токен. Проверьте адрес выхода и токен.",
                "The server is reachable but rejected the token. Check the exit URL and token."
            )
            summary != null && summary.startsWith("demo_refused") -> {
                when (summary.substringAfter(':', "")) {
                    "busy" -> L("Сеть в порядке, но демо-сервер сейчас перегружен. Попробуйте позже.",
                        "The network is fine, but the demo server is full right now. Try later.")
                    "expired" -> L("Сеть в порядке, но ваш пробный период на демо-сервере закончился.",
                        "The network is fine, but your demo trial has ended.")
                    "quota" -> L("Сеть в порядке, но демо-сервер исчерпал дневной лимит трафика.",
                        "The network is fine, but the demo server's daily traffic budget is spent.")
                    "ended" -> L("Сеть в порядке, но демо OpenFlux завершено.",
                        "The network is fine, but the OpenFlux demo has ended.")
                    else -> L("Сеть в порядке, но демо-сервер отказал в подключении ($summary).",
                        "The network is fine, but the demo server refused the connection ($summary).")
                }
            }
            else -> L(
                "Обнаружена проблема: $summary. Отправьте этот отчёт разработчику.",
                "Problem detected: $summary. Please send this report to the developer."
            )
        }

        // ── Service reachability verdict (appended; doesn't affect the verdict above) ──
        // Exit reachability as seen by the bridge (Go, not subject to the app's cleartext policy).
        val bridgeProbe = bridge?.json?.optJSONObject("http_probe")
        val bridgeExitOk = bridgeProbe?.optBoolean("x_of_proto") == true
        val servicesVerdict = probeVerdict(probes, endpoint.complete && direct, bridgeExitOk, ::L)

        // ── Report ──
        val sb = StringBuilder()
        fun yn(b: Boolean?) = when (b) { true -> L("да", "yes"); false -> L("нет", "no"); null -> "—" }

        sb.appendLine(L("Диагностика OpenFlux", "OpenFlux diagnostics"))
        sb.appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        sb.appendLine()
        sb.appendLine(L("ИТОГ: ", "RESULT: ") + sanitize(verdict))
        if (servicesVerdict != null) sb.appendLine(L("СЕРВИСЫ: ", "SERVICES: ") + sanitize(servicesVerdict))
        if (dataSaverOn && !whitelisted) {
            sb.appendLine(L(
                "Также: включена «Экономия трафика», и MyAppVPN не в исключениях — добавьте приложение в исключения, иначе фоновая работа может обрываться.",
                "Also: Data Saver is on and MyAppVPN isn't exempt — allow unrestricted data for the app, otherwise background traffic may be cut."
            ))
        }
        sb.appendLine()

        sb.appendLine(L("— Устройство —", "— Device —"))
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        // Per-ABI APKs carry different version codes; BuildConfig holds only the shared base one.
        val installedCode = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrNull() ?: BuildConfig.VERSION_CODE.toLong()
        val bits = if (android.os.Process.is64Bit()) "64-bit" else "32-bit"
        sb.appendLine("MyAppVPN ${BuildConfig.VERSION_NAME} ($installedCode, $bits)")
        sb.appendLine(L("Архитектуры устройства: ", "Device ABIs: ") + Build.SUPPORTED_ABIS.joinToString())
        sb.appendLine()

        sb.appendLine(L("— Выход —", "— Exit —"))
        sb.appendLine(
            when {
                !endpoint.complete -> L("не настроен", "not configured")
                endpoint.demo -> demoLabel
                !direct -> L("свой, канал: ", "own, channel: ") + channelLabel
                else -> L("свой: ", "own: ") + (host ?: "?")
            }
        )
        sb.appendLine()

        sb.appendLine(L("— Сеть —", "— Network —"))
        sb.appendLine(L("VPN поднят: ", "VPN up: ") + yn(vpnUp))
        if (activeFacts != null) {
            sb.appendLine(L("Активная сеть:", "Active network:"))
            appendFacts(sb, activeFacts, ::yn, ru)
        } else {
            sb.appendLine(L("Активная сеть: нет", "Active network: none"))
        }
        if (underlying != null && underlying.first != active) {
            sb.appendLine(L("Сеть под VPN:", "Network under the VPN:"))
            appendFacts(sb, underlying.second, ::yn, ru)
        }
        sb.appendLine(
            L("Экономия трафика: ", "Data Saver: ") + when (restrictStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> L("выкл", "off")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> L("вкл, приложение в исключениях", "on, app exempt")
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> L("вкл, приложение НЕ в исключениях", "on, app NOT exempt")
                else -> "—"
            }
        )
        sb.appendLine()

        sb.appendLine(L("— Доступность сервисов —", "— Service reachability —"))
        sb.appendLine(
            L("(в обход VPN, сеть: ", "(bypassing the VPN, network: ") +
                (underlying?.second?.transport?.let { "$it, " + L("с привязкой к сети", "socket-bound") }
                    ?: L("по умолчанию", "default")) + ")"
        )
        val exitLabelLen = if (exitProbe == null && endpoint.complete && direct) {
            (if (endpoint.demo) demoLabel else runCatching { URI(endpoint.url).host }.getOrNull() ?: "exit").length
        } else 0
        val labelWidth = maxOf(probes.maxOfOrNull { it.probe.label.length } ?: 0, exitLabelLen) + 2
        probes.forEach { r -> sb.appendLine(sanitize(r.probe.label.padEnd(labelWidth) + r.line())) }
        if (endpoint.complete && direct && exitProbe == null && bridgeProbe != null) {
            val label = if (endpoint.demo) demoLabel else (runCatching { URI(endpoint.url).host }.getOrNull() ?: "exit")
            val st = bridgeProbe.optInt("status", 0)
            val line = if (bridgeExitOk) "OK $st  ${bridgeProbe.optLong("ms")} ms  " + L("(по тесту моста)", "(bridge test)")
            else (bridgeProbe.optString("err").ifBlank { "HTTP $st" }) + "  " + L("(по тесту моста)", "(bridge test)")
            sb.appendLine(sanitize(label.padEnd(labelWidth) + line))
        }
        servicesVerdict?.let { sb.appendLine(L("Вывод: ", "Conclusion: ") + sanitize(it)) }
        sb.appendLine()

        sb.appendLine(L("— Тест моста —", "— Bridge test —"))
        when {
            bridge == null -> sb.appendLine(L("не выполнялся", "not run"))
            bridge.json != null -> sb.appendLine(sanitize(runCatching { bridge.json.toString(2) }.getOrDefault(bridge.rawLine.orEmpty())))
            else -> sb.appendLine(sanitize(bridge.rawLine ?: bridge.error ?: L("нет результата", "no result")))
        }
        sb.toString().trimEnd()
    }

    private fun appendFacts(sb: StringBuilder, f: NetFacts, yn: (Boolean?) -> String, ru: Boolean) {
        fun L(r: String, e: String) = if (ru) r else e
        sb.appendLine("  " + L("тип: ", "type: ") + f.transport)
        sb.appendLine("  " + L("лимитная: ", "metered: ") + yn(f.metered))
        sb.appendLine("  " + L("проверена (интернет есть): ", "validated: ") + yn(f.validated))
        sb.appendLine(
            "  " + L("частный DNS: ", "private DNS: ") + yn(f.privateDnsActive) +
                (f.privateDnsServer?.let { " ($it)" } ?: "")
        )
        val dnsFam = listOfNotNull("IPv4".takeIf { f.dnsV4 }, "IPv6".takeIf { f.dnsV6 }).joinToString("+").ifEmpty { "—" }
        sb.appendLine("  DNS: ${f.dnsCount} ($dnsFam)")
        val addrFam = listOfNotNull("IPv4".takeIf { f.addrV4 }, "IPv6".takeIf { f.addrV6 }).joinToString("+").ifEmpty { "—" }
        sb.appendLine("  " + L("адреса: ", "addresses: ") + addrFam)
    }

    private fun facts(cm: ConnectivityManager, n: Network): NetFacts? {
        val caps: NetworkCapabilities = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return null
        val lp: LinkProperties? = runCatching { cm.getLinkProperties(n) }.getOrNull()
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val dns = lp?.dnsServers.orEmpty()
        val addrs = lp?.linkAddresses.orEmpty().map { it.address }
        val (pdActive, pdName) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lp != null) {
            lp.isPrivateDnsActive to lp.privateDnsServerName
        } else null to null
        return NetFacts(
            transport = transports.joinToString("+").ifEmpty { "?" },
            isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            privateDnsActive = pdActive,
            privateDnsServer = pdName?.takeIf { it.isNotBlank() },
            dnsCount = dns.size,
            dnsV4 = dns.any { it is Inet4Address },
            dnsV6 = dns.any { it is Inet6Address },
            addrV4 = addrs.any { it is Inet4Address },
            addrV6 = addrs.any { it is Inet6Address && !it.isLinkLocalAddress },
        )
    }

    /** `libofbridge.so -diag …` with a [DIAG_TIMEOUT_MS] limit; cancellable. */
    private suspend fun runBridge(
        context: Context, endpoint: OpenFluxEndpoint.Resolved, clientId: String,
    ): BridgeResult {
        val cmd = mutableListOf(BinaryInstaller.getOpenFluxBridgePath(context))
        if (endpoint.channel == OpenFluxChannel.DIRECT) {
            cmd += listOf(
                "-url", endpoint.url,
                "-token", endpoint.token,
                "-ipcache", OpenFluxManager.ipCachePath(context),
                "-shortpoll", "auto",
            )
            if (clientId.isNotEmpty()) { cmd += "-client"; cmd += clientId }
        } else {
            // Carrier channel: the bridge tests the service host + whether the carrier joins.
            cmd += listOf("-transport", endpoint.channel.transport, "-link", endpoint.link)
            if (endpoint.secret.isNotEmpty()) { cmd += "-secret"; cmd += endpoint.secret }
        }
        cmd += "-diag"

        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Exception) {
            // The exception message contains the command line (token) → class name only.
            return BridgeResult(null, null, "start failed: ${e.javaClass.simpleName}")
        }
        val line = AtomicReference<String?>(null)
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { l ->
                    val t = l.trim()
                    if (t.startsWith(DIAG_PREFIX) && line.get() == null) line.set(t.removePrefix(DIAG_PREFIX).trim())
                }
            } catch (_: Exception) { /* process ended */ }
        }.also { it.isDaemon = true; it.name = "ofbridge-diag"; it.start() }

        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + DIAG_TIMEOUT_MS
            while (proc.isAlive && line.get() == null) {
                if (System.currentTimeMillis() > deadline) { timedOut = true; break }
                delay(200)
            }
            // The JSON line came: give the bridge a moment to exit on its own.
            if (proc.isAlive) proc.waitFor(1, TimeUnit.SECONDS)
        } catch (e: CancellationException) {
            proc.destroyForcibly()
            throw e
        } finally {
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
            }
        }
        reader.join(1000)

        val raw = line.get()
        if (raw == null) {
            val code = runCatching { proc.exitValue() }.getOrNull()
            return BridgeResult(null, null, if (timedOut) "timeout" else "no OFDIAG output (exit=$code)")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
        return BridgeResult(json, raw, if (json == null) "bad JSON" else null)
    }

    // ── Service reachability probes ──

    private const val G_MAIL = "mail"
    private const val G_YANDEX = "yandex"
    private const val G_RU = "ru"
    private const val G_YA = "ya"
    private const val G_FOREIGN = "foreign"
    private const val G_EXIT = "exit"

    private const val PROBE_TIMEOUT_MS = 6_000L
    private const val PROBES_TOTAL_MS = 15_000L
    private const val PROBE_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"

    private class Probe(val label: String, val url: String, val group: String, val websocket: Boolean = false)

    private class ProbeResult(
        val probe: Probe,
        /** "OK" when any HTTP response arrived; else timeout / reset / dns-fail / tls-fail / refused / blocked(…). */
        val status: String,
        val code: Int? = null,
        val ms: Long? = null,
        /** Cross-host redirect target (host only), if any. */
        val redirectHost: String? = null,
    ) {
        val reachable: Boolean get() = status == "OK"
        fun line(): String = buildString {
            append(status)
            if (code != null) append(' ').append(code)
            if (ms != null) append("  ").append(ms).append(" ms")
            if (redirectHost != null) append("  → ").append(redirectHost)
        }
    }

    private val PROBES = listOf(
        Probe("cloud.mail.ru", "https://cloud.mail.ru/", G_MAIL),
        Probe("docs.datacloudmail.ru", "https://docs.datacloudmail.ru/", G_MAIL),
        Probe("wss docs.datacloudmail.ru", "wss://docs.datacloudmail.ru/", G_MAIL, websocket = true),
        Probe("telemost.yandex.ru", "https://telemost.yandex.ru/", G_YANDEX),
        Probe("cloud-api.yandex.ru", "https://cloud-api.yandex.ru/", G_YANDEX),
        Probe("docs.yandex.ru", "https://docs.yandex.ru/", G_YANDEX),
        Probe("disk.yandex.ru", "https://disk.yandex.ru/", G_YANDEX),
        Probe("code.avito.ru", "https://code.avito.ru/", G_RU),
        Probe("interview.cups.online", "https://interview.cups.online/", G_RU),
        Probe("web.max.ru", "https://web.max.ru/", G_RU),
        Probe("ya.ru", "https://ya.ru/", G_YA),
        Probe("google.com/generate_204", "https://www.google.com/generate_204", G_FOREIGN),
    )

    /**
     * Runs all [probes] in parallel (each ≤ [PROBE_TIMEOUT_MS], all ≤ [PROBES_TOTAL_MS]).
     * When [net] is given, sockets and DNS are bound to it (the real, non-VPN network).
     */
    private suspend fun runProbes(net: Network?, probes: List<Probe>): List<ProbeResult> = coroutineScope {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .apply {
                if (net != null) {
                    socketFactory(net.socketFactory)
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = net.getAllByName(hostname).toList()
                    })
                }
            }
            .build()

        val results = arrayOfNulls<ProbeResult>(probes.size)
        val jobs = probes.mapIndexed { i, p ->
            async(Dispatchers.IO) { results[i] = if (p.websocket) probeWs(client, p) else probeHttp(client, p) }
        }
        val finished = withTimeoutOrNull(PROBES_TOTAL_MS) { jobs.awaitAll() } != null
        if (!finished) {
            client.dispatcher.cancelAll()
            jobs.forEach { it.cancel() }
        }
        // Calls are cancelled → the blocking ones return promptly; don't wait on stragglers anyway.
        val out = probes.mapIndexed { i, p -> results[i] ?: ProbeResult(p, "timeout") }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        out
    }

    private suspend fun probeHttp(client: OkHttpClient, p: Probe): ProbeResult {
        val req = Request.Builder().url(p.url)
            .header("User-Agent", PROBE_UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
            .build()
        val call = client.newCall(req)
        val t0 = System.nanoTime()
        return try {
            runInterruptible { call.execute() }.use { resp ->
                val ms = (System.nanoTime() - t0) / 1_000_000
                val loc = if (resp.isRedirect) resp.header("Location") else null
                val reqHost = req.url.host
                val locHost = loc?.let { runCatching { req.url.resolve(it)?.host }.getOrNull() }
                ProbeResult(p, "OK", resp.code, ms, locHost?.takeIf { it != reqHost })
            }
        } catch (e: CancellationException) {
            call.cancel(); throw e
        } catch (e: Throwable) {
            ProbeResult(p, classify(e), ms = (System.nanoTime() - t0) / 1_000_000)
        }
    }

    /** WebSocket upgrade attempt: any HTTP response (101 or not) counts as reachable. */
    private suspend fun probeWs(client: OkHttpClient, p: Probe): ProbeResult {
        val host = runCatching { URI(p.url).host }.getOrNull().orEmpty()
        val req = Request.Builder().url(p.url)
            .header("User-Agent", PROBE_UA)
            .header("Origin", "https://$host")
            .build()
        val done = CompletableDeferred<ProbeResult>()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                done.complete(ProbeResult(p, "OK", response.code, ms()))
                webSocket.cancel()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.complete(
                    if (response != null) ProbeResult(p, "OK", response.code, ms())
                    else ProbeResult(p, classify(t), ms = ms())
                )
            }
        })
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS + 500) { done.await() } ?: ProbeResult(p, "timeout", ms = ms())
        } finally {
            ws.cancel()
        }
    }

    private fun classify(t: Throwable): String {
        val msg = generateSequence(t) { it.cause }.take(4)
            .joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            t is UnknownHostException -> "dns-fail"
            t is SocketTimeoutException || msg.contains("timeout") || msg.contains("timed out") -> "timeout"
            msg.contains("reset") || msg.contains("closed by peer") || msg.contains("broken pipe") ||
                msg.contains("end of stream") || t is EOFException -> "reset"
            t is SSLException -> "tls-fail"
            t is ConnectException && msg.contains("refused") -> "refused"
            msg.contains("unreachable") || msg.contains("permission denied") || msg.contains("eperm") ->
                "blocked(unreachable)"
            else -> "blocked(${t.javaClass.simpleName})"
        }
    }

    /** One-sentence conclusion about what is reachable; null when there's nothing to say. */
    private fun probeVerdict(
        results: List<ProbeResult>,
        exitConfigured: Boolean,
        bridgeExitOk: Boolean,
        L: (String, String) -> String,
    ): String? {
        if (results.isEmpty()) return null
        fun any(g: String) = results.any { it.probe.group == g && it.reachable }
        val yaOk = any(G_YA)
        val googleOk = any(G_FOREIGN)
        val exitOk = any(G_EXIT) || bridgeExitOk
        val mailOk = any(G_MAIL)
        val wsOk = results.any { it.probe.websocket && it.reachable }
        val yandexOk = any(G_YANDEX)
        val ruOk = results.filter { it.probe.group == G_RU && it.reachable }.map { it.probe.label }

        if (results.none { it.reachable }) return L(
            "Ничего не открывается, даже ya.ru — интернета нет или сеть блокирует все соединения.",
            "Nothing is reachable, not even ya.ru — no internet, or the network blocks every connection."
        )
        val parts = mutableListOf<String>()
        if (yaOk && results.none { it.reachable && it.probe.group != G_YA }) {
            parts += L(
                "Открывается только ya.ru — похоже на режим белых списков, и проверенные сервисы в него не входят.",
                "Only ya.ru is reachable — looks like whitelist mode, and none of the tested services are on the list."
            )
            return parts.joinToString(" ")
        }
        if (yaOk && !googleOk && (!exitConfigured || !exitOk)) parts += L(
            "ya.ru открывается, а Google${if (exitConfigured) " и ваш выход" else ""} — нет: похоже на режим белых списков.",
            "ya.ru works but Google${if (exitConfigured) " and your exit" else ""} don't: looks like whitelist mode."
        )
        when {
            exitConfigured && exitOk -> parts += L(
                "Ваш выход доступен напрямую — обходной транспорт не нужен.",
                "Your exit is reachable directly — no workaround transport needed."
            )
            else -> {
                val exitWord = if (exitConfigured) L("а ваш выход — нет", "but your exit isn't") else L("выход не настроен", "no exit configured")
                if (mailOk) parts += L(
                    "Mail.ru доступен${if (wsOk) " (включая WebSocket)" else ""}, $exitWord: перспективен транспорт через Mail.ru.",
                    "Mail.ru is reachable${if (wsOk) " (WebSocket included)" else ""}, $exitWord: a transport via Mail.ru looks promising."
                )
                if (yandexOk) parts += L(
                    "Сервисы Яндекса доступны, $exitWord: перспективен транспорт через Яндекс.",
                    "Yandex services are reachable, $exitWord: a transport via Yandex looks promising."
                )
                if (!mailOk && !yandexOk) parts += if (ruOk.isNotEmpty()) L(
                    "Mail.ru и Яндекс-сервисы не проходят; доступны: ${ruOk.joinToString()}.",
                    "Mail.ru and Yandex services fail; reachable: ${ruOk.joinToString()}."
                ) else L(
                    "Ни Mail.ru, ни сервисы Яндекса не проходят — обход через них не поможет.",
                    "Neither Mail.ru nor Yandex services get through — tunnelling via them won't help."
                )
            }
        }
        return parts.joinToString(" ").ifEmpty { null }
    }

    private fun isRussian(context: Context): Boolean {
        val loc = context.resources.configuration.locales.get(0)
        return loc == null || loc.language != "en"
    }
}
