package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.protocol.RankBoard
import com.hongguo.account.protocol.RankEntry

/** Header button that opens the full boards. */
@Composable
internal fun RankEntryButton(model: HongguoViewModel, tag: String) {
    IconButton(onClick = model::openRank, modifier = Modifier.testTag(tag)) { Icon(Icons.Outlined.Leaderboard, "榜单", tint = Ink) }
}

/** The website's public boards: 综合, 真人剧, 漫剧 and AI 剧. */
@Composable
internal fun RankScreen(state: HongguoUiState, model: HongguoViewModel) {
    BackHandler(enabled = LocalPageActive.current, onBack = model::closeRank)
    Column(Modifier.fillMaxSize().testTag("rank_screen")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closeRank, modifier = Modifier.testTag("rank_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Ink)
            }
            Text("榜单", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        TabRow(selectedTabIndex = state.rankBoard.ordinal, containerColor = Canvas, contentColor = AccentInk) {
            RankBoard.entries.forEach { board ->
                Tab(selected = state.rankBoard == board, onClick = { model.selectRankBoard(board) },
                    modifier = Modifier.testTag("rank_board_${board.route}"), selectedContentColor = AccentInk, unselectedContentColor = Muted,
                    text = { Text(board.label, fontSize = 15.sp, maxLines = 1) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("rank_list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp).plusNavClearance(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.rankEntries, key = { it.series.id }) { entry -> RankRow(entry) { model.openDrama(entry.toCard()) } }
            when {
                state.rankError != null -> item { FailurePanel(state.rankError, model::retryRank) }
                state.rankLoading -> item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                }
                state.rankPage < state.rankTotalPages -> item { LaunchedEffect(state.rankPage) { model.loadMoreRank() } }
            }
        }
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
