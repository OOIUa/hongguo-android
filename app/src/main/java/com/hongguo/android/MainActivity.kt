package com.hongguo.android

import android.os.Bundle
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hongguo.api.service.Stream
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

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
                val updateModel: UpdateViewModel = viewModel()
                val updateState by updateModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.selectedCard != null || state.tab != AppTab.HOME) {
                    if (state.selectedCard != null) model.backToSearch() else model.selectTab(AppTab.HOME)
                }
                Scaffold(containerColor = Canvas, bottomBar = {
                    if (state.selectedCard == null) AppNavigation(state.tab, model::selectTab)
                }) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).semantics { testTagsAsResourceId = true }) {
                        if (state.selectedCard == null) {
                            BrandHeader(updateModel::checkForUpdates)
                            when (state.tab) {
                                AppTab.HOME -> HomeScreen(state, model)
                                AppTab.CATEGORY -> CategoryScreen(state, model)
                                AppTab.SEARCH -> SearchScreen(state, model)
                                AppTab.FAVORITES -> LibraryScreen(state, model, history = false)
                                AppTab.HISTORY -> LibraryScreen(state, model, history = true)
                            }
                        }
                        else DetailScreen(state, model)
                    }
                }
                UpdateDialogs(updateState, updateModel, this@MainActivity)
            }
        }
    }
}

