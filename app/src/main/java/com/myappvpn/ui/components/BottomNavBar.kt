package com.myappvpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.myappvpn.ui.navigation.Screen
import com.myappvpn.ui.theme.*

data class NavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val navItems = listOf(
    NavItem(Screen.Home, "Home", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem(Screen.Servers, "Servers", Icons.Filled.Public, Icons.Outlined.Public),
    NavItem(Screen.Stats, "Stats", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    NavItem(Screen.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
)

@Composable
fun AetherBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(AetherBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AetherSurface)
                .border(
                    width = 1.dp,
                    color = AetherBorder.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(20.dp)
                )
        ) {
            NavigationBar(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                                contentDescription = item.label
                            )
                        },
                        label = {
                            Text(
                                text = item.label,
                                fontSize = androidx.compose.ui.unit.TextUnit(
                                    10f,
                                    androidx.compose.ui.unit.TextUnitType.Sp
                                )
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
