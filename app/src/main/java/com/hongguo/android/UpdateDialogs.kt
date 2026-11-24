package com.hongguo.android

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

internal data class ReleaseNoteSection(
    val title: String?,
    val items: List<String>,
)

/** Parses Markdown changelog headings (### 新增 / ### 优化 / ### 修复) into structured display groups. */
internal fun parseUpdateNotes(raw: String): List<ReleaseNoteSection> {
    if (raw.isBlank()) return emptyList()
    val sections = mutableListOf<ReleaseNoteSection>()
    var currentTitle: String? = null
    val currentItems = mutableListOf<String>()

    fun flush() {
        if (currentItems.isNotEmpty()) {
            sections += ReleaseNoteSection(currentTitle, currentItems.toList())
            currentItems.clear()
        }
    }

    raw.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        if (trimmed.startsWith("#")) {
            flush()
            currentTitle = trimmed.trimStart('#').trim().ifEmpty { null }
        } else {
            val cleaned = trimmed
                .removePrefix("- ")
                .removePrefix("* ")
                .removePrefix("• ")
                .trim()
            if (cleaned.isNotEmpty()) currentItems += cleaned
        }
    }
    flush()
    return sections
}

private fun updateSourceShortLabel(source: UpdateSource): String = when (source) {
    UpdateSource.AUTO -> "自动"
    UpdateSource.DIRECT -> "直连"
    UpdateSource.PROXY_PRIMARY -> "加速1"
    UpdateSource.PROXY_BACKUP -> "加速2"
}

