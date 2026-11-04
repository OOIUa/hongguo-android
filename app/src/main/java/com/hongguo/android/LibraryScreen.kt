package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.AccountViewModel
import java.util.Locale

@Composable
internal fun LibraryScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val history = state.tab == AppTab.HISTORY
    val entries = if (history) state.history else state.favorites
    val kind = if (history) "history" else "favorites"
    var managing by rememberSaveable(state.tab, state.libraryCloud) { mutableStateOf(false) }
    var confirmClear by rememberSaveable(kind) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("片单", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!state.libraryCloud && entries.isNotEmpty()) {
                TextButton(onClick = { managing = !managing }, modifier = Modifier.testTag("library_manage")) {
                    Text(if (managing) "完成" else "管理", color = Muted)
                }
            }
        }
        val source = rememberTabIndicator()
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(50)).background(Cream)
            .slidingPill(source, state.libraryCloud, SoftOrange, corner = 24.dp)) {
            listOf(false to "本机", true to "红果账号").forEach { (cloud, label) ->
                val selected = state.libraryCloud == cloud
                Box(Modifier.weight(1f).tabAnchor(source, cloud).heightIn(min = 48.dp).clip(RoundedCornerShape(50))
                    .quietSelectable(selected) { model.selectLibrarySource(cloud) }
                    .testTag(if (cloud) "library_source_account" else "library_source_local"),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) AccentInk else Muted, fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
                }
            }
        }
        if (state.libraryCloud) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CloudLibraryContent(accountModel, model::openLibrary, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
            }
        } else {
            val kinds = rememberTabIndicator()
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp)
                .slidingIndicator(kinds, history, Orange, width = 30.dp, height = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(true to "历史", false to "收藏").forEach { (isHistory, label) ->
                    Column(Modifier.tabAnchor(kinds, isHistory).quietSelectable(history == isHistory) {
                        model.selectTab(if (isHistory) AppTab.HISTORY else AppTab.FAVORITES) }
                        .testTag(if (isHistory) "library_history" else "library_favorites"),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            color = if (history == isHistory) AccentInk else Muted,
                            modifier = Modifier.padding(vertical = 12.dp))
                        Spacer(Modifier.height(2.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (managing) TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(),
                    modifier = Modifier.testTag("clear_$kind")) { Text("清空") }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Line)
            Text(if (history) "本机观看记录" else "本机收藏", fontSize = 12.sp, color = Muted,
                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp))
            LocalLibraryList(entries, history, managing, model, Modifier.fillMaxWidth().weight(1f))
        }
    }
    if (confirmClear) LocalClearDialog(history, model) { confirmClear = false }
}

private fun entryCaption(entry: LibraryEntry) = when {
    entry.episodeIndex > 0 -> "第${entry.episodeIndex}集 · ${libraryPositionLabel(entry.positionMs)}"
    entry.card.episodeCount > 0 -> "${entry.card.episodeCount} 集"
    else -> "查看剧集"
}

/** One poster of the three-across library grid, shaped like the 剧场 grid. */
@Composable
internal fun LibraryPosterCell(entry: LibraryEntry, caption: String?, tag: String, modifier: Modifier, enabled: Boolean = true,
                               showProgress: Boolean = false, onOpen: () -> Unit,
                               badge: @Composable BoxScope.() -> Unit = {}, footer: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.clickable(enabled = enabled, role = Role.Button, onClick = onOpen).testTag(tag), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(12.dp))) {
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

/** The device's own records in three columns, shared by 追剧/片单 and the pages opened from 我的. */
@Composable
internal fun LocalLibraryList(entries: List<LibraryEntry>, history: Boolean, managing: Boolean, model: HongguoViewModel, modifier: Modifier) {
    val kind = if (history) "history" else "favorites"
    LazyColumn(modifier.testTag("${kind}_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp).plusNavClearance()) {
        if (entries.isEmpty()) item {
            EmptyPrompt(if (history) "还没有观看记录" else "还没有收藏的剧集",
                if (history) "开始播放后，这里会记住集数和进度。" else "在剧集详情或播放器中收藏，喜欢的故事就会留在这里。")
        }
        itemsIndexed(entries.chunked(3), key = { _, row -> row.first().card.id }) { rowIndex, row ->
            Row(Modifier.fillMaxWidth().animateItem().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { column, entry ->
                    val index = rowIndex * 3 + column
                    LibraryPosterCell(entry, entryCaption(entry), "${kind}_item_$index", Modifier.weight(1f),
                        showProgress = history, onOpen = { model.openLibrary(entry) }, badge = {
                            if (managing) RemoveBadge(if (history) "删除${entry.card.title}的记录" else "取消收藏${entry.card.title}",
                                Modifier.align(Alignment.TopEnd).testTag("${kind}_remove_$index")) {
                                if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card)
                            }
                        })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Text("本机记录仅保存在这台设备。", fontSize = 12.sp, color = Muted,
                modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))
        }
    }
}

@Composable
internal fun LocalClearDialog(history: Boolean, model: HongguoViewModel, onDismiss: () -> Unit) {
    val kind = if (history) "history" else "favorites"
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (history) "清空观看历史？" else "清空本机收藏？") },
        text = { Text(if (history) "将移除这台设备上的全部观看记录。" else "将移除这台设备上的全部收藏。") },
        confirmButton = { TextButton(onClick = {
            onDismiss()
            if (history) model.clearHistory() else model.clearFavorites()
        }, modifier = Modifier.testTag("confirm_clear_$kind")) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
