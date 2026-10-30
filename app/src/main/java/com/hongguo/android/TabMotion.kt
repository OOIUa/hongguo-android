package com.hongguo.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tab bounds measured inside one row, so a single indicator can glide from the old tab to the new one instead of
 * switching on and off under each label. Tabs report themselves with [tabAnchor]; the row draws with
 * [slidingIndicator] or [slidingPill]. Positions are read live, so labels that change size while animating are followed.
 */
@Stable
internal class TabIndicatorState {
    internal val bounds = mutableStateMapOf<Any, Pair<Float, Float>>()
}

@Composable
internal fun rememberTabIndicator(): TabIndicatorState = remember { TabIndicatorState() }

/** Marks a direct child of the row as the tab for [key]. */
internal fun Modifier.tabAnchor(state: TabIndicatorState, key: Any): Modifier = onPlaced { coordinates ->
    val left = coordinates.positionInParent().x
    val width = coordinates.size.width.toFloat()
    if (state.bounds[key] != left to width) state.bounds[key] = left to width
}

/** Left and width of the indicator, travelling from where it was towards [selected]'s live bounds. */
@Composable
private fun rememberIndicatorTrack(state: TabIndicatorState, selected: Any): IndicatorTrack {
    val track = remember { IndicatorTrack(state, selected) }
    LaunchedEffect(selected) {
        if (track.target == selected) return@LaunchedEffect
        // Start from wherever the indicator is drawn now, so a quick second tap does not jump.
        track.current()?.let { (left, width) -> track.fromLeft = left; track.fromWidth = width }
        track.target = selected
        track.progress.snapTo(0f)
        track.progress.animateTo(1f, spring(dampingRatio = .78f, stiffness = Spring.StiffnessMediumLow))
    }
    return track
}

@Stable
private class IndicatorTrack(private val state: TabIndicatorState, var target: Any) {
    val progress = Animatable(1f)
    var fromLeft = Float.NaN
    var fromWidth = Float.NaN

    fun current(): Pair<Float, Float>? {
        val (toLeft, toWidth) = state.bounds[target] ?: return null
        val t = progress.value
        if (fromLeft.isNaN() || t >= 1f) return toLeft to toWidth
        return (fromLeft + (toLeft - fromLeft) * t) to (fromWidth + (toWidth - fromWidth) * t)
    }

    /** 0 at rest, up to 1 halfway through a move: lets an underline stretch while it travels. */
    fun stretch(): Float = if (fromLeft.isNaN()) 0f else sin(PI * progress.value.coerceIn(0f, 1f)).toFloat()
}

/**
 * Short underline under the selected tab that slides (and briefly stretches) between tabs.
 * [width] null spans the whole tab; [bottom] lifts it from the row's bottom edge.
 */
@Composable
internal fun Modifier.slidingIndicator(state: TabIndicatorState, selected: Any, color: Color,
                                        width: Dp? = 18.dp, height: Dp = 3.dp, bottom: Dp = 0.dp): Modifier {
    val track = rememberIndicatorTrack(state, selected)
    return drawBehind {
        val (left, tabWidth) = track.current() ?: return@drawBehind
        val base = width?.toPx() ?: tabWidth
        val w = base * (1f + .7f * track.stretch())
        val h = height.toPx()
        drawRoundRect(color, topLeft = Offset(left + (tabWidth - w) / 2f, size.height - h - bottom.toPx()),
            size = Size(w, h), cornerRadius = CornerRadius(h / 2f))
    }
}

/** Filled capsule behind the selected segment of a segmented control, sliding between segments. */
@Composable
internal fun Modifier.slidingPill(state: TabIndicatorState, selected: Any, color: Color, corner: Dp,
                                  vertical: Dp = 0.dp): Modifier {
    val track = rememberIndicatorTrack(state, selected)
    return drawBehind {
        val (left, w) = track.current() ?: return@drawBehind
        val inset = vertical.toPx()
        drawRoundRect(color, topLeft = Offset(left, inset), size = Size(w, size.height - inset * 2),
            cornerRadius = CornerRadius(corner.toPx()))
    }
}

/** Label colour easing between idle and selected. */
@Composable
internal fun animatedTabColor(selected: Boolean, on: Color, off: Color): State<Color> =
    animateColorAsState(if (selected) on else off, tween(200), label = "tab_label_color")

/** Label size growing into the selected size, so the row reflows smoothly rather than jumping. */
@Composable
internal fun animatedTabSize(selected: Boolean, on: TextUnit, off: TextUnit): TextUnit {
    val size by animateFloatAsState(if (selected) on.value else off.value,
        spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow), label = "tab_label_size")
    return size.sp
}
