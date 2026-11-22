// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Danmaku"

/**
 * Hosts one [DanmakuView] over the picture. The playback position is read straight from the
 * player inside the view's own frame loop, so no Compose state ticks per frame; loading the
 * next windows happens on IO and never touches the UI thread.
 */
@Composable
internal fun DanmakuOverlay(
    seriesId: String,
    episodeId: String,
    durationMs: Long,
    player: ExoPlayer?,
    settings: DanmakuSettings,
    /** 用户设定的弹幕带高度（px）；视图本身会多留半行高度用于越界绘制。 */
    bandHeightPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(seriesId, episodeId) {
        DanmakuLoader(DanmakuApi.repository, seriesId, episodeId, durationMs,
            onFailure = { Log.w(TAG, "window failed: ${it.javaClass.simpleName} ${it.message}") })
    }
    // Duration arrives with the first video format; the first window can only be requested after
    // that, so this also kicks off the load that the bind-time attempt had to skip.
    LaunchedEffect(loader, durationMs, player) {
        if (durationMs > 0) {
            loader.durationMs = durationMs
            val active = player
            if (active != null) {
                withContext(Dispatchers.IO) { runCatching { loader.loadUpTo(active.currentPosition) } }
                Log.d(TAG, "loaded up to ${active.currentPosition}ms, buffered=${loader.buffered}")
            }
        }
    }
    // A plain holder, not Compose state: re-binding must not re-run composition.
    val boundPlayer = remember { arrayOfNulls<ExoPlayer>(1) }
    AndroidView(
        factory = { context ->
            DanmakuView(context).also {
                it.setBandHeight(bandHeightPx)
                it.applyPrefs(settings.opacity, settings.font, settings.speed, settings.effect)
            }
        },
        update = { view ->
            view.setBandHeight(bandHeightPx)
            view.applyPrefs(settings.opacity, settings.font, settings.speed, settings.effect)
            val active = player
            if (active == null) {
                if (boundPlayer[0] != null) {
                    view.unbind()
                    boundPlayer[0] = null
                }
            } else if (boundPlayer[0] !== active) {
                boundPlayer[0] = active
                view.bind(loader, { active.currentPosition.coerceAtLeast(0) }, needMore = { position ->
                    scope.launch(Dispatchers.IO) { runCatching { loader.loadUpTo(position) } }
                })
                val start = active.currentPosition.coerceAtLeast(0)
                scope.launch(Dispatchers.IO) {
                    runCatching { loader.loadUpTo(start) }
                    Log.d(TAG, "bind load at ${start}ms, buffered=${loader.buffered}")
                }
            }
        },
        onRelease = { view ->
            view.unbind()
            boundPlayer[0] = null
        },
        modifier = modifier,
    )
}
