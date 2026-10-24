package com.hongguo.android

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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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
            onClick(label = "播放或暂停") {
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
                            currentHoldChange(true)
                        }
                    },
                )
            } finally {
                releaseHold()
            }
        })
}
