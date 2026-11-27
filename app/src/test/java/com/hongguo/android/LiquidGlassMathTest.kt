package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassMathTest {
    @Test fun backdropScaleAdaptsToScreenDensity() {
        assertEquals(0.75f, glassBackdropScale(2.0f), 1e-4f)
        assertEquals(0.50f, glassBackdropScale(3.0f), 1e-4f)
        assertEquals(0.45f, glassBackdropScale(3.5f), 1e-4f)
        assertEquals(0.45f, glassBackdropScale(4.0f), 1e-4f)
        assertEquals(0.75f, glassBackdropScale(1.0f), 1e-4f)
        assertEquals(0.50f, glassBackdropScale(0f), 1e-4f)
    }

    @Test fun restingLensCentersOnSelectedTabWithZeroLiquid() {
        val lens = LensScratch()
        lens.update(
            barWidth = 360f, barHeight = 62f, tabCount = 4,
            position = 1f, velocity = 0f, press = 0f,
            insetX = 4.5f, insetY = 4.5f, growY = 3.2f,
        )
        assertEquals(81f, lens.width, 1e-3f)
        assertEquals(53f, lens.height, 1e-3f)
        assertEquals(94.5f, lens.left, 1e-3f)
        assertEquals(4.5f, lens.top, 1e-3f)
        assertEquals(0f, lens.liquid, 1e-4f)
    }

    @Test fun pressedAndGlidingLensSwellsStretchesAndStaysInsideBar() {
        val rest = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 0f, press = 0f, insetX = 4.5f, insetY = 4.5f, growY = 3.2f)
        }
        val pressed = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 0f, press = 1f, insetX = 4.5f, insetY = 4.5f, growY = 3.2f)
        }
        val gliding = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 14f, press = 0f, insetX = 4.5f, insetY = 4.5f, growY = 3.2f)
        }
        assertTrue(pressed.width > rest.width)
        assertTrue(pressed.height > rest.height)
        assertEquals(1f, pressed.liquid, 1e-4f)
        assertTrue(gliding.width > rest.width)
        assertTrue(gliding.height < rest.height)
        assertTrue(gliding.liquid > 0.5f)

        // Rubber-band past the right end stays cleanly bounded inside the bar.
        val edge = LensScratch().apply {
            update(360f, 62f, 4, position = 3.3f, velocity = 10f, press = 1f, insetX = 4.5f, insetY = 4.5f, growY = 3.2f)
        }
        assertTrue(edge.left >= 0f)
        assertTrue(edge.left + edge.width <= 360f)
        assertTrue(edge.top >= 0f)
        assertTrue(edge.top + edge.height <= 62f)
    }
}
