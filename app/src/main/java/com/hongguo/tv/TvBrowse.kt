package com.hongguo.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hongguo.account.protocol.RankBoard
import com.hongguo.android.*

@Composable
internal fun TvBrowse(state: HongguoUiState, model: HongguoViewModel, entryFocus: FocusRequester, focusRail: (() -> Unit)? = null) {
    when (state.tab) {
        AppTab.HOME -> TvHome(state, model, entryFocus, focusRail)
        AppTab.CATEGORY -> Discover(state, model, entryFocus)
        AppTab.RANK -> Ranking(state, model, entryFocus)
        AppTab.SEARCH -> Search(state, model, entryFocus)
        else -> Unit
    }
}

@Composable
private fun Title(text: String) = Text(text, color = Color.White, fontSize = 24.sp,
    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun Discover(state: HongguoUiState, model: HongguoViewModel, entry: FocusRequester) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    val filterFocus = remember { FocusRequester() }
    var restoreFilter by rememberSaveable { mutableStateOf(false) }
    fun closeFilters(apply: Boolean) {
        if (apply) model.applyBrowse() else model.discardBrowseDraft()
        filtersOpen = false
        restoreFilter = true
    }
    BackHandler(enabled = filtersOpen) { closeFilters(false) }
    if (filtersOpen) {
        Column(Modifier.fillMaxSize().testTag("tv_filters"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Title("筛选")
                Spacer(Modifier.weight(1f))
                TvButton("查看结果", { closeFilters(true) }, Modifier.focusRequester(entry), selected = true)
                TvButton("重置", { model.resetBrowse(); filtersOpen = false; restoreFilter = true })
                TvButton("取消", { closeFilters(false) })
            }
            if (state.filtersLoading) Text("正在加载筛选项…", color = Color.LightGray)
            state.filtersError?.let { issue ->
                Text(issue, color = Color.LightGray)
                TvButton("重试", model::reloadFilters)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                items(state.filterGroups, key = { it.key }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(group.label, color = Color.White, fontSize = 15.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            group.options.forEach { option ->
                                TvButton(option.label, { model.selectFilter(group.key, option.id) },
                                    selected = state.browseDraft.options[group.key].orEmpty() == option.id)
                            }
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { entry.requestFocus() }
        return
    }
    Column(Modifier.fillMaxSize().testTag("tv_discover"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Title("发现")
            Spacer(Modifier.weight(1f))
            TvButton("榜单", model::openRank, Modifier.testTag("tv_open_rank"))
            TvButton("筛选", { filtersOpen = true }, Modifier.focusRequester(filterFocus).testTag("tv_open_filters"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("short_play" to "短剧", "comic_series" to "漫剧", "ai_series" to "AI 剧").forEach { (genre, label) ->
                val selected = genre == state.browseDraft.genre
                TvButton(label, { model.changeGenre(genre) }, if (selected) Modifier.focusRequester(entry) else Modifier,
                    selected = selected)
            }
        }
        key(state.browseApplied) {
            val feed = state.category
            CatalogGrid(feed.items, model::openDrama, feed.loading || feed.loadingMore, feed.error,
                if (feed.items.isEmpty()) model::applyBrowse else model::loadMoreBrowse,
                feed.hasMore, model::loadMoreBrowse, "当前分类没有剧集")
        }
    }
    LaunchedEffect(filtersOpen) { if (restoreFilter) { filterFocus.requestFocus(); restoreFilter = false } }
}

@Composable
private fun Ranking(state: HongguoUiState, model: HongguoViewModel, entry: FocusRequester) {
    Column(Modifier.fillMaxSize().testTag("tv_rank"), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("榜单")
            Spacer(Modifier.weight(1f))
            TvButton("返回", model::closeRank)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RankBoard.entries.forEach { board ->
                val selected = board == state.rankBoard
                TvButton(board.label, { model.selectRankBoard(board) },
                    if (selected) Modifier.focusRequester(entry) else Modifier, selected = selected)
            }
        }
        key(state.rankBoard) {
            val cards = remember(state.rankEntries) { state.rankEntries.map { it.toCard() } }
            val ranks = remember(state.rankEntries) { state.rankEntries.associate { it.series.id to it.rank } }
            CatalogGrid(cards, model::openDrama, state.rankLoading, state.rankError, model::retryRank,
                state.rankPage < state.rankTotalPages, model::loadMoreRank, "暂无榜单内容", ranks)
        }
    }
}

@Composable
private fun Search(state: HongguoUiState, model: HongguoViewModel, entry: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); model.search() }
    val resultFocus = remember { FocusRequester() }
    var moveToResults by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("tv_search"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Title("搜索")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = state.query, onValueChange = model::updateQuery,
                placeholder = { Text("输入剧名或关键词") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { moveToResults = true; submit() }),
                modifier = Modifier.weight(1f).focusRequester(entry).testTag("tv_search_query"))
            TvButton("搜索", { moveToResults = true; submit() }, enabled = state.query.isNotBlank())
            if (state.searching) TvButton("取消", model::cancelSearch)
        }
        if (state.suggestions.isNotEmpty() && !state.searching) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.suggestions.forEach { value ->
                    TvButton(value, { keyboard?.hide(); moveToResults = true; model.pickSuggestion(value) })
                }
            }
        }
        if (state.searchedQuery.isNotBlank() || state.searching || state.searchError != null) {
            key(state.searchedQuery) {
                CatalogGrid(state.results, model::openDrama, state.searching || state.loadingMore, state.searchError,
                    if (state.results.isEmpty()) model::search else model::loadMore,
                    state.hasMore, model::loadMore, "没有找到相关剧集", firstFocus = resultFocus)
            }
        } else if (state.home.items.isNotEmpty()) {
            Text("你可能喜欢", color = Color.LightGray, fontSize = 16.sp)
            CatalogGrid(state.home.items.take(10), model::openDrama, false, null, {}, false, {}, "")
        }
    }
    LaunchedEffect(state.searching, state.results) {
        if (moveToResults && !state.searching) {
            if (state.results.isNotEmpty()) resultFocus.requestFocus()
            moveToResults = false
        }
    }
}

