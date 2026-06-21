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
                    navController.navigate(Screen.Servers.route)
                },
                onNavigateToHotspotSetup = {
                    navController.navigate(Screen.HotspotSetup.route)
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

        composable(Screen.Stats.route) {
            StatsScreen()
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateToSplitTunnel = {
                    navController.navigate(Screen.SplitTunnel.route)
                },
                onNavigateToSubscriptions = {
                    navController.navigate(Screen.Subscriptions.route)
                }
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
