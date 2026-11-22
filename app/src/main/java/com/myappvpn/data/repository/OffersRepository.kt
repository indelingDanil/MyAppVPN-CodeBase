package com.myappvpn.data.repository

import android.content.Context
import androidx.annotation.StringRes
import com.myappvpn.R
import com.myappvpn.data.remote.SubscriptionFetcher
import com.myappvpn.service.MyVpnService
import com.myappvpn.util.SubscriptionUrlValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One partner offer. [text] is keyed by language ("ru", "en", "zh", …); built-ins use [textRes].
 * [category] is "vpn" for paid VPN/subscription services; anything else ("shop", …) is shown under
 * "other services". [subscription] = the service gives a subscription link that can be added in
 * MyAppVPN (defaults to true for category "vpn"). [trialUrl] = optional free-trial subscription link
 * (https, passes [SubscriptionUrlValidator]) the card can add in one tap; [trialNote] = its short
 * per-language terms ("10 ГБ на 7 дней"), keyed like [text].
 */
data class Offer(
    val id: String,
    val title: String,
    val text: Map<String, String>,
    val url: String,
    val tg: String? = null,
    val tgStart: String? = null,
    val icon: String = "shop",
    val weight: Int = 1,
    val category: String = CATEGORY_OTHER,
    val subscription: Boolean = false,
    val trialUrl: String? = null,
    val trialNote: Map<String, String> = emptyMap(),
    @StringRes val textRes: Int = 0,
) {
    /** Text for [lang] (Locale language code), falling back to en → ru → first available. */
    fun textFor(lang: String): String = text.localized(lang).orEmpty()

    /** Trial terms for [lang] with the same fallback as [textFor]; null when there are none. */
    fun trialNoteFor(lang: String): String? = trialNote.localized(lang)

    val isVpn: Boolean get() = category == CATEGORY_VPN

    companion object {
        const val CATEGORY_VPN = "vpn"
        const val CATEGORY_OTHER = "other"

        private fun Map<String, String>.localized(lang: String): String? =
            this[lang] ?: this["en"] ?: this["ru"] ?: values.firstOrNull()
    }
}

/**
 * Partner offers for the "Verified paid subscriptions" screen (opened from Home and the Support tab).
 *
 * The list is a JSON file in the public subs repo, fetched at most every 12h over the same
 * [SubscriptionFetcher] as subscriptions (SSRF-safe, https-only, through the tunnel's SOCKS when
 * connected). Raw JSON + fetch time are cached in SharedPreferences; with no cache at all the
 * built-in [FALLBACK] list is used. No analytics, no click tracking, no other requests.
 */
