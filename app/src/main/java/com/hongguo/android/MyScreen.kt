package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PAGE_SETTINGS = "settings"
private const val PAGE_THEME = "theme"

@Composable
internal fun MyScreen(state: HongguoUiState, model: HongguoViewModel,
                      updateState: UpdateUiState, updateModel: UpdateViewModel,
                      accountName: String? = null, accountStatus: String = "登录后查看手机片单",
                      style: AppThemeStyle = AppThemeStyle.WARM,
                      onAccount: () -> Unit = { model.selectTab(AppTab.ACCOUNT) }) {
    // "" = 我的, PAGE_SETTINGS = 设置, PAGE_THEME = 主题样式. Saved so rotation keeps the page.
    var page by rememberSaveable { mutableStateOf("") }
    // The theme page can be opened from 设置 or (沉浸暗场) straight from 我的; back returns to where it came from.
    var themeReturn by rememberSaveable { mutableStateOf(PAGE_SETTINGS) }
    BackHandler(enabled = page.isNotEmpty() && LocalPageActive.current) { page = if (page == PAGE_THEME) themeReturn else "" }
    when (page) {
        PAGE_SETTINGS -> {
            SystemSettingsScreen(state, model, updateState, updateModel,
                onTheme = { themeReturn = PAGE_SETTINGS; page = PAGE_THEME }, onBack = { page = "" })
            return
        }
        PAGE_THEME -> {
            ThemeStyleScreen(state, model) { page = themeReturn }
            return
        }
    }
    val openSettings = { page = PAGE_SETTINGS }
    when (style) {
        AppThemeStyle.IMMERSIVE -> {
            ImmersiveMine(state, model, updateModel, accountName, accountStatus, onAccount, openSettings) { themeReturn = ""; page = PAGE_THEME }
            return
        }
        AppThemeStyle.CARDS -> {
            CardsMine(state, model, updateModel, accountName, accountStatus, onAccount, openSettings)
            return
        }
        AppThemeStyle.WARM -> Unit
    }
    LazyColumn(Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 20.dp),
        contentPadding = PaddingValues(0.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                IconButton(onClick = openSettings, modifier = Modifier.testTag("my_settings_icon")) {
                    Icon(Icons.Outlined.Settings, "设置", tint = Ink)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                Row(Modifier.fillMaxWidth().testTag("my_account_card").clickable(onClick = onAccount).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Cream), Alignment.Center) {
                        Icon(Icons.Outlined.PersonOutline, null, Modifier.size(28.dp), tint = Muted)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(accountName ?: "登录红果账号", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(accountStatus, color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = Muted)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MyStat("本机收藏", state.favorites.size, Modifier.weight(1f).testTag("my_favorites")) {
                    model.selectTab(AppTab.FAVORITES)
                }
                MyStat("本机看过", state.history.size, Modifier.weight(1f).testTag("my_history_count")) {
                    model.selectTab(AppTab.HISTORY)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                Column {
                    MyMenuRow("账号与隐私", "登录方式、云片单与账号管理", "my_account", onClick = onAccount)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("播放历史", "继续上次看到的地方", "my_history") { model.selectTab(AppTab.HISTORY) }
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("设置", "主题样式、自动连播、预加载与更新", "my_settings",
                        trailing = state.themeStyle.label, onClick = openSettings)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", "update_check") {
                        updateModel.checkForUpdates()
                    }
                }
            }
        }
        item {
            Text("本机收藏与观看记录仅保存在这台设备。", color = Muted, fontSize = 12.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp))
            Text("红果独立版 ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun MyStat(label: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = CardSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, fontSize = 14.sp)
            Text(count.toString(), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MyMenuRow(title: String, subtitle: String, tag: String, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        if (trailing != null) Text(trailing, color = Muted, fontSize = 13.sp, maxLines = 1)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = Muted)
    }
}

@Composable
private fun PageHeader(title: String, backLabel: String, backTag: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag(backTag)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, tint = Ink)
        }
        Text(title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SystemSettingsScreen(state: HongguoUiState, model: HongguoViewModel,
                                 updateState: UpdateUiState, updateModel: UpdateViewModel,
                                 onTheme: () -> Unit, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_screen").padding(horizontal = 20.dp),
        contentPadding = PaddingValues(0.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("设置", "返回我的", "settings_back", onBack) }
        item {
            Text("外观", color = Muted, fontSize = 14.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.padding(top = 12.dp)) {
                Column {
                    MyMenuRow("主题样式", "暖白精修、沉浸暗场、内容卡片", "settings_theme",
                        trailing = state.themeStyle.label, onClick = onTheme)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    NavBarStyleSetting(state.navBarStyle, model::setNavBarStyle)
                }
            }
        }
        item {
            Text("播放偏好", color = Muted, fontSize = 14.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.padding(top = 12.dp)) {
                Column {
                    SettingSwitch("自动连播下一集", "本集结束后继续播放，也可上下滑动切集", state.autoPlayNext,
                        "settings_autoplay", model::setAutoPlayNext)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    SettingSwitch("下一集预加载", "提前准备少量内容，关闭可节省流量", state.preloadNext,
                        "settings_preload", model::setPreloadNext)
                }
            }
        }
        item {
            Text("软件更新", color = Muted, fontSize = 14.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = Modifier.padding(top = 12.dp)) {
                SettingSwitch("启动时检查更新", "每次重新启动应用时检查，下载和安装仍需确认", updateState.automaticChecksEnabled,
                    "settings_auto_update", updateModel::setAutomaticChecksEnabled)
            }
        }
        item {
            Text("收藏、播放记录和设置保存在本机。卸载应用或清除应用数据后，这些记录会被移除。",
                color = Muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 28.dp))
        }
    }
}