@Composable
private fun BrandHeader(onCheckUpdates: () -> Unit) {
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
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onCheckUpdates, modifier = Modifier.height(32.dp).testTag("update_check")) {
                Text("检查更新", fontSize = 12.sp)
            }
            if (!compact) Text("本机直连", fontSize = 9.sp, color = Muted, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@Composable
private fun DetailScreen(state: HongguoUiState, model: HongguoViewModel) {
    val card = state.selectedCard ?: return
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = model::backToSearch) { Text("‹ 返回", color = Muted) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.detail?.title ?: card.title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(state.detail?.let { "共 ${it.episodes.size} 集 · 进度保存在本机" } ?: "正在读取剧集信息",
                    fontSize = 11.sp, color = Muted)
            }
            Cover(state.detail?.cover ?: card.cover, card.title, Modifier.width(36.dp).height(49.dp))
            val favorite = state.favorites.any { it.card.id == card.id }
            IconButton(onClick = { model.toggleFavorite(card) }, modifier = Modifier.testTag("favorite_toggle")
                .semantics { contentDescription = if (favorite) "取消收藏" else "收藏这部剧" }) {
                Text(if (favorite) "★" else "☆", fontSize = 26.sp, color = if (favorite) Orange else Muted)
            }
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
            // Keep the player outside the lazy list: browsing far-away episodes must not release it.
            PlaybackPanel(state, model)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = model::previousEpisode, enabled = state.episodePosition > 0,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("上一集") }
                Button(onClick = model::nextEpisode,
                    enabled = state.episodePosition >= 0 && state.episodePosition < detail.episodes.lastIndex,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp)) { Text("下一集") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("选集", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${detail.episodes.size} 集 · 播完自动下一集", color = Muted, fontSize = 11.sp)
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

@Composable
private fun PlaybackPanel(state: HongguoUiState, model: HongguoViewModel) {
    val screen = LocalConfiguration.current
    val videoHeight = if (screen.screenHeightDp < 500) 140.dp else ((screen.screenWidthDp - 40) * 10f / 16f).coerceAtMost(270f).dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(videoHeight).clip(RoundedCornerShape(16.dp)).background(Color(0xFF181614)), Alignment.Center) {
            when {
                state.video != null -> LocalVideoPlayer(state.video, state.resumePositionMs, model)
                state.preparing -> Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(29.dp), color = Color(0xFFFFA17C), strokeWidth = 3.dp)
                    Text(preparationLabel(state.preparationStage), color = Color.White, fontSize = 14.sp)
                    if (state.preparationTotal > 0) {
                        LinearProgressIndicator(progress = { (state.preparationDone.toFloat() / state.preparationTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Color(0xFF51443C))
                        Text(if (state.preparationStage == "processing") "正在整理音视频轨道" else "${byteLabel(state.preparationDone)} / ${byteLabel(state.preparationTotal)}",
                            color = Color(0xFFD2C5BC), fontSize = 11.sp)
                    }
                    TextButton(onClick = model::cancelPreparation) { Text("取消载入", color = Color(0xFFFFA17C)) }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("选一集，开始你的好故事", color = Color(0xFFD2C5BC), fontSize = 14.sp)
                    Button(onClick = { model.playEpisode(state.episodePosition.coerceAtLeast(0)) }, shape = RoundedCornerShape(12.dp)) {
                        val ep = state.detail?.episodes?.getOrNull(state.episodePosition)?.index ?: 1
                        Text("播放第 $ep 集")
                    }
                }
            }
        }
        val status = when {
            state.preparing -> preparationLabel(state.preparationStage)
            state.playbackError != null -> "播放失败"
            state.playbackStatus.isNotBlank() -> state.playbackStatus
            else -> "选择下方集数即可播放"
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(status, modifier = Modifier.weight(1f).testTag("media_status"), color = Muted, fontSize = 12.sp)
            if (state.firstFrameRendered && state.playbackError == null) Text("画面已就绪", color = Orange, fontSize = 11.sp)
        }
        if (state.playbackError != null) FailurePanel(state.playbackError, model::retryPlayback, "retry_playback")
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LocalVideoPlayer(video: PreparedVideo, initialPositionMs: Long, model: HongguoViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(video.requestId) { mutableStateOf<ExoPlayer?>(null) }
    var lastPosition by remember(video.requestId) { mutableStateOf(initialPositionMs) }

    DisposableEffect(video.requestId, lifecycle) {
        fun createPlayer() {
            if (player != null) return
            Stream.pin(video.path)
            val created = ExoPlayer.Builder(context.applicationContext).build()
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> model.onPlaybackStatus(video.episodeId, video.requestId, "正在缓冲")
                        Player.STATE_READY -> model.onPlaybackStatus(video.episodeId, video.requestId, if (created.isPlaying) "正在播放" else "已暂停")
                        Player.STATE_ENDED -> model.onPlaybackEnded(video.episodeId, video.requestId)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (created.playbackState == Player.STATE_READY) model.onPlaybackStatus(video.episodeId, video.requestId,
                        if (isPlaying) "正在播放" else "已暂停")
                }

                override fun onPlayerError(error: PlaybackException) {
                    model.onPlaybackError(video.episodeId, video.requestId, error.errorCode in 4000..4999)
                }

                override fun onRenderedFirstFrame() { model.onFirstFrame(video.episodeId, video.requestId) }
            })
            created.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(video.path))))
            created.seekTo(lastPosition.coerceAtLeast(0))
            created.prepare()
            created.playWhenReady = true
            player = created
        }

        fun releasePlayer() {
            player?.let { active ->
                lastPosition = if (active.playbackState == Player.STATE_ENDED) 0 else active.currentPosition.coerceAtLeast(0)
                model.savePosition(video.episodeId, lastPosition, video.requestId)
                active.pause()
                active.release()
                player = null
                Stream.unpin(video.path)
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> createPlayer()
                Lifecycle.Event.ON_STOP -> releasePlayer()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) createPlayer()
        onDispose { lifecycle.removeObserver(observer); releasePlayer() }
    }

    LaunchedEffect(player, video.episodeId) {
        val active = player ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            if (active.playbackState != Player.STATE_ENDED) model.savePosition(video.episodeId, active.currentPosition, video.requestId)
        }
    }

    AndroidView(factory = { viewContext ->
        PlayerView(viewContext).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setKeepContentOnPlayerReset(false)
            this.player = player
        }
    }, update = { it.player = player }, modifier = Modifier.fillMaxSize().testTag("player_view"))
}

private fun preparationLabel(stage: String): String = when {
    stage.contains("download", true) || stage.contains("下载") -> "正在下载本集视频"
    stage.contains("decrypt", true) || stage.contains("解密") -> "正在处理视频"
    stage == "processing" -> "正在处理本集视频"
    stage.contains("remux", true) || stage.contains("strip", true) || stage.contains("封装") -> "正在整理媒体文件"
    stage.contains("ready", true) || stage.contains("完成") || stage == "cached" -> "视频已就绪"
    else -> "正在准备本集视频"
}

private fun byteLabel(value: Long): String = if (value >= 1024 * 1024)
    String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0)) else "${value / 1024} KB"
