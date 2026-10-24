package com.hongguo.android

import com.hongguo.account.AccountViewModel

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
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
        controller?.let(::restorePlayerImmersive)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onPlayerForeground(true)
                Lifecycle.Event.ON_RESUME -> controller?.let(::restorePlayerImmersive)
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
    var showFavorites by rememberSaveable(detail.id) { mutableStateOf(false) }
    var doubleSpeedHeld by remember(state.video?.requestId) { mutableStateOf(false) }
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
    val sheetOpen = showEpisodes || showSpeed || showFavorites
    val temporaryDoubleSpeed = doubleSpeedHeld && activeVideo != null && !sheetOpen
    val playback = rememberVideoPlayback(state.video, state.resumePositionMs, model, enabled = activeVideo != null,
        playbackSpeed = if (temporaryDoubleSpeed) 2f else state.playbackSpeed)
    LaunchedEffect(activeVideo?.requestId, playback.playWhenReady, playback.ended, sheetOpen) {
        if (activeVideo == null || !playback.playWhenReady || playback.ended || sheetOpen) doubleSpeedHeld = false
    }
    val activity = LocalActivity.current
    val view = LocalView.current
    // A sheet owns another window and can reveal system bars without resuming the Activity.
    LaunchedEffect(sheetOpen, activity, view) {
        if (!sheetOpen) activity?.window?.let { restorePlayerImmersive(WindowCompat.getInsetsController(it, view)) }
    }
    val visibleIndex = pager.currentPage.coerceIn(detail.episodes.indices)
    val visibleEpisode = detail.episodes[visibleIndex]
    val favorite = state.favorites.any { it.card.id == detail.id }
    val cloudState = accountModel?.state?.collectAsStateWithLifecycle()?.value
    val cloudFavorite = cloudState?.let { account ->
        account.sessionStatus == AccountSessionStatus.LOGGED_IN && account.favorites.any { it.card.id == detail.id }
    } == true
    val anyFavorite = favorite || cloudFavorite
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
            userScrollEnabled = !showEpisodes && !showSpeed && !showFavorites && !seeking) { page ->
            Box(Modifier.fillMaxSize().testTag("episode_page_$page"), Alignment.Center) {
                AsyncImage(model = detail.cover, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(.24f), contentScale = ContentScale.Fit)
                if (page == selected && activeVideo != null && playback.player != null) {
                    VideoSurface(playback, Modifier.fillMaxSize())
                    PlayerInteractionLayer(modifier = Modifier.fillMaxSize().testTag("player_toggle"),
                        enabled = !sheetOpen, onTap = togglePlayback, onHoldSpeedChange = { holding ->
                            // Holding a paused video must not resume it or change the saved speed.
                            doubleSpeedHeld = holding && playback.isPlaying && !sheetOpen
                        })
                }
                when {
                    page != selected || pager.isScrollInProgress -> Unit
                    state.playbackError != null -> Column(Modifier.padding(horizontal = 34.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(state.playbackError, color = Color.White, fontSize = 14.sp, lineHeight = 23.sp,
                            modifier = Modifier.testTag("media_status"))
                        Button(onClick = model::retryPlayback, modifier = Modifier.testTag("retry_playback"), shape = RoundedCornerShape(13.dp)) { Text("重新播放") }
                    }
                    state.preparing || (activeVideo != null && playback.player == null) || (playback.isBuffering && playback.playWhenReady) -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 2.dp)
                            Text(if (state.preparing) preparationLabel(state.preparationStage) else "正在缓冲", color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                                modifier = Modifier.testTag("media_status"))
                            if (state.preparing) TextButton(onClick = model::closePlayer) { Text("返回详情", color = Color.White.copy(alpha = .75f)) }
                        }
                    }
                    playback.player != null && (!playback.playWhenReady || playback.ended) -> {
                        IconButton(onClick = togglePlayback, modifier = Modifier.size(76.dp).testTag("player_resume")
                            .semantics { contentDescription = if (playback.ended) "重新播放" else "继续播放" }) {
                            Surface(color = Color.Black.copy(alpha = .35f), shape = CircleShape) {
                                Box(Modifier.size(66.dp), Alignment.Center) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(42.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        PlayerTopBar(episodeIndex = visibleEpisode.index, episodeCount = detail.episodes.size,
            onBack = model::closePlayer, onShowEpisodes = { showEpisodes = true },
            temporaryDoubleSpeed = temporaryDoubleSpeed, modifier = Modifier.align(Alignment.TopCenter))

        // Keep the middle and subtitle area clear. Controls occupy only the lower edge of the frame.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .74f))))
            .navigationBarsPadding().padding(start = 23.dp, end = 21.dp, top = 14.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(detail.title, modifier = Modifier.weight(1f).padding(end = 8.dp), color = Color.White,
                    fontSize = if (LocalConfiguration.current.screenWidthDp < 380) 16.sp else 18.sp,
                    lineHeight = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showFavorites = true }, modifier = Modifier.testTag("player_favorite_options")
                    .semantics { contentDescription = "收藏选项" }) {
                    Icon(if (anyFavorite) Icons.Rounded.Star else Icons.Outlined.StarBorder, null,
                        tint = if (anyFavorite) Orange else Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (anyFavorite) "已收藏" else "收藏", color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
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
                    modifier = Modifier.testTag("player_time"), color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showSpeed = true }, modifier = Modifier.testTag("player_speed")
                    .semantics { contentDescription = "播放速度，当前${playbackSpeedLabel(state.playbackSpeed)}" }) {
                    Text("倍速 ${playbackSpeedLabel(state.playbackSpeed)}",
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    if (showFavorites) {
        ModalBottomSheet(onDismissRequest = { showFavorites = false }, containerColor = Color(0xFF211E1B), contentColor = Color.White) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 23.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("收藏这部剧", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(detail.title, color = Color.White.copy(alpha = .62f), fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("本机收藏", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("保存在当前设备", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                    }
                    TextButton(onClick = { model.toggleFavorite(card) }, modifier = Modifier.testTag("favorite_toggle")
                        .semantics { contentDescription = if (favorite) "取消本机收藏" else "加入本机收藏" }) {
                        Text(if (favorite) "已收藏" else "加入收藏", color = if (favorite) Color(0xFFFFA184) else Color.White, fontSize = 13.sp)
                    }
                }
                if (accountModel != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                        Icon(Icons.Outlined.CloudQueue, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(24.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("红果账号收藏", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("与手机端账号同步", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                        }
                        AccountFavoriteAction(card, accountModel, dark = true, compact = true, onSignIn = {
                            showFavorites = false
                            model.selectTab(AppTab.ACCOUNT)
                        })
                    }
                }
                Spacer(Modifier.height(22.dp))
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

/** Stable safe area: transient system bars must never move these controls into the status bar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerTopBar(
    episodeIndex: Int,
    episodeCount: Int,
    onBack: () -> Unit,
    onShowEpisodes: () -> Unit,
    modifier: Modifier = Modifier,
    temporaryDoubleSpeed: Boolean = false,
    statusBarInsets: WindowInsets = WindowInsets.statusBarsIgnoringVisibility,
    cutoutInsets: WindowInsets = WindowInsets.displayCutout,
) {
    val safeInsets = statusBarInsets.union(cutoutInsets).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(modifier.fillMaxWidth().testTag("player_top_bar")
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .48f), Color.Transparent)))
        .windowInsetsPadding(safeInsets).padding(start = 9.dp, end = 14.dp, top = 9.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("player_back").semantics { contentDescription = "返回详情" }) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Text("第 $episodeIndex 集 / 共 $episodeCount 集", color = Color.White,
                fontSize = if (LocalConfiguration.current.screenWidthDp < 360) 14.sp else 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.weight(1f).testTag("player_episode_label"))
            Box(Modifier.testTag("player_chapters")) {
                TextButton(onClick = onShowEpisodes, modifier = Modifier.testTag("player_episode_picker")) {
                    Text("选集", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
        if (temporaryDoubleSpeed) Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
            Surface(color = Color.Black.copy(alpha = .4f), shape = RoundedCornerShape(50)) {
                Text("2倍速播放中", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.testTag("player_hold_speed").padding(horizontal = 13.dp, vertical = 7.dp))
            }
        }
    }
}

private fun restorePlayerImmersive(controller: WindowInsetsControllerCompat) {
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}

private fun playerTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

internal fun preparationLabel(stage: String): String = when {
    stage.contains("download", true) || stage.contains("下载") -> "正在缓冲本集视频"
    stage.contains("decrypt", true) || stage == "processing" || stage.contains("解密") -> "正在准备画面"
    stage.contains("remux", true) || stage.contains("strip", true) || stage.contains("封装") -> "即将开始播放"
    stage == "ready" || stage == "cached" -> "即将开始播放"
    else -> "正在加载视频"
}
