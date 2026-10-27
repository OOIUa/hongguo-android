package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit, style: AppThemeStyle = AppThemeStyle.WARM) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val active = when (selected) {
        AppTab.HISTORY -> AppTab.FAVORITES
        AppTab.ACCOUNT -> AppTab.MINE
        else -> selected
    }
    // Same four destinations in every theme; labels, icons and the selection mark differ per layout.
    val tabs = when (style) {
        AppThemeStyle.IMMERSIVE -> listOf(
            NavItem(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
            NavItem(AppTab.CATEGORY, "剧场", Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
            NavItem(AppTab.FAVORITES, "追剧", Icons.Outlined.StarBorder, Icons.Filled.Star),
            NavItem(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Filled.Person))
        AppThemeStyle.CARDS -> listOf(
            NavItem(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
            NavItem(AppTab.CATEGORY, "发现", Icons.Outlined.Explore, Icons.Filled.Explore),
            NavItem(AppTab.FAVORITES, "片单", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
            NavItem(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Filled.Person))
        AppThemeStyle.WARM -> listOf(
            NavItem(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Outlined.Home),
            NavItem(AppTab.CATEGORY, "发现", Icons.Outlined.Explore, Icons.Outlined.Explore),
            NavItem(AppTab.FAVORITES, "片单", Icons.Outlined.VideoLibrary, Icons.Outlined.VideoLibrary),
            NavItem(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Outlined.PersonOutline))
    }
    val secondary = NavMuted
    Surface(color = if (style == AppThemeStyle.CARDS) CardSurface else Canvas, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().semantics { testTagsAsResourceId = true }) {
            HorizontalDivider(thickness = .5.dp, color = NavDivider)
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { item ->
                    val tab = item.tab
                    val isSelected = active == tab
                    val color = when {
                        !isSelected -> secondary
                        style == AppThemeStyle.WARM -> AccentInk
                        else -> Ink
                    }
                    Column(Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = {
                            keyboard?.hide()
                            focus.clearFocus()
                            onSelect(if (tab == AppTab.FAVORITES) AppTab.HISTORY else tab)
                        }).testTag("nav_${tab.name.lowercase(Locale.ROOT)}")
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        val icon = if (isSelected) item.selectedIcon else item.icon
                        if (style == AppThemeStyle.CARDS) {
                            Box(Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                                .background(if (isSelected) Cream else Color.Transparent), contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
                            }
                        } else Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, color = color, fontSize = if (style == AppThemeStyle.IMMERSIVE && isSelected) 14.sp else 13.sp,
                            lineHeight = 18.sp, maxLines = 2,
                            fontWeight = if (isSelected) (if (style == AppThemeStyle.WARM) FontWeight.SemiBold else FontWeight.Bold) else FontWeight.Medium)
                        if (style == AppThemeStyle.WARM) {
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(14.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
                                .background(if (isSelected) Orange else Color.Transparent))
                        }
                    }
                }
            }
        }
    }
}

private data class NavItem(val tab: AppTab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
