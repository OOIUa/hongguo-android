package com.hongguo.android

import com.hongguo.account.AccountViewModel

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SkipNext
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hongguo.account.AccountSessionStatus
import androidx.media3.common.Player
import android.widget.Toast
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun EpisodePlayerScreen(state: HongguoUiState, model: HongguoViewModel, accountModel: AccountViewModel? = null,
                                 active: Boolean = true) {
    val detail = state.detail ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val activity = LocalActivity.current
    // Portrait is the normal vertical-drama view: system bars stay visible (transparent, white icons), so the
    // system does not treat the app as full screen. Only landscape hides them for full-screen viewing.
    // Holding for 2× and the clean screen show only the picture, so they take the bars away too.
    var chromeHidden by remember { mutableStateOf(false) }
    val fullScreen = playerHidesSystemBars(LocalConfiguration.current.orientation) || chromeHidden
    val currentFullScreen by rememberUpdatedState(fullScreen)
    // [active] is false while the closing player fades out: the bars and foreground state are handed back at once,
    // and taken again if the player is reopened before the fade ends.
    DisposableEffect(lifecycle, model, activity, view, active) {
        if (!active) return@DisposableEffect onDispose { }
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNavigation = controller?.isAppearanceLightNavigationBars
        val barBehavior = controller?.systemBarsBehavior
        (activity as? ComponentActivity)?.applyPlayerBarStyle()
        controller?.let { applyPlayerSystemBars(it, currentFullScreen) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onPlayerForeground(true)
                Lifecycle.Event.ON_RESUME -> controller?.let { applyPlayerSystemBars(it, currentFullScreen) }
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
    LaunchedEffect(activity, view, fullScreen, active) {
        if (active) activity?.window?.let { applyPlayerSystemBars(WindowCompat.getInsetsController(it, view), fullScreen) }
    }
    // 全屏观看 locks the orientation; closing the player hands back whatever the app had before.
    DisposableEffect(activity, active) {
        if (!active || activity == null) return@DisposableEffect onDispose { }
        val original = activity.requestedOrientation
        onDispose { activity.requestedOrientation = original }
    }
    key(detail.id) { EpisodePager(state, model, detail, accountModel, active) { chromeHidden = it } }
}

/** Full screen (hidden status and navigation bars) only in landscape; portrait keeps the system bars. */
internal fun playerHidesSystemBars(orientation: Int): Boolean = orientation == Configuration.ORIENTATION_LANDSCAPE

private data class PagerMovement(val scrolling: Boolean, val settled: Int, val synchronizing: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodePager(state: HongguoUiState, model: HongguoViewModel, detail: DramaDetail, accountModel: AccountViewModel?,
                         active: Boolean, onChromeHiddenChange: (Boolean) -> Unit) {
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
    // 清屏: only the picture remains until a tap, an inward pinch or back brings the controls back.
    var cleanScreen by rememberSaveable(detail.id) { mutableStateOf(false) }
    // The badge for a double tap on either side; taps on the same side while it shows keep adding.
    var skipFeedback by remember(state.video?.requestId) { mutableStateOf<SkipFeedback?>(null) }
    // Where a sideways drag across the picture began; non-null while that drag moves the progress.
    var dragSeekFrom by remember(state.video?.requestId) { mutableStateOf<Long?>(null) }

    // Model-driven changes (continue, sheet selection and autoplay) never count as a user swipe. They slide like a
    // swipe. User scrolling is off until the slide ends, so a touch cannot stop it half-way on another episode.
    LaunchedEffect(selected, detail.id) {
        if (pager.currentPage != selected || pager.settledPage != selected) {
            synchronizing = selected
            // animateScrollToPage itself jumps close first when the target is several pages away.
            try { pager.animateScrollToPage(selected, animationSpec = tween(EpisodeSlideMillis, easing = FastOutSlowInEasing)) }
            finally { synchronizing = null }
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

    // The current episode stays attached while the user drags, so its picture slides away with the page instead of
    // vanishing at drag start. It is released once another page settles (or a model-driven slide starts).
    val activeVideo = state.video?.takeIf {
        pager.settledPage == selected && synchronizing == null && it.episodeId == detail.episodes[selected].id
    }
    // A temporary drag disables the owner without discarding this request's pause/resume intent.
    val sheetOpen = showEpisodes || showSpeed || showFavorites
    val temporaryDoubleSpeed = doubleSpeedHeld && activeVideo != null && !sheetOpen
    val playback = rememberVideoPlayback(state.video, state.resumePositionMs, model, enabled = activeVideo != null,
        playbackSpeed = if (temporaryDoubleSpeed) 2f else state.playbackSpeed)
    val togglePlayback = {
        playback.player?.let {
            if (it.playbackState == Player.STATE_ENDED) { it.seekTo(0); it.play() }
            else if (it.playWhenReady) it.pause() else it.play()
        }
        Unit
    }
    val skip: (Boolean) -> Unit = skip@{ forward ->
        val player = playback.player ?: return@skip
        val video = activeVideo ?: return@skip
        if (seeking || !state.firstFrameRendered) return@skip
        val duration = playback.durationMs
        val target = skipTarget(player.currentPosition, duration, if (forward) 10_000 else -10_000) ?: return@skip
        player.seekTo(target)
        model.savePosition(video.episodeId, target, video.requestId, duration)
        val previous = skipFeedback?.takeIf { it.forward == forward }
        skipFeedback = SkipFeedback(forward, (previous?.seconds ?: 0) + 10, (skipFeedback?.serial ?: 0) + 1)
    }
    LaunchedEffect(skipFeedback) {
        if (skipFeedback != null) { delay(900); skipFeedback = null }
    }
    // As in the official app: drag sideways across the picture to move the progress, the time shows in the middle,
    // and the jump happens on release. It shares the seek bar's state, so the bar follows and the pager stays still.
    val seekDrag: (SeekDragPhase, Float) -> Boolean = drag@{ phase, fraction ->
        val duration = playback.durationMs
        when (phase) {
            SeekDragPhase.START -> {
                val player = playback.player ?: return@drag false
                if (activeVideo == null || seeking || !state.firstFrameRendered || duration <= 0 || state.playbackError != null)
                    return@drag false
                dragSeekFrom = player.currentPosition
                seeking = true
                seekFraction = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
                true
            }
            SeekDragPhase.MOVE -> {
                val from = dragSeekFrom ?: return@drag false
                dragSeekTarget(from, duration, fraction)?.let { seekFraction = it.toFloat() / duration }
                true
            }
            SeekDragPhase.END, SeekDragPhase.CANCEL -> {
                val from = dragSeekFrom
                val video = activeVideo
                val target = if (phase == SeekDragPhase.END && from != null) dragSeekTarget(from, duration, fraction) else null
                if (target != null && video != null && kotlin.math.abs(target - from!!) > 200) {
                    playback.player?.seekTo(target)
                    model.savePosition(video.episodeId, target, video.requestId, duration)
                }
                dragSeekFrom = null
                seeking = false
                seekFraction = null
                true
            }
        }
    }
    val activity = LocalActivity.current
    val context = LocalContext.current
    // Landscape is full-screen viewing, whether 全屏观看 turned the screen or the user did: a tap shows or hides
    // the controls, and episodes change from the controls, the episode list or autoplay rather than by swiping.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pip = rememberPlayerPictureInPicture(enabled = active && activeVideo != null && !sheetOpen,
        playing = playback.isPlaying, videoWidth = playback.videoWidth, videoHeight = playback.videoHeight,
        onTogglePlay = togglePlayback)
    // The small window shows only the picture.
    val inPip = pip.active
    val chromeHidden = cleanScreen || temporaryDoubleSpeed || inPip
    LaunchedEffect(chromeHidden) { onChromeHiddenChange(chromeHidden) }
    BackHandler(enabled = cleanScreen) { cleanScreen = false }
    // Back leaves full screen before it leaves the player; declared later, so it wins while in landscape.
    BackHandler(enabled = landscape) { activity?.requestPlayerFullScreen(false) }
    LaunchedEffect(landscape) { cleanScreen = false }
    // In full screen the controls step aside after a few seconds of playback.
    LaunchedEffect(landscape, cleanScreen, playback.isPlaying, seeking, sheetOpen) {
        if (landscape && !cleanScreen && playback.isPlaying && !seeking && !sheetOpen) {
            delay(FullScreenControlsMillis)
            cleanScreen = true
        }
    }
    // Anything the viewer has to act on brings the controls back: an error, a pause they did not ask for, or an
    // ending with nothing playing next. Pinching while already paused stays clean, since this only runs on a change.
    val endedForGood = playback.ended && !(state.autoPlayNext && selected + 1 < detail.episodes.size)
    LaunchedEffect(playback.playWhenReady, endedForGood, state.playbackError) {
        if (!playback.playWhenReady || endedForGood || state.playbackError != null) cleanScreen = false
    }
    LaunchedEffect(activeVideo?.requestId, playback.playWhenReady, playback.ended, sheetOpen) {
        if (activeVideo == null || !playback.playWhenReady || playback.ended || sheetOpen) doubleSpeedHeld = false
    }
    // Pause while a swipe is in progress (the episode must not end and autoplay mid-drag); a swipe that comes back to
    // the same episode resumes it, a swipe to another episode leaves the release to the settled page change.
    // The hold starts on the drag's first event (not a frame later) and never changes the user's pause intent.
    val hold = rememberUpdatedState(playback.holdForGesture)
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { if (it is DragInteraction.Start) hold.value(true) }
    }
    val dragging = pager.isScrollInProgress && synchronizing == null
    LaunchedEffect(dragging, playback.player) {
        if (playback.player == null) return@LaunchedEffect
        if (dragging) hold.value(true)
        else if (pager.settledPage == latestSelected.value) hold.value(false)
    }
    // Fade the picture in once its first frame is on screen, instead of popping from black. Read only in the draw
    // layer, so the fade does not recompose the page.
    val pictureCover = animateFloatAsState(if (state.firstFrameRendered) 0f else 1f,
        tween(if (state.firstFrameRendered) 240 else 0), label = "first_frame_fade")
    val view = LocalView.current
    // A sheet owns another window and can reveal system bars or change their icons without resuming the Activity.
    val fullScreen = playerHidesSystemBars(LocalConfiguration.current.orientation) || chromeHidden
    LaunchedEffect(sheetOpen, activity, view, fullScreen, active) {
        if (active && !sheetOpen) activity?.window?.let { applyPlayerSystemBars(WindowCompat.getInsetsController(it, view), fullScreen) }
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

    Box(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }
        .playerPinch(active && !sheetOpen && !landscape && !inPip) { cleanScreen = it }) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("episode_pager"),
            key = { detail.episodes[it].id }, beyondViewportPageCount = 1,
            userScrollEnabled = active && synchronizing == null && !showEpisodes && !showSpeed && !showFavorites && !seeking &&
                !landscape && !inPip) { page ->
            Box(Modifier.fillMaxSize().testTag("episode_page_$page"), Alignment.Center) {
                if (page == selected && activeVideo != null && playback.player != null) {
                    VideoSurface(playback, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pictureCover.value }.background(Color.Black))
                    // As in Bilibili: a tap shows or hides the controls, a double tap in the middle plays or pauses,
                    // and a double tap on either side skips 10 seconds.
                    PlayerInteractionLayer(modifier = Modifier.fillMaxSize().testTag("player_toggle"),
                        enabled = !sheetOpen && !inPip, onTap = { x ->
                            val feedback = skipFeedback
                            val zone = tapZone(x)
                            if (feedback != null && zone == (if (feedback.forward) TapZone.FORWARD else TapZone.BACK)) skip(feedback.forward)
                            else cleanScreen = !cleanScreen
                        },
                        onDoubleTap = { x ->
                            when (tapZone(x)) {
                                TapZone.BACK -> skip(false)
                                TapZone.FORWARD -> skip(true)
                                TapZone.MIDDLE -> togglePlayback()
                            }
                        },
                        onHoldSpeedChange = { holding ->
                            // Holding a paused video must not resume it or change the saved speed.
                            doubleSpeedHeld = holding && playback.isPlaying && !sheetOpen
                        }, tapLabel = if (playback.playWhenReady && !playback.ended) "暂停" else "播放",
                        onAccessibilityClick = togglePlayback,
                        accessibilityActions = listOf(
                            "快退 10 秒" to { skip(false) },
                            "快进 10 秒" to { skip(true) },
                            (if (cleanScreen) "显示控制栏" else "隐藏控制栏") to { cleanScreen = !cleanScreen },
                        ), onSeekDrag = seekDrag)
                }
                when {
                    // Neighbouring episodes carry their number, so a swipe visibly brings the next one in.
                    page != selected -> EpisodePlaceholder(detail.episodes[page].index, detail.title)
                    inPip -> if (state.preparing || playback.player == null || (playback.isBuffering && playback.playWhenReady))
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    // While sliding to an episode that has no picture yet, show its number rather than an empty frame.
                    pager.isScrollInProgress -> if (activeVideo == null || playback.player == null)
                        EpisodePlaceholder(detail.episodes[page].index, detail.title)
                    // Only the picture while it can show; loading with no picture and errors still appear.
                    chromeHidden && !state.preparing && playback.player != null && state.playbackError == null -> Unit
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

        val dragFrom = dragSeekFrom
        val dragFraction = seekFraction
        if (dragFrom != null && dragFraction != null && playback.durationMs > 0) {
            val target = (playback.durationMs * dragFraction).toLong()
            SeekDragBadge(target, playback.durationMs, target - dragFrom, Modifier.align(Alignment.Center))
        }

        skipFeedback?.let { feedback ->
            SkipBadge(feedback, Modifier.align(if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = if (landscape) 70.dp else 26.dp))
        }

        AnimatedVisibility(!chromeHidden, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            PlayerTopBar(episodeIndex = visibleEpisode.index, episodeCount = detail.episodes.size,
                onBack = { if (landscape) activity?.requestPlayerFullScreen(false) else model.closePlayer() },
                onShowEpisodes = { showEpisodes = true },
                title = detail.title.takeIf { landscape },
                onPictureInPicture = if (pip.supported && activeVideo != null) {
                    { if (!pip.enter()) Toast.makeText(context, "系统未允许小窗播放，可在系统设置中为本应用开启画中画", Toast.LENGTH_LONG).show() }
                } else null)
        }

        // Just under a picture wider than tall, as in the official app; landscape is already full screen.
        val fullScreenOffered = !landscape && !chromeHidden && activeVideo != null && playback.player != null &&
            state.firstFrameRendered && state.playbackError == null && !pager.isScrollInProgress &&
            isLandscapeVideo(playback.videoWidth, playback.videoHeight)
        if (fullScreenOffered) BoxWithConstraints(Modifier.fillMaxSize()) {
            val top = fullScreenButtonTop(maxWidth.value, maxHeight.value, playback.videoWidth, playback.videoHeight, 14f)
            FullScreenWatchButton(onClick = { activity?.requestPlayerFullScreen(true) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top.dp))
        }

        // Keep the middle and subtitle area clear. Controls occupy only the lower edge of the frame.
        AnimatedVisibility(!chromeHidden, Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Column(Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .74f))))
            .navigationBarsPadding().padding(start = 23.dp, end = 21.dp, top = 14.dp, bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The title leads back to the drama's detail page, which the player covers.
                Row(Modifier.weight(1f).padding(end = 8.dp).heightIn(min = 44.dp).testTag("player_drama_title")
                    .clickable(onClickLabel = "打开短剧详情") { model.closePlayer() },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(detail.title, modifier = Modifier.weight(1f, fill = false), color = Color.White,
                        fontSize = if (LocalConfiguration.current.screenWidthDp < 380) 16.sp else 18.sp,
                        lineHeight = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(22.dp))
                }
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
                if (landscape) {
                    IconButton(onClick = togglePlayback, enabled = playback.player != null, modifier = Modifier.testTag("player_play_pause")
                        .semantics { contentDescription = if (playback.playWhenReady && !playback.ended) "暂停" else "播放" }) {
                        Icon(if (playback.playWhenReady && !playback.ended) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                            tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { model.playEpisode(selected + 1) }, enabled = selected + 1 < detail.episodes.size,
                        modifier = Modifier.testTag("player_next").semantics { contentDescription = "下一集" }) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White.copy(alpha = if (selected + 1 < detail.episodes.size) 1f else .35f),
                            modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text("${playerTime(displayedPosition)} / ${if (duration > 0) playerTime(duration) else "--:--"}",
                    modifier = Modifier.testTag("player_time"), color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showSpeed = true }, modifier = Modifier.testTag("player_speed")
                    .semantics { contentDescription = "播放速度，当前${playbackSpeedLabel(state.playbackSpeed)}" }) {
                    Text("倍速 ${playbackSpeedLabel(state.playbackSpeed)}",
                        color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
                if (landscape) IconButton(onClick = { activity?.requestPlayerFullScreen(false) },
                    modifier = Modifier.testTag("player_exit_full_screen").semantics { contentDescription = "退出全屏" }) {
                    Icon(Icons.Rounded.FullscreenExit, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
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
    statusBarInsets: WindowInsets = WindowInsets.statusBarsIgnoringVisibility,
    cutoutInsets: WindowInsets = WindowInsets.displayCutout,
    /** Shown in full screen, where the drama title is otherwise out of sight. */
    title: String? = null,
    /** 小窗播放; null where the device has no picture-in-picture. */
    onPictureInPicture: (() -> Unit)? = null,
) {
    val safeInsets = statusBarInsets.union(cutoutInsets).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    Column(modifier.fillMaxWidth().testTag("player_top_bar")
        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .48f), Color.Transparent)))
        .windowInsetsPadding(safeInsets).padding(start = 9.dp, end = 14.dp, top = 9.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("player_back")
                .semantics { contentDescription = if (title != null) "退出全屏" else "返回详情" }) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
            Text(listOfNotNull(title, "第 $episodeIndex 集 / 共 $episodeCount 集").joinToString("  "), color = Color.White,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (LocalConfiguration.current.screenWidthDp < 360) 14.sp else 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.weight(1f).testTag("player_episode_label"))
            if (onPictureInPicture != null) IconButton(onClick = onPictureInPicture,
                modifier = Modifier.testTag("player_pip").semantics { contentDescription = "小窗播放" }) {
                Icon(Icons.Rounded.PictureInPictureAlt, null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Box(Modifier.testTag("player_chapters")) {
                TextButton(onClick = onShowEpisodes, modifier = Modifier.testTag("player_episode_picker")) {
                    Text("选集", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Rounded.ChevronRight, null, tint = Color.White, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

private data class SkipFeedback(val forward: Boolean, val seconds: Int, val serial: Int)

@Composable
private fun SkipBadge(feedback: SkipFeedback, modifier: Modifier = Modifier) {
    Column(modifier.size(86.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).testTag("player_skip_badge"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (feedback.forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, null,
            tint = Color.White, modifier = Modifier.size(28.dp))
        Text("${if (feedback.forward) "+" else "-"}${feedback.seconds} 秒", color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SeekDragBadge(targetMs: Long, durationMs: Long, deltaMs: Long, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .55f))
        .padding(horizontal = 20.dp, vertical = 12.dp).testTag("player_drag_seek"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${playerTime(targetMs)} / ${playerTime(durationMs)}", color = Color.White, fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold)
        val seconds = kotlin.math.abs(deltaMs) / 1000
        Text(if (deltaMs >= 0) "快进 $seconds 秒" else "快退 $seconds 秒", color = Color.White.copy(alpha = .75f), fontSize = 12.sp)
    }
}

private fun applyPlayerSystemBars(controller: WindowInsetsControllerCompat, fullScreen: Boolean) {
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
    else controller.show(WindowInsetsCompat.Type.systemBars())
}

/**
 * Transparent bars with light icons over the black player, including the API 26-28 / three-button navigation
 * bar, which would otherwise keep the light theme's white scrim. The app theme re-applies its own style when the
 * player closes (HongguoTheme regains the window).
 */
private fun ComponentActivity.applyPlayerBarStyle() {
    val transparent = android.graphics.Color.TRANSPARENT
    enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(transparent), navigationBarStyle = SystemBarStyle.dark(transparent))
}

private const val EpisodeSlideMillis = 320
private const val FullScreenControlsMillis = 4_000L

@Composable
private fun EpisodePlaceholder(index: Int, title: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 40.dp)) {
        Text("第 $index 集", color = Color.White.copy(alpha = .78f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(title, color = Color.White.copy(alpha = .45f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
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
