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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier,
                           style: AppThemeStyle = AppThemeStyle.WARM, backdrop: GlassBackdrop? = null) {
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
            AppNavTab(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
            AppNavTab(AppTab.CATEGORY, "剧场", Icons.Outlined.LiveTv, Icons.Filled.LiveTv),
            AppNavTab(AppTab.FAVORITES, "追剧", Icons.Outlined.StarBorder, Icons.Filled.Star),
            AppNavTab(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Filled.Person))
        AppThemeStyle.CARDS -> listOf(
            AppNavTab(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
            AppNavTab(AppTab.CATEGORY, "发现", Icons.Outlined.Explore, Icons.Filled.Explore),
            AppNavTab(AppTab.FAVORITES, "片单", Icons.Outlined.VideoLibrary, Icons.Filled.VideoLibrary),
            AppNavTab(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Filled.Person))
        AppThemeStyle.WARM -> listOf(
            AppNavTab(AppTab.HOME, "首页", Icons.Outlined.Home, Icons.Outlined.Home),
            AppNavTab(AppTab.CATEGORY, "发现", Icons.Outlined.Explore, Icons.Outlined.Explore),
            AppNavTab(AppTab.FAVORITES, "片单", Icons.Outlined.VideoLibrary, Icons.Outlined.VideoLibrary),
            AppNavTab(AppTab.MINE, "我的", Icons.Outlined.PersonOutline, Icons.Outlined.PersonOutline))
    }
    // 悬浮玻璃 (设置 › 底栏样式): a liquid-glass capsule over the page. The caller passes [backdrop] only when it has
    // laid the page out underneath the capsule; otherwise the themed classic bar below is used.
    if (backdrop != null) {
        LiquidGlassNavigationBar(active, onSelect, tabs, style, backdrop, modifier)
        return
    }
    val secondary = NavMuted
    Surface(color = if (style == AppThemeStyle.CARDS) CardSurface else Canvas, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().semantics { testTagsAsResourceId = true }) {
            HorizontalDivider(thickness = .5.dp, color = NavDivider)
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEach { item ->
                    val tab = item.tab
                    val isSelected = active == tab
                    val color by animateColorAsState(when {
                        !isSelected -> secondary
                        style == AppThemeStyle.WARM -> AccentInk
                        else -> Ink
                    }, tween(180), label = "nav_color_${tab.name}")
                    // Selection marks grow in rather than switching on and off.
                    // Read only while drawing, so the spring redraws the mark without recomposing the bar.
                    val mark = animateFloatAsState(if (isSelected) 1f else 0f,
                        spring(dampingRatio = .7f, stiffness = Spring.StiffnessMediumLow), label = "nav_mark_${tab.name}")
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
                            val pill = Cream
                            Box(Modifier.width(56.dp).height(30.dp).drawBehind {
                                val m = mark.value
                                val w = size.width * (.6f + .4f * m)
                                drawRoundRect(pill.copy(alpha = pill.alpha * m.coerceIn(0f, 1f)), topLeft = Offset((size.width - w) / 2, 0f),
                                    size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
                            }, contentAlignment = Alignment.Center) {
                                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
                            }
                        } else Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, color = color, fontSize = if (style == AppThemeStyle.IMMERSIVE && isSelected) 14.sp else 13.sp,
                            lineHeight = 18.sp, maxLines = 2,
                            fontWeight = if (isSelected) (if (style == AppThemeStyle.WARM) FontWeight.SemiBold else FontWeight.Bold) else FontWeight.Medium)
                        if (style == AppThemeStyle.WARM) {
                            Spacer(Modifier.height(4.dp))
                            val line = Orange
                            Box(Modifier.width(17.dp).height(2.dp).drawBehind {
                                val m = mark.value
                                val w = 14.dp.toPx() * m.coerceIn(0f, 1.2f)
                                drawRoundRect(line.copy(alpha = m.coerceIn(0f, 1f)), topLeft = Offset((size.width - w) / 2, 0f),
                                    size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
                            })
                        }
                    }
                }
            }
        }
    }
}

internal data class AppNavTab(val tab: AppTab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)
