// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.danmaku

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(seriesId, episodeId) {
        DanmakuLoader(DanmakuApi.repository, seriesId, episodeId, durationMs)
    }
    // Duration arrives with the first video format; until then loadUpTo is a no-op.
    LaunchedEffect(loader, durationMs) {
        if (durationMs > 0) loader.durationMs = durationMs
    }
    // A plain holder, not Compose state: re-binding must not re-run composition.
    val boundPlayer = remember { arrayOfNulls<ExoPlayer>(1) }
    AndroidView(
        factory = { context ->
            DanmakuView(context).also {
                it.applyPrefs(settings.opacity, settings.font, settings.speed, settings.effect)
            }
        },
        update = { view ->
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
                scope.launch(Dispatchers.IO) { runCatching { loader.loadUpTo(start) } }
            }
        },
        onRelease = { view ->
            view.unbind()
            boundPlayer[0] = null
        },
        modifier = modifier,
    )
}