/** Installation and download remain owned by the update model, including return from Settings. */
@Composable
internal fun UpdateDialogs(state: UpdateUiState, model: UpdateViewModel, activity: Activity, deferred: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onResume(activity)
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.onResume(activity)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!state.visible || deferred) return

    var showErrorDetails by rememberSaveable(state.error) { mutableStateOf(false) }
    var routePickerExpanded by rememberSaveable { mutableStateOf(false) }
    val showRouteBar = routePickerExpanded || state.error != null || (state.update == null && !state.downloading)
    val update = state.update
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val bodyMaxHeight = (screenHeight * 0.48f).coerceIn(140f, 380f).dp
    val sections = remember(update?.notes) { parseUpdateNotes(update?.notes.orEmpty()) }

    Dialog(
        onDismissRequest = { if (!state.downloading) model.dismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !state.downloading,
            dismissOnClickOutside = !state.downloading,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.50f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !state.downloading,
                    onClick = model::dismiss,
                )
                .semantics { testTagsAsResourceId = true },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = CardSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = (screenHeight * 0.88f).dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .testTag("update_dialog"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Line),
                    )

                    // Header: version milestone + close circle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (update != null) "v${update.versionName}" else "检查更新",
                                    color = Ink,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                if (update != null) {
                                    Surface(shape = CircleShape, color = SoftOrange) {
                                        Text(
                                            text = "新版本",
                                            color = AccentInk,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "当前 v${BuildConfig.VERSION_NAME} · 红果独立版 Android",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                        if (!state.downloading) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Cream)
                                    .testTag("update_close")
                                    .clickable(onClick = model::dismiss),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp), tint = Muted)
                            }
                        }
                    }

                    // 3-Column Meta Pills Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        UpdateMetaPill(
                            label = "安装包",
                            value = if (update != null && update.sizeBytes > 0) updateSize(update.sizeBytes) else "—",
                            modifier = Modifier.weight(1f),
                        )
                        val routeActive = showRouteBar
                        val pillShape = RoundedCornerShape(14.dp)
                        Surface(
                            shape = pillShape,
                            color = if (routeActive) SoftOrange else Canvas,
                            modifier = Modifier
                                .weight(1f)
                                .clip(pillShape)
                                .border(
                                    width = 1.dp,
                                    color = if (routeActive) AccentBorder else Color.Transparent,
                                    shape = pillShape,
                                )
                                .testTag("update_route_toggle")
                                .clickable(enabled = !state.downloading) { routePickerExpanded = !routePickerExpanded },
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text("线路 ▾", color = if (routeActive) AccentInk else Muted, fontSize = 11.sp)
                                Text(
                                    text = updateSourceShortLabel(state.source),
                                    color = if (routeActive) AccentInk else Ink,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        UpdateMetaPill(
                            label = "校验",
                            value = if (state.downloadedFile != null) "通过 ✓" else "SHA-256",
                            valueColor = if (state.downloadedFile != null) StatusOk else Ink,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Expandable 4-segment route picker
                    AnimatedVisibility(
                        visible = showRouteBar,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Cream)
                                    .padding(4.dp)
                                    .selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                UpdateSource.entries.forEach { source ->
                                    val selected = state.source == source
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) CardSurface else Color.Transparent)
                                            .selectable(
                                                selected = selected,
                                                enabled = !state.downloading,
                                                role = Role.RadioButton,
                                            ) {
                                                model.selectSource(source)
                                                routePickerExpanded = false
                                            }
                                            .testTag("update_source_${source.name.lowercase(Locale.ROOT)}"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = updateSourceShortLabel(source),
                                            color = if (selected) AccentInk else Muted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                            val routeHint = state.activeSource?.let { "当前命中：${it.label}" }
                                ?: if (state.source == UpdateSource.AUTO) "直连失败时自动回退两条国内加速线路" else "已指定 ${state.source.label}"
                            Text(routeHint, color = Muted, fontSize = 11.sp)
                        }
                    }

                    // Scrollable body: checking / changelog / status / permission / error
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = bodyMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (state.checking) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Canvas,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp, horizontal = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Orange)
                                    Text(
                                        text = "正在通过${(state.activeSource ?: state.source).label}检查版本…",
                                        color = Ink,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        } else if (update != null && sections.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Canvas,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    sections.forEach { section ->
                                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            section.title?.let { heading ->
                                                val (bg, fg) = when {
                                                    heading.contains("新增") -> SoftOrange to AccentInk
                                                    heading.contains("优化") -> StatusBlueSoft to StatusBlue
                                                    heading.contains("修复") -> StatusOkSoft to StatusOk
                                                    else -> Cream to Ink
                                                }
                                                Surface(shape = RoundedCornerShape(6.dp), color = bg) {
                                                    Text(
                                                        text = heading,
                                                        color = fg,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    )
                                                }
                                            }
                                            section.items.forEach { itemText ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                                    verticalAlignment = Alignment.Top,
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .padding(top = 7.dp)
                                                            .size(4.dp)
                                                            .clip(CircleShape)
                                                            .background(Muted),
                                                    )
                                                    Text(
                                                        text = itemText,
                                                        color = Ink.copy(alpha = 0.9f),
                                                        fontSize = 13.sp,
                                                        lineHeight = 20.sp,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (update == null && state.error == null && state.status.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Canvas,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = StatusOk)
                                    Text(state.status, color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
                                }
                            }
                        }

                        if (state.installPermissionRequired) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = StatusWarnSoft,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        null,
                                        Modifier.padding(top = 1.dp).size(17.dp),
                                        tint = StatusWarn,
                                    )
                                    Text(
                                        text = "需授权「安装未知应用」，允许后返回本页即可自动继续安装。",
                                        color = StatusWarn,
                                        fontSize = 12.sp,
                                        lineHeight = 19.sp,
                                    )
                                }
                            }
                        }

                        if (state.error != null) {
                            val fallbackSource = if (state.source == UpdateSource.PROXY_PRIMARY) {
                                UpdateSource.PROXY_BACKUP
                            } else {
                                UpdateSource.PROXY_PRIMARY
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = StatusWarnSoft,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = StatusWarn)
                                        Text(
                                            text = state.status.ifBlank { "当前线路暂时无法完成更新" },
                                            color = StatusWarn,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = StatusWarn,
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable {
                                                    model.selectSource(fallbackSource)
                                                    if (update != null) model.downloadUpdate()
                                                },
                                        ) {
                                            Text(
                                                text = "改用${fallbackSource.label}重试",
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            )
                                        }
                                        Text(
                                            text = if (showErrorDetails) "收起详情" else "查看详情",
                                            color = StatusWarn,
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showErrorDetails = !showErrorDetails }
                                                .padding(horizontal = 4.dp, vertical = 4.dp),
                                        )
                                    }
                                    if (showErrorDetails) {
                                        Text(
                                            text = state.error,
                                            color = StatusWarn.copy(alpha = 0.88f),
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Thumb-zone Footer (Button-as-Progress during download)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            state.downloading -> {
                                val rawProgress = state.downloadProgress?.coerceIn(0f, 1f)
                                val animatedProgress by animateFloatAsState(
                                    targetValue = rawProgress ?: 0f,
                                    animationSpec = tween(160),
                                    label = "update_download_progress",
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .clip(CircleShape)
                                            .background(SoftOrange),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (rawProgress != null) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.CenterStart)
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(animatedProgress)
                                                    .background(Orange),
                                            )
                                            val pct = (rawProgress * 100).toInt()
                                            val sizeNote = if (update != null && update.sizeBytes > 0) {
                                                val done = (update.sizeBytes * rawProgress).toLong()
                                                " · ${updateSize(done)}/${updateSize(update.sizeBytes)}"
                                            } else ""
                                            Text(
                                                text = "下载中 $pct%$sizeNote",
                                                color = if (rawProgress > 0.45f) OnOrange else AccentInk,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxSize(),
                                                color = Orange.copy(alpha = 0.25f),
                                                trackColor = SoftOrange,
                                            )
                                            Text(
                                                text = "正在下载更新…",
                                                color = AccentInk,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(Cream)
                                            .testTag("update_cancel")
                                            .clickable(onClick = model::cancelDownload),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "取消下载", modifier = Modifier.size(18.dp), tint = Ink)
                                    }
                                }
                                Text(
                                    text = "当前命中：${(state.activeSource ?: state.source).label} · 完成后自动校验 SHA-256",
                                    color = Muted,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            state.checking -> {
                                UpdatePrimaryPillButton(
                                    label = "后台继续检查",
                                    containerColor = Cream,
                                    contentColor = Ink,
                                    tag = "update_cancel",
                                    onClick = model::dismiss,
                                )
                            }

                            state.installPermissionRequired -> {
                                UpdatePrimaryPillButton(
                                    label = "去授权并继续安装",
                                    containerColor = Orange,
                                    contentColor = OnOrange,
                                    tag = "update_permission",
                                    onClick = { model.openInstaller(activity) },
                                )
                            }

                            state.downloadedFile != null -> {
                                UpdatePrimaryPillButton(
                                    label = "校验通过 · 立即安装",
                                    containerColor = StatusOk,
                                    contentColor = Color.White,
                                    tag = "update_install",
                                    leadingIcon = Icons.Outlined.VerifiedUser,
                                    onClick = { model.openInstaller(activity) },
                                )
                            }

                            state.error != null -> {
                                UpdatePrimaryPillButton(
                                    label = if (update != null) "重新下载" else "重试",
                                    containerColor = Orange,
                                    contentColor = OnOrange,
                                    tag = "update_retry",
                                    onClick = { if (update != null) model.downloadUpdate() else model.checkForUpdates() },
                                )
                            }

                            update != null -> {
                                UpdatePrimaryPillButton(
                                    label = "立即下载更新",
                                    containerColor = Orange,
                                    contentColor = OnOrange,
                                    tag = "update_download",
                                    onClick = model::downloadUpdate,
                                )
                            }

                            else -> {
                                UpdatePrimaryPillButton(
                                    label = "关闭",
                                    containerColor = Cream,
                                    contentColor = Ink,
                                    tag = "update_cancel",
                                    onClick = model::dismiss,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateMetaPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Canvas,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, color = Muted, fontSize = 11.sp)
            Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun UpdatePrimaryPillButton(
    label: String,
    containerColor: Color,
    contentColor: Color,
    tag: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .testTag(tag)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, null, Modifier.size(18.dp), tint = contentColor)
            }
            Text(label, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun updateSize(size: Long): String = if (size >= 1024 * 1024)
    String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0)) else "${size / 1024} KB"
