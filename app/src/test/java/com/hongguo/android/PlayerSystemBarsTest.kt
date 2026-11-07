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

    @Test fun outerThirdsSkipAndTheMiddlePlays() {
        assertEquals(TapZone.BACK, tapZone(.12f))
        assertEquals(TapZone.MIDDLE, tapZone(.3f))
        assertEquals(TapZone.MIDDLE, tapZone(.5f))
        assertEquals(TapZone.MIDDLE, tapZone(.7f))
        assertEquals(TapZone.FORWARD, tapZone(.88f))
    }

    @Test fun skipStaysInsideTheEpisode() {
        assertEquals(40_000L, skipTarget(30_000, 120_000, 10_000))
        assertEquals(20_000L, skipTarget(30_000, 120_000, -10_000))
        assertEquals(0L, skipTarget(4_000, 120_000, -10_000))
        assertEquals(119_000L, skipTarget(115_000, 120_000, 10_000))
        assertEquals(null, skipTarget(119_000, 120_000, 10_000))
        assertEquals(null, skipTarget(100, 120_000, -10_000))
        assertEquals(null, skipTarget(5_000, 0, 10_000))
    }
}
