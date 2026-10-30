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
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("${kind}_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp).plusNavClearance()) {
                if (entries.isEmpty()) item {
                    EmptyPrompt(if (history) "还没有观看记录" else "还没有收藏的剧集",
                        if (history) "开始播放后，这里会记住集数和进度。" else "在剧集详情或播放器中收藏，喜欢的故事就会留在这里。")
                }
                itemsIndexed(entries, key = { _, entry -> entry.card.id }) { index, entry ->
                    // Removing an entry lets the rows below slide up instead of jumping.
                    Column(Modifier.animateItem()) {
                    LibraryRow(entry, history, index, managing,
                        onOpen = { model.openLibrary(entry) },
                        onRemove = { if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card) })
                    if (index < entries.lastIndex) HorizontalDivider(color = Line.copy(alpha = .6f))
                    }
                }
                item {
                    Text("本机记录仅保存在这台设备。", fontSize = 12.sp, color = Muted,
                        modifier = Modifier.padding(top = 24.dp, bottom = 24.dp))
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(if (history) "清空观看历史？" else "清空本机收藏？") },
        text = { Text(if (history) "将移除这台设备上的全部观看记录。" else "将移除这台设备上的全部收藏。") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            if (history) model.clearHistory() else model.clearFavorites()
        }, modifier = Modifier.testTag("confirm_clear_$kind")) { Text("确认清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun LibraryRow(entry: LibraryEntry, history: Boolean, index: Int, managing: Boolean,
                       onOpen: () -> Unit, onRemove: () -> Unit) {
    val kind = if (history) "history" else "favorite"
    val stackAction = (LocalDensity.current.fontScale >= 1.3f || LocalConfiguration.current.screenWidthDp < 360) &&
        (managing || (index == 0 && entry.episodeId.isNotBlank()))
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("${kind}_item_$index").padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Cover(entry.card.cover, entry.card.title, Modifier.width(64.dp).height(88.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(entry.card.title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (entry.episodeIndex > 0) "第${entry.episodeIndex}集 · ${libraryPositionLabel(entry.positionMs)}"
                else if (entry.card.episodeCount > 0) "${entry.card.episodeCount}集" else "查看完整选集",
                fontSize = 12.sp, color = Muted)
            if (stackAction) LibraryRowAction(entry, history, index, managing, onOpen, onRemove)
        }
        if (!stackAction) LibraryRowAction(entry, history, index, managing, onOpen, onRemove)
    }
}

@Composable
private fun LibraryRowAction(entry: LibraryEntry, history: Boolean, index: Int, managing: Boolean,
                             onOpen: () -> Unit, onRemove: () -> Unit) {
    val kind = if (history) "history" else "favorite"
    if (managing) TextButton(onClick = onRemove, modifier = Modifier.testTag("${kind}_remove_$index"),
        contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (history) "删除" else "取消收藏", fontSize = 12.sp) }
    else if (index == 0 && entry.episodeId.isNotBlank()) Button(onClick = onOpen, shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) { Text("继续看", fontSize = 14.sp) }
    else IconButton(onClick = onOpen) {
        Icon(Icons.Outlined.ChevronRight, contentDescription = if (entry.episodeId.isNotBlank()) "继续观看" else "查看选集",
            tint = Muted, modifier = Modifier.size(24.dp))
    }
}

internal fun libraryPositionLabel(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
