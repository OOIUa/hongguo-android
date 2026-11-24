package com.hongguo.android

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Tells the window whether a detail page's picture is behind the status bar, which decides the icons' color. */
internal val LocalDetailPictureUnderBar = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Tracks whether a detail page's header picture is still behind the transparent status bar so the window can switch
 * status-bar icon contrast without drawing an opaque cover bar on scroll.
 */
@Composable
internal fun BoxScope.DetailStatusBar(list: LazyListState, pictureBottom: Dp) {
    val barHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val limit = with(LocalDensity.current) { (pictureBottom - barHeight).roundToPx() }
    val underBar by remember(list, limit) { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < limit } }
    val report = LocalDetailPictureUnderBar.current
    // A page sliding out must not change the bar for the page replacing it.
    val active = LocalPageActive.current
    LaunchedEffect(underBar, active) { if (active) report(underBar) }
}
