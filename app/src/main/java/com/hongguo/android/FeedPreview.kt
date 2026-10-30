package com.hongguo.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.hongguo.api.service.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the 沉浸暗场 page showing [cardId] needs from the feed preview. */
internal class FeedPreviewUi(
    val cardId: String?,
    val player: ExoPlayer?,
    val detail: DramaDetail?,
    val rendered: Boolean,
    val paused: Boolean,
    val ended: Boolean,
    val holding: Boolean,
    val toggle: () -> Unit,
    /** Long-press on the left half: 2× while held; ignored while paused or ended. */
    val hold: (Boolean) -> Unit,
    val positionMs: () -> Long,
)

/**
 * Plays episode 1 of [card] like a short-video feed. One player for the settled page only: a new page, [enabled]
 * turning false (detail or full player open, page leaving) or the app stopping releases it. A preview never writes
 * local history or account progress; 立即播放 hands its position to the full player.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun rememberFeedPreview(card: DramaCard?, enabled: Boolean, model: HongguoViewModel): FeedPreviewUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) started = true
            if (event == Lifecycle.Event.ON_STOP) started = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val key = card?.id?.takeIf { enabled && started }
    var player by remember(key) { mutableStateOf<ExoPlayer?>(null) }
    var detail by remember(key) { mutableStateOf<DramaDetail?>(null) }
    var rendered by remember(key) { mutableStateOf(false) }
    var paused by remember(key) { mutableStateOf(false) }
    var ended by remember(key) { mutableStateOf(false) }
    var holding by remember(key) { mutableStateOf(false) }

    DisposableEffect(key) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var video: PreparedVideo? = null
        var pinned = false
        var handle: VideoSourceHandle? = null
        var active: ExoPlayer? = null
        if (key != null && card != null) scope.launch {
            // The playback API intermittently rejects a fresh anonymous identity; try this page once more.
            for (attempt in 0..1) try {
                if (attempt > 0) delay(2_000)
                val (loaded, prepared) = model.previewFirstEpisode(card)
                video = prepared
                if (prepared.session == null && prepared.path.isNotBlank()) { Stream.pin(prepared.path); pinned = true }
                val source = videoSourceHandleFor(prepared)
                handle = source
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3_000, 15_000, 500, 1_000)
                    .setTargetBufferBytes(6 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true).build()
                val created = ExoPlayer.Builder(context.applicationContext).setLoadControl(loadControl).build()
                active = created
                created.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { if (active === created) rendered = true }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (active === created && playbackState == Player.STATE_ENDED) {
                            created.setPlaybackSpeed(1f); holding = false; ended = true; paused = true
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) { if (active === created) { rendered = false; player = null } }
                })
                created.setMediaSource(source.mediaSource)
                created.prepare()
                created.playWhenReady = true
                detail = loaded
                player = created
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Drop whatever this attempt allocated; if no attempt is left the poster stays and 立即播放 still works.
                runCatching { handle?.cancelActiveReads() }
                active?.release(); active = null; handle = null
                video?.session?.close()
                if (pinned) video?.let { Stream.unpin(it.path) }
                video = null; pinned = false
            }
        }
        onDispose {
            scope.cancel()
            val owned = active
            active = null
            // Wake this source's blocked reader before ExoPlayer waits for its playback thread.
            runCatching { handle?.cancelActiveReads() }
            owned?.release()
            video?.session?.close()
            if (pinned) video?.let { Stream.unpin(it.path) }
        }
    }

    val current = player
    return FeedPreviewUi(
        cardId = key, player = current, detail = detail, rendered = rendered, paused = paused, ended = ended, holding = holding,
        toggle = {
            if (holding) { current?.setPlaybackSpeed(1f); holding = false }
            if (current != null && !ended) {
                if (current.playWhenReady) current.pause() else current.play()
                paused = !current.playWhenReady
            }
        },
        hold = { wanted ->
            if (current != null) {
                if (wanted && current.isPlaying && !ended && !holding) { current.setPlaybackSpeed(2f); holding = true }
                else if (!wanted && holding) { current.setPlaybackSpeed(1f); holding = false }
            }
        },
        positionMs = { current?.currentPosition?.coerceAtLeast(0) ?: 0 },
    )
}
