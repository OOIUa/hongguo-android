package com.hongguo.tv

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.hongguo.account.AccountSessionStatus
import com.hongguo.android.*
import com.hongguo.api.service.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

private val channels = listOf(HomeFeed.GUESS to "推荐", HomeFeed.RECOMMEND to "热度", HomeFeed.LATEST to "最新", HomeFeed.COMIC to "漫剧")

private class TvPreview {
    var player by mutableStateOf<ExoPlayer?>(null)
    var detail by mutableStateOf<DramaDetail?>(null)
    var rendered by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var muted by mutableStateOf(true)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun tvPreview(card: DramaCard?, model: HongguoViewModel, retry: Int): TvPreview {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val active = card != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val preview = remember(card?.id, active, retry) { TvPreview() }
    LaunchedEffect(preview) {
        if (card == null || !active) return@LaunchedEffect
        // Do not start preparation as the user is holding down a direction key.
        delay(350)
        var video: PreparedVideo? = null
        var pinnedPath: String? = null
        var source: VideoSourceHandle? = null
        var player: ExoPlayer? = null
        try {
            val (detail, media) = model.previewFirstEpisode(card)
            video = media
            if (media.session == null && media.path.isNotBlank()) {
                Stream.pin(media.path)
                pinnedPath = media.path
            }
            val handle = videoSourceHandleFor(media)
            source = handle
            val control = DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 15_000, 500, 1_000)
                .setTargetBufferBytes(6 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build()
            val created = createVideoPlayer(context, control)
            player = created
            val failure = CompletableDeferred<Unit>()
            created.volume = 0f
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { preview.rendered = true }
                override fun onPlaybackStateChanged(state: Int) {
                    preview.ended = state == Player.STATE_ENDED
                }
                override fun onPlayerError(error: PlaybackException) { failure.complete(Unit) }
            })
            created.setMediaSource(handle.mediaSource)
            preview.detail = detail
            preview.player = created
            created.prepare()
            created.play()
            failure.await()
            preview.failed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            preview.failed = true
        } finally {
            preview.rendered = false
            preview.player = null
            runCatching { source?.cancelActiveReads() }
            player?.release()
            video?.session?.close()
            pinnedPath?.let { Stream.unpin(it) }
        }
    }
    return preview
}

// Only public title/summary text is available in a card. Keep the original order for an equal match.
private fun terms(card: DramaCard): Set<String> =
    (card.title + " " + card.summary.take(180)).split(Regex("[^\\p{L}\\p{N}]+"))
        .flatMap { word -> if (word.length > 1) word.windowed(2) else emptyList() }.toSet()

@Composable
internal fun TvHome(state: HongguoUiState, model: HongguoViewModel, entryFocus: FocusRequester, focusRail: (() -> Unit)?) {
    val application = LocalContext.current.applicationContext as HongguoApplication
    val account by application.accountRepository.state.collectAsStateWithLifecycle()
    val owner = account.profile?.id?.takeIf { account.sessionStatus == AccountSessionStatus.LOGGED_IN }.orEmpty()
    key(owner, state.homeFeed) { HomeChannel(state, model, entryFocus, focusRail) }
}

