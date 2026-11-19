// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.android.danmaku.DanmakuPrefs
import com.hongguo.android.danmaku.DanmakuSettings
import kotlin.math.roundToInt

/** 弹幕 on/off chip plus the panel's own entry, sized to sit beside 倍速 in either orientation. */
@Composable
internal fun DanmakuControl(enabled: Boolean, onToggle: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .14f))
            .clickable(onClickLabel = if (enabled) "关闭弹幕" else "开启弹幕", onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("player_danmaku")
            .semantics { contentDescription = if (enabled) "关闭弹幕" else "开启弹幕" },
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (enabled) "弹幕 开" else "弹幕 关",
                color = if (enabled) Orange else Color.White.copy(alpha = .55f),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(32.dp).testTag("player_danmaku_settings")
            .semantics { contentDescription = "弹幕设置" }) {
            Icon(Icons.Rounded.Tune, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The panel's contents, shared by the portrait bottom sheet and the full-screen side panel.
 * Every change applies to the next frame and persists immediately.
 */
@Composable
internal fun DanmakuSettingsPanel(
    settings: DanmakuSettings,
    onChange: (DanmakuSettings) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("player_danmaku_panel"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("弹幕", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("滚动播放这一集的官方弹幕", color = Color.White.copy(alpha = .62f), fontSize = 12.sp)
            }
            Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Orange,
                    uncheckedThumbColor = Color.White.copy(alpha = .8f),
                    uncheckedTrackColor = Color.White.copy(alpha = .22f),
                    uncheckedBorderColor = Color.White.copy(alpha = .35f)))
        }
        DanmakuSlider("不透明度", "${settings.opacity}%", settings.opacity.toFloat(),
            DanmakuPrefs.OPACITY_MIN.toFloat()..DanmakuPrefs.OPACITY_MAX.toFloat(),
            enabled = settings.enabled) { onChange(settings.copy(opacity = it.roundToInt())) }
        DanmakuChoiceRow("字体大小", DanmakuPrefs.FONT_LABELS, settings.font, settings.enabled) {
            onChange(settings.copy(font = it))
        }
        DanmakuChoiceRow("移动速度", DanmakuPrefs.SPEED_LABELS, settings.speed, settings.enabled) {
            onChange(settings.copy(speed = it))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("展示行数", color = Color.White.copy(alpha = if (settings.enabled) .9f else .45f), fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton(Icons.Rounded.Remove, "减少行数", settings.enabled && settings.rows > DanmakuPrefs.ROWS_MIN) {
                    onChange(settings.copy(rows = settings.rows - 1))
                }
                Text("${settings.rows} 行", color = if (settings.enabled) Color.White else Color.White.copy(alpha = .45f),
                    fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                StepperButton(Icons.Rounded.Add, "增加行数", settings.enabled && settings.rows < DanmakuPrefs.ROWS_MAX) {
                    onChange(settings.copy(rows = settings.rows + 1))
                }
            }
        }
        Text("恢复默认设置", color = Color.White.copy(alpha = .7f), fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable(onClickLabel = "恢复弹幕默认设置", onClick = onReset)
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag("player_danmaku_reset"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DanmakuSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>,
                          enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = if (enabled) .9f else .45f), fontSize = 13.sp)
            Text(valueLabel, color = Color.White.copy(alpha = if (enabled) .62f else .35f), fontSize = 13.sp)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            enabled = enabled, steps = 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Orange,
                inactiveTrackColor = Color.White.copy(alpha = .22f),
                disabledThumbColor = Color.White.copy(alpha = .45f), disabledActiveTrackColor = Orange.copy(alpha = .4f),
                disabledInactiveTrackColor = Color.White.copy(alpha = .12f)))
    }
}

/** Five labelled steps, laid out as equal chips so the current one is obvious at a glance. */
@Composable
private fun DanmakuChoiceRow(label: String, labels: List<String>, current: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, color = Color.White.copy(alpha = if (enabled) .9f else .45f), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { index, text ->
                val selected = index == current
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).heightIn(min = 34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) Orange.copy(alpha = .9f) else Color.White.copy(alpha = .12f))
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center) {
                    Text(text, color = if (selected) Color.White else Color.White.copy(alpha = if (enabled) .75f else .35f),
                        fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)
        .semantics { contentDescription = label }) {
        Icon(icon, null, tint = Color.White.copy(alpha = if (enabled) .85f else .3f), modifier = Modifier.size(18.dp))
    }
}
