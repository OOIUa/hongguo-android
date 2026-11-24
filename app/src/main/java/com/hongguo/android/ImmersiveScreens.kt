package com.hongguo.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hongguo.account.AccountViewModel

/* ---------------------------------------------------------------------------------------------
 * B · 沉浸暗场: video-first, dark. Home is a vertical full-screen feed, 剧场 holds ranking and
 * filters, 追剧 shows continue-watching rails, details open on a full-bleed poster.
 * ------------------------------------------------------------------------------------------- */

private val Gold = Color(0xFFE9C77B)

/** Edge-to-edge poster without the rounded placeholder used by list covers. */
@Composable
internal fun PosterImage(url: String, modifier: Modifier) {
    Box(modifier.background(Cream)) {
        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(url).crossfade(true).build(),
            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun ImmersiveHome(state: HongguoUiState, model: HongguoViewModel) {
    val items = state.home.items
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("immersive_home")) {
        when {
            items.isEmpty() && (state.home.loading || !state.home.loaded) -> Row(Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                Text("正在准备推荐…", Modifier.padding(start = 12.dp), color = Color.White.copy(alpha = .8f), fontSize = 14.sp)
            }
            items.isEmpty() -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.home.error ?: "这一组暂时没有剧集", color = Color.White, fontSize = 15.sp)
                OutlinedButton(onClick = model::refreshHome, modifier = Modifier.testTag("immersive_retry")) {
                    Text("重新加载", color = Color.White)
                }
            }
            else -> {
                val pager = rememberPagerState(pageCount = { items.size })
                LaunchedEffect(pager.currentPage, items.size, state.home.error) {
                    if (pager.currentPage >= items.size - 3 && state.home.hasMore && state.home.error == null) model.loadMoreHome()
                }
                CoverPreloadEffect(items, { pager.currentPage }, { items.size })
                // Only the settled page of a visible, unobscured home plays; anything else releases the preview.
                val previewEnabled = LocalPageActive.current && state.tab == AppTab.HOME && state.selectedCard == null && !state.playerOpen
                val preview = rememberFeedPreview(items.getOrNull(pager.settledPage), previewEnabled, model)
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("immersive_feed"),
                    key = { items.getOrNull(it)?.id ?: it }, beyondViewportPageCount = 1) { page ->
                    val card = items.getOrNull(page) ?: return@VerticalPager
                    ImmersiveFeedPage(card, page, state.favorites.any { it.card.id == card.id }, model,
                        preview.takeIf { it.cardId == card.id })
                }
                // On the last page, say why nothing follows instead of letting the swipe silently do nothing.
                if (pager.currentPage >= items.lastIndex) {
                    val home = state.home
                    val (message, action, onAction) = when {
                        home.error != null -> Triple(home.error, "重试", model::loadMoreHome)
                        home.loadingMore -> Triple("正在加载更多…", null, {})
                        !home.hasMore -> Triple("已经看到最后一部了", "刷新", model::refreshHome)
                        else -> Triple(null, null, {})
                    }
                    if (message != null) Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = .62f))
                        .padding(start = 16.dp, end = if (action != null) 4.dp else 16.dp).heightIn(min = 40.dp)
                        .testTag("immersive_feed_end"), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(vertical = 8.dp))
                        if (action != null) TextButton(onClick = onAction, modifier = Modifier.testTag("immersive_feed_end_action")) {
                            Text(action, color = Gold, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        // Feed switcher and search float above the feed.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp, start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::refreshHome, modifier = Modifier.testTag("immersive_refresh")) {
                Icon(Icons.Outlined.Refresh, "刷新推荐", tint = Color.White)
            }
            val tabs = rememberTabIndicator()
            Row(Modifier.weight(1f).slidingIndicator(tabs, state.homeFeed, Color.White),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom) {
                listOf(HomeFeed.RECOMMEND to "热度", HomeFeed.LATEST to "最新", HomeFeed.COMIC to "漫剧", HomeFeed.GUESS to "推荐").forEach { (feed, label) ->
                    val selected = state.homeFeed == feed
                    val color by animatedTabColor(selected, Color.White, Color.White.copy(alpha = .7f))
                    Column(Modifier.tabAnchor(tabs, feed).quietSelectable(selected) { model.selectHomeFeed(feed) }
                        .heightIn(min = 44.dp).testTag("immersive_feed_${feed.name.lowercase()}").padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, color = color, fontSize = animatedTabSize(selected, 17.sp, 16.sp),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
            IconButton(onClick = { model.selectTab(AppTab.SEARCH) }, modifier = Modifier.testTag("immersive_search")) {
                Icon(Icons.Outlined.Search, "搜索", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ImmersiveFeedPage(card: DramaCard, page: Int, favorite: Boolean, model: HongguoViewModel, preview: FeedPreviewUi?) {
    val playing = preview?.player
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("immersive_page_$page")) {
        // The cover stays until the first frame; then landscape episodes sit on black, not on their poster.
        if (preview?.rendered != true) PosterImage(card.cover, Modifier.fillMaxSize())
        if (playing != null) VideoSurface(VideoPlaybackUi(player = playing, isPlaying = !preview.paused),
            Modifier.fillMaxSize().alpha(if (preview.rendered) 1f else 0f))
        // Tap pauses / resumes and holding either side plays at 2×, as in the full player; the buttons drawn above
        // keep their own clicks and a vertical drag still belongs to the pager.
        if (playing != null) PlayerInteractionLayer(Modifier.fillMaxSize().testTag("immersive_preview_$page"),
            onTap = { preview.toggle() }, onHoldSpeedChange = preview.hold)
        AnimatedVisibility(preview?.holding == true, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
            enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .5f))
                .padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FastForward, null, Modifier.size(16.dp), tint = Color.White)
                Text(" 2× 临时加速中", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (playing != null && preview.paused && !preview.ended) Icon(Icons.Outlined.PlayArrow, "已暂停",
            Modifier.align(Alignment.Center).size(64.dp), tint = Color.White.copy(alpha = .8f))
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = .6f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(380.dp).background(Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val roomy = maxHeight >= 520.dp
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 16.dp + LocalNavClearance.current),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(card.title, color = Color.White, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val info = listOfNotNull("第 1 集".takeIf { playing != null }, card.episodeCount.takeIf { it > 0 }?.let { "全 $it 集" })
                        if (info.isNotEmpty()) Text(info.joinToString(" · "), color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
                        if (roomy && card.summary.isNotBlank()) Text(card.summary, color = Color.White.copy(alpha = .82f), fontSize = 13.sp,
                            lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(if (roomy) 14.dp else 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        RailAction(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (favorite) "已追" else "追剧",
                            tint = if (favorite) Gold else Color.White, tag = "immersive_favorite_$page") { model.toggleFavorite(card) }
                        RailAction(Icons.Outlined.GridView, "选集", tag = "immersive_episodes_$page") { model.openDrama(card) }
                        if (roomy) RailAction(Icons.Outlined.Info, "详情", tag = "immersive_detail_$page") { model.openDrama(card) }
                    }
                }
                // After episode 1 ends, the same button turns into 继续播放 for episode 2.
                val next = preview?.detail?.episodes?.getOrNull(1)?.takeIf { preview.ended }
                val container by animateColorAsState(if (next != null) Orange else Color.White.copy(alpha = .16f),
                    tween(350), label = "immersive_play_color")
                val scale by animateFloatAsState(if (next != null) 1.02f else 1f, spring(dampingRatio = .6f), label = "immersive_play_scale")
                Button(onClick = {
                    // 立即播放 continues the preview where it is instead of restarting episode 1.
                    val first = preview?.detail?.episodes?.firstOrNull()
                    when {
                        next != null -> model.playFromPreview(card, next, 0)
                        first != null && !preview.ended -> model.playFromPreview(card, first, preview.positionMs())
                        else -> model.playDrama(card)
                    }
                }, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp).heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                        .testTag("immersive_play_$page")) {
                    AnimatedContent(next, transitionSpec = {
                        (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { -it } + fadeOut())
                    }, label = "immersive_play_label") { target ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (target != null) Icons.Outlined.SkipNext else Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (target != null) "继续播放" else "立即播放", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            if (target != null) Text(" · 第 ${target.index} 集", fontSize = 14.sp, color = Color.White.copy(alpha = .85f))
                            else if (card.episodeCount > 0) Text(" · 全 ${card.episodeCount} 集", fontSize = 14.sp, color = Color.White.copy(alpha = .75f))
                        }
                    }
                }
                if (roomy) Text("上滑看下一部", color = Color.White.copy(alpha = .55f), fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun RailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.White,
                       tag: String, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .testTag(tag).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImmersiveTheater(state: HongguoUiState, model: HongguoViewModel) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val items = state.category.items
    val ranked = items.take(3)
    val grid = rememberLazyGridState()
    LoadAheadEffect(state.category, { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
        { grid.layoutInfo.totalItemsCount }, state.browseDraft == state.browseApplied, model::loadMoreBrowse)
    LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxSize().testTag("theater_results"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).plusNavClearance(),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tabs = rememberTabIndicator()
                Row(Modifier.weight(1f).slidingIndicator(tabs, state.browseDraft.genre, Orange, width = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                        val selected = state.browseDraft.genre == genre
                        val color by animatedTabColor(selected, Ink, Muted)
                        Text(label, color = color, fontSize = animatedTabSize(selected, 24.sp, 17.sp),
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                            modifier = Modifier.tabAnchor(tabs, genre).quietSelectable(selected) { model.changeGenre(genre) }
                                .heightIn(min = 44.dp).padding(top = 8.dp, bottom = 7.dp).wrapContentHeight(Alignment.Bottom)
                                .testTag("theater_genre_$genre"))
                    }
                }
                RankEntryButton(model, "theater_rank")
                IconButton(onClick = { model.selectTab(AppTab.SEARCH) }, modifier = Modifier.testTag("theater_search")) {
                    Icon(Icons.Outlined.Search, "搜索", tint = Ink)
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val selected = state.browseApplied.options.values.count { it.isNotBlank() }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkChip(if (selected > 0) "筛选 · $selected" else "筛选", selected > 0, "theater_filters", Icons.Outlined.Tune) { showFilters = true }
                state.filterGroups.firstOrNull { it.key == "sort" }?.options?.filter { it.id.isNotBlank() }?.forEach { option ->
                    val on = state.browseApplied.options["sort"] == option.id
                    DarkChip(option.label, on, "theater_sort_${option.id}") {
                        model.selectFilter("sort", option.id)
                        model.applyBrowse()
                    }
                }
            }
        }
        if (ranked.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = CardSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("theater_ranking")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("热播榜", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    ranked.forEachIndexed { index, card ->
                        Row(Modifier.fillMaxWidth().clickable { model.openDrama(card) }.padding(vertical = 8.dp)
                            .testTag("theater_rank_$index"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${index + 1}", color = if (LocalHongguoPalette.current.isDark) listOf(AccentInk, Color(0xFFFFB35C), Gold)[index] else AccentInk, fontSize = 24.sp,
                                fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, modifier = Modifier.width(22.dp))
                            Cover(card.cover, card.title, Modifier.width(48.dp).height(64.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (card.episodeCount > 0) Text("全 ${card.episodeCount} 集", color = Muted, fontSize = 12.sp)
                            }
                            Surface(onClick = { model.playDrama(card) }, shape = RoundedCornerShape(50), color = Cream,
                                modifier = Modifier.heightIn(min = 36.dp).testTag("theater_rank_play_$index")) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.PlayArrow, null, tint = Ink, modifier = Modifier.size(16.dp))
                                    Text("播放", color = Ink, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.category.loading && items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { DarkLoading("正在读取剧场…") }
        if (state.category.loaded && !state.category.loading && items.isEmpty() && state.category.error == null)
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyPrompt("没有符合条件的剧集", "试试减少筛选条件，或切换类型。") }
        if (items.size > 3) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("全部剧集", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        }
        itemsIndexed(items.drop(3), key = { _, card -> card.id }) { index, card ->
            Column(Modifier.animateItem().clickable(role = Role.Button) { model.openDrama(card) }.testTag("theater_result_${index + 3}"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cover(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(.75f))
                Text(card.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        state.category.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
            FailurePanel(error, if (items.isNotEmpty() && state.category.hasMore) model::loadMoreBrowse else model::applyBrowse, "theater_retry")
        } }
        if (state.category.hasMore && items.isNotEmpty() && state.category.error == null) item(span = { GridItemSpan(maxLineSpan) }) {
            LoadAheadFooter("theater_load_more")
        }
        if (!state.category.loading && !state.category.hasMore && items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("已经到底了", Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted, fontSize = 12.sp)
        }
    }
    // Swiping the sheet away discards unapplied choices; otherwise auto-loading and retry would be blocked by a hidden draft.
    if (showFilters) ModalBottomSheet(onDismissRequest = { model.discardBrowseDraft(); showFilters = false }, containerColor = CardSurface) {
        FilterSheetContent(state, model) { showFilters = false }
    }
}

/** Filter groups in a sheet; shared by 剧场 (B) and 发现 (C). Applying closes the sheet. */
@Composable
internal fun FilterSheetContent(state: HongguoUiState, model: HongguoViewModel, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 20.dp)
        .verticalScroll(rememberScrollState()).testTag("filter_sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("筛选", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (state.filtersLoading) DarkLoading("正在读取可选条件…")
        state.filtersError?.let { FailurePanel(it, model::reloadFilters, "retry_filters") }
        state.filterGroups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(group.label, color = Muted, fontSize = 13.sp)
                val selected = state.browseDraft.options[group.key].orEmpty()
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.options.forEach { option ->
                        DarkChip(option.label, if (option.id.isBlank()) selected.isBlank() else selected == option.id,
                            "sheet_filter_${group.key}_${option.id}") { model.selectFilter(group.key, option.id) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { model.resetBrowse(); onDone() }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sheet_reset")) { Text("重置", color = Ink) }
            Button(onClick = { model.applyBrowse(); onDone() }, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("sheet_apply")) { Text("查看结果") }
        }
    }
}

@Composable
internal fun DarkChip(label: String, selected: Boolean, tag: String,
                      icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = if (selected) Ink else Cream,
        modifier = Modifier.heightIn(min = 36.dp).semantics { this.selected = selected }.testTag(tag)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, null, tint = if (selected) Canvas else Muted, modifier = Modifier.size(16.dp))
            Text(label, color = if (selected) Canvas else Muted, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun DarkLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, Modifier.padding(start = 12.dp), color = Muted, fontSize = 14.sp)
    }
}

@Composable
internal fun ImmersiveLibrary(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaChaseScreen(state = state, model = model, accountModel = accountModel, rootTag = "immersive_library")
}

@Composable
internal fun RemoveBadge(description: String, modifier: Modifier, onClick: () -> Unit) {
    // 48dp touch target around a 32dp badge, so a near miss does not open (and auto-play) the poster underneath.
    Box(modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ImmersiveDetail(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    CinemaDetailScreen(state = state, model = model, accountModel = accountModel, rootTag = "immersive_detail")
}
