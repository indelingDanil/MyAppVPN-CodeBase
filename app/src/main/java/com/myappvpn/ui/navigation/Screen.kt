package com.myappvpn.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Servers : Screen("servers")
    object Stats : Screen("stats")
    object Settings : Screen("settings")
    object SplitTunnel : Screen("split_tunnel")
    object HotspotSetup : Screen("hotspot_setup")
}
