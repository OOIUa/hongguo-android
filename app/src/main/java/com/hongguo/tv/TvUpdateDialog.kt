package com.hongguo.tv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hongguo.android.*
import java.util.Locale

private val tvDialogSurface = Color(0xFF211E22)
private val tvDialogBorder = Color(0xFF423A3E)
private val tvDialogMuted = Color(0xFFD0C6CA)
private val tvDialogAccent = Color(0xFFFFC6B4)
private val tvDialogWarn = Color(0xFFFFB48A)

/** D-pad friendly update dialog for TV, reusing UpdateViewModel and the same release manifest. */
@Composable
internal fun TvUpdateDialog(state: UpdateUiState, model: UpdateViewModel, activity: Activity) {
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

    val update = state.update
    val sections = remember(update?.notes) { parseUpdateNotes(update?.notes.orEmpty()) }
    val primaryFocus = remember { FocusRequester() }
    val focusKey = Triple(state.downloading, state.downloadedFile != null, state.error != null)

    Dialog(
        onDismissRequest = { if (!state.downloading) model.dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !state.downloading,
            dismissOnClickOutside = !state.downloading,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BackHandler(enabled = !state.downloading) { model.dismiss() }
        LaunchedEffect(focusKey, state.checking, state.installPermissionRequired, update?.versionCode) {
            withFrameNanos { }
            runCatching { primaryFocus.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 48.dp, vertical = 28.dp)
                .testTag("tv_update_dialog"),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(tvDialogSurface)
                    .border(1.dp, tvDialogBorder, shape)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (update != null) "发现新版本 v${update.versionName}" else "检查更新",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        val meta = buildList {
                            add("当前 v${BuildConfig.VERSION_NAME}")
                            if (update != null && update.sizeBytes > 0) add(formatTvUpdateSize(update.sizeBytes))
                            add("线路：${(state.activeSource ?: state.source).label}")
                        }.joinToString(" · ")
                        Text(meta, color = tvDialogMuted, fontSize = 13.sp)
                    }
                    TvButton(
                        label = "线路：${state.source.label}",
                        onClick = {
                            val entries = UpdateSource.entries
                            val next = entries[(entries.indexOf(state.source) + 1) % entries.size]
                            model.selectSource(next)
                        },
                        enabled = !state.downloading,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.checking) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tvDialogAccent)
                            Text(state.status.ifBlank { "正在检查新版本…" }, color = Color.White, fontSize = 15.sp)
                        }
                    } else if (update != null && sections.isNotEmpty()) {
                        sections.forEach { section ->
                            section.title?.let { heading ->
                                Text(heading, color = tvDialogAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            section.items.forEach { item ->
                                Text("• $item", color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    } else if (state.status.isNotBlank()) {
                        Text(state.status, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
                    }

                    if (state.downloading) {
                        val progress = state.downloadProgress?.coerceIn(0f, 1f)
                        if (progress != null) {
                            val pct = (progress * 100).toInt()
                            val detail = if (update != null && update.sizeBytes > 0) {
                                val done = (update.sizeBytes * progress).toLong()
                                "（${formatTvUpdateSize(done)} / ${formatTvUpdateSize(update.sizeBytes)}）"
                            } else ""
                            Text("正在下载更新 $pct%$detail", color = Color.White, fontSize = 14.sp)
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text("正在下载更新…", color = Color.White, fontSize = 14.sp)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (state.installPermissionRequired) {
                        Text(
                            text = "需要授权「安装未知应用」，允许后返回本页即可继续安装；部分电视系统需在系统设置中手动开启。",
                            color = tvDialogWarn,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }

                    state.error?.let { err ->
                        Text(err, color = tvDialogWarn, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        state.downloading -> {
                            TvButton("取消下载", model::cancelDownload, Modifier.focusRequester(primaryFocus))
                        }
                        state.checking -> {
                            TvButton("后台继续检查", model::dismiss, Modifier.focusRequester(primaryFocus))
                        }
                        state.installPermissionRequired -> {
                            TvButton(
                                "去授权并继续安装",
                                { model.openInstaller(activity) },
                                Modifier.focusRequester(primaryFocus),
                                selected = true,
                            )
                            TvButton("关闭", model::dismiss)
                        }
                        state.downloadedFile != null -> {
                            TvButton(
                                "校验通过 · 立即安装",
                                { model.openInstaller(activity) },
                                Modifier.focusRequester(primaryFocus),
                                selected = true,
                            )
                            TvButton("关闭", model::dismiss)
                        }
                        state.error != null -> {
                            val fallback = if (state.source == UpdateSource.PROXY_PRIMARY) {
                                UpdateSource.PROXY_BACKUP
                            } else {
                                UpdateSource.PROXY_PRIMARY
                            }
                            TvButton(
                                if (update != null) "重新下载" else "重试",
                                { if (update != null) model.downloadUpdate() else model.checkForUpdates() },
                                Modifier.focusRequester(primaryFocus),
                                selected = true,
                            )
                            TvButton("改用${fallback.label}", {
                                model.selectSource(fallback)
                                if (update != null) model.downloadUpdate()
                            })
                            TvButton("关闭", model::dismiss)
                        }
                        update != null -> {
                            TvButton(
                                "立即下载更新",
                                model::downloadUpdate,
                                Modifier.focusRequester(primaryFocus).testTag("tv_update_download"),
                                selected = true,
                            )
                            TvButton("稍后再说", model::dismiss)
                        }
                        else -> {
                            TvButton("关闭", model::dismiss, Modifier.focusRequester(primaryFocus), selected = true)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTvUpdateSize(size: Long): String = if (size >= 1024 * 1024)
    String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0)) else "${size / 1024} KB"
