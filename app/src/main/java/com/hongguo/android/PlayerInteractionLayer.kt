package com.hongguo.android

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Fingers moving apart turn the clean screen on, together off; null until the pinch is clear. */
internal fun pinchCleanScreen(scale: Float): Boolean? = when {
    scale >= 1.18f -> true
    scale <= .85f -> false
    else -> null
}

/** The phases of a sideways drag across the picture. */
internal enum class SeekDragPhase { START, MOVE, END, CANCEL }

/** A drag across the full width moves through the whole episode, or through this much of a longer one. */
internal const val DragSeekSpanMillis = 180_000L

/**
 * Where a sideways drag of [fraction] of the width (negative to the left) lands, from where the drag began; held a
 * second short of the end. Null without a known duration.
 */
internal fun dragSeekTarget(startMs: Long, durationMs: Long, fraction: Float): Long? {
    if (durationMs <= 0) return null
    val span = durationMs.coerceAtMost(DragSeekSpanMillis)
    return (startMs + (span * fraction.toDouble()).toLong()).coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
}

/**
 * A seek drag may begin only away from the top and bottom edges: a finger landing there is usually pulling the
 * notification shade or making the system home/back gesture (issue #3: pulling the shade moved the progress).
 */
internal fun seekDragMayStart(downY: Float, height: Float, topEdge: Float, bottomEdge: Float): Boolean =
    height > 0f && downY >= topEdge && downY <= height - bottomEdge

/** A clearly sideways movement (not a diagonal page swipe). */
internal fun isSidewaysDrag(dx: Float, dy: Float): Boolean = kotlin.math.abs(dx) >= 2f * kotlin.math.abs(dy)

/**
 * Sits above the pager and sees every pointer first: once a second finger lands, the gesture belongs to the pinch, so
 * neither the page swipe nor the tap and hold below react, including to the finger left down after the other lifts.
 */
