package com.hongguo.android

import com.hongguo.account.AccountViewModel

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import coil.compose.AsyncImage
import java.util.Locale

@Composable
internal fun EpisodePlayerScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel? = null) {
    val detail = state.detail ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val activity = LocalActivity.current
    DisposableEffect(lifecycle, model, activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        val barBehavior = controller?.systemBarsBehavior
        controller?.let {
            it.isAppearanceLightStatusBars = false
            it.isAppearanceLightNavigationBars = false
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onPlayerForeground(true)
                Lifecycle.Event.ON_STOP -> model.onPlayerForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onPlayerForeground(true)
        onDispose {
            lifecycle.removeObserver(observer)
            model.onPlayerForeground(false)
            controller?.let {
                it.show(WindowInsetsCompat.Type.systemBars())
                if (lightStatus != null) it.isAppearanceLightStatusBars = lightStatus
                if (lightNavigation != null) it.isAppearanceLightNavigationBars = lightNavigation
                if (barBehavior != null) it.systemBarsBehavior = barBehavior
            }
        }
    }
    key(detail.id) { EpisodePager(state, model, detail, accountModel) }
}

private data class PagerMovement(val scrolling: Boolean, val settled: Int, val synchronizing: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodePager(state: HongguoUiState, model: HongguoViewModel, detail: DramaDetail, accountModel: AccountViewModel?) {
    if (detail.episodes.isEmpty()) return
    val selected = state.episodePosition.coerceIn(detail.episodes.indices)
    val pager = rememberPagerState(initialPage = selected, pageCount = { detail.episodes.size })
    val latestSelected = rememberUpdatedState(selected)
    var synchronizing by remember { mutableStateOf<Int?>(null) }
    var showEpisodes by rememberSaveable(detail.id) { mutableStateOf(false) }
    var showSpeed by rememberSaveable(detail.id) { mutableStateOf(false) }
    var seeking by remember(state.video?.requestId) { mutableStateOf(false) }
    var seekFraction by remember(state.video?.requestId) { mutableStateOf<Float?>(null) }

    // Model-driven changes (continue, sheet selection and autoplay) never count as a user swipe.
    LaunchedEffect(selected, detail.id) {
        if (pager.currentPage != selected || pager.settledPage != selected) {
            synchronizing = selected
            try { pager.scrollToPage(selected) } finally { synchronizing = null }
        }
    }
    LaunchedEffect(pager, detail.id) {
        var userScrollArmed = false
        snapshotFlow { PagerMovement(pager.isScrollInProgress, pager.settledPage, synchronizing) }.collect { movement ->
            if (movement.scrolling && movement.synchronizing == null) userScrollArmed = true
            if (!movement.scrolling && userScrollArmed) {
                userScrollArmed = false
                if (movement.synchronizing == null && movement.settled != latestSelected.value)
                    model.playEpisode(movement.settled)
            }
        }
    }

    // Releasing at drag start also prevents the old episode ending while the next page is settling.
    val activeVideo = state.video?.takeIf {
        !pager.isScrollInProgress && pager.currentPage == selected && it.episodeId == detail.episodes[selected].id
    }
    // A temporary drag disables the owner without discarding this request's pause/resume intent.
    val playback = rememberVideoPlayback(state.video, state.resumePositionMs, model, enabled = activeVideo != null,
        playbackSpeed = state.playbackSpeed)
    val visibleIndex = pager.currentPage.coerceIn(detail.episodes.indices)
    val visibleEpisode = detail.episodes[visibleIndex]
    val favorite = state.favorites.any { it.card.id == detail.id }
    val card = state.selectedCard ?: DramaCard(detail.id, detail.title, detail.cover, detail.episodes.size, detail.summary)
    val togglePlayback = {
        playback.player?.let {
            if (it.playbackState == Player.STATE_ENDED) { it.seekTo(0); it.play() }
            else if (it.playWhenReady) it.pause() else it.play()
        }
        Unit
    }

    Box(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("episode_pager"),
            key = { detail.episodes[it].id }, beyondViewportPageCount = 1,
            userScrollEnabled = !showEpisodes && !showSpeed && !seeking) { page ->
            Box(Modifier.fillMaxSize().testTag("episode_page_$page"), Alignment.Center) {
                AsyncImage(model = detail.cover, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(.24f), contentScale = ContentScale.Fit)
                if (page == selected && activeVideo != null && playback.player != null) {
                    VideoSurface(playback, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().testTag("player_toggle").clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = togglePlayback))
                }
                when {
                    page != selected || pager.isScrollInProgress -> Text("第 ${detail.episodes[page].index} 集", color = Color.White.copy(alpha = .65f), fontSize = 17.sp)
                    state.playbackError != null -> Column(Modifier.padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(state.playbackError, color = Color.White, fontSize = 14.sp, lineHeight = 23.sp)
                        Button(onClick = model::retryPlayback, modifier = Modifier.testTag("retry_playback"), shape = RoundedCornerShape(13.dp)) { Text("重新播放") }
                    }
                    state.preparing || (activeVideo != null && playback.player == null) || (playback.isBuffering && playback.playWhenReady) -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
                            Text(if (state.preparing) preparationLabel(state.preparationStage) else "正在缓冲", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
                            if (state.preparing) TextButton(onClick = model::closePlayer) { Text("返回详情", color = Color.White.copy(alpha = .75f)) }
                        }
                    }
                    playback.player != null && (!playback.playWhenReady || playback.ended) -> {
                        IconButton(onClick = togglePlayback, modifier = Modifier.size(76.dp).testTag("player_resume")
                            .semantics { contentDescription = if (playback.ended) "重新播放" else "继续播放" }) {
                            Surface(color = Color.Black.copy(alpha = .35f), shape = CircleShape) {
                                Box(Modifier.size(66.dp), Alignment.Center) { Text("▶", color = Color.White, fontSize = 30.sp) }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .5f), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 9.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::closePlayer, modifier = Modifier.testTag("player_back").semantics { contentDescription = "返回详情" }) {
                Text("‹", color = Color.White, fontSize = 39.sp, fontWeight = FontWeight.Light)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("红果剧场", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                Text("第 ${visibleEpisode.index} 集 / 共 ${detail.episodes.size} 集", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            TextButton(onClick = { showEpisodes = true }, modifier = Modifier.testTag("player_chapters")) { Text("选集 ›", color = Color.White, fontSize = 13.sp) }
        }

        Column(Modifier.align(Alignment.CenterEnd).padding(end = 13.dp, top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(21.dp)) {
            if (accountModel != null) AccountFavoriteAction(card, accountModel, dark = true,
                onSignIn = { model.selectTab(AppTab.ACCOUNT) })
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { model.toggleFavorite(card) }, modifier = Modifier.size(49.dp).testTag("favorite_toggle")
                    .semantics { contentDescription = if (favorite) "取消收藏" else "收藏这部剧" }) {
                    Text(if (favorite) "★" else "☆", fontSize = 36.sp, color = if (favorite) Orange else Color.White)
                }
                Text(if (favorite) "已收藏" else "收藏", color = Color.White, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { showEpisodes = true }, modifier = Modifier.size(49.dp).testTag("player_episode_picker")) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color.White.copy(alpha = .14f)) {
                        Box(Modifier.size(37.dp), Alignment.Center) { Text("▦", color = Color.White, fontSize = 26.sp) }
                    }
                }
                Text("选集", color = Color.White, fontSize = 11.sp)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .86f))))
            .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(detail.title, modifier = Modifier.padding(end = 57.dp), color = Color.White, fontSize = 20.sp, lineHeight = 27.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            val status = when {
                visibleIndex != selected || pager.isScrollInProgress -> "松开即可播放第 ${visibleEpisode.index} 集"
                state.playbackError != null -> "播放失败，可点击重试"
                state.preparing -> preparationLabel(state.preparationStage)
                state.playbackStatus.isNotBlank() -> state.playbackStatus
                else -> "正在准备播放"
            }
            Text(status, Modifier.testTag("media_status"), fontSize = 12.sp, color = Color.White.copy(alpha = .77f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (visibleIndex == detail.episodes.lastIndex) "已到最后一集 · 下滑回看" else "上滑看下一集 · 下滑看上一集",
                    color = Color.White.copy(alpha = .5f), fontSize = 10.sp, modifier = Modifier.weight(1f))
                if (state.firstFrameRendered && activeVideo != null) Text("画面已就绪", color = Color.White.copy(alpha = .6f), fontSize = 10.sp)
            }
            val duration = playback.durationMs
            val fraction = seekFraction ?: if (duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val displayedPosition = seekFraction?.let { (duration * it).toLong() } ?: playback.positionMs
            PlayerSeekBar(value = fraction,
                bufferedFraction = if (duration > 0) playback.bufferedPositionMs.toFloat() / duration else 0f,
                onValueChange = { seeking = true; seekFraction = it },
                onValueChangeFinished = {
                    val video = activeVideo
                    if (video != null && duration > 0) {
                        val target = ((seekFraction ?: fraction) * duration).toLong().coerceIn(0, duration)
                        playback.player?.seekTo(target)
                        model.savePosition(video.episodeId, target, video.requestId, duration)
                    }
                    seeking = false; seekFraction = null
                }, enabled = duration > 0 && activeVideo != null && state.playbackError == null,
                modifier = Modifier.testTag("player_seek").semantics { contentDescription = "播放进度" })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${playerTime(displayedPosition)} / ${if (duration > 0) playerTime(duration) else "--:--"}",
                    modifier = Modifier.testTag("player_time"), color = Color.White.copy(alpha = .75f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showSpeed = true }, modifier = Modifier.testTag("player_speed")
                    .semantics { contentDescription = "播放速度，当前${playbackSpeedLabel(state.playbackSpeed)}" }) {
                    Text(if (state.playbackSpeed == 1f) "倍速 1×" else "${playbackSpeedLabel(state.playbackSpeed)} 倍速",
                        color = if (state.playbackSpeed == 1f) Color.White else Orange, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showSpeed) {
        ModalBottomSheet(onDismissRequest = { showSpeed = false }, containerColor = Color(0xFF211E1B), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("播放速度", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("自动记住选择，对所有剧集生效", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxWidth().height(116.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(PlaybackSpeeds) { _, speed ->
                        val isSelected = state.playbackSpeed == speed
                        Surface(color = if (isSelected) Orange else Color.White.copy(alpha = .08f), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp).testTag("player_speed_$speed")
                                .semantics { this.selected = isSelected }
                                .clip(RoundedCornerShape(12.dp)).clickable {
                                    model.setPlaybackSpeed(speed)
                                    showSpeed = false
                                }) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(if (speed == 1f) "1× 正常" else playbackSpeedLabel(speed), fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showEpisodes) {
        ModalBottomSheet(onDismissRequest = { showEpisodes = false }, containerColor = Color(0xFF211E1B), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(detail.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("共 ${detail.episodes.size} 集 · 当前第 ${detail.episodes[selected].index} 集", color = Color.White.copy(alpha = .65f), fontSize = 12.sp)
                val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (selected / 5) * 5)
                LazyVerticalGrid(columns = GridCells.Fixed(5), state = grid,
                    modifier = Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .52f).dp).testTag("player_episode_list"),
                    horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    itemsIndexed(detail.episodes, key = { _, episode -> episode.id }) { index, episode ->
                        Surface(color = if (index == selected) Orange else Color.White.copy(alpha = .08f), shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.height(44.dp).testTag("player_episode_$index").clip(RoundedCornerShape(11.dp)).clickable {
                                showEpisodes = false
                                model.playEpisode(index)
                            }) {
                            Box(contentAlignment = Alignment.Center) { Text("${episode.index}", color = Color.White, fontSize = 14.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun playerTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

internal fun preparationLabel(stage: String): String = when {
    stage.contains("download", true) || stage.contains("下载") -> "正在缓冲本集视频"
    stage.contains("decrypt", true) || stage == "processing" || stage.contains("解密") -> "正在准备画面"
    stage.contains("remux", true) || stage.contains("strip", true) || stage.contains("封装") -> "正在整理媒体文件"
    stage == "ready" || stage == "cached" -> "视频已就绪"
    else -> "正在获取本集片源"
}