@Singleton
class OffersRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val fetcher: SubscriptionFetcher,
) {
    companion object {
        private const val PREFS = "offers"
        private const val KEY_JSON = "json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_HIDDEN_LEGACY = "hidden"   // 24h-hide map of the removed Home slot
        private const val MAX_AGE_MS = 12 * 60 * 60_000L

        private val MIRRORS = listOf(
            "https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@main/offers/offers.json",
            "https://raw.githack.com/indelingDanil/MyAppVPN-subs/main/offers/offers.json",
            "https://raw.githubusercontent.com/indelingDanil/MyAppVPN-subs/main/offers/offers.json",
        )

        private val TG_RE = Regex("^[A-Za-z0-9_]{5,32}$")
        private val TG_START_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        private val FALLBACK = listOf(
            Offer(
                id = "yepshop",
                title = "YepShop",
                text = emptyMap(),
                textRes = R.string.partner_text,
                url = "https://yepshop.app/?ref=6A086889",
                tg = "YepShopBot",
                tgStart = "ref_6A086889",
                icon = "game",
                category = "shop",
            )
        )

        /**
         * Lenient parse: unknown fields ignored, invalid/expired/disabled entries skipped. Null = not
         * an offers file. Result is ordered by weight (desc); equal weights keep the file order.
         */
        fun parse(json: String): List<Offer>? = runCatching {
            val arr = JSONObject(json).getJSONArray("offers")
            val today = LocalDate.now()
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.optJSONObject(i) ?: return@runCatching null
                    if (!o.optBoolean("enabled", true)) return@runCatching null
                    val id = o.optString("id").trim().ifEmpty { return@runCatching null }
                    val title = o.optString("title").trim().ifEmpty { return@runCatching null }
                    val url = o.optString("url").trim()
                    if (!url.startsWith("https://") || URI(url).host.isNullOrBlank()) return@runCatching null
                    val until = o.optString("until").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.trim()) }
                    if (until != null && today.isAfter(until)) return@runCatching null
                    val text = langMap(o.optJSONObject("text"))
                    if (text.isEmpty()) return@runCatching null
                    // Same rules as a user-added subscription; an invalid trial link only drops
                    // the trial button, the offer itself is still shown.
                    val trialUrl = o.optString("trialUrl").trim()
                        .takeIf { it.isNotEmpty() && SubscriptionUrlValidator.isValid(it) }
                    val tg = o.optString("tg").trim().takeIf { TG_RE.matches(it) }
                    val category = o.optString("category").trim().lowercase().ifEmpty { Offer.CATEGORY_OTHER }
                    Offer(
                        id = id,
                        title = title,
                        text = text,
                        url = url,
                        tg = tg,
                        tgStart = o.optString("tgStart").trim().takeIf { tg != null && TG_START_RE.matches(it) },
                        icon = o.optString("icon").trim().lowercase().ifEmpty { "shop" },
                        weight = o.optInt("weight", 1).coerceAtLeast(1),
                        category = category,
                        subscription = o.optBoolean("subscription", category == Offer.CATEGORY_VPN),
                        trialUrl = trialUrl,
                        trialNote = if (trialUrl != null) langMap(o.optJSONObject("trialNote")) else emptyMap(),
                    )
                }.getOrNull()
            }.distinctBy { it.id }.sortedByDescending { it.weight }
        }.getOrNull()

        /** {"ru": "…", "en-US": "…"} → non-blank values keyed by bare lowercase language code. */
        private fun langMap(obj: JSONObject?): Map<String, String> = buildMap {
            obj?.keys()?.forEach { k ->
                val v = obj.optString(k).trim()
                if (v.isNotEmpty()) putIfAbsent(k.substringBefore('-').lowercase(), v)
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshStarted = AtomicBoolean(false)

    private val _offers = MutableStateFlow(prefs.getString(KEY_JSON, null)?.let(::parse) ?: FALLBACK)
    /** All valid offers, higher weight first. */
    val offers: StateFlow<List<Offer>> = _offers.asStateFlow()

    init {
        if (prefs.contains(KEY_HIDDEN_LEGACY)) prefs.edit().remove(KEY_HIDDEN_LEGACY).apply()
    }

    /** Refreshes the list in the background if the cache is older than 12h. Once per process. */
    fun refreshIfStale() {
        if (!refreshStarted.compareAndSet(false, true)) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0L) < MAX_AGE_MS) return
        scope.launch {
            val (json, list) = fetchFirstWorking() ?: return@launch   // failed → keep cache/fallback
            prefs.edit().putString(KEY_JSON, json).putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            _offers.value = list
        }
    }

    /** Races the mirrors (≤8s each), first one that parses as an offers file wins. */
    private suspend fun fetchFirstWorking(): Pair<String, List<Offer>>? = coroutineScope {
        val winner = CompletableDeferred<Pair<String, List<Offer>>?>()
        val jobs = MIRRORS.map { url ->
            launch {
                val json = withTimeoutOrNull(8_000) {
                    runCatching { fetcher.fetch(url, MyVpnService.activeSocksPort) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                } ?: return@launch
                parse(json)?.let { winner.complete(json to it) }
            }
        }
        launch { jobs.joinAll(); winner.complete(null) }
        val result = winner.await()
        jobs.forEach { it.cancel() }
        result
    }
}
