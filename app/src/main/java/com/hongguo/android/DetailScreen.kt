package com.hongguo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hongguo.account.AccountViewModel

@Composable
internal fun DetailScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val card = state.selectedCard ?: return
    val detail = state.detail
    val favorite = state.favorites.any { it.card.id == card.id }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::backToSearch, modifier = Modifier.testTag("detail_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("剧集详情", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("episode_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Cover(detail?.cover ?: card.cover, card.title, Modifier.width(96.dp).height(132.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(detail?.title ?: card.title, fontSize = 20.sp, lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(detail?.let { "共 ${it.episodes.size} 集" }
                            ?: if (card.episodeCount > 0) "共 ${card.episodeCount} 集" else "正在读取剧集信息",
                            fontSize = 13.sp, color = Muted)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { model.toggleFavorite(card) },
                                modifier = Modifier.testTag("favorite_toggle"),
                                contentPadding = PaddingValues(end = 8.dp)) {
                                Icon(if (favorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = null, Modifier.size(20.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (favorite) "本机已收藏" else "本机收藏", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            item {
                Surface(color = CardSurface, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("红果账号", fontSize = 14.sp, color = Muted, modifier = Modifier.weight(1f))
                        AccountFavoriteAction(card, accountModel, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
                    }
                }
            }
            if (state.loadingDetail) item {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("正在获取完整选集…", Modifier.padding(start = 12.dp), color = Muted, fontSize = 14.sp)
                }
            }
            if (state.detailError != null) item { FailurePanel(state.detailError, model::retryDetails) }
            if (detail != null) {
                if (detail.summary.isNotBlank()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("故事简介", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(detail.summary, color = Muted, fontSize = 14.sp, lineHeight = 23.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("选集", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(if (state.autoPlayNext) "自动连播已开启" else "自动连播已关闭", color = Muted, fontSize = 12.sp)
                    }
                }
                items((detail.episodes.size + 4) / 5, key = { "episodes_$it" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(5) { column ->
                            val index = row * 5 + column
                            val episode = detail.episodes.getOrNull(index)
                            if (episode == null) Spacer(Modifier.weight(1f))
                            else {
                                val selected = state.episodePosition == index
                                Surface(onClick = { model.playEpisode(index) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .testTag("episode_$index"), shape = RoundedCornerShape(12.dp),
                                    color = if (selected) Orange else CardSurface) {
                                    Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                        Text("${episode.index}", fontSize = 15.sp,
                                            color = if (selected) OnOrange else Ink,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (detail != null && detail.episodes.isNotEmpty()) {
            Surface(color = Canvas) {
                val episode = detail.episodes.getOrNull(state.episodePosition)?.index ?: detail.episodes.first().index
                Button(onClick = model::openPlayer, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .heightIn(min = 50.dp).testTag("open_player"), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, Modifier.size(22.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("播放第 $episode 集", fontSize = 16.sp)
                }
            }
        }
    }
}
