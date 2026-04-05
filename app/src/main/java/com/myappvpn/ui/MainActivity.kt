package com.myappvpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.myappvpn.ui.components.AetherBottomNavBar
import com.myappvpn.ui.navigation.AppNavGraph
import com.myappvpn.ui.navigation.Screen
import com.myappvpn.ui.theme.AetherBackground
import com.myappvpn.ui.theme.MyAppVPNTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notifications are optional, ignore result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MyAppVPNTheme {
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Routes that show the bottom nav bar
    val bottomNavRoutes = setOf(
        Screen.Home.route,
        Screen.Servers.route,
        Screen.Stats.route,
        Screen.Settings.route
    )
    val showBottomBar = currentRoute in bottomNavRoutes

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherBackground)
            .systemBarsPadding()
    ) {
        // Main content (takes remaining space)
        Box(modifier = Modifier.weight(1f)) {
            AppNavGraph(navController = navController)
        }

        // Bottom navigation bar
        if (showBottomBar) {
            AetherBottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    navController.navigate(screen.route) {
                        // Pop up to the home screen to avoid building up a large back stack
                        popUpTo(Screen.Home.route) {
                            saveState = true
                        }
                        // Avoid multiple copies of the same destination
                        launchSingleTop = true
                        // Restore state when re-selecting a previously selected item
                        restoreState = true
                    }
                }
            )
        }
    }
}