@Composable
private fun ThemeStyleScreen(state: HongguoUiState, model: HongguoViewModel, onBack: () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val overridden = state.darkFollowsSystem && systemDark && state.themeStyle != AppThemeStyle.IMMERSIVE
    LazyColumn(Modifier.fillMaxSize().testTag("theme_screen").padding(horizontal = 20.dp),
        contentPadding = PaddingValues(0.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeader("主题样式", "返回设置", "theme_back", onBack) }
        item {
            Text("选择喜欢的界面风格，点选后立即生效。收藏、历史与播放设置不受影响。",
                color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
        }
        item {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeStyle.entries.forEach { style ->
                    ThemeOption(style, selected = state.themeStyle == style, modifier = Modifier.weight(1f)) {
                        model.setThemeStyle(style)
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(state.themeStyle.palette().accent))
                        Text(state.themeStyle.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(state.themeStyle.summary, color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    if (overridden) Text("系统当前为深色模式，正在临时使用「沉浸暗场」。", color = AccentText,
                        fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.testTag("theme_dark_override_note"))
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = CardSurface) {
                SettingSwitch("深色模式时自动切换", "系统进入深色模式时使用「沉浸暗场」，退出后恢复所选主题",
                    state.darkFollowsSystem, "settings_theme_dark_follow", model::setDarkFollowsSystem)
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
internal fun ThemeOption(style: AppThemeStyle, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val ring = if (selected) Orange else Color.Transparent
    Column(modifier.clip(RoundedCornerShape(16.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
        .semantics { contentDescription = "${style.label}主题" }
        .testTag("theme_option_${style.key}")
        .border(2.dp, ring, RoundedCornerShape(16.dp))
        .background(CardSurface)
        .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemePreview(style)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            if (selected) Box(Modifier.size(18.dp).clip(CircleShape).background(Orange), Alignment.Center) {
                Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = OnOrange)
            } else Box(Modifier.size(18.dp).padding(1.dp).border(2.dp, Muted, CircleShape))
            // Wraps instead of clipping on narrow screens or large font scales.
            Text(style.label, color = Ink, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

private val PreviewTones = listOf(Color(0xFF7B3B2C), Color(0xFF8A4F62), Color(0xFF5B4636), Color(0xFF3E4A61),
    Color(0xFF2F5D50), Color(0xFF7A5C2E), Color(0xFF4B3F6B))

/** A miniature of each theme's home screen, always drawn in that theme's own colors. */
@Composable
private fun ThemePreview(style: AppThemeStyle) {
    val p = style.palette()
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(p.canvas)
        .border(1.dp, p.line, RoundedCornerShape(10.dp))) {
        when (style) {
            AppThemeStyle.IMMERSIVE -> {
                Box(Modifier.fillMaxSize().background(PreviewTones[0]))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
                Column(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = .85f))) }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(Color.White))
                    Box(Modifier.width(30.dp).height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = .6f)))
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(12.dp).background(p.canvas))
            }
            AppThemeStyle.WARM -> Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(p.cream))
                Box(Modifier.fillMaxWidth().height(26.dp).clip(RoundedCornerShape(4.dp)).background(PreviewTones[5]))
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(3) { col ->
                            Box(Modifier.weight(1f).aspectRatio(.75f).clip(RoundedCornerShape(2.dp))
                                .background(PreviewTones[(row * 3 + col) % PreviewTones.size]))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                PreviewNav(p)
            }
            AppThemeStyle.CARDS -> Column(Modifier.fillMaxSize().padding(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(p.card))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(p.card).padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.fillMaxWidth(.7f).height(3.dp).background(p.accent))
                    Box(Modifier.fillMaxWidth(.85f).height(3.dp).background(p.line))
                    Box(Modifier.fillMaxWidth(.6f).height(3.dp).background(p.line))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1.4f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[5]))
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[2]))
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[1]))
                        Box(Modifier.fillMaxWidth().weight(1.4f).clip(RoundedCornerShape(3.dp)).background(PreviewTones[6]))
                    }
                }
                PreviewNav(p)
            }
        }
    }
}

