package com.hongguo.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hongguo.account.AccountViewModel
import com.hongguo.android.*
import com.hongguo.android.danmaku.DanmakuOverlay
import com.hongguo.android.danmaku.DanmakuPrefs
import kotlinx.coroutines.delay

private val panelColor = Color(0xff211e22)
private val secondaryText = Color(0xffd6cdd0)
internal val tvSpeeds = PlaybackSpeeds
internal fun speedLabel(value: Float) = playbackSpeedLabel(value)

@Composable
internal fun TvWatch(state: HongguoUiState, model: HongguoViewModel, account: AccountViewModel) {
    val card = state.selectedCard ?: return
    key(card.id) {
        if (state.playerOpen) WatchPlayer(state, model, account) else WatchDetail(state, model, account)
    }
}

@Composable
private fun WatchDetail(state: HongguoUiState, model: HongguoViewModel, account: AccountViewModel) {
    val card = state.selectedCard ?: return
    val detail = state.detail
    val episodes = detail?.episodes.orEmpty()
    val current = episodes.getOrNull(state.episodePosition)
    var chooser by rememberSaveable { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    LaunchedEffect(detail != null, state.detailError) {
        if (current != null || state.detailError != null) primary.requestFocus() else back.requestFocus()
    }
    Row(Modifier.fillMaxSize().background(Color(0xff171518)).padding(horizontal = 34.dp, vertical = 23.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(145.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            TvButton("返回", model::closeDetail, Modifier.focusRequester(back))
            Cover(detail?.cover ?: card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(.69f))
            val count = episodes.size.takeIf { it > 0 } ?: card.episodeCount
            if (count > 0) Text("全 $count 集", color = secondaryText)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("剧集详情", color = secondaryText, fontSize = 15.sp)
            Text(detail?.title ?: card.title, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            (detail?.summary ?: card.summary).takeIf { it.isNotBlank() }?.let { Text(it, color = secondaryText, fontSize = 15.sp, lineHeight = 23.sp, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            if (state.loadingDetail) Text("正在获取剧集…", color = secondaryText)
            state.detailError?.let { Text(it, color = secondaryText); TvButton("重试", model::retryDetails, Modifier.focusRequester(primary)) }
            if (current != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("播放第 ${current.index} 集", { model.playEpisode(state.episodePosition) }, Modifier.focusRequester(primary).testTag("tv_detail_play"), selected = true)
                    TvButton("选集", { chooser = true }, Modifier.testTag("tv_detail_episodes"))
                }
            } else if (!state.loadingDetail && state.detailError == null) Text("暂时没有可播放的集数", color = secondaryText)
            FavoritePair(card, state.favorites.any { it.card.id == card.id }, { model.toggleFavorite(card) }, account) { model.selectTab(AppTab.ACCOUNT) }
            detail?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                Text("演员", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(cast.joinToString("　") { if (it.role.isBlank()) it.name else "${it.name} 饰 ${it.role}" }, color = secondaryText, fontSize = 14.sp, lineHeight = 22.sp)
            }
            RelatedWorksSection(state, model, account)
        }
    }
    if (chooser && detail != null) EpisodeChooser(detail, state.episodePosition, { chooser = false }) { index -> chooser = false; model.playEpisode(index) }
}

@Composable
private fun WatchDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.fillMaxHeight().fillMaxWidth(.49f).background(panelColor).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    TvButton("关闭", onDismiss)
                }
                content()
            }
        }
    }
}

@Composable
private fun EpisodeChooser(detail: DramaDetail, selected: Int, dismiss: () -> Unit, select: (Int) -> Unit) {
    if (detail.episodes.isEmpty()) return
    var page by rememberSaveable(detail.id) { mutableIntStateOf(selected.coerceAtLeast(0) / 20) }
    val start = page * 20
    val end = minOf(start + 20, detail.episodes.size)
    val focus = remember { FocusRequester() }
    WatchDialog("选集", dismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("上一组", { page-- }, enabled = page > 0)
            Text("${start + 1}–$end / ${detail.episodes.size}", Modifier.weight(1f), color = secondaryText, fontSize = 13.sp)
            TvButton("下一组", { page++ }, enabled = end < detail.episodes.size)
        }
        val focusIndex = if (selected in start until end) selected else start
        // All twenty buttons are composed before requesting initial focus, including the last row.
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in (start until end).chunked(5)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { index ->
                        val target = if (index == focusIndex) Modifier.focusRequester(focus) else Modifier
                        TvButton("${detail.episodes[index].index}", { select(index) }, Modifier.weight(1f).then(target).testTag("tv_episode_${index + 1}"), selected = index == selected)
                    }
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        LaunchedEffect(page) { focus.requestFocus() }
    }
}

