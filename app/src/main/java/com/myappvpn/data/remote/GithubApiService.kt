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
 * Subscription sources. Each source is a *fallback chain*: mirrors are identical,
 * so we try them in order and take the first that responds with vless:// links.
 *
 * - UNIVERSAL: zieng2/wl unified "Universal" list (formerly the xync source).
 *   Last two entries are experimental mirrors that keep working under restrictions
 *   (note: different filename `list_universal.txt`, and GitVerse uses the `master` branch).
 * - IGARECK_BLACK: igareck/vpn-configs-for-russia BLACK list — a separate project,
 *   used as an additional pool.
 */
object SubscriptionUrls {

    val UNIVERSAL_CHAIN = listOf(
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
        "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt",
        // Experimental mirrors (work under white-list restrictions). Different filename.
        "https://hub.mos.ru/zieng2/wl/raw/main/list_universal.txt",
        "https://gitverse.ru/api/repos/zieng2/wl/raw/branch/master/list_universal.txt",
    )

    val IGARECK_BLACK_CHAIN = listOf(
        "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt",
        "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS.txt",
        "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/BLACK_VLESS_RUS.txt",
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

    /** Stable ids for the built-in sources (used as DataStore keys for enable/disable state). */
    const val ID_MYAPPVPN = "builtin:myappvpn"
    const val ID_UNIVERSAL = "builtin:universal"
    const val ID_IGARECK = "builtin:igareck"

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
        SubscriptionSource(
            id = ID_UNIVERSAL,
            name = "Universal (zieng2/wl)",
            urls = UNIVERSAL_CHAIN,
            enabled = true,
            isBuiltin = true,
        ),
        SubscriptionSource(
            id = ID_IGARECK,
            name = "igareck BLACK",
            urls = IGARECK_BLACK_CHAIN,
            enabled = true,
            isBuiltin = true,
        ),
    )
}