@Composable
private fun PreviewNav(p: HongguoPalette) {
    Row(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(2.dp)).background(p.card),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i -> Box(Modifier.size(width = 6.dp, height = 3.dp).clip(CircleShape)
            .background(if (i == 0) p.accent else p.line)) }
    }
}

/** 底栏样式: two-way choice, applied immediately so the bar below changes while the user watches. */
@Composable
private fun NavBarStyleSetting(selected: NavBarStyle, onSelect: (NavBarStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("底栏样式", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("悬浮玻璃：页面在半透明胶囊下滚动，Android 12 及以上有模糊效果", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Cream).padding(3.dp).selectableGroup()) {
            NavBarStyle.entries.forEach { style ->
                val isSelected = style == selected
                Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) CardSurface else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(style) }
                    .testTag("settings_navbar_${style.key}"), contentAlignment = Alignment.Center) {
                    Text(style.label, color = if (isSelected) Ink else Muted, fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        }
        // Off state uses the secondary text color so the control stays visible on every theme.
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(uncheckedThumbColor = Muted, uncheckedBorderColor = Muted,
                uncheckedTrackColor = Cream))
    }
}

/** B · 沉浸暗场: avatar header, four shortcuts and one grouped list. */
@Composable
private fun ImmersiveMine(state: HongguoUiState, model: HongguoViewModel, updateModel: UpdateViewModel,
                          accountName: String?, accountStatus: String, onAccount: () -> Unit,
                          openSettings: () -> Unit, openTheme: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 16.dp),
        contentPadding = PaddingValues(0.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = openSettings, modifier = Modifier.testTag("my_settings_icon")) {
                    Icon(Icons.Outlined.Settings, "设置", tint = Ink)
                }
            }
            Row(Modifier.fillMaxWidth().clickable(onClick = onAccount).testTag("my_account_card").padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Cream), Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, null, Modifier.size(32.dp), tint = Ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(accountName ?: "登录红果账号", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountStatus, color = Muted, fontSize = 13.sp)
                }
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(22.dp), tint = Muted)
            }
        }
        item {
            Surface(color = CardSurface, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    listOf(
                        Triple(Icons.Outlined.History, "历史 ${state.history.size}") { model.selectTab(AppTab.HISTORY) },
                        Triple(Icons.Outlined.StarBorder, "追剧 ${state.favorites.size}") { model.selectTab(AppTab.FAVORITES) },
                        Triple(Icons.Outlined.CloudQueue, "云片单") { model.selectTab(AppTab.FAVORITES); model.selectLibrarySource(true) },
                        Triple(Icons.Outlined.Palette, "主题", openTheme),
                    ).forEachIndexed { index, (icon, label, action) ->
                        Column(Modifier.weight(1f).clickable(onClick = action).heightIn(min = 56.dp).testTag("my_shortcut_$index"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(icon, null, tint = Ink, modifier = Modifier.size(24.dp))
                            Text(label, color = Ink, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        item {
            Surface(color = CardSurface, shape = RoundedCornerShape(16.dp)) {
                Column {
                    MyMenuRow("账号与隐私", "登录方式、云片单与账号管理", "my_account", onClick = onAccount)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("设置", "主题、播放与更新", "my_settings", trailing = state.themeStyle.label, onClick = openSettings)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", "update_check") { updateModel.checkForUpdates() }
                }
            }
        }
        item {
            Text("红果独立版 ${BuildConfig.VERSION_NAME} · 本机数据仅保存在这台设备", color = Muted, fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
        }
    }
}

/** C · 内容卡片: compact header, two stat cards and two grouped cards. */
@Composable
private fun CardsMine(state: HongguoUiState, model: HongguoViewModel, updateModel: UpdateViewModel,
                      accountName: String?, accountStatus: String, onAccount: () -> Unit, openSettings: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 12.dp),
        contentPadding = PaddingValues(0.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = openSettings, modifier = Modifier.testTag("my_settings_icon")) {
                    Icon(Icons.Outlined.Settings, "设置", tint = Ink)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(CardSurface).border(1.dp, Line, CircleShape), Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, null, Modifier.size(28.dp), tint = Muted)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(accountName ?: "未登录", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Black,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(accountStatus, color = Muted, fontSize = 13.sp)
                }
                Surface(onClick = onAccount, shape = RoundedCornerShape(50), color = Ink,
                    modifier = Modifier.heightIn(min = 36.dp).testTag("my_account_card")) {
                    Text(if (accountName == null) "登录" else "账号", color = Canvas, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("本机看过", state.history.size, AppTab.HISTORY), Triple("本机收藏", state.favorites.size, AppTab.FAVORITES))
                    .forEach { (label, count, tab) ->
                        Surface(onClick = { model.selectTab(tab) }, color = CardSurface, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f).testTag(if (tab == AppTab.HISTORY) "my_history_count" else "my_favorites")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, color = Muted, fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("$count", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black)
                                    Text(" 部", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                                }
                            }
                        }
                    }
            }
        }
        item {
            Surface(color = CardSurface, shape = RoundedCornerShape(14.dp)) {
                Column {
                    MyMenuRow("播放历史", "继续上次看到的地方", "my_history") { model.selectTab(AppTab.HISTORY) }
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("云片单", "账号收藏、历史与预约", "my_cloud") { model.selectTab(AppTab.FAVORITES); model.selectLibrarySource(true) }
                }
            }
        }
        item {
            Surface(color = CardSurface, shape = RoundedCornerShape(14.dp)) {
                Column {
                    MyMenuRow("设置", "主题样式、自动连播、预加载与更新", "my_settings", trailing = state.themeStyle.label, onClick = openSettings)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("账号与隐私", "登录方式与账号管理", "my_account", onClick = onAccount)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", "update_check") { updateModel.checkForUpdates() }
                }
            }
        }
        item {
            Text("本机收藏与观看记录仅保存在这台设备。", color = Muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 24.dp))
        }
    }
}
