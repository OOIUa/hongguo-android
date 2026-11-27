package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hongguo.account.protocol.RankBoard
import com.hongguo.account.protocol.RankEntry
import java.util.Locale

/** Header button that opens the full boards. */
@Composable
internal fun RankEntryButton(model: HongguoViewModel, tag: String) {
    IconButton(onClick = model::openRank, modifier = Modifier.testTag(tag)) { Icon(Icons.Outlined.Leaderboard, "榜单", tint = Ink) }
}

/** The four drama boards and the separate actor ranking. */
@Composable
internal fun RankScreen(state: HongguoUiState, model: HongguoViewModel) {
    BackHandler(enabled = LocalPageActive.current, onBack = model::closeRank)
    val listState = key(state.actorRankSelected, state.rankBoard, state.actorRankBoard) { rememberLazyListState() }
    if (isWideLayout()) {
        // 横屏：标题与榜单类型在左，榜单条目在右。
        Box(Modifier.fillMaxSize().testTag("rank_screen")) {
        WarmTwoPane(
            left = {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 2.dp)) { RankHeader(state, model) }
            },
            right = { RankList(state, model, listState, Modifier.fillMaxSize()) },
        )
        }
        return
    }
    Column(Modifier.fillMaxSize().testTag("rank_screen")) {
        RankHeader(state, model)
        RankList(state, model, listState, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun RankHeader(state: HongguoUiState, model: HongguoViewModel) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closeRank, modifier = Modifier.testTag("rank_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
            }
            Text("榜单", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        val boards = RankBoard.entries
        val selectedIndex = if (state.actorRankSelected) boards.size else state.rankBoard.ordinal
        val tabs: @Composable () -> Unit = {
            boards.forEachIndexed { index, board ->
                RankTab(board.label, "rank_board_${board.route}", selectedIndex == index) { model.selectRankBoard(board) }
            }
            RankTab("演员榜", "rank_board_actors", state.actorRankSelected, model::selectActorRank)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumTabWidth = with(LocalDensity.current) { 45.sp.toDp() } + 16.dp
            if (maxWidth < minimumTabWidth * (boards.size + 1)) {
                ScrollableTabRow(selectedIndex, containerColor = Canvas, contentColor = AccentInk, edgePadding = 0.dp, tabs = tabs)
            } else {
                TabRow(selectedIndex, containerColor = Canvas, contentColor = AccentInk, tabs = tabs)
            }
        }
        if (state.actorRankSelected) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActorRankBoard.entries.forEach { board ->
                    FilterChip(selected = state.actorRankBoard == board, onClick = { model.selectActorRankBoard(board) },
                        modifier = Modifier.testTag("rank_actor_board_${board.name.lowercase(Locale.ROOT)}"),
                        label = { Text(board.label, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Cream, selectedLabelColor = AccentText,
                            containerColor = CardSurface, labelColor = Muted))
                }
            }
        }
    }
}

@Composable
private fun RankTab(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Tab(selected = selected, onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
        selectedContentColor = AccentInk, unselectedContentColor = Muted) {
        Text(label, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun RankList(state: HongguoUiState, model: HongguoViewModel, listState: LazyListState, modifier: Modifier) {
    LazyColumn(modifier.testTag("rank_list"), state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp).plusNavClearance(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.actorRankSelected) {
            items(state.actorRankEntries, key = { it.id }) { entry ->
                ActorRankRow(entry) { model.searchRankActor(entry.name) }
            }
        } else {
            items(state.rankEntries, key = { it.series.id }) { entry -> RankRow(entry) { model.openDrama(entry.toCard()) } }
        }
        when {
            state.rankError != null -> item { FailurePanel(state.rankError, model::retryRank) }
            state.rankLoading -> item {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            state.hasMoreRanks -> item {
                LaunchedEffect(state.actorRankSelected, state.rankBoard, state.actorRankBoard, state.rankPage) { model.loadMoreRank() }
            }
            state.actorRankSelected && state.actorRankEntries.isEmpty() -> item {
                EmptyPrompt("暂无演员榜数据", "请稍后再来看看。")
            }
        }
    }
}

@Composable
private fun ActorRankRow(entry: ActorRankEntry, onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("rank_actor_${entry.id}")
        .clickable(role = Role.Button, onClickLabel = "搜索${entry.name}的作品", onClick = onSearch)
        .heightIn(min = 72.dp).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (entry.rank > 0) entry.rank.toString() else "–", Modifier.widthIn(min = 30.dp),
            color = if (entry.rank in 1..3) AccentInk else Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Box(Modifier.size(48.dp).clip(CircleShape).background(Cream), contentAlignment = Alignment.Center) {
            Text(entry.name.take(1), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clearAndSetSemantics { })
            if (entry.avatar.isNotBlank()) {
                AsyncImage(model = entry.avatar, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.heat.isNotBlank()) Text(entry.heat, color = AccentInk, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.description.isNotBlank()) {
                Text(entry.description, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

internal fun RankEntry.toCard() = DramaCard(series.id, series.title, series.cover, episodeCount, series.description)

@Composable
private fun RankRow(entry: RankEntry, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).heightIn(min = 112.dp).testTag("rank_${entry.series.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (entry.rank > 0) entry.rank.toString() else "–", Modifier.width(30.dp), color = if (entry.rank in 1..3) AccentInk else Muted,
            fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Cover(entry.series.cover, entry.series.title, Modifier.width(78.dp).height(106.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.series.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(entry.heat, if (entry.episodeCount > 0) "全 ${entry.episodeCount} 集" else "").filter { it.isNotBlank() }.joinToString(" · "),
                color = AccentInk, fontSize = 12.sp, maxLines = 1)
            if (entry.series.description.isNotBlank()) Text(entry.series.description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
