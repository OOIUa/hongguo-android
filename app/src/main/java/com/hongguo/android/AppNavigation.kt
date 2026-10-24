package com.hongguo.android

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val barHeight = if (LocalDensity.current.fontScale > 1.2f) 72.dp else 52.dp
    val active = when (selected) {
        AppTab.HISTORY -> AppTab.FAVORITES
        AppTab.ACCOUNT -> AppTab.MINE
        else -> selected
    }
    Surface(color = Color.White) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().height(barHeight).semantics { testTagsAsResourceId = true }) {
        listOf(
            Triple(AppTab.HOME, "首页", Icons.Outlined.Home),
            Triple(AppTab.CATEGORY, "发现", Icons.Outlined.Search),
            Triple(AppTab.FAVORITES, "片单", Icons.AutoMirrored.Outlined.PlaylistPlay),
            Triple(AppTab.MINE, "我的", Icons.Outlined.Person),
        ).forEach { (tab, label, icon) ->
            NavigationBarItem(selected = active == tab, onClick = {
                keyboard?.hide(); focus.clearFocus()
                onSelect(if (tab == AppTab.FAVORITES) AppTab.HISTORY else tab)
            }, modifier = Modifier.testTag("nav_${tab.name.lowercase(java.util.Locale.ROOT)}"),
                icon = { Icon(icon, contentDescription = null, Modifier.size(25.dp)) },
                label = { Text(label, fontSize = 12.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Orange, selectedTextColor = Orange,
                    indicatorColor = SoftOrange, unselectedIconColor = Muted, unselectedTextColor = Muted))
        }
    }
    }
}
