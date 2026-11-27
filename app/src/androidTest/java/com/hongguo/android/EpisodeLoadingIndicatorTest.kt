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
    private val indicator get() = compose.onNodeWithTag("loading_allowed")

    @Before fun start() {
        compose.setContent {
            if (rememberEpisodeLoadingAllowed(episode, rendered, failed, settled))
                Box(Modifier.testTag("loading_allowed"))
        }
        compose.mainClock.autoAdvance = false
    }

    @Test fun shortSwitchSuppressesLoadingButAllowsImmediateRebufferAfterFirstFrame() {
        indicator.assertExists() // First play.
        update { rendered = true }
        update { episode = "two"; rendered = false }
        indicator.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(160)
        indicator.assertDoesNotExist()
        update { rendered = true }
        indicator.assertExists()
        update { rendered = false } // Same episode retries never start another switch delay.
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
