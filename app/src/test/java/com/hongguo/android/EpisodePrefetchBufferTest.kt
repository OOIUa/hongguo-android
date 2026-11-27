package com.hongguo.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePrefetchBufferTest {
    @Test fun requiresFiveSecondsWhileTheRestOfTheEpisodeIsStillLoading() {
        assertFalse(hasBufferForNextEpisode(4_999, 30_000))
        assertTrue(hasBufferForNextEpisode(5_000, 30_000))
    }

    @Test fun startsAtAShortTailOnlyOnceItIsBufferedToTheEnd() {
        assertFalse(hasBufferForNextEpisode(2_999, 3_000))
        assertTrue(hasBufferForNextEpisode(3_000, 3_000))
        assertTrue(hasBufferForNextEpisode(3_001, 3_000))
    }

    @Test fun unknownDurationStillRequiresFiveBufferedSeconds() {
        assertFalse(hasBufferForNextEpisode(4_999, 0))
        assertFalse(hasBufferForNextEpisode(4_999, -500))
        assertTrue(hasBufferForNextEpisode(5_000, 0))
    }
}
