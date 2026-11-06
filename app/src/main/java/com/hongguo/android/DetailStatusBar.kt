package com.hongguo.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Tells the window whether a detail page's picture is behind the status bar, which decides the icons' color. */
internal val LocalDetailPictureUnderBar = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Status bar of a detail page whose first list item starts with a picture. While the picture is behind the bar nothing
 * is drawn; once it scrolls away ([pictureBottom] is where it stops being readable), the page color covers the bar so
 * the list does not run under the clock, and the icons go back to the theme's color.
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
    val cover by animateFloatAsState(if (underBar) 0f else 1f, tween(120), label = "detail_status_bar")
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(barHeight).graphicsLayer { alpha = cover }.background(Canvas))
}
