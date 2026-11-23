// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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

/** 弹幕开关与设置的图标入口，尺寸刚好贴在倍速左边，横竖屏一致。 */
@Composable
internal fun DanmakuControl(enabled: Boolean, onToggle: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
            .background(if (enabled) Orange.copy(alpha = .22f) else Color.White.copy(alpha = .10f))
            .clickable(onClickLabel = if (enabled) "关闭弹幕" else "开启弹幕", onClick = onToggle)
            .testTag("player_danmaku")
            .semantics { contentDescription = if (enabled) "关闭弹幕" else "开启弹幕" },
            contentAlignment = Alignment.Center) {
            DanmakuGlyph(enabled = enabled, tint = if (enabled) Orange else Color.White.copy(alpha = .6f),
                modifier = Modifier.size(22.dp))
        }
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
            .clickable(onClickLabel = "弹幕设置", onClick = onSettings)
            .testTag("player_danmaku_settings")
            .semantics { contentDescription = "弹幕设置" },
            contentAlignment = Alignment.Center) {
            DanmakuGlyph(enabled = true, tint = Color.White.copy(alpha = .82f), settings = true,
                modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * 参数面板。开关独占最上一行，往下四行都是「左边名称、右边一整条分段按钮」，行与行之间只留
 * 一条窄缝，整个面板内容在一屏里放得下，不用滚动也不用滑杆。屏占比决定弹幕层的高度，行数由
 * 字号和这块高度自动换算，所以这里不再单独给行数选项。
 */
@Composable
internal fun DanmakuSettingsPanel(
    settings: DanmakuSettings,
    onChange: (DanmakuSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("player_danmaku_panel"),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("弹幕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) },
                modifier = Modifier.scale(.85f),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Orange,
                    uncheckedThumbColor = Color.White.copy(alpha = .8f),
                    uncheckedTrackColor = Color.White.copy(alpha = .22f),
                    uncheckedBorderColor = Color.White.copy(alpha = .35f)))
        }
        DanmakuChoiceRow("屏幕占比", DanmakuPrefs.COVERAGE_LABELS, settings.coverage, settings.enabled) {
            onChange(settings.copy(coverage = it))
        }
        DanmakuChoiceRow("字体大小", DanmakuPrefs.FONT_LABELS, settings.font, settings.enabled) {
            onChange(settings.copy(font = it))
        }
        // 存的是百分比，按钮按下标排，这里把两者对上，才不会出现「一格都不亮」的情况。
        DanmakuChoiceRow("不透明度", DanmakuPrefs.OPACITY_LABELS, DanmakuPrefs.opacityIndex(settings.opacity),
            settings.enabled) {
            onChange(settings.copy(opacity = DanmakuPrefs.OPACITY_STEPS[it]))
        }
        DanmakuChoiceRow("文字效果", DanmakuPrefs.EFFECT_LABELS, settings.effect, settings.enabled) {
            onChange(settings.copy(effect = it))
        }
        DanmakuChoiceRow("弹幕密度", DanmakuPrefs.DENSITY_LABELS, settings.density, settings.enabled) {
            onChange(settings.copy(density = it))
        }
        DanmakuChoiceRow("移动速度", DanmakuPrefs.SPEED_LABELS, settings.speed, settings.enabled) {
            onChange(settings.copy(speed = it))
        }
        Text("恢复默认设置", color = Color.White.copy(alpha = .7f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "恢复弹幕默认设置") {
                    // 只重置显示参数，开关状态保持用户当前的选择。
                    onChange(DanmakuSettings(enabled = settings.enabled))
                }
                .padding(horizontal = 8.dp, vertical = 5.dp).testTag("player_danmaku_reset"))
    }
}

/** 参数名的固定占位，四条候选条左右两端因此完全对齐。 */
private val LabelWidth = 74.dp

/** 名称在左、候选在右的一行；候选挤在同一个圆角矩形里，靠一条细分隔线分开，中间不留空隙。 */
@Composable
private fun DanmakuChoiceRow(label: String, labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        // 名称占固定宽度，四条候选才会左右两端对齐，不会因为「屏幕占比」少一个字就变长。
        Text(label, color = Color.White.copy(alpha = if (enabled) .9f else .45f), fontSize = 13.sp,
            modifier = Modifier.width(LabelWidth), maxLines = 1)
        Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = .12f)).selectableGroup()) {
            labels.forEachIndexed { index, text ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = .16f)))
                }
                val active = index == selected
                Box(Modifier.weight(1f).fillMaxHeight().testTag("player_danmaku_choice_${label}_$index")
                    .background(if (active) Orange.copy(alpha = .92f) else Color.Transparent)
                    .selectable(selected = active, enabled = enabled, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center) {
                    Text(text, color = when {
                        active -> Color.White
                        enabled -> Color.White.copy(alpha = .72f)
                        else -> Color.White.copy(alpha = .32f)
                    }, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
