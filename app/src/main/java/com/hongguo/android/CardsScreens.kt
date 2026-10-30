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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
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
import androidx.compose.material3.OutlinedButton
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
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), modifier = Modifier.fillMaxSize().testTag("cards_home"),
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
                IconButton(onClick = { model.selectTab(AppTab.HISTORY) }, modifier = Modifier.testTag("cards_history")) {
                    Icon(Icons.Outlined.History, "观看历史", tint = Ink)
                }
            }
        }
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                listOf(HomeFeed.RECOMMEND to "推荐", HomeFeed.GUESS to "猜你喜欢", HomeFeed.LATEST to "新剧", HomeFeed.COMIC to "漫剧").forEach { (feed, label) ->
                    val selected = state.homeFeed == feed
                    Column(Modifier.quietSelectable(selected) { model.selectHomeFeed(feed) }
                        .heightIn(min = 48.dp).testTag("cards_feed_${feed.name.lowercase()}"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(label, color = if (selected) Ink else Muted, fontSize = if (selected) 19.sp else 15.sp,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium)
                        Box(Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Orange else Color.Transparent))
                    }
                }
                Column(Modifier.clickable(role = Role.Tab) { model.selectTab(AppTab.CATEGORY) }.heightIn(min = 48.dp)
                    .testTag("cards_feed_category"), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("分类", color = Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
                    Text(when (state.homeFeed) { HomeFeed.LATEST -> "新剧榜"; HomeFeed.COMIC -> "漫剧榜"; HomeFeed.GUESS -> "猜你喜欢"; else -> "热度榜" },
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
            WaterfallCard(card, WaterfallRatios[index % WaterfallRatios.size], "cards_result_${index + 5}") { model.openDrama(card) }
        }
        state.home.error?.let { error -> item(span = StaggeredGridItemSpan.FullLine) {
            FailurePanel(error, if (items.isNotEmpty() && state.home.hasMore) model::loadMoreHome else model::refreshHome, "cards_retry")
        } }
        if (state.home.hasMore && items.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            OutlinedButton(onClick = model::loadMoreHome, enabled = !state.home.loadingMore && !state.home.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cards_load_more"), shape = RoundedCornerShape(12.dp)) {
                if (state.home.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("加载更多", color = Ink, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun WaterfallCard(card: DramaCard, ratio: Float, tag: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = CardSurface, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag(tag)) {
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
    LazyColumn(Modifier.fillMaxSize().testTag("cards_discover"),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("发现", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { model.selectTab(AppTab.SEARCH) }, modifier = Modifier.testTag("cards_discover_search")) {
                    Icon(Icons.Outlined.Search, "搜索", tint = Ink)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardSurface).padding(4.dp)) {
                listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                    val selected = state.browseDraft.genre == genre
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (selected) Ink else Color.Transparent)
                        .quietSelectable(selected) { model.changeGenre(genre) }.heightIn(min = 38.dp)
                        .testTag("cards_genre_$genre"), contentAlignment = Alignment.Center) {
                        Text(label, color = if (selected) Canvas else Muted, fontSize = 14.sp,
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
                modifier = Modifier.testTag("cards_discover_$index")) {
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
        if (state.category.hasMore && items.isNotEmpty()) item {
            OutlinedButton(onClick = model::loadMoreBrowse, enabled = !state.category.loadingMore && !state.category.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cards_discover_more"), shape = RoundedCornerShape(12.dp)) {
                if (state.category.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("加载更多", color = Ink, fontSize = 14.sp)
            }
        }
    }
    // Swiping the sheet away discards unapplied choices, so quick chips and 加载更多 keep working on what is shown.
    if (showFilters) ModalBottomSheet(onDismissRequest = { model.discardBrowseDraft(); showFilters = false }, containerColor = CardSurface) {
        FilterSheetContent(state, model) { showFilters = false }
    }
}

@Composable
internal fun CardsLibrary(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val history = state.tab == AppTab.HISTORY
    var editing by rememberSaveable(state.tab, state.libraryCloud) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("cards_library")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(Triple("历史", !state.libraryCloud && history, "cards_tab_history"),
                Triple("收藏", !state.libraryCloud && !history, "cards_tab_favorites"),
                Triple("账号云片单", state.libraryCloud, "cards_tab_account")).forEachIndexed { index, (label, selected, tag) ->
                Text(label, color = if (selected) Ink else Muted, fontSize = if (selected) 22.sp else 15.sp,
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
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp).plusNavClearance(),
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
            items(entries.chunked(3).size, key = { "cards_row_$it" }) { rowIndex ->
                val row = entries.chunked(3)[rowIndex]
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
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
    LazyColumn(Modifier.fillMaxSize().testTag("cards_detail"), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            // The header image ends at 170dp; the title column sits entirely below it and grows downward with large
            // text, while the poster overlaps the image edge.
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(170.dp)) {
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
                IconButton(onClick = model::backToSearch, modifier = Modifier.padding(4.dp).testTag("detail_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
                }
                Box(Modifier.padding(start = 16.dp, top = 123.dp).width(100.dp).height(133.dp).clip(RoundedCornerShape(10.dp))
                    .border(3.dp, CardSurface, RoundedCornerShape(10.dp))) {
                    PosterImage(detail?.cover ?: card.cover, Modifier.fillMaxSize())
                }
            }
        }
        item {
            CardBlock {
                val label = detail?.episodes?.getOrNull(state.episodePosition)?.index ?: detail?.episodes?.firstOrNull()?.index
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = model::openPlayer, enabled = detail != null && detail.episodes.isNotEmpty(),
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(2f).heightIn(min = 46.dp).testTag("open_player")) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp))
                        Text(when { label != null -> "播放第 $label 集"; detail != null -> "暂无剧集"; state.loadingDetail -> "读取中"
                            else -> "无法播放" }, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(onClick = { model.toggleFavorite(card) }, shape = RoundedCornerShape(10.dp), color = Canvas,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("favorite_toggle")) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 4.dp)) {
                            Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null,
                                tint = if (favorite) Color(0xFFF2A900) else Ink, modifier = Modifier.size(18.dp))
                            Text(if (favorite) "已收藏" else "收藏", color = Ink, fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("红果账号", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    AccountFavoriteAction(card, accountModel, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
                }
            }
        }
        if (state.loadingDetail) item { CardsLoading("正在获取完整选集…") }
        state.detailError?.let { error -> item { Box(Modifier.padding(horizontal = 12.dp)) { FailurePanel(error, model::retryDetails) } } }
        if (detail != null) {
            if (detail.summary.isNotBlank()) item {
                CardBlock {
                    Text("简介", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text(detail.summary, color = Muted, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                CardBlock {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选集", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showAll = !showAll }, modifier = Modifier.testTag("cards_episodes_toggle")) {
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
                        detail.episodes.indices.chunked(6).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { index ->
                                    EpisodeSquare(detail.episodes[index].index, state.episodePosition == index,
                                        Modifier.weight(1f).height(48.dp).testTag("episode_all_$index")) { model.playEpisode(index) }
                                }
                                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
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
