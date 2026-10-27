package com.hongguo.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Where users can change the theme later; shown in the picker and in the confirmation message. */
internal const val THEME_SETTINGS_PATH = "「我的 › 设置 › 主题样式」"

internal fun themeAppliedMessage(style: AppThemeStyle, chosen: Boolean): String =
    if (chosen) "已应用「${style.label}」，可在${THEME_SETTINGS_PATH}中随时切换"
    else "已保留「${style.label}」，可在${THEME_SETTINGS_PATH}中随时切换"

/**
 * First-launch theme picker (0.7.3). Tapping a card previews it inside the dialog only; nothing is saved
 * until the user confirms or skips. Back press counts as "暂不选择" so the dialog can never trap the user.
 */
@Composable
internal fun ThemeOnboardingDialog(current: AppThemeStyle, onConfirm: (AppThemeStyle) -> Unit, onSkip: () -> Unit) {
    var pending by rememberSaveable { mutableStateOf(current) }
    Dialog(onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, dismissOnBackPress = true)) {
        HongguoTheme(pending, applyToWindow = false) {
            Surface(shape = RoundedCornerShape(24.dp), color = Canvas,
                modifier = Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth().testTag("theme_onboarding")) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("选择你喜欢的界面风格", color = Ink, fontSize = 21.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("0.7.3 新增三套主题，点选即可预览。之后也可以在${THEME_SETTINGS_PATH}中随时切换。",
                        color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeStyle.entries.forEach { style ->
                            ThemeOption(style, selected = pending == style, modifier = Modifier.weight(1f)) { pending = style }
                        }
                    }
                    Text(pending.summary, color = Muted, fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.testTag("theme_onboarding_summary"))
                    Button(onClick = { onConfirm(pending) },
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = OnOrange),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme_onboarding_confirm")) {
                        Text("使用「${pending.label}」", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)
                        .heightIn(min = 48.dp).testTag("theme_onboarding_skip")) {
                        Text("暂不选择，保持「${current.label}」", color = Muted, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
