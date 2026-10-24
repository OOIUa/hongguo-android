package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MyScreen(state: HongguoUiState, model: HongguoViewModel,
                      updateState: UpdateUiState, updateModel: UpdateViewModel,
                      accountName: String? = null, accountStatus: String = "登录后查看手机片单",
                      onAccount: () -> Unit = { model.selectTab(AppTab.ACCOUNT) }) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    if (settingsOpen) {
        SystemSettingsScreen(state, model, updateState, updateModel) { settingsOpen = false }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("我的", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
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
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                Column {
                    MyMenuRow("账号与隐私", "登录方式、云片单与账号管理", "my_account", onAccount)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("播放历史", "继续上次看到的地方", "my_history") { model.selectTab(AppTab.HISTORY) }
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("播放设置", "自动连播、预加载与更新偏好", "my_settings") { settingsOpen = true }
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
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, fontSize = 14.sp)
            Text(count.toString(), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MyMenuRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = Muted)
    }
}

@Composable
private fun SystemSettingsScreen(state: HongguoUiState, model: HongguoViewModel,
                                 updateState: UpdateUiState, updateModel: UpdateViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_screen").padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回我的", tint = Ink)
                }
                Text("播放设置", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("播放偏好", color = Muted, fontSize = 14.sp)
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.padding(top = 12.dp)) {
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
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.padding(top = 12.dp)) {
                SettingSwitch("启动时检查更新", "每天最多检查一次，下载和安装仍需确认", updateState.automaticChecksEnabled,
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
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
