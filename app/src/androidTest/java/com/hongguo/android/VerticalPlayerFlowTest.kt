package com.hongguo.android

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsEnabled
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
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
    private var originalPlaybackSpeed: Float? = null

    @After fun restorePlaybackSettings() {
        // JUnit runs @After before the Compose rule tears down the Activity.
        compose.runOnIdle {
            originalAutoPlayNext?.let { playbackModel?.setAutoPlayNext(it) }
            originalPlaybackSpeed?.let { playbackModel?.setPlaybackSpeed(it) }
        }
    }

    @Test fun swipingPickerAndAutomaticNextKeepOneActivePlayer() {
        lateinit var model: HongguoViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java]
            playbackModel = model
            originalAutoPlayNext = model.state.value.autoPlayNext
            originalPlaybackSpeed = model.state.value.playbackSpeed
            model.setAutoPlayNext(true)
            model.setPlaybackSpeed(1f)
        }
        compose.onNodeWithTag("home_search").performClick()
        compose.onNodeWithTag("search_input").performTextClearance()
        val dramaQuery = InstrumentationRegistry.getArguments().getString("dramaQuery") ?: "总裁"
        compose.onNodeWithTag("search_input").performTextInput(dramaQuery)
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

        // Holding is a temporary decoder override, never a persisted speed preference.
        assertTemporaryHold(first, model, expectedSpeed = 2f, playing = true)

        // A paused episode must stay paused after a boundary drag or a drag that returns to the same page.
        compose.runOnIdle { assertTrue("The initial episode must begin with play enabled", first.playWhenReady) }
        compose.onNodeWithTag("player_toggle").performClick()
        compose.waitUntil(5_000) {
            var paused = false
            compose.runOnIdle { paused = attachedPlayers(compose.activity.window.decorView).singleOrNull()?.playWhenReady == false }
            paused
        }

        // Changing speed applies to the current decoder without replacing it or resuming playback.
        var pausedPosition = 0L
        compose.runOnIdle { pausedPosition = first.currentPosition }
        compose.onNodeWithTag("player_speed").performClick()
        captureScreenshot("qa-player-speed-sheet.png")
        compose.onNodeWithTag("player_speed_1.5").performClick()
        compose.waitUntil(5_000) {
            var applied = false
            compose.runOnIdle {
                applied = attachedPlayers(compose.activity.window.decorView).singleOrNull()?.playbackParameters?.speed == 1.5f
            }
            applied
        }
        compose.runOnIdle {
            assertSame("Changing speed must keep the current player", first, attachedPlayers(compose.activity.window.decorView).single())
            assertFalse("Changing speed must preserve a paused episode", first.playWhenReady)
            assertTrue("Changing speed must not seek the episode", kotlin.math.abs(pausedPosition - first.currentPosition) < 500)
            assertEquals("Speed changes must preserve the original voice pitch", 1f, first.playbackParameters.pitch, .001f)
            assertEquals(1.5f, model.state.value.playbackSpeed, .001f)
            assertEquals("The selected speed must be persisted on this device", 1.5f,
                compose.activity.getSharedPreferences("hongguo_local", 0).getFloat("playback_speed", 0f), .001f)
            val restoredStore = ViewModelStore()
            try {
                val restoredModel = ViewModelProvider(restoredStore,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(compose.activity.application))[HongguoViewModel::class.java]
                assertEquals("A fresh ViewModel must restore the device's saved speed", 1.5f,
                    restoredModel.state.value.playbackSpeed, .001f)
            } finally {
                restoredStore.clear()
            }
        }
        captureScreenshot("qa-player-controls.png")

        // Long pressing a paused episode must neither resume it nor replace its selected speed.
        assertTemporaryHold(first, model, expectedSpeed = 1.5f, playing = false)

        // Exercise the actual seek control, with a slight vertical drift as on a handheld screen.
        // The horizontal drag must seek this episode instead of being taken by the vertical pager.
        compose.onNodeWithTag("player_seek").assertIsEnabled().performTouchInput {
            swipe(start = Offset(width * .25f, center.y), end = Offset(width * .60f, center.y + 8f), durationMillis = 600)
        }
        compose.waitUntil(60_000) {
            var sought = false
            compose.runOnIdle {
                sought = first.playbackState == Player.STATE_READY && first.currentPosition.toDouble() / first.duration in 0.5..0.7
            }
            sought
        }
        assertStillPaused(model, 0, firstRequest)
        compose.runOnIdle {
            assertSame("Dragging progress must not create a different player", first, attachedPlayers(compose.activity.window.decorView).single())
            assertTrue("Dragging progress must save the selected media position", model.state.value.resumePositionMs > first.duration / 2)
        }

        // Recreation releases the decoder, but must restore speed, seek position and pause intent.
        compose.activityRule.scenario.recreate()
        first = awaitEpisode(model, 0)
        assertStillPaused(model, 0, firstRequest)
        compose.runOnIdle {
            assertEquals(1.5f, first.playbackParameters.speed, .001f)
            assertTrue("Recreation must restore the selected media position", first.currentPosition.toDouble() / first.duration in 0.5..0.7)
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

    private fun assertTemporaryHold(player: ExoPlayer, model: HongguoViewModel, expectedSpeed: Float, playing: Boolean) {
        val preferred = model.state.value.playbackSpeed
        if (playing) compose.waitUntil(10_000) {
            var ready = false
            compose.runOnIdle { ready = player.isPlaying }
            ready
        }
        val clockWasAutomatic = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("player_toggle").performTouchInput {
                down(Offset(width * .2f, height * .4f))
            }
            compose.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.autoAdvance = clockWasAutomatic
            compose.waitUntil(5_000) {
                var applied = false
                compose.runOnIdle { applied = player.playbackParameters.speed == expectedSpeed }
                applied
            }
            compose.runOnIdle {
                assertSame("Holding must retain the current decoder", player, attachedPlayers(compose.activity.window.decorView).single())
                assertEquals(playing, player.playWhenReady)
                assertEquals(preferred, model.state.value.playbackSpeed, .001f)
                assertEquals(preferred, compose.activity.getSharedPreferences("hongguo_local", 0).getFloat("playback_speed", 0f), .001f)
                assertEquals(1f, player.playbackParameters.pitch, .001f)
            }
            if (playing) {
                compose.onNodeWithTag("player_hold_speed").assertExists()
                captureScreenshot("qa-player-hold-speed.png")
            } else compose.onNodeWithTag("player_hold_speed").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = clockWasAutomatic
            compose.onNodeWithTag("player_toggle").performTouchInput { up() }
        }
        compose.waitUntil(5_000) {
            var restored = false
            compose.runOnIdle { restored = player.playbackParameters.speed == preferred }
            restored
        }
        compose.runOnIdle { assertEquals(playing, player.playWhenReady) }
        compose.onNodeWithTag("player_hold_speed").assertDoesNotExist()
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
            assertEquals("A non-settling drag must preserve the selected speed", model.state.value.playbackSpeed,
                player.playbackParameters.speed, .001f)
        }
    }

    private fun awaitEpisode(model: HongguoViewModel, index: Int): ExoPlayer {
        compose.waitUntil(180_000) {
            var count = 0
            var renderedFrame = false
            compose.runOnIdle {
                val players = attachedPlayers(compose.activity.window.decorView)
                count = players.size
                renderedFrame = (players.singleOrNull()?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            assertTrue("Adjacent pages must never own simultaneous players", count <= 1)
            val state = model.state.value
            state.episodePosition == index && !state.preparing &&
                ((state.firstFrameRendered && renderedFrame) || state.playbackError != null)
        }
        assertNull("The selected real episode must decode", model.state.value.playbackError)
        assertTrue(model.state.value.firstFrameRendered)
        lateinit var active: ExoPlayer
        compose.runOnIdle {
            val players = attachedPlayers(compose.activity.window.decorView)
            assertEquals("Exactly one visible page must bind a player", 1, players.size)
            active = players.single()
            assertTrue("A frame must come from the real video decoder", (active.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0)
            assertEquals("Every newly selected or automatically advanced episode must inherit the selected speed",
                model.state.value.playbackSpeed, active.playbackParameters.speed, .001f)
        }
        return active
    }

    private fun captureScreenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("The device must provide a screenshot for visual review")
        try {
            File(compose.activity.filesDir, name).outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun attachedPlayers(view: View): List<ExoPlayer> {
        val found = mutableListOf<ExoPlayer>()
        if (view is PlayerView) (view.player as? ExoPlayer)?.let(found::add)
        if (view is ViewGroup) for (index in 0 until view.childCount) found += attachedPlayers(view.getChildAt(index))
        return found
    }
}
