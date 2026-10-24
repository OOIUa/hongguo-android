package com.hongguo.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hongguo.api.service.Stream
import kotlinx.coroutines.delay

internal data class VideoPlaybackUi(
    val player: ExoPlayer? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val playWhenReady: Boolean = true,
)

/** One player owner above the pager; page composition only attaches its surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun rememberVideoPlayback(video: PreparedVideo?, initialPositionMs: Long, model: HongguoViewModel,
    enabled: Boolean = true, playbackSpeed: Float = 1f): VideoPlaybackUi {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(video?.requestId) { mutableStateOf<ExoPlayer?>(null) }
    var ui by remember(video?.requestId) { mutableStateOf(VideoPlaybackUi(positionMs = initialPositionMs)) }
    var lastPosition by remember(video?.requestId) { mutableStateOf(initialPositionMs.coerceAtLeast(0)) }
    var wantedPlaying by rememberSaveable(video?.requestId) { mutableStateOf(true) }
    val latestSpeed = rememberUpdatedState(playbackSpeed)

    DisposableEffect(video?.requestId, lifecycle, enabled) {
        var pinned = false
        var sourceHandle: VideoSourceHandle? = null
        fun isCurrent(active: ExoPlayer): Boolean = enabled && player === active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        fun updateSnapshot(active: ExoPlayer) {
            if (!isCurrent(active)) return
            val duration = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            ui = VideoPlaybackUi(active, active.isPlaying, active.playbackState == Player.STATE_BUFFERING,
                active.playbackState == Player.STATE_ENDED, active.currentPosition.coerceAtLeast(0), duration,
                active.bufferedPosition.coerceAtLeast(0), active.playWhenReady)
        }

        fun releasePlayer() {
            val active = player
            player = null // Invalidate callbacks before cancellation/release, including queued callbacks.
            val ownedSource = sourceHandle
            sourceHandle = null
            if (active != null) {
                wantedPlaying = active.playWhenReady
                lastPosition = if (active.playbackState == Player.STATE_ENDED) 0 else active.currentPosition.coerceAtLeast(0)
                if (video != null) model.savePosition(video.episodeId, lastPosition, video.requestId,
                    active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
                    force = true, completed = active.playbackState == Player.STATE_ENDED)
            }
            // Wake this source's blocked reader before ExoPlayer waits for its playback thread.
            runCatching { ownedSource?.cancelActiveReads() }
            if (active != null) {
                active.pause()
                active.release()
                ui = ui.copy(player = null, isPlaying = false, isBuffering = false, positionMs = lastPosition)
            }
            if (pinned && video != null) { Stream.unpin(video.path); pinned = false }
        }

        fun createPlayer() {
            if (!enabled || video == null || player != null) return
            var created: ExoPlayer? = null
            try {
                if (video.session == null && video.path.isNotBlank()) { Stream.pin(video.path); pinned = true }
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(5_000, 20_000, 500, 1_000)
                    .setTargetBufferBytes(8 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true).build()
                val active = ExoPlayer.Builder(context.applicationContext).setLoadControl(loadControl).build()
                created = active
                active.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        // Publish seeks immediately so releasing the thumb never restores an old snapshot.
                        if (isCurrent(active)) updateSnapshot(active)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!isCurrent(active)) return
                        updateSnapshot(active)
                        when (playbackState) {
                            Player.STATE_BUFFERING -> model.onPlaybackStatus(video.episodeId, video.requestId, "正在缓冲")
                            Player.STATE_READY -> model.onPlaybackStatus(video.episodeId, video.requestId, if (active.isPlaying) "正在播放" else "已暂停")
                            Player.STATE_ENDED -> model.onPlaybackEnded(video.episodeId, video.requestId,
                                active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (!isCurrent(active)) return
                        updateSnapshot(active)
                        if (!isPlaying && active.playbackState != Player.STATE_ENDED)
                            model.savePosition(video.episodeId, active.currentPosition, video.requestId,
                                active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0, force = true)
                        if (active.playbackState == Player.STATE_READY) model.onPlaybackStatus(video.episodeId, video.requestId,
                            if (isPlaying) "正在播放" else "已暂停")
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!isCurrent(active)) return
                        wantedPlaying = playWhenReady
                        updateSnapshot(active)
                        model.onPlaybackStatus(video.episodeId, video.requestId,
                            if (!playWhenReady) "已暂停" else if (active.playbackState == Player.STATE_READY) "正在播放" else "正在缓冲")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (!isCurrent(active)) return
                        updateSnapshot(active)
                        model.onPlaybackError(video.episodeId, video.requestId, error.errorCode in 4000..4999)
                    }

                    override fun onRenderedFirstFrame() {
                        if (isCurrent(active)) model.onFirstFrame(video.episodeId, video.requestId)
                    }
                })
                val handle = videoSourceHandleFor(video)
                sourceHandle = handle
                active.setMediaSource(handle.mediaSource)
                active.seekTo(lastPosition)
                active.setPlaybackSpeed(latestSpeed.value)
                player = active
                active.prepare()
                active.playWhenReady = wantedPlaying
                updateSnapshot(active)
            } catch (_: Exception) {
                player = null
                val failedSource = sourceHandle
                sourceHandle = null
                runCatching { failedSource?.cancelActiveReads() }
                created?.release()
                if (pinned) { Stream.unpin(video.path); pinned = false }
                ui = ui.copy(player = null, isPlaying = false, isBuffering = false)
                model.onPlaybackError(video.episodeId, video.requestId, false)
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

    // Updating speed must preserve the current source, position and pause intent.
    LaunchedEffect(player, playbackSpeed) {
        player?.setPlaybackSpeed(playbackSpeed)
    }

    LaunchedEffect(player, video?.requestId, enabled) {
        val active = player ?: return@LaunchedEffect
        val currentVideo = video ?: return@LaunchedEffect
        var lastSavedAt = 0L
        while (enabled && player === active && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            val duration = active.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            ui = VideoPlaybackUi(active, active.isPlaying, active.playbackState == Player.STATE_BUFFERING,
                active.playbackState == Player.STATE_ENDED, active.currentPosition.coerceAtLeast(0), duration,
                active.bufferedPosition.coerceAtLeast(0), active.playWhenReady)
            val now = android.os.SystemClock.elapsedRealtime()
            if (active.isPlaying) model.onPlaybackBuffered(currentVideo.episodeId, currentVideo.requestId,
                active.bufferedPosition - active.currentPosition)
            if (now - lastSavedAt >= 2_000 && active.playbackState != Player.STATE_ENDED) {
                model.savePosition(currentVideo.episodeId, active.currentPosition, currentVideo.requestId, duration)
                lastSavedAt = now
            }
            delay(350)
        }
    }
    return ui
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoSurface(playback: VideoPlaybackUi, modifier: Modifier = Modifier) {
    AndroidView(factory = { context ->
        PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(false)
            player = playback.player
        }
    }, update = { view -> view.player = playback.player; view.keepScreenOn = playback.isPlaying },
        onRelease = { it.player = null; it.keepScreenOn = false }, modifier = modifier.testTag("player_view"))
}
