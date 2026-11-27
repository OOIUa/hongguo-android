package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassMathTest {
    @Test fun verticalLensCentersOnEachDestination() {
        val lens = LensScratch()
        for (tab in 0..3) {
            lens.update(62f, 256f, 4, tab.toFloat(), 0f, 0f, 4.5f, 4.5f, 3.2f, vertical = true)
            assertEquals(31f, lens.left + lens.width / 2f, 1e-3f)
            assertEquals(64f * tab + 32f, lens.top + lens.height / 2f, 1e-3f)
            assertEquals(55f, lens.height, 1e-3f)
            assertEquals(0f, lens.liquid, 1e-4f)
        }
    }

    @Test fun rotatingTheTrackTransposesTheMovingLensAndPreservesEdgeBounds() {
        val lens = LensScratch()
        for (position in listOf(-0.3f, 1.4f, 3.3f)) {
            lens.update(320f, 60f, 4, position, -14f, 0.8f, 4.5f, 5.5f, 3.2f)
            val left = lens.left
            val top = lens.top
            val width = lens.width
            val height = lens.height
            val liquid = lens.liquid
            lens.update(60f, 320f, 4, position, -14f, 0.8f, 5.5f, 4.5f, 3.2f, vertical = true)
            assertEquals(left, lens.top, 1e-3f)
            assertEquals(top, lens.left, 1e-3f)
            assertEquals(width, lens.height, 1e-3f)
            assertEquals(height, lens.width, 1e-3f)
            assertEquals(liquid, lens.liquid, 1e-4f)
            assertTrue(lens.top >= 0f && lens.top + lens.height <= 320f)
            assertTrue(lens.left >= 0f && lens.left + lens.width <= 60f)
        }
    }

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
            insetX = 4.5f, insetY = 4.5f, crossGrowth = 3.2f,
        )
        assertEquals(81f, lens.width, 1e-3f)
        assertEquals(53f, lens.height, 1e-3f)
        assertEquals(94.5f, lens.left, 1e-3f)
        assertEquals(4.5f, lens.top, 1e-3f)
        assertEquals(0f, lens.liquid, 1e-4f)
    }

    @Test fun pressedAndGlidingLensSwellsStretchesAndStaysInsideBar() {
        val rest = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 0f, press = 0f, insetX = 4.5f, insetY = 4.5f, crossGrowth = 3.2f)
        }
        val pressed = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 0f, press = 1f, insetX = 4.5f, insetY = 4.5f, crossGrowth = 3.2f)
        }
        val gliding = LensScratch().apply {
            update(360f, 62f, 4, position = 1.5f, velocity = 14f, press = 0f, insetX = 4.5f, insetY = 4.5f, crossGrowth = 3.2f)
        }
        assertTrue(pressed.width > rest.width)
        assertTrue(pressed.height > rest.height)
        assertEquals(1f, pressed.liquid, 1e-4f)
        assertTrue(gliding.width > rest.width)
        assertTrue(gliding.height < rest.height)
        assertTrue(gliding.liquid > 0.5f)

        // Rubber-band past the right end stays cleanly bounded inside the bar.
        val edge = LensScratch().apply {
            update(360f, 62f, 4, position = 3.3f, velocity = 10f, press = 1f, insetX = 4.5f, insetY = 4.5f, crossGrowth = 3.2f)
        }
        assertTrue(edge.left >= 0f)
        assertTrue(edge.left + edge.width <= 360f)
        assertTrue(edge.top >= 0f)
        assertTrue(edge.top + edge.height <= 62f)
    }
}
