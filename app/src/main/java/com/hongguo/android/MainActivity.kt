package com.hongguo.android

import android.os.Bundle
import com.hongguo.account.AccountViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

internal val Orange = Color(0xFFE85A37)
internal val Ink = Color(0xFF292421)
internal val Muted = Color(0xFF81756D)
internal val Canvas = Color(0xFFFCF8F4)
internal val Cream = Color(0xFFF5EDE5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Orange, onPrimary = Color.White,
                    background = Canvas, surface = Color.White, onSurface = Ink,
                    secondary = Color(0xFFB46A32)),
                typography = Typography(),
            ) {
                val model: HongguoViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                val accountModel: AccountViewModel = viewModel()
                val accountState by accountModel.state.collectAsStateWithLifecycle()
                val updateModel: UpdateViewModel = viewModel()
                val updateState by updateModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.playerOpen || state.selectedCard != null || state.tab != AppTab.HOME) {
                    if (state.playerOpen) model.closePlayer()
                    else if (state.selectedCard != null) model.backToSearch()
                    else if (state.tab in setOf(AppTab.HISTORY, AppTab.ACCOUNT)) model.selectTab(AppTab.MINE)
                    else model.selectTab(AppTab.HOME)
                }
                if (state.playerOpen && state.detail != null) EpisodePlayerScreen(state, model, accountModel)
                else {
                    Scaffold(containerColor = Canvas, bottomBar = {
                        if (state.selectedCard == null) AppNavigation(state.tab, model::selectTab)
                    }) { insets ->
                        Column(Modifier.fillMaxSize().padding(insets).semantics { testTagsAsResourceId = true }) {
                            if (state.selectedCard == null) {
                                BrandHeader()
                                when (state.tab) {
                                    AppTab.HOME -> HomeScreen(state, model)
                                    AppTab.CATEGORY -> CategoryScreen(state, model)
                                    AppTab.SEARCH -> SearchScreen(state, model)
                                    AppTab.FAVORITES -> LibraryScreen(state, model, history = false)
                                    AppTab.MINE -> MyScreen(state, model, updateState, updateModel,
                                        accountName = accountState.profile?.nickname,
                                        accountStatus = if (accountState.profile != null) "查看账号收藏、历史与预约" else "登录后查看手机片单")
                                    AppTab.ACCOUNT -> AccountScreen(accountModel,
                                        onBack = { model.selectTab(AppTab.MINE) }, onOpenLibrary = model::openLibrary)
                                    AppTab.HISTORY -> Column(Modifier.fillMaxSize()) {
                                        TextButton(onClick = { model.selectTab(AppTab.MINE) }) { Text("‹ 我的") }
                                        LibraryScreen(state, model, history = true)
                                    }
                                }
                            }
                            else DetailScreen(state, model, accountModel)
                        }
                    }
                }
                UpdateDialogs(updateState, updateModel, this@MainActivity)
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    val compact = LocalConfiguration.current.screenHeightDp < 500
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = if (compact) 7.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 30.dp else 40.dp).clip(RoundedCornerShape(11.dp)).background(Orange), Alignment.Center) {
            Text("果", color = Color.White, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("红果剧场", fontSize = if (compact) 18.sp else 21.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (!compact) Text("好故事，随时看", fontSize = 11.sp, color = Muted)
        }
        Spacer(Modifier.weight(1f))
        Surface(color = Cream, shape = RoundedCornerShape(20.dp)) {
            Text("本机直连", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 10.sp, color = Muted)
        }
    }
}

@Composable
private fun DetailScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel) {
    val card = state.selectedCard ?: return
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = model::backToSearch) { Text("‹ 返回", color = Muted) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.detail?.title ?: card.title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.detail?.let { "共 ${it.episodes.size} 集" } ?: "正在读取剧集信息",
                    fontSize = 11.sp, color = Muted)
            }
            Cover(state.detail?.cover ?: card.cover, card.title, Modifier.width(36.dp).height(49.dp))
            val favorite = state.favorites.any { it.card.id == card.id }
            IconButton(onClick = { model.toggleFavorite(card) }, modifier = Modifier.testTag("favorite_toggle")
                .semantics { contentDescription = if (favorite) "取消收藏" else "收藏这部剧" }) {
                Text(if (favorite) "★" else "☆", fontSize = 26.sp, color = if (favorite) Orange else Muted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            AccountFavoriteAction(card, accountModel, onSignIn = { model.selectTab(AppTab.ACCOUNT) })
        }
        if (state.loadingDetail) {
            Column(Modifier.fillMaxWidth().padding(vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(27.dp), strokeWidth = 3.dp)
                Text("正在获取完整选集…", Modifier.padding(top = 15.dp), color = Muted, fontSize = 13.sp)
                TextButton(onClick = model::backToSearch) { Text("取消") }
            }
        }
        if (state.detailError != null) FailurePanel(state.detailError, model::retryDetails)
        state.detail?.let { detail ->
            Box(Modifier.fillMaxWidth().height(if (LocalConfiguration.current.screenHeightDp < 500) 115.dp else 165.dp)
                .clip(RoundedCornerShape(18.dp)).background(Color(0xFF181614)), Alignment.Center) {
                Cover(detail.cover, detail.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("上下滑动，接着看好故事", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                    val episode = detail.episodes.getOrNull(state.episodePosition)?.index ?: detail.episodes.firstOrNull()?.index ?: 1
                    Button(onClick = model::openPlayer, modifier = Modifier.testTag("open_player"), shape = RoundedCornerShape(13.dp)) {
                        Text("▶  播放第 $episode 集")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选集", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${detail.episodes.size} 集 · ${if (state.autoPlayNext) "播完自动下一集" else "上下滑动切集"}", color = Muted, fontSize = 11.sp)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("episode_list"), state = listState,
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                items((detail.episodes.size + 4) / 5, key = { "episodes_$it" }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { column ->
                            val index = row * 5 + column
                            val episode = detail.episodes.getOrNull(index)
                            if (episode == null) Spacer(Modifier.weight(1f))
                            else {
                                val selected = state.episodePosition == index
                                Surface(modifier = Modifier.weight(1f).height(43.dp).testTag("episode_$index")
                                    .clip(RoundedCornerShape(11.dp)).clickable { model.playEpisode(index) },
                                    shape = RoundedCornerShape(11.dp), color = if (selected) Orange else Color.White) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("${episode.index}", fontSize = 14.sp,
                                            color = if (selected) Color.White else Ink,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
                if (detail.summary.isNotBlank()) item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp)) {
                        Text("故事简介", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(detail.summary, Modifier.padding(top = 9.dp), color = Muted, fontSize = 13.sp, lineHeight = 22.sp)
                    }
                }
                item { Spacer(Modifier.height(15.dp)) }
            }
        }
    }
}
