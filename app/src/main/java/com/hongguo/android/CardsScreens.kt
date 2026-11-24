package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.AccountViewModel

/* ---------------------------------------------------------------------------------------------
 * C · 内容卡片: light gray canvas with white cards. Home switches channels and shows a ranking
 * plus a two-column waterfall, 发现 lists results with summaries, 片单 is a progress grid.
 * ------------------------------------------------------------------------------------------- */

private val WaterfallRatios = floatArrayOf(.72f, .6f, .8f, .66f)

@Composable
internal fun CardsHome(state: HongguoUiState, model: HongguoViewModel) {
    val items = state.home.items
    val ranked = items.take(5)
    val rest = if (items.size > 5) items.drop(5) else emptyList()
    val grid = rememberLazyStaggeredGridState()
    // Staggered columns lay out independently, so the last visible entry is not always the highest index.
    LoadAheadEffect(state.home, { grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1 },
        { grid.layoutInfo.totalItemsCount }, onLoad = model::loadMoreHome)
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize().testTag("cards_home"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp).plusNavClearance(),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(CardSurface)
                    .clickable(role = Role.Button) { model.selectTab(AppTab.SEARCH) }.heightIn(min = 40.dp)
                    .padding(horizontal = 14.dp).testTag("cards_search"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Text(ranked.firstOrNull()?.title ?: "搜索剧名或关键词", color = Muted, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { model.openPlayHistory() }, modifier = Modifier.testTag("cards_history")) {
                    Icon(Icons.Outlined.History, "观看历史", tint = Ink)
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            // 分类 used to sit at the end of this row as a fifth "channel", but it only opened the 发现 tab that the
            // bottom bar already offers, and looked selectable like the feeds beside it.
            val tabs = rememberTabIndicator()
            // 推荐 is the default and sits last, so the row opens scrolled to its end.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(Int.MAX_VALUE)).padding(horizontal = 4.dp)
                .slidingIndicator(tabs, state.homeFeed, Orange, width = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                listOf(HomeFeed.RECOMMEND to "热度", HomeFeed.LATEST to "新剧", HomeFeed.COMIC to "漫剧", HomeFeed.GUESS to "推荐").forEach { (feed, label) ->
                    val selected = state.homeFeed == feed
                    val color by animatedTabColor(selected, Ink, Muted)
                    Column(Modifier.tabAnchor(tabs, feed).quietSelectable(selected) { model.selectHomeFeed(feed) }
                        .heightIn(min = 48.dp).testTag("cards_feed_${feed.name.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom)) {
                        Text(label, color = color, fontSize = animatedTabSize(selected, 19.sp, 15.sp),
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }
        state.history.firstOrNull()?.let { entry ->
            item(span = StaggeredGridItemSpan.FullLine) {
                Surface(onClick = { model.openLibrary(entry) }, color = CardSurface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("cards_continue")) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null, tint = AccentInk, modifier = Modifier.size(20.dp))
                        Text("继续看", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${entry.card.title} · 第 ${entry.episodeIndex.coerceAtLeast(1)} 集", color = Muted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (ranked.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Surface(color = CardSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("cards_ranking")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(when (state.homeFeed) { HomeFeed.LATEST -> "新剧榜"; HomeFeed.COMIC -> "漫剧榜"; HomeFeed.GUESS -> "为你推荐"; else -> "热度榜" },
                        color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 2.dp))
                    ranked.forEachIndexed { index, card ->
                        Row(Modifier.fillMaxWidth().clickable { model.openDrama(card) }.heightIn(min = 52.dp)
                            .padding(vertical = 4.dp).testTag("cards_rank_$index"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${index + 1}", color = if (index < 3) AccentInk else Muted, fontSize = 18.sp,
                                fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
                            Cover(card.cover, card.title, Modifier.width(40.dp).height(52.dp))
                            Text(card.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (card.episodeCount > 0) Text("${card.episodeCount} 集", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (state.home.loading && items.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) { CardsLoading("正在读取剧集…") }
        if (state.home.loaded && !state.home.loading && items.isEmpty() && state.home.error == null)
            item(span = StaggeredGridItemSpan.FullLine) { EmptyPrompt("这一页暂时没有剧集", "可以刷新，或切换上方频道。") }
        if (rest.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Text("猜你想看", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }
        staggeredItemsIndexed(rest, key = { _, card -> card.id }) { index, card ->
            WaterfallCard(card, WaterfallRatios[index % WaterfallRatios.size], "cards_result_${index + 5}",
                Modifier.animateItem()) { model.openDrama(card) }
        }
        state.home.error?.let { error -> item(span = StaggeredGridItemSpan.FullLine) {
            FailurePanel(error, if (items.isNotEmpty() && state.home.hasMore) model::loadMoreHome else model::refreshHome, "cards_retry")
        } }
        if (state.home.hasMore && items.isNotEmpty() && state.home.error == null) item(span = StaggeredGridItemSpan.FullLine) {
            LoadAheadFooter("cards_load_more")
        }
        if (!state.home.loading && !state.home.hasMore && items.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WaterfallCard(card: DramaCard, ratio: Float, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = CardSurface, shape = RoundedCornerShape(12.dp), modifier = modifier.testTag(tag)) {
        Column {
            PosterImage(card.cover, Modifier.fillMaxWidth().aspectRatio(ratio))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.title, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.episodeCount > 0) Text("全 ${card.episodeCount} 集", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CardsLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, Modifier.padding(start = 12.dp), color = Muted, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardsDiscover(state: HongguoUiState, model: HongguoViewModel) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val items = state.category.items
    val list = rememberLazyListState()
    LoadAheadEffect(state.category, { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        { list.layoutInfo.totalItemsCount }, state.browseDraft == state.browseApplied, model::loadMoreBrowse)
    LazyColumn(Modifier.fillMaxSize().testTag("cards_discover"), state = list,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发现", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f).padding(start = 4.dp))
                RankEntryButton(model, "cards_discover_rank")
                IconButton(onClick = { model.selectTab(AppTab.SEARCH) }, modifier = Modifier.testTag("cards_discover_search")) {
                    Icon(Icons.Outlined.Search, "搜索", tint = Ink)
                }
            }
        }
        item {
            val tabs = rememberTabIndicator()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardSurface).padding(4.dp)
                .slidingPill(tabs, state.browseDraft.genre, Ink, corner = 9.dp)) {
                listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                    val selected = state.browseDraft.genre == genre
                    val color by animatedTabColor(selected, Canvas, Muted)
                    Box(Modifier.weight(1f).tabAnchor(tabs, genre).clip(RoundedCornerShape(9.dp))
                        .quietSelectable(selected) { model.changeGenre(genre) }.heightIn(min = 38.dp)
                        .testTag("cards_genre_$genre"), contentAlignment = Alignment.Center) {
                        Text(label, color = color, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
        item {
            // The first filter group stays visible as one-tap chips; the rest live in the sheet.
            val group = state.filterGroups.firstOrNull { it.key == "theme" } ?: state.filterGroups.firstOrNull()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val count = state.browseApplied.options.values.count { it.isNotBlank() }
                DarkChip(if (count > 0) "筛选 · $count" else "全部筛选", count > 0, "cards_filters", Icons.Outlined.Tune) { showFilters = true }
                group?.options?.filter { it.id.isNotBlank() }?.take(12)?.forEach { option ->
                    val on = state.browseApplied.options[group.key] == option.id
                    DarkChip(option.label, on, "cards_quick_${group.key}_${option.id}") {
                        model.selectFilter(group.key, option.id)
                        model.applyBrowse()
                    }
                }
            }
        }
        if (state.category.loading && items.isEmpty()) item { CardsLoading("正在读取剧集…") }
        if (state.category.loaded && !state.category.loading && items.isEmpty() && state.category.error == null)
            item { EmptyPrompt("没有符合条件的剧集", "试试减少筛选条件，或切换类型。") }
        itemsIndexed(items, key = { _, card -> card.id }) { index, card ->
            Surface(onClick = { model.openDrama(card) }, color = CardSurface, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.animateItem().testTag("cards_discover_$index")) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Cover(card.cover, card.title, Modifier.width(78.dp).height(104.dp))
                    Column(Modifier.weight(1f).heightIn(min = 104.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(card.title, color = Ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (card.summary.isNotBlank()) Text(card.summary, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (card.episodeCount > 0) "全 ${card.episodeCount} 集" else "查看剧集", color = Muted, fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            Surface(onClick = { model.playDrama(card) }, shape = RoundedCornerShape(50), color = Orange,
                                modifier = Modifier.heightIn(min = 32.dp).testTag("cards_discover_play_$index")) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.PlayArrow, null, tint = OnOrange, modifier = Modifier.size(16.dp))
                                    Text("播放", color = OnOrange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        state.category.error?.let { error -> item {
            FailurePanel(error, if (items.isNotEmpty() && state.category.hasMore) model::loadMoreBrowse else model::applyBrowse, "cards_discover_retry")
        } }
        if (state.category.hasMore && items.isNotEmpty() && state.category.error == null) item { LoadAheadFooter("cards_discover_more") }
        if (!state.category.loading && !state.category.hasMore && items.isNotEmpty()) item {
            Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
        }
    }
    // Swiping the sheet away discards unapplied choices, so quick chips and auto-loading keep working on what is shown.
    if (showFilters) ModalBottomSheet(onDismissRequest = { model.discardBrowseDraft(); showFilters = false }, containerColor = CardSurface) {
        FilterSheetContent(state, model) { showFilters = false }
    }
}

@Composable
internal fun CardsLibrary(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaChaseScreen(state = state, model = model, accountModel = accountModel, rootTag = "cards_library")
}

@Composable
internal fun CardsDetail(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaDetailScreen(state = state, model = model, accountModel = accountModel, rootTag = "cards_detail")
}

/** Ranked list shown in the search page (C) before the user searches. */
@Composable
internal fun TrendingList(cards: List<DramaCard>, onOpen: (DramaCard) -> Unit) {
    Surface(color = CardSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.testTag("search_trending")) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("大家都在看", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 6.dp))
            cards.forEachIndexed { index, card ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(card) }.heightIn(min = 44.dp).testTag("search_trending_$index"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${index + 1}", color = if (index < 3) AccentInk else Muted, fontSize = 15.sp,
                        fontWeight = FontWeight.Black, modifier = Modifier.width(20.dp))
                    Text(card.title, color = Ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (card.episodeCount > 0) Text("${card.episodeCount} 集", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
