package com.hongguo.android

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSystemBarsTest {
    @Test fun portraitPlaybackKeepsSystemBarsSoItIsNotFullScreen() {
        assertFalse(playerHidesSystemBars(Configuration.ORIENTATION_PORTRAIT))
    }

    @Test fun onlyLandscapePlaybackIsFullScreen() {
        assertTrue(playerHidesSystemBars(Configuration.ORIENTATION_LANDSCAPE))
        assertFalse(playerHidesSystemBars(Configuration.ORIENTATION_UNDEFINED))
    }

    @Test fun pinchDecidesOnlyOnceItIsClear() {
        assertEquals(true, pinchCleanScreen(1.18f))
        assertEquals(false, pinchCleanScreen(.85f))
        assertEquals(null, pinchCleanScreen(1f))
        assertEquals(null, pinchCleanScreen(1.1f))
        assertEquals(null, pinchCleanScreen(.9f))
    }

    @Test fun sidewaysDragMovesThroughTheEpisodeAndStaysInside() {
        // A short episode: the full width spans all of it.
        assertEquals(90_000L, dragSeekTarget(30_000, 120_000, .5f))
        assertEquals(0L, dragSeekTarget(30_000, 120_000, -.5f))
        assertEquals(119_000L, dragSeekTarget(30_000, 120_000, 1f))
        assertEquals(30_000L, dragSeekTarget(30_000, 120_000, 0f))
        // A long one: the full width is three minutes.
        assertEquals(DragSeekSpanMillis / 2 + 600_000, dragSeekTarget(600_000, 3_600_000, .5f))
        assertEquals(null, dragSeekTarget(10_000, 0, .5f))
    }

    /** Issue #3: pulling the notification shade must not move the progress. */
    @Test fun seekDragIgnoresSystemEdges() {
        assertFalse(seekDragMayStart(20f, 1000f, 120f, 60f))
        assertFalse(seekDragMayStart(960f, 1000f, 120f, 60f))
        assertTrue(seekDragMayStart(500f, 1000f, 120f, 60f))
        assertFalse(seekDragMayStart(500f, 0f, 120f, 60f))
        assertTrue(isSidewaysDrag(40f, 10f))
        assertFalse(isSidewaysDrag(30f, 20f))
        assertFalse(isSidewaysDrag(10f, 60f))
    }
}
