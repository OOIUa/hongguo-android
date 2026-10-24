package com.hongguo.android

import androidx.compose.foundation.Canvas as DrawingCanvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun AppNavigation(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val tabs = listOf(AppTab.HOME to "首页", AppTab.CATEGORY to "分类", AppTab.SEARCH to "搜索",
        AppTab.FAVORITES to "收藏", AppTab.MINE to "我的")
    val activeTab = if (selected in setOf(AppTab.HISTORY, AppTab.ACCOUNT)) AppTab.MINE else selected
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp,
        modifier = Modifier.semantics { testTagsAsResourceId = true }) {
        tabs.forEach { (tab, label) ->
            NavigationBarItem(selected = activeTab == tab,
                onClick = { keyboard?.hide(); focus.clearFocus(); onSelect(tab) },
                modifier = Modifier.testTag("nav_${tab.name.lowercase(Locale.ROOT)}"),
                icon = { NavigationGlyph(tab, activeTab == tab) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Orange,
                    selectedTextColor = Orange, indicatorColor = Color(0xFFFFEEE5),
                    unselectedIconColor = Muted, unselectedTextColor = Muted))
        }
    }
}

@Composable
private fun NavigationGlyph(tab: AppTab, selected: Boolean) {
    val color = if (selected) Orange else Muted
    DrawingCanvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        when (tab) {
            AppTab.HOME -> {
                val outline = Path().apply {
                    moveTo(w * .12f, h * .43f); lineTo(w * .5f, h * .12f); lineTo(w * .88f, h * .43f)
                    moveTo(w * .21f, h * .39f); lineTo(w * .21f, h * .87f); lineTo(w * .79f, h * .87f)
                    lineTo(w * .79f, h * .39f)
                    moveTo(w * .42f, h * .87f); lineTo(w * .42f, h * .62f); lineTo(w * .59f, h * .62f)
                    lineTo(w * .59f, h * .87f)
                }
                drawPath(outline, color, style = stroke)
            }
            AppTab.CATEGORY -> listOf(.14f to .14f, .57f to .14f, .14f to .57f, .57f to .57f).forEach { (x, y) ->
                drawRoundRect(color, Offset(w * x, h * y), Size(w * .29f, h * .29f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
            }
            AppTab.SEARCH -> {
                drawCircle(color, w * .28f, Offset(w * .42f, h * .42f), style = stroke)
                drawLine(color, Offset(w * .64f, h * .64f), Offset(w * .89f, h * .89f), stroke.width, StrokeCap.Round)
            }
            AppTab.FAVORITES -> {
                val star = Path()
                repeat(10) { index ->
                    val angle = -Math.PI / 2 + index * Math.PI / 5
                    val radius = if (index % 2 == 0) w * .42 else w * .2
                    val x = w * .5f + (cos(angle) * radius).toFloat()
                    val y = h * .52f + (sin(angle) * radius).toFloat()
                    if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                drawPath(star, color, style = stroke)
            }
            AppTab.MINE, AppTab.ACCOUNT -> {
                drawCircle(color, w * .17f, Offset(w * .5f, h * .29f), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(w * .16f, h * .52f), Size(w * .68f, h * .64f), style = stroke)
            }
            AppTab.HISTORY -> {
                drawCircle(color, w * .37f, Offset(w * .5f, h * .5f), style = stroke)
                drawLine(color, Offset(w * .5f, h * .27f), Offset(w * .5f, h * .51f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .5f, h * .51f), Offset(w * .67f, h * .61f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun HomeScreen(state: HongguoUiState, model: HongguoViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("总有一部，合你心意", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("看看热度好剧，发现下一段故事", Modifier.padding(top = 6.dp), color = Muted, fontSize = 12.sp)
            }
            TextButton(onClick = model::refreshHome, enabled = !state.home.loading) { Text("刷新") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
            items(listOf(HomeFeed.RECOMMEND to "热度精选", HomeFeed.LATEST to "最新短剧", HomeFeed.COMIC to "漫剧榜单")) { (feed, label) ->
                SelectorChip(label, state.homeFeed == feed, "home_feed_${feed.name.lowercase(Locale.ROOT)}") { model.selectHomeFeed(feed) }
            }
        }
        FeedList(feed = state.home, tagPrefix = "home", emptyTitle = "这一页暂时没有剧集",
            emptyHint = "可以刷新，或切换上方内容类型。", onOpen = model::openDrama,
            onRefresh = model::refreshHome, onMore = model::loadMoreHome)
    }
}

@Composable
internal fun CategoryScreen(state: HongguoUiState, model: HongguoViewModel) {
    var expanded by rememberSaveable(state.browseDraft.genre) { mutableStateOf(false) }
    val dirty = state.browseDraft != state.browseApplied
    val filterHeight = (LocalConfiguration.current.screenHeightDp * .32f).coerceIn(110f, 250f).dp
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("按喜好，找到好故事", Modifier.padding(top = 6.dp, bottom = 5.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                SelectorChip(label, state.browseDraft.genre == genre, "genre_$genre") { model.changeGenre(genre) }
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 10.dp)) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (dirty) "筛选已更改，尚未应用" else "筛选条件", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = if (dirty) Orange else Ink)
                        val selectedCount = state.browseDraft.options.values.count { it.isNotBlank() }
                        Text(if (state.filtersLoading) "正在读取可选条件…" else if (selectedCount > 0) "已选 $selectedCount 项条件" else "按题材、排序等条件查找",
                            fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                    }
                    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle_filters")) {
                        Text(if (expanded) "收起 ▴" else "展开 ▾", fontSize = 12.sp)
                    }
                }
                if (expanded) {
                    Column(Modifier.fillMaxWidth().heightIn(max = filterHeight).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.filtersLoading) Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("正在加载筛选…", Modifier.padding(start = 8.dp), color = Muted, fontSize = 12.sp)
                        }
                        state.filterGroups.forEach { group ->
                            Column {
                                Text(group.label, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                                val selected = state.browseDraft.options[group.key].orEmpty()
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    item {
                                        SelectorChip("全部", selected.isBlank(), "filter_${group.key}_") { model.selectFilter(group.key, "") }
                                    }
                                    items(group.options.filter { it.id.isNotBlank() }, key = { it.id }) { option ->
                                        SelectorChip(option.label, selected == option.id, "filter_${group.key}_${option.id}") {
                                            model.selectFilter(group.key, option.id)
                                        }
                                    }
                                }
                            }
                        }
                        if (!state.filtersLoading && state.filterGroups.isEmpty() && state.filtersError == null) {
                            Text("当前类型暂无更多筛选条件。", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 9.dp))
                        }
                    }
                }
                if (state.filtersError != null) FailurePanel(state.filtersError, model::reloadFilters, "retry_filters")
                if (expanded || dirty) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = model::resetBrowse, modifier = Modifier.weight(1f).testTag("reset_filters"),
                            shape = RoundedCornerShape(11.dp)) { Text("重置", fontSize = 12.sp) }
                        Button(onClick = model::applyBrowse, enabled = !state.category.loading,
                            modifier = Modifier.weight(1f).testTag("apply_filters"), shape = RoundedCornerShape(11.dp)) {
                            Text(if (dirty) "应用筛选" else "重新查询", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        FeedList(feed = state.category, tagPrefix = "category", emptyTitle = "没有符合条件的剧集",
            emptyHint = "试试减少筛选条件，或切换内容类型。", onOpen = model::openDrama,
            onRefresh = model::applyBrowse, onMore = model::loadMoreBrowse, canLoadMore = !dirty)
    }
}

@Composable
private fun SelectorChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, modifier = Modifier.testTag(tag),
        label = { Text(label, fontSize = 12.sp, maxLines = 1) }, shape = RoundedCornerShape(11.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFFFE8DC), selectedLabelColor = Orange,
            containerColor = Color.White, labelColor = Muted),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
            borderColor = Cream, selectedBorderColor = Color(0xFFF8C2A9)))
}

