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
 */
@Composable
internal fun PlayerInteractionLayer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onTap: () -> Unit,
    onHoldSpeedChange: (Boolean) -> Unit,
    tapLabel: String = "播放或暂停",
    cleanScreen: Boolean = false,
    onCleanScreenChange: ((Boolean) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(onTap)
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
            // A pinch has no screen-reader equivalent.
            onCleanScreenChange?.let { change ->
                customActions = listOf(CustomAccessibilityAction(if (cleanScreen) "退出清屏" else "清屏播放") { change(!cleanScreen); true })
            }
            onClick(label = tapLabel) {
                if (currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    currentTap()
                    true
                } else false
            }
        }
        .pointerInput(enabled, foreground, lifecycle) {
            if (!enabled || !foreground) return@pointerInput
            try {
                detectTapGestures(
                    onPress = {
                        try { tryAwaitRelease() }
                        finally { releaseHold() }
                    },
                    onTap = {
                        if (currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentTap()
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
