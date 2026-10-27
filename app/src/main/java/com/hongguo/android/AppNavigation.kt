package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val active = when (selected) {
        AppTab.HISTORY -> AppTab.FAVORITES
        AppTab.ACCOUNT -> AppTab.MINE
        else -> selected
    }
    val secondary = NavMuted
    Surface(color = Canvas, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().semantics { testTagsAsResourceId = true }) {
            HorizontalDivider(thickness = .5.dp, color = NavDivider)
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    Triple(AppTab.HOME, "首页", Icons.Outlined.Home),
                    Triple(AppTab.CATEGORY, "发现", Icons.Outlined.Explore),
                    Triple(AppTab.FAVORITES, "片单", Icons.Outlined.VideoLibrary),
                    Triple(AppTab.MINE, "我的", Icons.Outlined.PersonOutline),
                ).forEach { (tab, label, icon) ->
                    val isSelected = active == tab
                    val color = if (isSelected) AccentInk else secondary
                    Column(Modifier.weight(1f).heightIn(min = 64.dp)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = {
                            keyboard?.hide()
                            focus.clearFocus()
                            onSelect(if (tab == AppTab.FAVORITES) AppTab.HISTORY else tab)
                        }).testTag("nav_${tab.name.lowercase(Locale.ROOT)}")
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label, color = color, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.width(14.dp).height(2.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (isSelected) Orange else Color.Transparent))
                    }
                }
            }
        }
    }
}