@Composable
private fun FeedList(feed: FeedState, tagPrefix: String, emptyTitle: String, emptyHint: String,
    onOpen: (DramaCard) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, canLoadMore: Boolean = true) {
    LazyColumn(Modifier.fillMaxSize().testTag("${tagPrefix}_results"), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        if (feed.loading) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 17.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                Text("正在读取剧集…", Modifier.padding(start = 10.dp), color = Muted, fontSize = 12.sp)
            }
        }
        if (feed.loaded && !feed.loading && feed.items.isEmpty() && feed.error == null) {
            item { EmptyPrompt(emptyTitle, emptyHint) }
        }
        itemsIndexed(feed.items, key = { _, card -> card.id }) { index, card ->
            DramaResult(card, index, tag = "${tagPrefix}_result_$index") { onOpen(card) }
        }
        if (feed.error != null) item {
            FailurePanel(feed.error, if (canLoadMore && feed.items.isNotEmpty() && feed.hasMore) onMore else onRefresh, "${tagPrefix}_retry")
        }
        if (feed.hasMore && feed.items.isNotEmpty()) item {
            OutlinedButton(onClick = onMore, enabled = canLoadMore && !feed.loadingMore && !feed.loading,
                modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_load_more"), shape = RoundedCornerShape(13.dp)) {
                if (feed.loadingMore) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                else Text(if (canLoadMore) "加载更多" else "请先应用新的筛选条件")
            }
        }
        if (!feed.loading && !feed.hasMore && feed.items.isNotEmpty()) item {
            Text("已经到底了", Modifier.padding(vertical = 12.dp), color = Muted, fontSize = 11.sp)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
internal fun LibraryScreen(state: HongguoUiState, model: HongguoViewModel, history: Boolean) {
    val entries = if (history) state.history else state.favorites
    val kind = if (history) "history" else "favorites"
    val title = if (history) "观看历史" else "我的收藏"
    var confirmClear by rememberSaveable(kind) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text(if (history) "接着上次的故事，继续看" else "喜欢的故事，留着慢慢看", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty(), modifier = Modifier.testTag("clear_$kind")) {
                Text("清空", fontSize = 12.sp)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("${kind}_list"), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (entries.isEmpty()) item {
                EmptyPrompt(if (history) "还没有观看记录" else "还没有收藏的剧集",
                    if (history) "开始播放后，这里会记住集数和进度。" else "在剧集详情点亮星标，喜欢的剧就会留在这里。")
            }
            itemsIndexed(entries, key = { _, entry -> entry.card.id }) { index, entry ->
                LibraryCard(entry, history, index, onOpen = { model.openLibrary(entry) },
                    onRemove = { if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card) })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("清空$title？") },
        text = { Text(if (history) "将移除这台设备上的全部观看记录。" else "将移除这台设备上的全部收藏。") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                if (history) model.clearHistory() else model.clearFavorites()
            }, modifier = Modifier.testTag("confirm_clear_$kind")) { Text("确认清空") }
        }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

@Composable
private fun LibraryCard(entry: LibraryEntry, history: Boolean, index: Int, onOpen: () -> Unit, onRemove: () -> Unit) {
    val card = entry.card
    val kind = if (history) "history" else "favorite"
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("${kind}_item_$index"),
        colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(17.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Cover(card.cover, card.title, Modifier.width(71.dp).height(100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(card.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (entry.episodeIndex > 0) "上次看到第 ${entry.episodeIndex} 集 · ${positionLabel(entry.positionMs)}"
                    else if (card.episodeCount > 0) "共 ${card.episodeCount} 集" else "查看完整选集", fontSize = 11.sp, color = Muted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.episodeId.isNotBlank()) "继续观看 →" else "查看选集 →", color = Orange, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        modifier = Modifier.testTag("${kind}_remove_$index")) {
                        Text(if (history) "删除" else "取消收藏", fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
    }
}

private fun positionLabel(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