@Composable
private fun CatalogGrid(cards: List<DramaCard>, open: (DramaCard) -> Unit, loading: Boolean,
                        error: String?, retry: () -> Unit, hasMore: Boolean, more: () -> Unit,
                        empty: String, ranks: Map<String, Int> = emptyMap(), firstFocus: FocusRequester? = null) {
    var returnId by rememberSaveable { mutableStateOf("") }
    val restore = remember { FocusRequester() }
    val layout = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Adaptive(minSize = 106.dp), state = layout, modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(9.dp),
        contentPadding = PaddingValues(4.dp)) {
        itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
            val isReturn = card.id == returnId
            val extra = when {
                isReturn && index == 0 && firstFocus != null -> Modifier.focusRequester(firstFocus).focusRequester(restore)
                isReturn -> Modifier.focusRequester(restore)
                index == 0 && firstFocus != null -> Modifier.focusRequester(firstFocus)
                else -> Modifier
            }
            PosterTile(card, ranks[card.id], extra) { returnId = card.id; open(card) }
            if (isReturn) LaunchedEffect(Unit) {
                restore.requestFocus()
                returnId = ""
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    loading -> Text("正在加载…", color = Color.LightGray)
                    error != null -> { Text(error, color = Color.LightGray); TvButton("重试", retry) }
                    cards.isEmpty() -> Text(empty, color = Color.LightGray)
                    hasMore -> TvButton("加载更多", more)
                }
            }
        }
    }
}

@Composable
private fun PosterTile(card: DramaCard, rank: Int?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
        .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
        .clip(shape).clickable(role = Role.Button, onClick = onClick)
        .padding(3.dp).testTag("tv_card_${card.id}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(.85f).clip(shape).background(Color(0xFF292629))) {
            AsyncImage(model = card.cover, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (rank != null && rank > 0) Text("#$rank", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0xDA432A29)).padding(horizontal = 5.dp))
        }
        Text(card.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp,
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        if (card.episodeCount > 0) Text("全 ${card.episodeCount} 集", color = Color.LightGray, fontSize = 13.sp)
    }
}
