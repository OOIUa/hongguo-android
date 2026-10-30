package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun HomeScreen(state: HongguoUiState, model: HongguoViewModel) {
    CatalogGrid(
        feed = state.home, tagPrefix = "home", emptyTitle = "这一页暂时没有剧集", columns = warmColumns(),
        emptyHint = "可以刷新，或切换上方内容类型。", onOpen = model::openDrama,
        onRefresh = model::refreshHome, onMore = model::loadMoreHome,
        header = {
            item(key = "home_search", span = { GridItemSpan(maxLineSpan) }) {
                CatalogSearchEntry("搜索剧名，发现好故事", "home_search") { model.selectTab(AppTab.SEARCH) }
            }
            state.home.items.firstOrNull()?.let { featured ->
                item(key = "home_featured", span = { GridItemSpan(maxLineSpan) }) {
                    WarmFeaturedBanner(featured, onOpen = { model.openDrama(featured) }, onPlay = { model.playDrama(featured) })
                }
            }
            if (state.history.isNotEmpty()) {
                item(key = "home_continue", span = { GridItemSpan(maxLineSpan) }) {
                    WarmContinueRail(state.history, onOpen = model::openLibrary,
                        onHistory = { model.selectTab(AppTab.HISTORY) })
                }
            }
            item(key = "home_feeds", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val tabs = rememberTabIndicator()
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())
                        .slidingIndicator(tabs, state.homeFeed, Orange, width = 32.dp, height = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        listOf(HomeFeed.GUESS to "推荐", HomeFeed.RECOMMEND to "热度精选", HomeFeed.LATEST to "最新短剧", HomeFeed.COMIC to "漫剧榜单")
                            .forEach { (feed, label) ->
                                CatalogTextTab(label, state.homeFeed == feed, "home_feed_${feed.name.lowercase(Locale.ROOT)}",
                                    Modifier.tabAnchor(tabs, feed)) { model.selectHomeFeed(feed) }
                            }
                    }
                    IconButton(onClick = model::refreshHome, enabled = !state.home.loading,
                        modifier = Modifier.size(36.dp).testTag("home_refresh")) {
                        Icon(Icons.Outlined.Refresh, "刷新首页", tint = Muted)
                    }
                }
            }
        },
    )
}

/** 3 columns like the A design; falls back to 2 on narrow screens or large text so titles stay readable. */
@Composable
private fun warmColumns(): Int {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() }
    val narrow = LocalDensity.current.fontScale >= 1.3f || width < 360
    // Phones get 3 (or 2) columns; tablets and landscape keep posters near phone size instead of stretching them.
    return if (narrow) (width / 170).coerceIn(2, 6) else (width / 120).coerceIn(3, 8)
}

@Composable
private fun WarmFeaturedBanner(card: DramaCard, onOpen: () -> Unit, onPlay: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(188.dp).clip(RoundedCornerShape(20.dp))
        .clickable(role = Role.Button, onClick = onOpen).testTag("home_featured")) {
        Cover(card.cover, card.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
             0f to Color.Black.copy(alpha = .12f), .35f to Color.Black.copy(alpha = .38f), 1f to Color.Black.copy(alpha = .82f))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("今日推荐", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                Text(card.title, color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.episodeCount > 0) Text("全 ${card.episodeCount} 集", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
            Button(onClick = onPlay, shape = RoundedCornerShape(50),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1F1A17)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.heightIn(min = 44.dp).testTag("home_featured_play")) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Text("播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WarmContinueRail(entries: List<LibraryEntry>, onOpen: (LibraryEntry) -> Unit, onHistory: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("home_continue"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("继续观看", fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onHistory, modifier = Modifier.testTag("home_continue_history")) {
                Text("全部", color = Muted, fontSize = 13.sp)
                Icon(Icons.Outlined.ChevronRight, "查看本机观看历史", tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries.take(8), key = { it.card.id }) { entry ->
                Surface(onClick = { onOpen(entry) }, color = CardSurface, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.width(250.dp).testTag(if (entry == entries.first()) "home_continue_play" else "home_continue_${entry.card.id}")) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Cover(entry.card.cover, entry.card.title, Modifier.width(54.dp).height(72.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(entry.card.title, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("看到第 ${entry.episodeIndex.coerceAtLeast(1)} 集 · ${catalogPosition(entry.positionMs)}",
                                color = Muted, fontSize = 12.sp, maxLines = 1)
                            WatchProgress(entry)
                        }
                    }
                }
            }
        }
    }
}

/** Thin progress bar: how far into the series the saved episode is. */
@Composable
internal fun WatchProgress(entry: LibraryEntry, modifier: Modifier = Modifier, track: Color = Line, fill: Color = Orange) {
    val total = entry.card.episodeCount
    val fraction = if (total > 0) (entry.episodeIndex.coerceAtLeast(1).toFloat() / total).coerceIn(.03f, 1f) else .05f
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(fill))
    }
}