@Composable
private fun WatchPlayer(state: HongguoUiState, model: HongguoViewModel, account: AccountViewModel) {
    val detail = state.detail ?: return
    val episode = detail.episodes.getOrNull(state.episodePosition) ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.onPlayerForeground(true)
            if (event == Lifecycle.Event.ON_STOP) model.onPlayerForeground(false)
        }
        lifecycle.addObserver(listener)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onPlayerForeground(true)
        onDispose { lifecycle.removeObserver(listener); model.onPlayerForeground(false) }
    }
    val playback = rememberVideoPlayback(state.video, state.resumePositionMs, model, playbackSpeed = state.playbackSpeed)
    var chrome by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf("") }
    var activity by remember { mutableIntStateOf(0) }
    val picture = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var lastAction by remember { mutableStateOf("play") }
    fun focusedAction(name: String) = if (lastAction == name) Modifier.focusRequester(actionFocus) else Modifier
    fun openPanel(name: String) { lastAction = name; panel = name }
    fun toggle() {
        val player = playback.player ?: return
        if (playback.ended) { player.seekTo(0); player.play() }
        else if (player.playWhenReady) player.pause() else player.play()
    }
    fun seek(delta: Long) {
        val player = playback.player ?: return
        val upper = playback.durationMs.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + delta).coerceIn(0, upper))
    }
    LaunchedEffect(chrome, panel, state.playbackError) {
        if (panel.isEmpty()) (if (chrome) actionFocus else picture).requestFocus()
    }
    LaunchedEffect(chrome, panel, activity, playback.isPlaying) {
        if (chrome && panel.isEmpty() && playback.isPlaying) { delay(6500); chrome = false }
    }
    LaunchedEffect(state.playbackError, playback.ended) {
        if (state.playbackError != null || playback.ended) { lastAction = "play"; chrome = true }
    }
    BackHandler { if (panel.isNotEmpty()) panel = "" else if (chrome && state.playbackError == null) chrome = false else model.closePlayer() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { key ->
        val event = key.nativeKeyEvent
        if (panel.isNotEmpty()) return@onPreviewKeyEvent false
        val command = event.keyCode
        val transport = command in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        val pictureKeys = !chrome && command in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)
        if (event.action == KeyEvent.ACTION_DOWN) activity++
        if (!transport && !pictureKeys) return@onPreviewKeyEvent false
        if (event.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
        when (command) {
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10000)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { lastAction = "play"; chrome = true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (event.repeatCount == 0) model.nextEpisode()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (event.repeatCount == 0) model.previousEpisode()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> playback.player?.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> playback.player?.play()
            else -> if (event.repeatCount == 0) toggle()
        }; true
    }.testTag("tv_player")) {
        VideoSurface(playback, Modifier.fillMaxSize())
        if (state.danmaku.enabled && state.firstFrameRendered && playback.durationMs > 0 && playback.player != null) {
            val band = maxHeight * DanmakuPrefs.coverageFraction(state.danmaku.coverage)
            DanmakuOverlay(detail.id, episode.id, playback.durationMs, playback.player, state.danmaku,
                bandHeightPx = with(LocalDensity.current) { band.toPx() }, modifier = Modifier.fillMaxWidth().height(band))
        }
        if (!chrome) Box(Modifier.fillMaxSize().focusRequester(picture).focusable())
        if (state.preparing || playback.isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (chrome) {
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(alpha = .78f)).padding(horizontal = 30.dp, vertical = 14.dp)) {
                Text(detail.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("第 ${episode.index} 集 / 全 ${detail.episodes.size} 集", color = secondaryText, fontSize = 14.sp)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .84f)).padding(horizontal = 30.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val status = state.playbackError ?: if (state.preparing) state.preparationStage else state.playbackStatus.replace("上滑观看", "可播放")
                Text("${libraryPositionLabel(playback.positionMs)} / ${libraryPositionLabel(playback.durationMs)}    $status", color = Color.White, fontSize = 14.sp, maxLines = 2)
                LinearProgressIndicator(progress = { if (playback.durationMs > 0) (playback.positionMs / playback.durationMs.toFloat()).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val playLabel = when {
                        state.playbackError != null -> "重试"
                        playback.ended -> "重播"
                        playback.player != null && playback.playWhenReady -> "暂停"
                        else -> "播放"
                    }
                    TvButton(if (state.preparing) "取消加载" else playLabel, { when { state.preparing -> model.cancelPreparation(); state.playbackError != null || playback.player == null -> model.retryPlayback(); else -> toggle() } }, focusedAction("play").testTag("tv_player_play"))
                    TvButton("−10 秒", { seek(-10000) }, enabled = playback.durationMs > 0)
                    TvButton("+10 秒", { seek(10000) }, enabled = playback.durationMs > 0)
                    TvButton("下一集", model::nextEpisode, enabled = state.episodePosition < detail.episodes.lastIndex)
                    TvButton("选集", { openPanel("选集") }, focusedAction("选集"))
                    TvButton(speedLabel(state.playbackSpeed), { openPanel("倍速") }, focusedAction("倍速"))
                    TvButton("收藏", { openPanel("收藏") }, focusedAction("收藏"))
                    TvButton("弹幕", { openPanel("弹幕") }, focusedAction("弹幕"))
                }
            }
        }
    }
    when (panel) {
        "选集" -> EpisodeChooser(detail, state.episodePosition, { panel = "" }) { index -> panel = ""; model.playEpisode(index) }
        "倍速" -> WatchDialog(panel, { panel = "" }) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tvSpeeds.forEach { speed -> TvButton(speedLabel(speed), { model.setPlaybackSpeed(speed); panel = "" }, if (state.playbackSpeed == speed) Modifier.focusRequester(panelFocus) else Modifier, selected = state.playbackSpeed == speed) }
            }
            LaunchedEffect(Unit) { panelFocus.requestFocus() }
        }
        "收藏" -> WatchDialog(panel, { panel = "" }) { state.selectedCard?.let { card ->
            FavoritePair(card, state.favorites.any { it.card.id == card.id }, { model.toggleFavorite(card) }, account) { model.selectTab(AppTab.ACCOUNT) }
        } }
        "弹幕" -> WatchDialog("弹幕设置", { panel = "" }) { DanmakuSettingsPanel(state.danmaku, model::setDanmaku) }
    }
}
