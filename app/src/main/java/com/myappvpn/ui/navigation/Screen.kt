package com.myappvpn.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Servers : Screen("servers")
    object Stats : Screen("stats")
    object Settings : Screen("settings")
    object Project : Screen("project")
    object SplitTunnel : Screen("split_tunnel")
    object HotspotSetup : Screen("hotspot_setup")
    object Subscriptions : Screen("subscriptions")
    object Dns : Screen("dns")
    object OpenFlux : Screen("openflux")
    object Offers : Screen("offers")
}
