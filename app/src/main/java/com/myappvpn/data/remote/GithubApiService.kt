package com.myappvpn.data.remote

import com.myappvpn.data.model.SubscriptionSource
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Legacy Retrofit entry point. Subscription fetching now goes through [SubscriptionFetcher]
 * (8 MB cap, SSRF-safe DNS + redirect validation, real cancellation). Returns a streaming
 * [ResponseBody] so a caller can never accidentally buffer an unbounded body into a String.
 */
interface GithubApiService {

    @Streaming
    @GET
    suspend fun fetchRawFile(@Url url: String): ResponseBody
}

/**
 * Subscription sources. Each source is a *fallback chain*: mirrors are identical, so they are
 * raced and the first one that responds with vless:// links wins.
 *
 * The only visible built-in source is MyAppVPN (our own aggregated list). [FALLBACK_CHAIN]
 * (zieng2/wl "Universal") is not shown anywhere: ConfigRepository fetches it silently only when
 * MyAppVPN is enabled and every one of its mirrors failed (typical for whitelist-only mobile
 * networks that block jsDelivr/githack/GitHub).
 *
 * The former visible sources "Universal (zieng2/wl)" and "igareck BLACK" were removed. Their old
 * ids ("builtin:universal", "builtin:igareck") may still sit in the DataStore set of disabled
 * built-ins — harmless, that set is only matched against [BUILTIN_SOURCES] ids. Their servers are
 * pruned from Room by the next refresh (rows absent from every fetched list are deleted).
 */
object SubscriptionUrls {

    /**
     * zieng2/wl unified "Universal" list — silent fallback for [MYAPPVPN_CHAIN]. RU mirrors first
     * (Mos.Hub / GitVerse stay reachable under whitelist restrictions; note the different filename
     * `list_universal.txt` and GitVerse's `master` branch). All mirrors are raced, so on a
     * whitelist network the RU ones simply win while the blocked ones time out.
     */
    val FALLBACK_CHAIN = listOf(
        "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
        "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
        "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt",
    )

    /**
     * Our own aggregated list (github.com/indelingDanil/MyAppVPN-subs, `output` branch): a bot
     * discovers public sources, keeps live reality/tls VLESS only. jsDelivr first — raw GitHub is
     * often throttled on RU mobile.
     */
    val MYAPPVPN_CHAIN = listOf(
        "https://cdn.jsdelivr.net/gh/indelingDanil/MyAppVPN-subs@output/vless.txt",
        "https://raw.githack.com/indelingDanil/MyAppVPN-subs/output/vless.txt",
        "https://raw.githubusercontent.com/indelingDanil/MyAppVPN-subs/output/vless.txt",
    )

    /** Stable id of the built-in source (DataStore key for its enable/disable state). */
    const val ID_MYAPPVPN = "builtin:myappvpn"

    /**
     * Built-in subscription sources. These ship with the app; users can disable them but not
     * delete them. Custom user sources are added on top by SubscriptionRepository.
     */
    val BUILTIN_SOURCES: List<SubscriptionSource> = listOf(
        SubscriptionSource(
            id = ID_MYAPPVPN,
            name = "MyAppVPN (авто-сбор)",
            urls = MYAPPVPN_CHAIN,
            enabled = true,
            isBuiltin = true,
        ),
    )
}
