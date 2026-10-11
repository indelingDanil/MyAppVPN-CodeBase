package com.myappvpn.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myappvpn.R
import com.myappvpn.ui.navigation.Screen
import com.myappvpn.ui.theme.*

data class NavItem(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val navItems = listOf(
    NavItem(Screen.Home, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    NavItem(Screen.Servers, R.string.nav_servers, Icons.Filled.Public, Icons.Outlined.Public),
    NavItem(Screen.Stats, R.string.nav_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    NavItem(Screen.Settings, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
    NavItem(Screen.Project, R.string.nav_project, Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder)
)

@Composable
fun AetherBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AetherBackground)
            // ~8dp total vertical chrome around the 80dp bar (was 16dp).
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AetherSurface)
                .border(
                    width = 1.dp,
                    color = AetherBorder.copy(alpha = 0.3f),
                    shape = shape
                )
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                // System-bar insets are already applied by MainContent (systemBarsPadding).
                windowInsets = WindowInsets(0, 0, 0, 0),
                modifier = Modifier.fillMaxWidth()
            ) {
                navItems.forEach { item ->
                    val isSelected = currentRoute == item.screen.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onNavigate(item.screen) },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                // The visible label already names the item.
                                contentDescription = null
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(item.labelRes),
                                // Theme styles bake in a text color; use the item's content
                                // color so selected/unselected tinting still applies.
                                color = LocalContentColor.current,
                                // 5 tabs: 11sp, no letter-spacing so "Статистика"/"Поддержать" fit uncut.
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AetherCyan,
                            selectedTextColor = AetherCyan,
                            unselectedIconColor = AetherTextTertiary,
                            unselectedTextColor = AetherTextTertiary,
                            indicatorColor = AetherCyanGlow
                        )
                    )
                }
            }
        }
    }
}
