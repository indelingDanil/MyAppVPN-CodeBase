package com.myappvpn.data.remote

import retrofit2.http.GET
import retrofit2.http.Url

interface GithubApiService {

    @GET
    suspend fun fetchRawFile(@Url url: String): String
}

object SubscriptionUrls {
    const val VLESS_LITE =
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt"
    const val VLESS_LITE_MIRROR =
        "https://wl.xync.net/vless_lite.txt"

    // igareck/vpn-configs-for-russia — Russia-oriented VLESS Reality configs
    const val IGARECK_BLACK_MOBILE =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt"
    const val IGARECK_BLACK_FULL =
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS.txt"
}
