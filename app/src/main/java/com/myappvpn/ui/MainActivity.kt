package com.myappvpn.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import com.myappvpn.data.datastore.AppPreferences
import com.myappvpn.ui.theme.ThemeMode
import javax.inject.Inject
import android.os.Build
import android.os.Bundle
import android.content.Intent
import com.myappvpn.ui.viewmodel.OpenFluxImport
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
import com.myappvpn.ui.components.BottomBarHeight
import com.myappvpn.ui.components.BottomBarMargin
import com.myappvpn.ui.components.LocalBottomBarPadding
import com.myappvpn.ui.components.LocalHazeState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.myappvpn.ui.navigation.AppNavGraph
import com.myappvpn.ui.navigation.Screen
import com.myappvpn.ui.theme.AetherBackground
import com.myappvpn.ui.theme.MyAppVPNTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: AppPreferences

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

        enableEdgeToEdge()
        setContent {
            val themeMode by preferences.themeMode.collectAsState(initial = null)
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.LIGHT.name -> false
                ThemeMode.DARK.name -> true
                else -> systemDark
            }
            // Transparent system bars; icon colour follows the app theme (not only the system one).
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            MyAppVPNTheme(darkTheme = dark) {
                MainContent()
            }
        }
        // Launched by an openflux:// link (not on recreation — the link was handled already).
        if (savedInstanceState == null) handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** openflux:// share link → OpenFlux screen, prefilled after the user confirms there. */
    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        OpenFluxImport.offer(intent.dataString)
    }
}

@Composable
fun MainContent() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // An imported openflux:// link waits for confirmation on the OpenFlux screen.
    val pendingImport by OpenFluxImport.pending.collectAsState()
    LaunchedEffect(pendingImport) {
        if (pendingImport != null && navController.currentDestination?.route != Screen.OpenFlux.route) {
            navController.navigate(Screen.OpenFlux.route) { launchSingleTop = true }
        }
    }

    // Routes that show the bottom nav bar
    val bottomNavRoutes = setOf(
        Screen.Home.route,
        Screen.Servers.route,
        Screen.Stats.route,
        Screen.Settings.route,
        Screen.Project.route
    )
    val showBottomBar = currentRoute in bottomNavRoutes

    val hazeState = rememberHazeState()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarPadding =
        if (showBottomBar) navBarBottom + BottomBarMargin + BottomBarHeight + 8.dp else 0.dp

    CompositionLocalProvider(
        LocalHazeState provides hazeState,
        LocalBottomBarPadding provides bottomBarPadding
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AetherBackground)
        ) {
            // Screens fill the window and scroll under the floating bar (its blur source).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .background(AetherBackground)
                    .statusBarsPadding()
                    .then(
                        if (showBottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        else Modifier.navigationBarsPadding()
                    )
            ) {
                AppNavGraph(navController = navController)
            }

            // Floating bottom navigation bar
            if (showBottomBar) {
                AetherBottomNavBar(
                    currentRoute = currentRoute,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = BottomBarMargin, end = BottomBarMargin, bottom = BottomBarMargin),
                    onNavigate = { screen ->
                        if (screen == Screen.Home) {
                            // Home is the stack root. Navigating to it with saveState/restoreState
                            // RESTORED the saved Home stack — which contained Servers (opened from the
                            // mode card) — so tapping Home bounced straight back to Servers.
                            // Just pop back to the root instead.
                            if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
                                navController.navigate(Screen.Home.route) { launchSingleTop = true }
                            }
                            return@AetherBottomNavBar
                        }
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
}
