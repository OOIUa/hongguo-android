package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    /** 横屏时搜索、频道、继续看与榜单都在左栏，瀑布流只剩海报（[headerInLeft]）。 */
    @Composable
    fun Feed(headerInLeft: Boolean) {
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = grid, modifier = Modifier.fillMaxSize().testTag("cards_home"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp + LocalWarmTopInset.current, bottom = 24.dp).plusNavClearance(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp) {
            if (!headerInLeft) {
                item(span = StaggeredGridItemSpan.FullLine) { CardsHomeSearch(ranked.firstOrNull(), model) }
                item(span = StaggeredGridItemSpan.FullLine) { CardsHomeChannels(state, model) }
                state.history.firstOrNull()?.let { entry ->
                    item(span = StaggeredGridItemSpan.FullLine) { CardsHomeContinue(entry, model) }
                }
                if (ranked.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { CardsHomeRanking(state, ranked, model) }
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
    if (isWideLayout()) {
        WarmTwoPane(
            left = {
                WarmTopInset()
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardsHomeSearch(ranked.firstOrNull(), model)
                    CardsHomeChannels(state, model)
                    state.history.firstOrNull()?.let { entry -> CardsHomeContinue(entry, model) }
                    if (ranked.isNotEmpty()) CardsHomeRanking(state, ranked, model)
                }
            },
            right = { Feed(headerInLeft = true) },
        )
        return
    }
    Feed(headerInLeft = false)
}

@Composable
private fun CardsHomeSearch(ranked: DramaCard?, model: HongguoViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(CardSurface)
            .clickable(role = Role.Button) { model.selectTab(AppTab.SEARCH) }.heightIn(min = 40.dp)
            .padding(horizontal = 14.dp).testTag("cards_search"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
            Text(ranked?.title ?: "搜索剧名或关键词", color = Muted, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { model.openPlayHistory() }, modifier = Modifier.testTag("cards_history")) {
            Icon(Icons.Outlined.History, "观看历史", tint = Ink)
        }
    }
}

@Composable
private fun CardsHomeChannels(state: HongguoUiState, model: HongguoViewModel) {
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

@Composable
private fun CardsHomeContinue(entry: LibraryEntry, model: HongguoViewModel) {
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

@Composable
private fun CardsHomeRanking(state: HongguoUiState, ranked: List<DramaCard>, model: HongguoViewModel) {
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
    /** 横屏时标题、剧种与筛选都在左栏，列表里不再画一遍（[headerInLeft]）。 */
    @Composable
    fun Results(headerInLeft: Boolean) {
        LazyColumn(Modifier.fillMaxSize().testTag("cards_discover"), state = list,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp + LocalWarmTopInset.current, bottom = 24.dp).plusNavClearance(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!headerInLeft) {
                item { CardsDiscoverTitleBar(state, model) }
                item { CardsDiscoverGenres(state, model) }
                item { CardsDiscoverFilters(state, model, stacked = false) { showFilters = true } }
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
    }
    if (isWideLayout()) {
        WarmTwoPane(
            left = {
                WarmTopInset()
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CardsDiscoverTitleBar(state, model)
                    CardsDiscoverGenres(state, model)
                    CardsDiscoverFilters(state, model, stacked = true) { showFilters = true }
                }
            },
            right = { Results(headerInLeft = true) },
        )
    } else {
        Results(headerInLeft = false)
    }
    // Swiping the sheet away discards unapplied choices, so quick chips and auto-loading keep working on what is shown.
    if (showFilters) ModalBottomSheet(onDismissRequest = { model.discardBrowseDraft(); showFilters = false }, containerColor = CardSurface) {
        FilterSheetContent(state, model) { showFilters = false }
    }
}

@Composable
private fun CardsDiscoverTitleBar(state: HongguoUiState, model: HongguoViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("发现", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f).padding(start = 4.dp))
        RankEntryButton(model, "cards_discover_rank")
        IconButton(onClick = { model.selectTab(AppTab.SEARCH) }, modifier = Modifier.testTag("cards_discover_search")) {
            Icon(Icons.Outlined.Search, "搜索", tint = Ink)
        }
    }
}

@Composable
private fun CardsDiscoverGenres(state: HongguoUiState, model: HongguoViewModel) {
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

/** The first filter group stays visible as one-tap chips; the rest live in the sheet. */
@Composable
private fun CardsDiscoverFilters(state: HongguoUiState, model: HongguoViewModel, stacked: Boolean, onMore: () -> Unit) {
    val group = state.filterGroups.firstOrNull { it.key == "theme" } ?: state.filterGroups.firstOrNull()
    val count = state.browseApplied.options.values.count { it.isNotBlank() }
    val chips = buildList {
        add(DiscoverChip(if (count > 0) "筛选 · $count" else "全部筛选", count > 0, "cards_filters", Icons.Outlined.Tune, onMore))
        group?.options?.filter { it.id.isNotBlank() }?.take(12)?.forEach { option ->
            val on = state.browseApplied.options[group.key] == option.id
            add(DiscoverChip(option.label, on, "cards_quick_${group.key}_${option.id}", null) {
                model.selectFilter(group.key, option.id)
                model.applyBrowse()
            })
        }
    }
    if (stacked) {
        // 半宽左栏里横向滚动看不全后面的 chip，改成换行排布。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { chip -> DarkChip(chip.label, chip.selected, chip.tag, chip.icon, chip.onClick) }
        }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            chips.forEach { chip -> DarkChip(chip.label, chip.selected, chip.tag, chip.icon, chip.onClick) }
        }
    }
}

private class DiscoverChip(
    val label: String, val selected: Boolean, val tag: String,
    val icon: ImageVector?, val onClick: () -> Unit,
)

@Composable
internal fun CardsLibrary(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val history = state.tab == AppTab.HISTORY
    var editing by rememberSaveable(state.tab, state.libraryCloud) { mutableStateOf(false) }
    // 横屏一行放 6 个，竖屏维持 3 个；本机历史与本机收藏共用这一条路径。
    val columns = if (isWideLayout()) 6 else 3
    Column(Modifier.fillMaxSize().testTag("cards_library")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(Triple("历史", !state.libraryCloud && history, "cards_tab_history"),
                Triple("收藏", !state.libraryCloud && !history, "cards_tab_favorites"),
                Triple("账号云片单", state.libraryCloud, "cards_tab_account")).forEachIndexed { index, (label, selected, tag) ->
                val color by animatedTabColor(selected, Ink, Muted)
                Text(label, color = color, fontSize = animatedTabSize(selected, 22.sp, 15.sp),
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                    modifier = Modifier.quietSelectable(selected) {
                        when (index) {
                            0 -> { model.selectLibrarySource(false); model.selectTab(AppTab.HISTORY) }
                            1 -> { model.selectLibrarySource(false); model.selectTab(AppTab.FAVORITES) }
                            else -> model.selectLibrarySource(true)
                        }
                    }.heightIn(min = 48.dp).wrapContentHeight(Alignment.Bottom).testTag(tag))
            }
            Spacer(Modifier.weight(1f))
            val entries = if (history) state.history else state.favorites
            LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) editing = false }
            if (!state.libraryCloud && entries.isNotEmpty()) TextButton(onClick = { editing = !editing },
                modifier = Modifier.testTag("cards_library_edit")) { Text(if (editing) "完成" else "编辑", color = Muted) }
        }
        if (state.libraryCloud) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CloudLibraryContent(accountModel, model::openLibrary, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
            }
            return@Column
        }
        val entries = if (history) state.history else state.favorites
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp,
            top = 12.dp, bottom = 12.dp).plusNavClearance(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(onClick = { model.selectLibrarySource(true) }, color = CardSurface, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("cards_library_cloud_hint")) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.CloudQueue, null, tint = Ink, modifier = Modifier.size(20.dp))
                        Text("登录红果账号，手机上的追剧记录也能接着看", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("查看", color = AccentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (entries.isEmpty()) item {
                EmptyPrompt(if (history) "还没有观看记录" else "还没有收藏的剧集",
                    if (history) "开始播放后，这里会记住集数和进度。" else "在剧集详情中收藏，喜欢的故事就会留在这里。")
            }
            items(entries.chunked(columns).size, key = { "cards_row_$it" }) { rowIndex ->
                val row = entries.chunked(columns)[rowIndex]
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.weight(1f).clickable { model.openLibrary(entry) }.testTag("cards_entry_${entry.card.id}"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(10.dp))) {
                                PosterImage(entry.card.cover, Modifier.fillMaxSize())
                                if (history) {
                                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp).background(
                                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                                    Text("第 ${entry.episodeIndex.coerceAtLeast(1)} 集", color = Color.White, fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 9.dp))
                                    WatchProgress(entry, track = Color.White.copy(alpha = .35f), fill = Color.White,
                                        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 4.dp))
                                }
                                if (editing) RemoveBadge(if (history) "删除${entry.card.title}的记录" else "取消收藏${entry.card.title}",
                                    Modifier.align(Alignment.TopEnd)) {
                                    if (history) model.removeHistory(entry.card.id) else model.toggleFavorite(entry.card)
                                }
                            }
                            Text(entry.card.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun CardsDetail(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val card = state.selectedCard ?: return
    val detail = state.detail
    val favorite = state.favorites.any { it.card.id == card.id }
    var showAll by rememberSaveable(card.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (isWideLayout()) {
        // 横屏：矮头图直接顶到屏幕最上（压着状态栏），下面接海报标题、播放收藏与简介；相关推荐与选集在右。
        WarmTwoPane(
            left = {
                Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardsDetailHeader(card, detail, model::closeDetail, 80.dp)
                    CardsDetailActions(card, state, model, detail, favorite, accountModel)
                    if (state.loadingDetail) CardsLoading("正在获取完整选集…")
                    state.detailError?.let { error -> Box(Modifier.padding(horizontal = 12.dp)) { FailurePanel(error, model::retryDetails) } }
                    if (detail != null && detail.summary.isNotBlank()) CardsDetailSummary(detail)
                    if (detail != null && detail.cast.isNotEmpty()) CardBlock { CastSection(detail.cast) }
                }
            },
            rightScroll = true,
            right = {
                WarmTopInset()
                Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.related.isNotEmpty()) CardBlock { RelatedWorksSection(state, model, accountModel) }
                    if (detail != null) CardsDetailEpisodes(state, model, detail, showAll) { showAll = !showAll }
                }
            },
        )
        return
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("cards_detail"), state = list, contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { CardsDetailHeader(card, detail, model::closeDetail, 170.dp + statusTop) }
        item { CardsDetailActions(card, state, model, detail, favorite, accountModel) }
        if (state.loadingDetail) item { CardsLoading("正在获取完整选集…") }
        state.detailError?.let { error -> item { Box(Modifier.padding(horizontal = 12.dp)) { FailurePanel(error, model::retryDetails) } } }
        if (detail != null) {
            if (detail.summary.isNotBlank()) item { CardsDetailSummary(detail) }
            if (detail.cast.isNotEmpty()) item { CardBlock { CastSection(detail.cast) } }
            if (state.related.isNotEmpty()) item { CardBlock { RelatedWorksSection(state, model, accountModel) } }
            item { CardsDetailEpisodes(state, model, detail, showAll) { showAll = !showAll } }
        }
    }
    DetailStatusBar(list, 170.dp + statusTop)
    }
}

