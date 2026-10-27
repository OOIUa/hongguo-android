package com.hongguo.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal val PlaybackSpeeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)

internal fun playbackSpeedLabel(speed: Float): String = "${speed.toString().removeSuffix(".0")}×"

/** One track owns all three progress layers, aligned to the slider's circular thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(
    value: Float,
    bufferedFraction: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier.fillMaxWidth().height(48.dp),
        thumb = {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (dragging) 16.dp else 12.dp)
                    .shadow(if (enabled) 2.dp else 0.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (enabled) 1f else .35f)))
            }
        },
        track = { slider ->
            val accent = Orange
            Canvas(Modifier.fillMaxWidth().height(if (dragging) 5.dp else 3.dp)) {
                val corners = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = .22f), cornerRadius = corners)
                drawRoundRect(Color.White.copy(alpha = if (enabled) .42f else .18f),
                    size = Size(size.width * bufferedFraction.coerceIn(0f, 1f), size.height), cornerRadius = corners)
                drawRoundRect(if (enabled) accent else Color.White.copy(alpha = .3f),
                    size = Size(size.width * slider.value.coerceIn(0f, 1f), size.height), cornerRadius = corners)
            }
        },
    )
}
