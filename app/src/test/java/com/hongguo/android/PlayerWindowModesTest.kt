package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWindowModesTest {
    @Test fun onlyWidePicturesOfferFullScreen() {
        assertTrue(isLandscapeVideo(1920, 1080))
        assertFalse(isLandscapeVideo(1080, 1920))
        assertFalse(isLandscapeVideo(1080, 1080))
        assertFalse(isLandscapeVideo(0, 0))
    }

    @Test fun fullScreenButtonSitsJustUnderTheFittedPicture() {
        // 402 × 874 portrait screen, 16:9 picture: 226 tall, centred, so its bottom edge is at 550.
        assertEquals(550f + 14f, fullScreenButtonTop(402f, 874f, 1920, 1080, 14f), .5f)
        // Unknown size falls back to the middle of the screen.
        assertEquals(437f + 14f, fullScreenButtonTop(402f, 874f, 0, 0, 14f), .01f)
    }

    @Test fun smallWindowRatioStaysWithinPlatformLimits() {
        assertEquals(1080 to 1920, pictureInPictureRatio(1080, 1920))
        assertEquals(9 to 16, pictureInPictureRatio(0, 0))
        assertEquals(239 to 100, pictureInPictureRatio(3000, 1000))
        assertEquals(100 to 239, pictureInPictureRatio(400, 1000))
    }
}
