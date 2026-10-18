package com.myappvpn.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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

val BottomBarHeight = 64.dp
val BottomBarMargin = 12.dp

/** Extra bottom padding scrollable screen content needs to clear the floating bar (0 when hidden). */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

@Composable
fun AetherBottomNavBar(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AetherPalette
    val selectedIndex = navItems.indexOfFirst { it.screen.route == currentRoute }

    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(BottomBarHeight),
        shape = RoundedCornerShape(50)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            val itemWidth = maxWidth / navItems.size
            val indicatorX by animateDpAsState(
                targetValue = itemWidth * selectedIndex.coerceAtLeast(0),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "navIndicator"
            )
            if (selectedIndex >= 0) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(indicatorX.roundToPx(), 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .background(c.accentSoft, RoundedCornerShape(50))
                )
            }
            Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
                navItems.forEachIndexed { index, item ->
                    NavBarItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onNavigate(item.screen) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun NavBarItem(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = AetherPalette
    val color by animateColorAsState(
        targetValue = if (selected) c.accent else c.textSecondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navItemColor"
    )
    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            )
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
            // The visible label already names the item.
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(item.labelRes),
            color = color,
            // 5 tabs: no letter-spacing so "Статистика"/"Поддержать" fit uncut.
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
