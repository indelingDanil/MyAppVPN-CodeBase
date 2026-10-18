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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URI
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

        // ── Bridge network tests ──
        val bridge = if (endpoint.complete) runBridge(context, endpoint, clientId) else null

        // ── Masking ──
        val host = runCatching { URI(endpoint.url).host }.getOrNull()?.takeIf { it.isNotBlank() }
        val demoLabel = L("демо-сервер", "demo server")
        fun sanitize(s: String): String {
            var out = s
            if (endpoint.token.isNotEmpty()) out = out.replace(endpoint.token, "***")
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

        // ── Report ──
        val sb = StringBuilder()
        fun yn(b: Boolean?) = when (b) { true -> L("да", "yes"); false -> L("нет", "no"); null -> "—" }

        sb.appendLine(L("Диагностика OpenFlux", "OpenFlux diagnostics"))
        sb.appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        sb.appendLine()
        sb.appendLine(L("ИТОГ: ", "RESULT: ") + verdict)
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
        sb.appendLine("MyAppVPN ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine()

        sb.appendLine(L("— Выход —", "— Exit —"))
        sb.appendLine(
            when {
                !endpoint.complete -> L("не настроен", "not configured")
                endpoint.demo -> demoLabel
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
        val cmd = mutableListOf(
            BinaryInstaller.getOpenFluxBridgePath(context),
            "-url", endpoint.url,
            "-token", endpoint.token,
            "-ipcache", OpenFluxManager.ipCachePath(context),
            "-shortpoll", "auto",
            "-diag",
        )
        if (clientId.isNotEmpty()) { cmd += "-client"; cmd += clientId }

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

    private fun isRussian(context: Context): Boolean {
        val loc = context.resources.configuration.locales.get(0)
        return loc == null || loc.language != "en"
    }
}