@Composable
internal fun CategoryScreen(state: HongguoUiState, model: HongguoViewModel) {
    var expanded by rememberSaveable(state.browseDraft.genre) { mutableStateOf(false) }
    val dirty = state.browseDraft != state.browseApplied
    val filterHeight = (LocalConfiguration.current.screenHeightDp * .34f).coerceIn(150f, 320f).dp
    CatalogGrid(
        feed = state.category, tagPrefix = "category", emptyTitle = "没有符合条件的剧集",
        emptyHint = "试试减少筛选条件，或切换内容类型。", onOpen = model::openDrama,
        onRefresh = model::applyBrowse, onMore = model::loadMoreBrowse, canLoadMore = !dirty,
        header = {
            item(key = "category_title", span = { GridItemSpan(maxLineSpan) }) {
                Text("发现", color = Ink, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp))
            }
            item(key = "category_search", span = { GridItemSpan(maxLineSpan) }) {
                CatalogSearchEntry("搜索剧名或关键词", "category_search") { model.selectTab(AppTab.SEARCH) }
            }
            item(key = "category_genres", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                        SelectorChip(label, state.browseDraft.genre == genre, "genre_$genre") { model.changeGenre(genre) }
                    }
                }
            }
            item(key = "category_filters", span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = CardSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Tune, null, tint = Muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(if (dirty) "筛选已更改，尚未应用" else "筛选条件", fontSize = 14.sp, lineHeight = 21.sp,
                                    fontWeight = FontWeight.Medium, color = if (dirty) AccentInk else Ink)
                                val selectedCount = state.browseDraft.options.values.count { it.isNotBlank() }
                                Text(if (state.filtersLoading) "正在读取可选条件…"
                                    else if (selectedCount > 0) "已选 $selectedCount 项条件" else "按题材、排序等条件查找",
                                    fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
                            }
                            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("toggle_filters")) {
                                Text(if (expanded) "收起" else "筛选", fontSize = 14.sp)
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (expanded) {
                            Column(Modifier.fillMaxWidth().heightIn(max = filterHeight).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (state.filtersLoading) CatalogLoading("正在加载筛选…")
                                state.filterGroups.forEach { group ->
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(group.label, fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                                        val selected = state.browseDraft.options[group.key].orEmpty()
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    Text("当前类型暂无更多筛选条件。", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                                }
                            }
                        }
                        state.filtersError?.let { FailurePanel(it, model::reloadFilters, "retry_filters") }
                        if (expanded || dirty) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = model::resetBrowse,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reset_filters"),
                                    shape = RoundedCornerShape(12.dp)) { Text("重置", fontSize = 14.sp) }
                                Button(onClick = model::applyBrowse, enabled = !state.category.loading,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("apply_filters"),
                                    shape = RoundedCornerShape(12.dp)) {
                                    Text(if (dirty) "应用筛选" else "重新查询", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun CatalogSearchEntry(placeholder: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Cream)
        .clickable(role = Role.Button, onClick = onClick).heightIn(min = 38.dp)
        .testTag(tag).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(24.dp))
        Text(placeholder, color = Muted, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
/** Text tab whose underline is drawn by the row ([slidingIndicator]) so it can glide between tabs. */
internal fun CatalogTextTab(label: String, isSelected: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.heightIn(min = 36.dp).quietSelectable(isSelected, onClick = onClick).testTag(tag).padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val color by animateColorAsState(if (isSelected) AccentInk else Muted, tween(180), label = "catalog_tab_color")
        Text(label, color = color, fontSize = 16.sp, lineHeight = 24.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun SelectorChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        label = { Text(label, fontSize = 14.sp, maxLines = 1) }, shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentText,
            containerColor = CardSurface, labelColor = Muted),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
            borderColor = Cream, selectedBorderColor = AccentBorder))
}

@Composable
private fun CatalogGrid(
    feed: FeedState, tagPrefix: String, emptyTitle: String, emptyHint: String,
    onOpen: (DramaCard) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    canLoadMore: Boolean = true, columns: Int = 0, header: LazyGridScope.() -> Unit,
) {
    val minimumCardWidth = if (LocalDensity.current.fontScale >= 1.5f) 184.dp else 148.dp
    LazyVerticalGrid(columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minimumCardWidth),
        modifier = Modifier.fillMaxSize().testTag("${tagPrefix}_results"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp).plusNavClearance(),
        horizontalArrangement = Arrangement.spacedBy(if (columns >= 3) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header()
        if (feed.loading && feed.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { CatalogLoading("正在读取剧集…") }
        if (feed.loaded && !feed.loading && feed.items.isEmpty() && feed.error == null) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyPrompt(emptyTitle, emptyHint) }
        }
        itemsIndexed(feed.items, key = { _, card -> card.id }) { index, card ->
            CatalogPoster(card, "${tagPrefix}_result_$index", tall = columns >= 3, Modifier.animateItem()) { onOpen(card) }
        }
        feed.error?.let { error ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                FailurePanel(error, if (canLoadMore && feed.items.isNotEmpty() && feed.hasMore) onMore else onRefresh, "${tagPrefix}_retry")
            }
        }
        if (feed.hasMore && feed.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = onMore, enabled = canLoadMore && !feed.loadingMore && !feed.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("${tagPrefix}_load_more"), shape = RoundedCornerShape(12.dp)) {
                if (feed.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (canLoadMore) "加载更多" else "请先应用新的筛选条件", fontSize = 14.sp)
            }
        }
        if (!feed.loading && !feed.hasMore && feed.items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CatalogPoster(card: DramaCard, tag: String, tall: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Cover(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(if (tall) .75f else .95f)
            .clip(RoundedCornerShape(if (tall) 12.dp else 16.dp)))
        Text(card.title, color = Ink, fontSize = if (tall) 13.sp else 14.sp, lineHeight = if (tall) 18.sp else 20.sp,
            fontWeight = FontWeight.Medium, maxLines = if (tall) 2 else 1, overflow = TextOverflow.Ellipsis)
        Text(if (card.episodeCount > 0) "${card.episodeCount} 集" else "查看剧集", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun CatalogLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, Modifier.padding(start = 12.dp), color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
    }
}

private fun catalogPosition(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
