package com.myappvpn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.myappvpn.ui.screens.*

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToServers = {
                    navController.navigate(Screen.Servers.route) { launchSingleTop = true }
                },
                onNavigateToHotspotSetup = {
                    navController.navigate(Screen.HotspotSetup.route) { launchSingleTop = true }
                },
                onNavigateToOpenFlux = {
                    navController.navigate(Screen.OpenFlux.route) { launchSingleTop = true }
                }
            )
        }

        composable(Screen.Servers.route) {
            ServersScreen(
                onServerSelected = {
                    navController.popBackStack(Screen.Home.route, false)
                }
            )
        }

        composable(Screen.Project.route) {
            ProjectScreen()
        }

        composable(Screen.Stats.route) {
            StatsScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToSplitTunnel = {
                    navController.navigate(Screen.SplitTunnel.route) { launchSingleTop = true }
                },
                onNavigateToSubscriptions = {
                    navController.navigate(Screen.Subscriptions.route) { launchSingleTop = true }
                },
                onNavigateToDns = {
                    navController.navigate(Screen.Dns.route) { launchSingleTop = true }
                },
                onNavigateToHotspotSetup = {
                    navController.navigate(Screen.HotspotSetup.route) { launchSingleTop = true }
                },
                onNavigateToOpenFlux = {
                    navController.navigate(Screen.OpenFlux.route) { launchSingleTop = true }
                }
            )
        }

        composable(Screen.OpenFlux.route) {
            OpenFluxScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Dns.route) {
            DnsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.SplitTunnel.route) {
            SplitTunnelScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Subscriptions.route) {
            SubscriptionsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.HotspotSetup.route) {
            HotspotSetupScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