@Composable
private fun HomeChannel(state: HongguoUiState, model: HongguoViewModel, entryFocus: FocusRequester, focusRail: (() -> Unit)?) {
    val feed = state.home
    var index by rememberSaveable { mutableIntStateOf(0) }
    var cursor by rememberSaveable { mutableStateOf("") }
    var hidden by rememberSaveable(stateSaver = listSaver(save = { it }, restore = { it })) {
        mutableStateOf(emptyList<String>())
    }
    var preferred by rememberSaveable { mutableStateOf("") }
    val visible = remember(feed.items, hidden) { feed.items.filterNot { it.id in hidden } }
    val ordered = remember(visible, preferred) {
        val source = visible.indexOfFirst { it.id == preferred }
        if (source < 0) visible else {
            val favorites = terms(visible[source])
            val tail = visible.drop(source + 1)
            val score = tail.associate { card -> card.id to terms(card).count { it in favorites } }
            visible.take(source + 1) + tail.sortedByDescending { score.getValue(it.id) }
        }
    }
    val current = ordered.indexOfFirst { it.id == cursor }.takeIf { it >= 0 }
        ?: index.coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
    val card = ordered.getOrNull(current)
    var retry by remember(card?.id) { mutableIntStateOf(0) }
    val preview = tvPreview(card.takeIf { state.selectedCard == null && !state.playerOpen }, model, retry)
    val railFocused by rememberUpdatedState(LocalRailFocused.current)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(card != null, windowFocused) {
        if (card != null && windowFocused) {
            withFrameNanos { }
            if (!railFocused) entryFocus.requestFocus()
        }
    }
    LaunchedEffect(current, ordered.size, feed.hasMore, feed.error) {
        if (current >= ordered.size - 3 && feed.hasMore && feed.error == null) model.loadMoreHome()
    }
    fun step(by: Int) {
        val next = current + by
        if (next in ordered.indices) { index = next; cursor = ordered[next].id }
        else if (by > 0 && feed.hasMore && !feed.loadingMore) model.loadMoreHome()
    }
    val play = {
        if (card != null) {
            val list = preview.detail?.episodes.orEmpty()
            val ep = if (preview.ended && list.size > 1) list[1] else list.firstOrNull()
            if (ep != null && preview.rendered) model.playFromPreview(card, ep,
                if (preview.ended) 0 else preview.player?.currentPosition?.coerceAtLeast(0) ?: 0)
            else model.playDrama(card)
        }
    }
    Column(Modifier.fillMaxSize().focusGroup().testTag("tv_home"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            channels.forEach { (channel, title) ->
                val target = if (card == null && state.homeFeed == channel) Modifier.focusRequester(entryFocus) else Modifier
                TvButton(title, { model.selectHomeFeed(channel) }, target.onPreviewKeyEvent {
                    if (channel == HomeFeed.GUESS && it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && focusRail != null) {
                        focusRail(); true
                    } else false
                },
                    selected = channel == state.homeFeed)
            }
            Spacer(Modifier.weight(1f))
            TvButton("刷新", { index = 0; cursor = ""; model.refreshHome() }, enabled = !feed.loading)
        }
        if (card == null) {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    feed.loading || !feed.loaded -> "正在加载剧集…"
                    feed.error != null -> feed.error
                    else -> "这批推荐已看完"
                }, color = Color.LightGray, fontSize = 17.sp)
                if (!feed.loading) TvButton("重新加载", { index = 0; cursor = ""; hidden = emptyList(); model.refreshHome() })
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(25.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(.9f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    PreviewPicture(card, preview, Modifier.fillMaxWidth().weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (preview.failed) TvButton("重试试看", { retry++ })
                        if (preview.player != null) {
                            val player = preview.player
                            TvButton(when { preview.ended -> "重播试看"; preview.paused -> "继续试看"; else -> "暂停试看" }, {
                                if (preview.ended) { player?.seekTo(0); player?.play(); preview.paused = false }
                                else if (preview.paused) { player?.play(); preview.paused = false }
                                else { player?.pause(); preview.paused = true }
                            })
                            TvButton(if (preview.muted) "开启声音" else "静音", {
                                preview.muted = !preview.muted
                                player?.volume = if (preview.muted) 0f else 1f
                            })
                        }
                    }
                }
                Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    val count = card.episodeCount
                    Text(if (count > 0) "全 ${count} 集" else "短剧", fontSize = 15.sp, color = Color(0xFFD2BBB3))
                    Text(card.title, color = Color.White, fontSize = 29.sp, lineHeight = 35.sp,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (card.summary.isNotBlank()) Text(card.summary, fontSize = 15.sp, lineHeight = 22.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TvButton("观看正片", play, Modifier.focusRequester(entryFocus).testTag("tv_feed_play")
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false
                                else when (event.key) {
                                    Key.DirectionLeft -> if (focusRail != null) { focusRail(); true } else false
                                    Key.DirectionUp -> if (current > 0) { step(-1); true } else false
                                    Key.DirectionDown -> if (current < ordered.lastIndex || feed.hasMore) { step(1); true } else false
                                    else -> false
                                }
                            }, selected = true)
                        TvButton("剧集详情", { model.openDrama(card) }, Modifier.testTag("tv_feed_detail"))
                    }
                    if (state.homeFeed == HomeFeed.GUESS) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TvButton(if (preferred == card.id) "已偏好这类" else "多看这类", {
                            cursor = card.id
                            preferred = if (preferred == card.id) "" else card.id
                        }, selected = preferred == card.id)
                        TvButton("不感兴趣", {
                            // The visible list shrinks synchronously. Preserve the position of the following title.
                            if (preferred == card.id) preferred = ""
                            cursor = (ordered.getOrNull(current + 1) ?: ordered.getOrNull(current - 1))?.id.orEmpty()
                            hidden = (hidden + card.id).takeLast(540)
                            index = current.coerceAtMost((ordered.size - 2).coerceAtLeast(0))
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            state.history.firstOrNull()?.let { recent ->
                TvButton("继续观看 · " + recent.card.title.take(13), { model.openLibrary(recent) },
                    Modifier.testTag("tv_feed_continue"))
            }
            Spacer(Modifier.weight(1f))
            if (card != null) {
                Text("${current + 1} / ${ordered.size}", fontSize = 13.sp, color = Color.LightGray)
                TvButton("上一部", { step(-1) }, enabled = current > 0)
                TvButton("下一部", { step(1) }, Modifier.testTag("tv_feed_next"),
                    enabled = current < ordered.lastIndex || (feed.hasMore && !feed.loadingMore))
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewPicture(card: DramaCard, preview: TvPreview, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF171518)), contentAlignment = Alignment.Center) {
        if (!preview.rendered) AsyncImage(model = card.cover, contentDescription = card.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        val mediaPlayer = preview.player
        if (mediaPlayer != null) AndroidView(factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isFocusable = false
                isClickable = false
            }
        }, update = { view ->
            view.alpha = if (preview.rendered) 1f else 0f
            view.player = mediaPlayer
        }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize().testTag("tv_feed_video"))
        if (preview.failed || preview.rendered) Text(
            if (preview.failed) "试看暂不可用" else "第 1 集试看", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .7f)).padding(5.dp))
    }
}
