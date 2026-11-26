package com.hongguo.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.ViewConfiguration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.android.danmaku.DanmakuSettings
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LandscapePlayerLockTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var media: File
    private lateinit var player: Player
    private val accessibility = object : AccessibilityManager {
        override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
                containsText: Boolean, containsControls: Boolean): Long =
            if (containsControls) 6_000L else originalTimeoutMillis
    }
    private val surface get() = compose.onNodeWithTag("player_locked_surface")
    private val unlock get() = compose.onNodeWithTag("player_touch_unlock")

    @Before fun openLocalPlayer() {
        media = File.createTempFile("touch-lock-", ".wav", compose.activity.cacheDir)
        writeSilentWave(media)
        val episodes = (1..2).map { Episode(it.toString(), it, "第 $it 集") }
        val detail = DramaDetail("lock-fixture", "触控锁回归", "", "", episodes)
        val state = HongguoUiState(detail = detail, playerOpen = true, firstFrameRendered = true,
            video = PreparedVideo(episodes.first().id, media.absolutePath, 1), danmaku = DanmakuSettings(enabled = false))
        compose.activityRule.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[HongguoViewModel::class.java]
            activity.setContent {
                CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                    HongguoTheme(applyToWindow = false) { EpisodePlayerScreen(state, model) }
                }
            }
        }
        compose.waitUntil(8_000) { compose.onNodeWithTag("player_view").isDisplayed() }
        onView(isAssignableFrom(PlayerView::class.java)).check { view, error ->
            if (error != null) throw error
            player = requireNotNull((view as PlayerView).player)
        }
        compose.waitUntil(8_000) { compose.runOnIdle { player.isPlaying && player.isCurrentMediaItemSeekable } }
    }

    @After fun removeLocalMedia() { if (::media.isInitialized) media.delete() }

    @Test fun lockedGesturesLeaveTransportAloneAndOnlyRevealUnlock() {
        rotate(landscape = true)
        val commands = mutableListOf<String>()
        compose.runOnIdle { player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { commands += "play=$playWhenReady" }
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { commands += "speed=${parameters.speed}" }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) commands += "seek=${new.positionMs}"
            }
        }) }
        compose.onNodeWithTag("player_touch_lock").performClick()
        assertOnlyUnlockIsOffered()
        compose.mainClock.advanceTimeBy(4_500)
        unlock.assertIsDisplayed()
        surface.performTouchInput {
            doubleClick(center)
            swipeUp()
        }
        surface.performTouchInput { down(Offset(width * .25f, center.y)) }
        compose.mainClock.advanceTimeBy(ViewConfiguration.getLongPressTimeout() + 150L)
        surface.performTouchInput {
            moveTo(Offset(width * .75f, center.y), delayMillis = 100)
            up()
        }
        compose.onNodeWithTag("episode_page_0").assertIsDisplayed()
        compose.runOnIdle { assertTrue("Unexpected transport changes: $commands", commands.isEmpty()) }

        awaitHiddenUnlock()
        surface.performTouchInput { click(center) }
        assertOnlyUnlockIsOffered()
        awaitHiddenUnlock()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertOnlyUnlockIsOffered()
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
        unlock.performClick()
        surface.assertDoesNotExist()
        compose.onNodeWithTag("player_top_bar").assertIsDisplayed()
        compose.onNodeWithTag("player_toggle").performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { compose.runOnIdle { !player.playWhenReady } }
    }

    @Test fun rotatingToPortraitClearsLockAndKeepsPortraitGestures() {
        rotate(landscape = true)
        compose.onNodeWithTag("player_touch_lock").performClick()
        unlock.assertIsDisplayed()
        rotate(landscape = false)
        surface.assertDoesNotExist()
        unlock.assertDoesNotExist()
        compose.onNodeWithTag("player_touch_lock").assertDoesNotExist()
        compose.onNodeWithTag("player_top_bar").assertIsDisplayed()
        compose.onNodeWithTag("player_toggle").performTouchInput { doubleClick(center) }
        compose.waitUntil(5_000) { compose.runOnIdle { !player.playWhenReady } }
        rotate(landscape = true)
        compose.onNodeWithTag("player_touch_lock").assertIsDisplayed()
    }

    private fun rotate(landscape: Boolean) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == expected }
        compose.waitForIdle()
    }

    private fun assertOnlyUnlockIsOffered() {
        unlock.assertIsDisplayed()
        for (tag in listOf("player_top_bar", "player_seek", "player_speed", "player_next", "player_touch_lock")) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    private fun awaitHiddenUnlock() {
        compose.mainClock.advanceTimeBy(6_100)
        compose.waitUntil(8_000) { !unlock.isDisplayed() }
        surface.assertIsDisplayed()
    }

    // Seekable mono 8 kHz PCM lets the test observe real player commands without a server or video decoder.
    private fun writeSilentWave(file: File) {
        val dataSize = 8_000 * 2 * 90
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8_000); putInt(16_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }
        RandomAccessFile(file, "rw").use {
            it.write(header.array())
            it.setLength(44L + dataSize)
        }
    }
}