@Composable
private fun CardsDetailHeader(card: DramaCard, detail: DramaDetail?, onBack: () -> Unit, heroHeight: Dp) {
    // 头图从屏幕最上开始、压着透明状态栏，底边接标题列；海报压在头图下沿。
    // 竖屏 heroHeight = 170dp + 状态栏高度；横屏把整块压矮，直接从 80dp 起算。
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(heroHeight)) {
                PosterImage(detail?.cover ?: card.cover, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)))
            }
            Column(Modifier.fillMaxWidth().padding(start = 130.dp, end = 16.dp, top = 8.dp).heightIn(min = 78.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(detail?.title ?: card.title, color = Ink, fontSize = 19.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail?.let { "全 ${it.episodes.size} 集" } ?: if (card.episodeCount > 0) "全 ${card.episodeCount} 集" else "正在读取",
                    color = Muted, fontSize = 12.sp)
            }
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(4.dp).testTag("detail_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
        }
        Box(Modifier.padding(start = 16.dp, top = (heroHeight - 47.dp).coerceAtLeast(0.dp)).width(100.dp).height(133.dp)
            .clip(RoundedCornerShape(10.dp)).border(3.dp, CardSurface, RoundedCornerShape(10.dp))) {
            PosterImage(detail?.cover ?: card.cover, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CardsDetailActions(card: DramaCard, state: HongguoUiState, model: HongguoViewModel, detail: DramaDetail?,
                               favorite: Boolean, accountModel: AccountViewModel) {
    CardBlock {
        val label = detail?.episodes?.getOrNull(state.episodePosition)?.index ?: detail?.episodes?.firstOrNull()?.index
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::openPlayer, enabled = detail != null && detail.episodes.isNotEmpty(),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("open_player")) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                Text(when { label != null -> "播放第 $label 集"; detail != null -> "暂无剧集"; state.loadingDetail -> "读取中"
                    else -> "无法播放" }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.padding(top = 8.dp)) {
            FavoritePair(card, favorite, { model.toggleFavorite(card) }, accountModel, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
        }
    }
}

@Composable
private fun CardsDetailSummary(detail: DramaDetail) {
    CardBlock {
        Text("简介", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(detail.summary, color = Muted, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardsDetailEpisodes(state: HongguoUiState, model: HongguoViewModel, detail: DramaDetail,
                                showAll: Boolean, onToggle: () -> Unit) {
    val columns = if (isWideLayout()) 5 else 6
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选集", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggle, modifier = Modifier.testTag("cards_episodes_toggle")) {
                Text(if (showAll) "收起" else "全部 ${detail.episodes.size} 集", color = Muted, fontSize = 13.sp)
            }
        }
        if (!showAll) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(detail.episodes.size, key = { it }) { index ->
                    EpisodeSquare(detail.episodes[index].index, state.episodePosition == index,
                        Modifier.size(52.dp).testTag("episode_$index")) { model.playEpisode(index) }
                }
            }
        } else {
            detail.episodes.indices.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        EpisodeSquare(detail.episodes[index].index, state.episodePosition == index,
                            Modifier.weight(1f).height(48.dp).testTag("episode_all_$index")) { model.playEpisode(index) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun EpisodeSquare(number: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = if (selected) Ink else Canvas, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Text("$number", color = if (selected) Canvas else Ink, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
    Surface(color = CardSurface, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
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
