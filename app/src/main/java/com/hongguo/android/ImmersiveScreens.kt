package com.hongguo.android

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
                VerticalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("immersive_feed"),
                    key = { items.getOrNull(it)?.id ?: it }, beyondViewportPageCount = 1) { page ->
                    val card = items.getOrNull(page) ?: return@VerticalPager
                    ImmersiveFeedPage(card, page, items.size, state.favorites.any { it.card.id == card.id }, model)
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
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                listOf(HomeFeed.RECOMMEND to "热度", HomeFeed.GUESS to "猜你喜欢", HomeFeed.LATEST to "最新", HomeFeed.COMIC to "漫剧").forEach { (feed, label) ->
                    val selected = state.homeFeed == feed
                    Column(Modifier.quietSelectable(selected) { model.selectHomeFeed(feed) }
                        .heightIn(min = 44.dp).testTag("immersive_feed_${feed.name.lowercase()}").padding(top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, color = if (selected) Color.White else Color.White.copy(alpha = .7f),
                            fontSize = if (selected) 17.sp else 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (selected) Color.White else Color.Transparent))
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
private fun ImmersiveFeedPage(card: DramaCard, page: Int, total: Int, favorite: Boolean, model: HongguoViewModel) {
    Box(Modifier.fillMaxSize().testTag("immersive_page_$page")) {
        PosterImage(card.cover, Modifier.fillMaxSize())
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
                        Text(listOfNotNull(card.episodeCount.takeIf { it > 0 }?.let { "全 $it 集" }, "${page + 1} / $total").joinToString(" · "),
                            color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
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
                Button(onClick = { model.playDrama(card) }, shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = .16f), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp).heightIn(min = 48.dp).testTag("immersive_play_$page")) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("立即播放", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (card.episodeCount > 0) Text(" · 全 ${card.episodeCount} 集", fontSize = 14.sp, color = Color.White.copy(alpha = .75f))
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
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().testTag("theater_results"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp).plusNavClearance(),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                        val selected = state.browseDraft.genre == genre
                        Text(label, color = if (selected) Ink else Muted, fontSize = if (selected) 24.sp else 17.sp,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                            modifier = Modifier.quietSelectable(selected) { model.changeGenre(genre) }
                                .heightIn(min = 44.dp).padding(top = 8.dp).testTag("theater_genre_$genre"))
                    }
                }
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
                            Text("${index + 1}", color = listOf(AccentInk, Color(0xFFFFB35C), Gold)[index], fontSize = 24.sp,
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
            Column(Modifier.clickable(role = Role.Button) { model.openDrama(card) }.testTag("theater_result_${index + 3}"),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Cover(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(.75f))
                Text(card.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        state.category.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
            FailurePanel(error, if (items.isNotEmpty() && state.category.hasMore) model::loadMoreBrowse else model::applyBrowse, "theater_retry")
        } }
        if (state.category.hasMore && items.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = model::loadMoreBrowse, enabled = !state.category.loadingMore && !state.category.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theater_load_more"), shape = RoundedCornerShape(12.dp)) {
                if (state.category.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("加载更多", color = Ink, fontSize = 14.sp)
            }
        }
    }
    // Swiping the sheet away discards unapplied choices; otherwise 加载更多 and retry would be blocked by a hidden draft.
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
    var managing by rememberSaveable { mutableStateOf(false) }
    val canManage = state.history.isNotEmpty() || state.favorites.isNotEmpty()
    LaunchedEffect(canManage) { if (!canManage) managing = false }
    Column(Modifier.fillMaxSize().testTag("immersive_library")) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("追剧", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50)).background(Cream).padding(3.dp)) {
                listOf(false to "本机", true to "账号").forEach { (cloud, label) ->
                    val selected = state.libraryCloud == cloud
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Ink else Color.Transparent)
                        .quietSelectable(selected) { model.selectLibrarySource(cloud) }
                        .heightIn(min = 40.dp).padding(horizontal = 16.dp).testTag("immersive_source_${if (cloud) "account" else "local"}"),
                        contentAlignment = Alignment.Center) {
                        Text(label, color = if (selected) Canvas else Muted, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (state.libraryCloud) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CloudLibraryContent(accountModel, model::openLibrary, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp).plusNavClearance(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SectionTitle("继续看", if (canManage) (if (managing) "完成" else "管理") else null,
                    "immersive_manage") { managing = !managing }
            }
            item {
                if (state.history.isEmpty()) Text("开始播放后，这里会记住看到哪一集。", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
                else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.history, key = { it.card.id }) { entry ->
                        Column(Modifier.animateItem().width(132.dp).clickable { model.openLibrary(entry) }.testTag("immersive_history_${entry.card.id}"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(10.dp))) {
                                PosterImage(entry.card.cover, Modifier.fillMaxSize())
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(60.dp)
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
                                Text("第 ${entry.episodeIndex.coerceAtLeast(1)} 集", color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp, 0.dp, 8.dp, 10.dp))
                                WatchProgress(entry, track = Color.White.copy(alpha = .25f), fill = Orange,
                                    modifier = Modifier.align(Alignment.BottomCenter))
                                if (managing) RemoveBadge("删除${entry.card.title}的记录", Modifier.align(Alignment.TopEnd)) {
                                    model.removeHistory(entry.card.id)
                                }
                            }
                            Text(entry.card.title, color = Ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { SectionTitle("我的追剧", null, "") {} }
            if (state.favorites.isEmpty()) item {
                Text("在推荐流或详情里点“追剧”，喜欢的故事就会留在这里。", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(state.favorites.chunked(3), key = { row -> row.first().card.id }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry ->
                        Column(Modifier.weight(1f).clickable { model.openLibrary(entry) }.testTag("immersive_favorite_${entry.card.id}"),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(.75f).clip(RoundedCornerShape(10.dp))) {
                                PosterImage(entry.card.cover, Modifier.fillMaxSize())
                                if (managing) RemoveBadge("取消追剧${entry.card.title}", Modifier.align(Alignment.TopEnd)) {
                                    model.toggleFavorite(entry.card)
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
private fun SectionTitle(title: String, action: String?, tag: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.testTag(tag)) { Text(action, color = Muted, fontSize = 13.sp) }
    }
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
    val card = state.selectedCard ?: return
    val detail = state.detail
    val favorite = state.favorites.any { it.card.id == card.id }
    var range by rememberSaveable(card.id) { mutableIntStateOf(-1) }
    LazyColumn(Modifier.fillMaxSize().testTag("immersive_detail"), contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            // The picture runs up under the transparent status bar; the back button stays below it.
            val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            Box(Modifier.fillMaxWidth().height(360.dp + statusTop)) {
                PosterImage(detail?.cover ?: card.cover, Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().height(statusTop + 56.dp).background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .45f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    .35f to Color.Transparent, 1f to Canvas)))
                IconButton(onClick = model::backToSearch, modifier = Modifier.statusBarsPadding().padding(6.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .35f)).testTag("detail_back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(detail?.title ?: card.title, color = Ink, fontSize = 26.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(detail?.let { "全 ${it.episodes.size} 集" } ?: if (card.episodeCount > 0) "全 ${card.episodeCount} 集" else "正在读取剧集信息",
                        color = Muted, fontSize = 13.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val episodeLabel = detail?.episodes?.getOrNull(state.episodePosition)?.index ?: detail?.episodes?.firstOrNull()?.index
                Button(onClick = model::openPlayer, enabled = detail != null && detail.episodes.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Canvas),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("open_player")) {
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(when { episodeLabel != null -> "播放第 $episodeLabel 集"; detail != null -> "暂无可播放剧集"
                        state.loadingDetail -> "正在读取选集"; else -> "暂时无法播放" }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { model.toggleFavorite(card) }, modifier = Modifier.weight(1f).testTag("favorite_toggle")) {
                        Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, null,
                            tint = if (favorite) Gold else Ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (favorite) "已追剧" else "追剧", color = Ink, fontSize = 14.sp)
                    }
                    Box(Modifier.weight(1.4f), contentAlignment = Alignment.Center) {
                        AccountFavoriteAction(card, accountModel, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
                    }
                }
                Text(if (state.autoPlayNext) "自动连播已开启" else "自动连播已关闭", color = Muted, fontSize = 12.sp)
            }
        }
        if (state.loadingDetail) item { DarkLoading("正在获取完整选集…") }
        state.detailError?.let { error -> item { Box(Modifier.padding(horizontal = 16.dp)) { FailurePanel(error, model::retryDetails) } } }
        if (detail != null) {
            if (detail.summary.isNotBlank()) item {
                Text(detail.summary, color = Muted, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val ranges = detail.episodes.indices.chunked(30)
            val current = if (range in ranges.indices) range
                else ranges.indexOfFirst { state.episodePosition in it }.coerceAtLeast(0)
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
                    Text("选集", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (ranges.size > 1) ranges.forEachIndexed { i, indices ->
                        val label = "${detail.episodes[indices.first()].index}-${detail.episodes[indices.last()].index}"
                        Text(label, color = if (i == current) Ink else Muted, fontSize = 14.sp,
                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.quietSelectable(i == current) { range = i }
                                .heightIn(min = 48.dp).wrapContentHeight().testTag("episode_range_$i"))
                    }
                }
            }
            items(ranges.getOrElse(current) { emptyList() }.chunked(6), key = { "row_${it.first()}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        val selected = state.episodePosition == index
                        Surface(onClick = { model.playEpisode(index) }, shape = RoundedCornerShape(8.dp),
                            color = if (selected) Orange else Cream,
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp).testTag("episode_$index")) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("${detail.episodes[index].index}", color = if (selected) OnOrange else Ink, fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(vertical = 12.dp))
                            }
                        }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
