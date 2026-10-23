package com.hongguo.android

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

/** Installation and download remain owned by the update model, including return from Settings. */
@Composable
internal fun UpdateDialogs(state: UpdateUiState, model: UpdateViewModel, activity: Activity) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onResume(activity)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.onResume(activity)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!state.visible) return

    var showErrorDetails by rememberSaveable(state.error) { mutableStateOf(false) }
    val update = state.update
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val contentHeight = (screenHeight * .52f).coerceIn(100f, 430f).dp
    Dialog(onDismissRequest = { if (!state.downloading) model.dismiss() },
        properties = DialogProperties(dismissOnBackPress = !state.downloading, dismissOnClickOutside = !state.downloading)) {
        Surface(shape = RoundedCornerShape(23.dp), color = Color.White,
            modifier = Modifier.fillMaxWidth().heightIn(max = (screenHeight * .9f).dp)
                .testTag("update_dialog").semantics { testTagsAsResourceId = true }) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFFFEAE0)), Alignment.Center) {
                        Text("↑", fontSize = 27.sp, color = Orange, fontWeight = FontWeight.Medium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (update != null) "发现新版本" else "检查更新", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("当前版本 ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Muted)
                    }
                }

                Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = contentHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    if (state.checking) {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                            Text("正在检查版本，请稍候…", fontSize = 13.sp, color = Muted)
                        }
                    }

                    if (update != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Cream, shape = RoundedCornerShape(8.dp)) {
                                Text("新版 ${update.versionName}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Orange, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.weight(1f))
                            if (update.sizeBytes > 0) Text(updateSize(update.sizeBytes), fontSize = 11.sp, color = Muted)
                        }
                        if (update.notes.isNotBlank()) {
                            Text("更新内容", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text(update.notes, fontSize = 13.sp, lineHeight = 21.sp, color = Muted)
                        }
                    }

                    if (state.downloading) {
                        HorizontalDivider(color = Cream)
                        val progress = state.downloadProgress?.coerceIn(0f, 1f)
                        Text(if (progress == null) "正在下载更新…" else "正在下载更新 ${(progress * 100).toInt()}%",
                            color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Cream)
                        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Cream)
                        Text("下载完成并验证后，即可安装新版本。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                    }

                    if (state.installPermissionRequired) {
                        Surface(color = Color(0xFFFFEFE6), shape = RoundedCornerShape(12.dp)) {
                            Text("请允许安装此应用的更新。完成设置并返回后，会继续安装。",
                                Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 21.sp, color = Color(0xFF995135))
                        }
                    }

                    if (state.error != null) {
                        Surface(color = Color(0xFFFFEFE6), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text("暂时无法完成更新，请稍后重试。", color = Color(0xFF995135), fontSize = 13.sp, lineHeight = 21.sp)
                                TextButton(onClick = { showErrorDetails = !showErrorDetails }) {
                                    Text(if (showErrorDetails) "收起原因" else "查看原因", fontSize = 11.sp)
                                }
                                if (showErrorDetails) Text(state.error, color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
                            }
                        }
                    } else if (state.status.isNotBlank()) {
                        Text(state.status, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when {
                        state.downloading -> OutlinedButton(onClick = model::cancelDownload,
                            modifier = Modifier.fillMaxWidth().testTag("update_cancel"), shape = RoundedCornerShape(13.dp)) {
                            Text("取消下载")
                        }
                        state.installPermissionRequired -> Button(onClick = { model.openInstaller(activity) },
                            modifier = Modifier.fillMaxWidth().testTag("update_permission"), shape = RoundedCornerShape(13.dp)) {
                            Text("允许安装此应用的更新")
                        }
                        state.downloadedFile != null -> Button(onClick = { model.openInstaller(activity) },
                            modifier = Modifier.fillMaxWidth().testTag("update_install"), shape = RoundedCornerShape(13.dp)) {
                            Text("安装更新")
                        }
                        state.error != null -> Button(onClick = {
                            if (update != null) model.downloadUpdate() else model.checkForUpdates()
                        }, modifier = Modifier.fillMaxWidth().testTag("update_retry"), shape = RoundedCornerShape(13.dp)) {
                            Text("重试")
                        }
                        update != null && !state.checking -> Button(onClick = model::downloadUpdate,
                            modifier = Modifier.fillMaxWidth().testTag("update_download"), shape = RoundedCornerShape(13.dp)) {
                            Text("下载更新")
                        }
                    }
                    if (!state.downloading) TextButton(onClick = model::dismiss,
                        modifier = Modifier.fillMaxWidth().testTag("update_cancel")) {
                        Text(if (update == null) "关闭" else "稍后", color = Muted)
                    }
                }
            }
        }
    }
}

private fun updateSize(size: Long): String = if (size >= 1024 * 1024)
    String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0)) else "${size / 1024} KB"
