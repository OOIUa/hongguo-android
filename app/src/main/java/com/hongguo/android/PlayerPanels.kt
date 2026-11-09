package com.hongguo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val PlayerPanelColor = Color(0xFF211E1B)

/**
 * Full screen: 选集 and 收藏 slide in from the right edge over half the picture, and the drama's details from the
 * left ([fromStart]), next to the title that opens them. A bottom sheet there only had half the screen's height to
 * show. The rest of the picture dims; a tap on it or back closes the panel.
 * Drawn inside the player, not in its own window, so the hidden system bars stay hidden.
 */
@Composable
internal fun PlayerSidePanel(visible: Boolean, onDismiss: () -> Unit, tag: String, fromStart: Boolean = false,
                             content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = maxOf(maxWidth * .5f, 340.dp).coerceAtMost(maxWidth)
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(180))) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f)).testTag("${tag}_scrim")
                .pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
        }
        val offscreen: (Int) -> Int = if (fromStart) { width -> -width } else { width -> width }
        AnimatedVisibility(visible, Modifier.align(if (fromStart) Alignment.CenterStart else Alignment.CenterEnd),
            enter = slideInHorizontally(tween(240), offscreen), exit = slideOutHorizontally(tween(200), offscreen)) {
            Column(Modifier.width(panelWidth).fillMaxHeight().testTag(tag)
                .clip(if (fromStart) RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
                    else RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp))
                .background(PlayerPanelColor.copy(alpha = .97f))
                // Taps on the panel's own blank space must not reach the dimmed picture behind it.
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                    .only((if (fromStart) WindowInsetsSides.Start else WindowInsetsSides.End) + WindowInsetsSides.Vertical))
                .padding(start = if (fromStart) 20.dp else 22.dp, end = if (fromStart) 22.dp else 20.dp, top = 22.dp, bottom = 16.dp)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) { content() }
            }
        }
    }
}

/**
 * 倍速 opens a short vertical list right above its button instead of a sheet; the fastest speed is on top.
 * [anchor] is the button's bounds and [origin] the player's top-left, both in root coordinates.
 */
@Composable
internal fun PlaybackSpeedPicker(current: Float, anchor: Rect, origin: Offset, onSelect: (Float) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().testTag("player_speed_scrim").pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
    Column(Modifier
        .layout { measurable, constraints ->
            val list = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (anchor.center.x - origin.x - list.width / 2f).roundToInt()
                    .coerceIn(0, (constraints.maxWidth - list.width).coerceAtLeast(0))
                val y = (anchor.top - origin.y - list.height - 6.dp.toPx()).roundToInt().coerceAtLeast(0)
                list.place(x, y)
            }
        }
        .width(IntrinsicSize.Max)
        .clip(RoundedCornerShape(12.dp))
        .background(PlayerPanelColor.copy(alpha = .94f))
        .padding(vertical = 6.dp)
        .testTag("player_speed_list")) {
        PlaybackSpeeds.asReversed().forEach { speed ->
            val isSelected = current == speed
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("player_speed_$speed")
                .semantics { selected = isSelected; contentDescription = "${playbackSpeedLabel(speed)}倍速" }
                .clickable { onSelect(speed) }.padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center) {
                Text(playbackSpeedLabel(speed), color = if (isSelected) Orange else Color.White, fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