internal fun Modifier.playerPinch(enabled: Boolean, onDecided: (Boolean) -> Unit): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        var startSpan = 0f
        var decided = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val span = (pressed[0].position - pressed[1].position).getDistance()
                if (startSpan == 0f) startSpan = span
                else if (!decided && startSpan > 0f) pinchCleanScreen(span / startSpan)?.let {
                    decided = true
                    onDecided(it)
                }
            }
            if (startSpan != 0f) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Put below the player's buttons/seek bar. The seek/sideways handler comes before the tap detector in the modifier
 * chain, so its Final-pass consumption reaches the tap detector but not the pager above. A vertical drag cancels the tap detector before its
 * long-press timeout, so the surrounding VerticalPager still owns normal episode swipes.
 * enabled controls both touch and accessibility clicks; a paused player can keep this layer
 * enabled and decide whether to accept a hold in its callback without losing tap-to-resume.
 * Taps report where they landed as a fraction of the width. With [onDoubleTap] set, a single tap waits out the
 * double-tap timeout before it fires.
 * With [onSeekDrag] set, holding until 2× and then moving sideways moves the progress (reported as a fraction of the
 * width); START answers whether the drag is taken, and the 2× ends once it is. A drag without the long press first is
 * never a seek, so swipes, the notification shade and other quick movements cannot move the progress.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerInteractionLayer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTap: (Float) -> Unit,
    onHoldSpeedChange: (Boolean) -> Unit,
    tapLabel: String = "播放或暂停",
    onDoubleTap: ((Float) -> Unit)? = null,
    /** The screen-reader click; a tap in the middle by default. */
    onAccessibilityClick: (() -> Unit)? = null,
    /** Named screen-reader actions for what only gestures do, such as skipping or the clean screen. */
    accessibilityActions: List<Pair<String, () -> Unit>> = emptyList(),
    onSeekDrag: ((SeekDragPhase, Float) -> Boolean)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(onTap)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentAccessibilityClick by rememberUpdatedState(onAccessibilityClick)
    val currentHoldChange by rememberUpdatedState(onHoldSpeedChange)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSeekDrag by rememberUpdatedState(onSeekDrag)
    // Status bar / gesture areas, measured even while the bars are hidden in full screen.
    val density = LocalDensity.current
    val topInset = maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(density), WindowInsets.systemGestures.getTop(density))
    val bottomInset = maxOf(WindowInsets.navigationBarsIgnoringVisibility.getBottom(density),
        WindowInsets.systemGestures.getBottom(density))
    val currentEdges by rememberUpdatedState(with(density) {
        SeekEdges(maxOf(topInset + 16.dp.toPx(), 48.dp.toPx()), maxOf(bottomInset + 8.dp.toPx(), 32.dp.toPx()))
    })
    val currentWindowFocused by rememberUpdatedState(LocalWindowInfo.current.isWindowFocused)
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var holding by remember { mutableStateOf(false) }

    fun releaseHold() {
        if (holding) {
            holding = false
            currentHoldChange(false)
        }
    }

    DisposableEffect(lifecycle, enabled) {
        if (!enabled || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) releaseHold()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    releaseHold()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            releaseHold()
        }
    }

    Box(modifier.fillMaxSize()
        .semantics {
            role = Role.Button
            if (!enabled || !foreground) disabled()
            if (accessibilityActions.isNotEmpty())
                customActions = accessibilityActions.map { (label, action) -> CustomAccessibilityAction(label) { action(); true } }
            onClick(label = tapLabel) {
                if (currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    currentAccessibilityClick?.invoke() ?: currentTap(.5f)
                    true
                } else false
            }
        }
        .pointerInput(enabled, foreground, lifecycle, onSeekDrag != null) {
            if (!enabled || !foreground || onSeekDrag == null) return@pointerInput
            var taken = false
            var dragged = 0f
            fun finish(phase: SeekDragPhase) {
                if (taken) currentSeekDrag?.invoke(phase, dragged / size.width.coerceAtLeast(1))
                taken = false
                dragged = 0f
            }
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!seekDragMayStart(down.position.y, size.height.toFloat(), currentEdges.top, currentEdges.bottom))
                        return@awaitEachGesture
                    // Where the finger was when the long press switched to 2×; sideways movement from there seeks.
                    var anchorX: Float? = null
                    var swipedSideways = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // The shade or another window taking focus mid-drag means the finger was not seeking.
                            finish(if (currentWindowFocused) SeekDragPhase.END else SeekDragPhase.CANCEL)
                            break
                        }
                        if (taken) {
                            dragged += change.positionChangeIgnoreConsumed().x
                            change.consume()
                            currentSeekDrag?.invoke(SeekDragPhase.MOVE, dragged / size.width.coerceAtLeast(1))
                            continue
                        }
                        // Before the long press a drag is never a seek. A clearly sideways swipe does nothing at all:
                        // consuming it cancels the tap and the long press. It is consumed only on the Final pass, after
                        // the VerticalPager above has seen the event: a Main-pass consumption made the pager drop the
                        // whole gesture, so a thumb swipe that opened with a short sideways arc never changed the
                        // episode. The tap detector below this modifier checks the Final pass and still cancels.
                        if (!holding) {
                            val moved = change.position - down.position
                            if (!swipedSideways && kotlin.math.abs(moved.x) > viewConfiguration.touchSlop &&
                                isSidewaysDrag(moved.x, moved.y)) swipedSideways = true
                            if (swipedSideways) {
                                awaitPointerEvent(PointerEventPass.Final).changes
                                    .firstOrNull { it.id == down.id }?.consume()
                            }
                            continue
                        }
                        val anchor = anchorX ?: change.position.x.also { anchorX = it }
                        if (kotlin.math.abs(change.position.x - anchor) <= viewConfiguration.touchSlop) continue
                        taken = currentWindowFocused && currentEnabled &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            currentSeekDrag?.invoke(SeekDragPhase.START, 0f) == true
                        if (!taken) break
                        change.consume()
                        dragged = 0f
                        // Moving the progress takes over from 2×: play at the normal speed and show the progress bar.
                        currentHoldChange(false)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            } finally {
                finish(SeekDragPhase.CANCEL)
            }
        }
        .pointerInput(enabled, foreground, lifecycle, onDoubleTap != null) {
            if (!enabled || !foreground) return@pointerInput
            try {
                detectTapGestures(
                    onPress = {
                        try { tryAwaitRelease() }
                        finally { releaseHold() }
                    },
                    onDoubleTap = if (onDoubleTap == null) null else { position ->
                        if (currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            currentDoubleTap?.invoke(position.x / size.width.coerceAtLeast(1))
                    },
                    onTap = { position ->
                        if (currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                            currentTap(position.x / size.width.coerceAtLeast(1))
                    },
                    onLongPress = { position ->
                        if (currentEnabled &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !holding) {
                            holding = true
                            // A short buzz confirms that holding switched to 2×.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentHoldChange(true)
                        }
                    },
                )
            } finally {
                releaseHold()
            }
        })
}

/** The top and bottom bands, in pixels, where the system's own edge gestures start. */
private data class SeekEdges(val top: Float, val bottom: Float)
