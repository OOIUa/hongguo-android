package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.AccountViewModel
import java.util.Locale

@Composable
internal fun LibraryScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaChaseScreen(state = state, model = model, accountModel = accountModel, rootTag = "library_screen")
}

/**
 * 方案 C 追剧 / 片单页（全 3 套主题 × 深浅 6 色板统一适配）：
 * 顶部大字重无框三栏切换（追剧 / 历史 / 云片单）+ 宽幅「接着看」横滑剧场卡 + 高密度 3 列大圆角海报墙。
 */
@Composable
internal fun CinemaChaseScreen(
    state: HongguoUiState,
    model: HongguoViewModel,
    accountModel: AccountViewModel,
    rootTag: String = "library_screen",
) {
    val history = state.tab == AppTab.HISTORY
    val kind = if (history) "history" else "favorites"
    val activeEntries = if (history) state.history else state.favorites
    val canManage = !state.libraryCloud && (activeEntries.isNotEmpty() || (!history && state.history.isNotEmpty()))

    var managing by rememberSaveable(state.tab, state.libraryCloud) { mutableStateOf(false) }
    var confirmClear by rememberSaveable(kind) { mutableStateOf(false) }
    LaunchedEffect(canManage) { if (!canManage) managing = false }

    val historyById = state.history.associateBy { it.card.id }

    Column(Modifier.fillMaxSize().testTag(rootTag)) {
        // Bold borderless typographic top switcher: 追剧 / 历史 / 云片单
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val chaseSelected = !state.libraryCloud && !history
            val historySelected = !state.libraryCloud && history
            val cloudSelected = state.libraryCloud

            val chaseColor by animatedTabColor(chaseSelected, Ink, Muted)
            val historyColor by animatedTabColor(historySelected, Ink, Muted)
            val cloudColor by animatedTabColor(cloudSelected, Ink, Muted)

            Text(
                text = "追剧",
                color = chaseColor,
                fontSize = animatedTabSize(chaseSelected, 24.sp, 15.sp),
                fontWeight = if (chaseSelected) FontWeight.Black else FontWeight.Medium,
                modifier = Modifier
                    .quietSelectable(chaseSelected) {
                        model.selectLibrarySource(false)
                        model.selectTab(AppTab.FAVORITES)
                    }
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(Alignment.Bottom)
                    .testTag(if (cloudSelected) "library_source_local" else "library_favorites"),
            )
            Text(
                text = "历史",
                color = historyColor,
                fontSize = animatedTabSize(historySelected, 24.sp, 15.sp),
                fontWeight = if (historySelected) FontWeight.Black else FontWeight.Medium,
                modifier = Modifier
                    .quietSelectable(historySelected) {
                        model.selectLibrarySource(false)
                        model.selectTab(AppTab.HISTORY)
                    }
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(Alignment.Bottom)
                    .testTag("library_history"),
            )
            Text(
                text = "云片单",
                color = cloudColor,
                fontSize = animatedTabSize(cloudSelected, 24.sp, 15.sp),
                fontWeight = if (cloudSelected) FontWeight.Black else FontWeight.Medium,
                modifier = Modifier
                    .quietSelectable(cloudSelected) {
                        model.selectLibrarySource(true)
                    }
                    .heightIn(min = 44.dp)
                    .wrapContentHeight(Alignment.Bottom)
                    .testTag("library_source_account"),
            )

            Spacer(Modifier.weight(1f))

            if (!state.libraryCloud) {
                if (managing && activeEntries.isNotEmpty()) {
                    TextButton(
                        onClick = { confirmClear = true },
                        modifier = Modifier.testTag("clear_$kind"),
                    ) {
                        Text("清空", color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (canManage) {
                    TextButton(
                        onClick = { managing = !managing },
                        modifier = Modifier.testTag("library_manage"),
                    ) {
                        Text(
                            text = if (managing) "完成" else "管理",
                            color = if (managing) AccentInk else Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        if (state.libraryCloud) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CloudLibraryContent(accountModel, model::openLibrary, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
            }
        } else {
            val itemPrefix = if (history) "history" else "favorite"
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("${kind}_list"),
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp).plusNavClearance(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.libraryError != null) {
                    item(key = "storage_error") {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            FailurePanel(state.libraryError, model::reloadLibrary, "${kind}_storage_retry")
                        }
                    }
                }

                // Wide Theater Resume Carousel on the 追剧 tab
                if (!history && state.history.isNotEmpty()) {
                    item(key = "resume_carousel") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.history.take(8), key = { "resume_${it.card.id}" }) { entry ->
                                TheaterResumeCard(
                                    entry = entry,
                                    managing = managing,
                                    onOpen = { model.openLibrary(entry) },
                                    onRemove = { model.removeHistory(entry.card.id) },
                                )
                            }
                        }
                    }
                }

                // Section header for the 3-column poster wall
                item(key = "wall_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (history) "全部看过（${state.history.size}）" else "全部在追（${state.favorites.size}）",
                            color = Muted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (managing) {
                            Text("点右上角 × 移除", color = AccentInk, fontSize = 11.sp)
                        }
                    }
                }

                if (activeEntries.isEmpty() && state.libraryError == null) {
                    item(key = "empty") {
                        if (!history && state.history.isNotEmpty()) {
                            // Compact refined card when user already has continue-watching items above
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = CardSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .border(1.dp, Line, RoundedCornerShape(18.dp)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(CircleShape)
                                            .background(SoftOrange),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.StarBorder, null, Modifier.size(20.dp), tint = AccentInk)
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("还没有收藏的剧集", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text("在短剧详情页点星标收藏，喜欢的故事会留在海报墙中。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                                    }
                                }
                            }
                        } else {
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                EmptyPrompt(
                                    title = if (history) "还没有观看记录" else "还没有收藏的剧集",
                                    subtitle = if (history) "开始播放后，这里会记住集数和进度。"
                                    else "在剧集详情或播放器中点收藏，喜欢的故事就会留在这里。",
                                )
                            }
                        }
                    }
                }

                // High-density 3-column large-radius poster wall
                itemsIndexed(activeEntries.chunked(3), key = { _, row -> "${kind}_row_${row.first().card.id}" }) { rowIndex, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEachIndexed { column, entry ->
                            val index = rowIndex * 3 + column
                            val progressEntry = if (history) entry else historyById[entry.card.id] ?: entry
                            CinemaPosterTile(
                                entry = entry,
                                progressEntry = progressEntry,
                                isHistory = history,
                                managing = managing,
                                itemTag = "${itemPrefix}_item_$index",
                                removeTag = "${itemPrefix}_remove_$index",
                                modifier = Modifier.weight(1f),
                                onOpen = { model.openLibrary(entry) },
                                onRemove = {
                                    if (history) model.removeHistory(entry.card.id)
                                    else model.toggleFavorite(entry.card)
                                },
                            )
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (confirmClear) LocalClearDialog(history, model) { confirmClear = false }
}

@Composable
private fun TheaterResumeCard(
    entry: LibraryEntry,
    managing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val ep = entry.episodeIndex.coerceAtLeast(1)
    val pct = if (entry.card.episodeCount > 0) {
        ((ep.toFloat() / entry.card.episodeCount).coerceIn(0.01f, 1f) * 100).toInt()
    } else null
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .width(264.dp)
            .height(136.dp)
            .clip(shape)
            .background(Color(0xFF2B1814))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .testTag("chase_resume_${entry.card.id}")
            .clickable(onClick = onOpen),
    ) {
        Cover(entry.card.cover, entry.card.title, Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0x52120B09),
                        0.45f to Color(0x66120B09),
                        1.0f to Color(0xE8100907),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = "接着看 · 第 $ep 集",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
                if (managing) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.62f))
                            .clickable(role = Role.Button, onClick = onRemove)
                            .semantics { contentDescription = "删除${entry.card.title}的记录" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, null, Modifier.size(15.dp), tint = Color.White)
                    }
                } else if (pct != null) {
                    Text(
                        text = "$pct%",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = entry.card.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Orange),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp), tint = OnOrange)
                    }
                }
                WatchProgress(
                    entry = entry,
                    track = Color.White.copy(alpha = 0.28f),
                    fill = Color.White,
                )
            }
        }
    }
}

