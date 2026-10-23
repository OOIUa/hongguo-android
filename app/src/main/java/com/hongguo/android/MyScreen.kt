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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MyScreen(state: HongguoUiState, model: HongguoViewModel,
                      updateState: UpdateUiState, updateModel: UpdateViewModel) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    if (settingsOpen) {
        SystemSettingsScreen(state, model, updateState, updateModel) { settingsOpen = false }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("my_screen").padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("我的", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink,
                modifier = Modifier.padding(top = 16.dp))
            Text("好故事，留给下次继续", fontSize = 13.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFFFFEEE5)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(18.dp)).background(Orange), Alignment.Center) {
                        Text("果", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("我的观影空间", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("收藏与记录，随时接着看", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                MyStat("我的收藏", state.favorites.size, Modifier.weight(1f).testTag("my_favorites")) {
                    model.selectTab(AppTab.FAVORITES)
                }
                MyStat("看过的剧", state.history.size, Modifier.weight(1f).testTag("my_history_count")) {
                    model.selectTab(AppTab.HISTORY)
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
                Column {
                    MyMenuRow("播放历史", "继续上次看到的地方", "my_history") { model.selectTab(AppTab.HISTORY) }
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("系统设置", "播放偏好与更新设置", "my_settings") { settingsOpen = true }
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    MyMenuRow("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", "update_check") {
                        updateModel.checkForUpdates()
                    }
                }
            }
        }
        item {
            Text("红果独立版  ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 28.dp))
        }
    }
}

@Composable
private fun MyStat(label: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(count.toString(), color = Orange, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MyMenuRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Text("›", color = Muted, fontSize = 25.sp)
    }
}

@Composable
private fun SystemSettingsScreen(state: HongguoUiState, model: HongguoViewModel,
                                 updateState: UpdateUiState, updateModel: UpdateViewModel, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("settings_screen").padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text("‹ 我的", color = Muted) }
                Spacer(Modifier.weight(1f))
                Text("系统设置", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Text("播放偏好", color = Muted, fontSize = 12.sp)
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.padding(top = 10.dp)) {
                Column {
                    SettingSwitch("自动连播下一集", "本集结束后继续播放，仍可随时上下滑动切集", state.autoPlayNext,
                        "settings_autoplay", model::setAutoPlayNext)
                    HorizontalDivider(color = Canvas, modifier = Modifier.padding(horizontal = 18.dp))
                    SettingSwitch("下一集预加载", "提前准备少量内容，关闭可减少预先消耗的流量", state.preloadNext,
                        "settings_preload", model::setPreloadNext)
                }
            }
        }
        item {
            Text("软件更新", color = Muted, fontSize = 12.sp)
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.padding(top = 10.dp)) {
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
    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}
