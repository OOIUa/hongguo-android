package com.hongguo.android

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

/** Where a tap lands across the picture: the outer 30% on each side skips, the middle plays or pauses. */
internal enum class TapZone { BACK, MIDDLE, FORWARD }

internal fun tapZone(fraction: Float): TapZone = when {
    fraction < .3f -> TapZone.BACK
    fraction > .7f -> TapZone.FORWARD
    else -> TapZone.MIDDLE
}

/** The position a skip of [deltaMs] lands on, held a second short of the end; null when it would not move. */
internal fun skipTarget(positionMs: Long, durationMs: Long, deltaMs: Long): Long? {
    if (durationMs <= 0) return null
    val target = (positionMs + deltaMs).coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
    return target.takeIf { kotlin.math.abs(it - positionMs) > 200 }
}

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
 * Put below the player's buttons/seek bar. A vertical drag cancels the tap detector before its
 * long-press timeout, so the surrounding VerticalPager still owns normal episode swipes.
 * enabled controls both touch and accessibility clicks; a paused player can keep this layer
 * enabled and decide whether to accept a hold in its callback without losing tap-to-resume.
 * Taps report where they landed as a fraction of the width. With [onDoubleTap] set, a single tap waits out the
 * double-tap timeout before it fires.
 */
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
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(onTap)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentAccessibilityClick by rememberUpdatedState(onAccessibilityClick)
    val currentHoldChange by rememberUpdatedState(onHoldSpeedChange)
    val currentEnabled by rememberUpdatedState(enabled)
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
                        if (position.x >= 0 && position.x < size.width / 2f && currentEnabled &&
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
