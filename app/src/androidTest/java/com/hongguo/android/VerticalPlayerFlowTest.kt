package com.hongguo.android

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real metadata and media through the full-screen pager; no player is installed in adjacent pages. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VerticalPlayerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var playbackModel: HongguoViewModel? = null
    private var originalAutoPlayNext: Boolean? = null

    @After fun restoreAutoPlaySetting() {
        val original = originalAutoPlayNext ?: return
        // JUnit runs @After before the Compose rule tears down the Activity.
        compose.runOnIdle { playbackModel?.setAutoPlayNext(original) }
    }

    @Test fun swipingPickerAndAutomaticNextKeepOneActivePlayer() {
        lateinit var model: HongguoViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java]
            playbackModel = model
            originalAutoPlayNext = model.state.value.autoPlayNext
            model.setAutoPlayNext(true)
        }
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_input").performTextClearance()
        compose.onNodeWithTag("search_input").performTextInput("总裁")
        compose.onNodeWithTag("search_button").performClick()
        compose.waitUntil(120_000) { !model.state.value.searching }
        assertNull(model.state.value.searchError)
        assertTrue("The live search must supply a real series", model.state.value.results.isNotEmpty())
        compose.onNodeWithTag("result_0").performClick()
        compose.waitUntil(120_000) { !model.state.value.loadingDetail }
        assertNull(model.state.value.detailError)
        assertTrue("This live series must contain at least three episodes", (model.state.value.detail?.episodes?.size ?: 0) >= 3)
        compose.onNodeWithTag("episode_0").performScrollTo().performClick()
        var first = awaitEpisode(model, 0)
        val firstRequest = model.state.value.video!!.requestId

        // A paused episode must stay paused after a boundary drag or a drag that returns to the same page.
        compose.runOnIdle { assertTrue("The initial episode must begin with play enabled", first.playWhenReady) }
        compose.onNodeWithTag("player_toggle").performClick()
        compose.waitUntil(5_000) {
            var paused = false
            compose.runOnIdle { paused = attachedPlayers(compose.activity.window.decorView).singleOrNull()?.playWhenReady == false }
            paused
        }
        compose.onNodeWithTag("episode_pager").performTouchInput {
            swipe(start = Offset(center.x * .5f, center.y - 40f), end = Offset(center.x * .5f, center.y + 40f), durationMillis = 900)
        }
        assertStillPaused(model, 0, firstRequest)
        compose.onNodeWithTag("episode_pager").performTouchInput {
            swipe(start = Offset(center.x * .5f, center.y + 35f), end = Offset(center.x * .5f, center.y - 35f), durationMillis = 900)
        }
        assertStillPaused(model, 0, firstRequest)
        compose.onNodeWithTag("player_resume").performClick()
        compose.waitUntil(10_000) {
            var playing = false
            compose.runOnIdle { playing = attachedPlayers(compose.activity.window.decorView).singleOrNull()?.isPlaying == true }
            playing
        }
        first = awaitEpisode(model, 0)

        compose.onNodeWithTag("episode_pager").performTouchInput { swipeUp(durationMillis = 600) }
        val second = awaitEpisode(model, 1)
        assertNotSame("A settled episode must replace the previous player", first, second)
        assertNotEquals(firstRequest, model.state.value.video!!.requestId)
        compose.runOnIdle { assertEquals("The old episode must release its player", Player.STATE_IDLE, first.playbackState) }

        compose.onNodeWithTag("episode_pager").performTouchInput { swipeDown(durationMillis = 600) }
        val revisited = awaitEpisode(model, 0)
        compose.runOnIdle {
            assertEquals(Player.STATE_IDLE, second.playbackState)
            assertTrue("The actual decoder must expose a finite duration", revisited.duration > 1_000)
            revisited.seekTo((revisited.duration - 200).coerceAtLeast(0))
        }
        val automatic = awaitEpisode(model, 1)
        assertNotSame("End-of-episode must advance to one new player", revisited, automatic)
        compose.runOnIdle { assertEquals(Player.STATE_IDLE, revisited.playbackState) }

        compose.onNodeWithTag("player_episode_picker").performClick()
        compose.onNodeWithTag("player_episode_2").performClick()
        val selected = awaitEpisode(model, 2)
        assertNotSame(automatic, selected)
        compose.runOnIdle { assertEquals(Player.STATE_IDLE, automatic.playbackState) }
        compose.onNodeWithTag("player_back").performClick()
        compose.waitUntil(10_000) {
            var empty = false
            compose.runOnIdle { empty = attachedPlayers(compose.activity.window.decorView).isEmpty() }
            empty
        }
        compose.runOnIdle { assertEquals("Leaving full-screen playback must release the player", Player.STATE_IDLE, selected.playbackState) }
    }

    private fun assertStillPaused(model: HongguoViewModel, index: Int, requestId: Long) {
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnIdle {
                val players = attachedPlayers(compose.activity.window.decorView)
                assertTrue("A non-settling drag must not introduce extra players", players.size <= 1)
                ready = players.singleOrNull()?.playbackState == Player.STATE_READY
            }
            ready
        }
        assertEquals("A canceled or boundary drag must keep the same selected episode", index, model.state.value.episodePosition)
        assertEquals(requestId, model.state.value.video?.requestId)
        assertNull(model.state.value.playbackError)
        compose.runOnIdle {
            val player = attachedPlayers(compose.activity.window.decorView).single()
            assertFalse("A non-settling drag must preserve the user's pause intent", player.playWhenReady)
            assertFalse(player.isPlaying)
        }
    }

    private fun awaitEpisode(model: HongguoViewModel, index: Int): ExoPlayer {
        compose.waitUntil(180_000) {
            var count = 0
            compose.runOnIdle { count = attachedPlayers(compose.activity.window.decorView).size }
            assertTrue("Adjacent pages must never own simultaneous players", count <= 1)
            val state = model.state.value
            state.episodePosition == index && !state.preparing &&
                (state.firstFrameRendered || state.playbackError != null)
        }
        assertNull("The selected real episode must decode", model.state.value.playbackError)
        assertTrue(model.state.value.firstFrameRendered)
        lateinit var active: ExoPlayer
        compose.runOnIdle {
            val players = attachedPlayers(compose.activity.window.decorView)
            assertEquals("Exactly one visible page must bind a player", 1, players.size)
            active = players.single()
            assertTrue("A frame must come from the real video decoder", (active.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
        }
        return active
    }

    private fun attachedPlayers(view: View): List<ExoPlayer> {
        val found = mutableListOf<ExoPlayer>()
        if (view is PlayerView) (view.player as? ExoPlayer)?.let(found::add)
        if (view is ViewGroup) for (index in 0 until view.childCount) found += attachedPlayers(view.getChildAt(index))
        return found
    }
}
