package com.hongguo.android

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpisodeLoadingIndicatorTest {
    @get:Rule val compose = createComposeRule()

    private var episode by mutableStateOf("one")
    private var rendered by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var settled by mutableStateOf(true)
    private var buffering by mutableStateOf(true)
    private val indicator get() = compose.onNodeWithTag("loading_allowed")

    @Before fun start() {
        compose.setContent {
            if (rememberEpisodeLoadingAllowed(episode, rendered, failed, settled) && buffering)
                Box(Modifier.testTag("loading_allowed"))
        }
        compose.mainClock.autoAdvance = false
    }

    @Test fun shortSwitchHidesStartupBufferingThenShowsLaterRebuffering() {
        indicator.assertExists() // First play.
        update { rendered = true }
        update { episode = "two"; rendered = false }
        indicator.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(160)
        indicator.assertDoesNotExist()
        update { rendered = true; buffering = false }
        update { buffering = true }
        indicator.assertDoesNotExist()
        update { buffering = false }
        compose.mainClock.advanceTimeBy(350)
        update { buffering = true }
        indicator.assertExists()
        update { rendered = false } // A restart of this same episode keeps normal loading feedback.
        indicator.assertExists()
    }

    @Test fun longSwitchShowsLoadingAfterTheTargetPageSettles() {
        update { rendered = true }
        update { episode = "two"; rendered = false; settled = false }
        compose.mainClock.advanceTimeBy(400)
        indicator.assertDoesNotExist()
        update { settled = true }
        compose.mainClock.advanceTimeBy(200)
        indicator.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(150)
        indicator.assertExists()
    }

    @Test fun lateFirstFrameGetsItsOwnBoundedWindowForReadyToBufferingBounce() {
        update { rendered = true }
        update { episode = "two"; rendered = false; settled = false }
        compose.mainClock.advanceTimeBy(320)
        update { settled = true }
        compose.mainClock.advanceTimeBy(360)
        indicator.assertExists() // Still waiting for the first frame after the page settled.

        update { rendered = true }
        indicator.assertDoesNotExist() // The first frame may precede READY.
        update { buffering = false }
        update { buffering = true } // Media3 briefly leaves READY just after painting the first frame.
        indicator.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(160)
        indicator.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(180)
        indicator.assertExists() // A continuing stall becomes visible even without another ready event.
    }

    @Test fun immediateFailureEndsDelayForRetryOfSameEpisode() {
        update { rendered = true }
        update { episode = "two"; rendered = false }
        indicator.assertDoesNotExist()
        update { failed = true }
        indicator.assertExists()
        update { failed = false }
        indicator.assertExists()
    }

    private fun update(change: () -> Unit) {
        compose.runOnIdle(change)
        compose.mainClock.advanceTimeByFrame()
    }
}