@Composable
private fun CinemaPosterTile(
    entry: LibraryEntry,
    progressEntry: LibraryEntry,
    isHistory: Boolean,
    managing: Boolean,
    itemTag: String,
    removeTag: String,
    modifier: Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val badgeText = when {
        progressEntry.episodeIndex > 0 && entry.card.episodeCount > 0 ->
            "看至 ${progressEntry.episodeIndex}/${entry.card.episodeCount} 集"
        progressEntry.episodeIndex > 0 ->
            "第 ${progressEntry.episodeIndex} 集 · ${libraryPositionLabel(progressEntry.positionMs)}"
        entry.card.episodeCount > 0 ->
            "全 ${entry.card.episodeCount} 集"
        else -> "查看剧集"
    }
    val showBar = isHistory || progressEntry.episodeIndex > 0

    Column(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(itemTag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(16.dp))
                .background(Cream),
        ) {
            Cover(entry.card.cover, entry.card.title, Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)))),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.48f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, end = 6.dp, bottom = if (showBar) 8.dp else 6.dp),
            ) {
                Text(
                    text = badgeText,
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (showBar) {
                WatchProgress(
                    entry = progressEntry,
                    track = Color.White.copy(alpha = 0.3f),
                    fill = Orange,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (managing) {
                RemoveBadge(
                    description = if (isHistory) "删除${entry.card.title}的记录" else "取消收藏${entry.card.title}",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .testTag(removeTag),
                    onClick = onRemove,
                )
            }
        }
        Text(
            text = entry.card.title,
            color = Ink,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun entryCaption(entry: LibraryEntry) = when {
    entry.episodeIndex > 0 -> "第${entry.episodeIndex}集 · ${libraryPositionLabel(entry.positionMs)}"
    entry.card.episodeCount > 0 -> "${entry.card.episodeCount} 集"
    else -> "查看剧集"
}

/** One poster of the three-across library grid, shared by cloud lists and sub-pages. */
@Composable
internal fun LibraryPosterCell(
    entry: LibraryEntry,
    caption: String?,
    tag: String,
    modifier: Modifier,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    onOpen: () -> Unit,
    badge: @Composable BoxScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpen)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(14.dp))) {
            Cover(entry.card.cover, entry.card.title, Modifier.fillMaxSize())
            if (showProgress) WatchProgress(entry, track = Color.White.copy(alpha = .3f), fill = Orange, modifier = Modifier.align(Alignment.BottomCenter))
            badge()
        }
        Text(entry.card.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        footer()
    }
}

internal fun libraryPositionLabel(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** The device's own records in three columns, shared by the pages opened from 我的. */
@Composable
internal fun LocalLibraryList(
    entries: List<LibraryEntry>,
    history: Boolean,
    managing: Boolean,
    model: HongguoViewModel,
    modifier: Modifier,
    storageError: String? = null,
) {
    val kind = if (history) "history" else "favorites"
    val itemPrefix = if (history) "history" else "favorite"
    LazyColumn(
        modifier.testTag("${kind}_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp).plusNavClearance(),
    ) {
        if (storageError != null) item { FailurePanel(storageError, model::reloadLibrary, "${kind}_storage_retry") }
        if (entries.isEmpty() && storageError == null) item {
            EmptyPrompt(
                if (history) "还没有观看记录" else "还没有收藏的剧集",
                if (history) "开始播放后，这里会记住集数和进度。" else "在剧集详情或播放器中收藏，喜欢的故事就会留在这里。",
            )
        }
        itemsIndexed(entries.chunked(3), key = { _, row -> row.first().card.id }) { rowIndex, row ->
            Row(Modifier.fillMaxWidth().animateItem().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { column, entry ->
                    val index = rowIndex * 3 + column
                    LibraryPosterCell(
                        entry = entry,
                        caption = entryCaption(entry),
                        tag = "${itemPrefix}_item_$index",
                        modifier = Modifier.weight(1f),
                        showProgress = history,
                        onOpen = { model.openLibrary(entry) },
                        badge = {
                            if (managing) RemoveBadge(
                                if (history) "删除${entry.card.title}的记录" else "取消收藏${entry.card.title}",
                                Modifier.align(Alignment.TopEnd).testTag("${itemPrefix}_remove_$index"),
                            ) {
                                if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card)
                            }
                        },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun LocalClearDialog(history: Boolean, model: HongguoViewModel, onDismiss: () -> Unit) {
    val kind = if (history) "history" else "favorites"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (history) "清空观看历史？" else "清空本机收藏？") },
        text = { Text(if (history) "将移除这台设备上的全部观看记录。" else "将移除这台设备上的全部收藏。") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    if (history) model.clearHistory() else model.clearFavorites()
                },
                modifier = Modifier.testTag("confirm_clear_$kind"),
            ) { Text("确认清空") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
