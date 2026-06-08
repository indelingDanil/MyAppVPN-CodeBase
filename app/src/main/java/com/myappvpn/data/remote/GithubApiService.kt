package com.myappvpn.data.remote

import retrofit2.http.GET
import retrofit2.http.Url

interface GithubApiService {

    @GET
    suspend fun fetchRawFile(@Url url: String): String
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

    /** All fallback chains, fetched and merged by ConfigRepository. */
    val ALL_CHAINS = listOf(UNIVERSAL_CHAIN, IGARECK_BLACK_CHAIN)
}
