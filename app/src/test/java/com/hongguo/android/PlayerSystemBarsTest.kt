package com.hongguo.android

import android.content.res.Configuration
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
}
