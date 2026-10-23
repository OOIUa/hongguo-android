package com.hongguo.android

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.hongguo.api.core.Sm3
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real upstream -> Android processing -> rendered video, without a local server. */
@RunWith(AndroidJUnit4::class)
class StandaloneFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun searchSelectPrepareAndRenderOnDevice() {
        assertTrue("SM3 must pass on Android", Sm3.selfTest())
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        compose.runOnIdle { model.clearFavorites(); model.clearHistory() }
        compose.onNodeWithTag("nav_search").performClick()
        compose.onNodeWithTag("search_input").performTextClearance()
        compose.onNodeWithTag("search_input").performTextInput("总裁")
        compose.onNodeWithTag("search_button").performClick()
        compose.waitUntil(120_000) { !model.state.value.searching }
        assertNull("Search error: ${model.state.value.searchError}", model.state.value.searchError)
        assertTrue("Search returned no items", model.state.value.results.isNotEmpty())
        compose.onNodeWithTag("result_0").performClick()
        compose.waitUntil(120_000) { !model.state.value.loadingDetail }
        assertNull("Detail error: ${model.state.value.detailError}", model.state.value.detailError)
        assertTrue("No episodes", model.state.value.detail?.episodes?.isNotEmpty() == true)
        val selectedId = model.state.value.detail!!.id
        compose.onNodeWithTag("favorite_toggle").performClick()
        compose.waitUntil(5_000) { model.state.value.favorites.any { it.card.id == selectedId } }
        assertTrue("Opening details must not create history", model.state.value.history.isEmpty())
        compose.onNodeWithTag("episode_0").performScrollTo().performClick()
        compose.waitUntil(240_000) { !model.state.value.preparing }
        assertNull("Preparation error: ${model.state.value.playbackError}", model.state.value.playbackError)
        assertTrue("No prepared local file", File(model.state.value.video?.path.orEmpty()).length() > 1024)
        compose.waitUntil(60_000) { model.state.value.firstFrameRendered || model.state.value.playbackError != null }
        assertNull("Playback error: ${model.state.value.playbackError}", model.state.value.playbackError)
        assertTrue("A video frame must actually render", model.state.value.firstFrameRendered)
        compose.waitUntil(5_000) { model.state.value.history.any { it.card.id == selectedId } }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) {
            var rendered = false
            compose.runOnIdle {
                val player = findPlayer(compose.activity.window.decorView)
                rendered = player?.isPlaying == true && (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
            }
            rendered
        }
        if (model.state.value.detail!!.episodes.size > 1) {
            compose.onNodeWithTag("episode_1").performScrollTo().performClick()
            compose.waitUntil(240_000) { !model.state.value.preparing }
            assertEquals("Selecting another episode must change selection", 1, model.state.value.episodePosition)
            assertNull(model.state.value.playbackError)
            compose.waitUntil(60_000) { model.state.value.firstFrameRendered || model.state.value.playbackError != null }
            assertTrue("Selected second episode must render", model.state.value.firstFrameRendered)
        }
        compose.waitUntil(30_000) { model.state.value.playbackStatus == "正在播放" }
        var seekTarget = 0L
        compose.runOnIdle {
            val player = findPlayer(compose.activity.window.decorView)!!
            assertTrue("Prepared media must have a duration", player.duration > 1_000)
            seekTarget = minOf(5_000L, player.duration / 3)
            player.seekTo(seekTarget)
        }
        compose.waitUntil(20_000) {
            var resumed = false
            compose.runOnIdle {
                val player = findPlayer(compose.activity.window.decorView)
                resumed = player?.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition >= seekTarget
            }
            resumed
        }
        compose.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "qa-playback.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "hongguo-playback-qa.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HongguoQA")
        }
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        // Reopening a prepared episode must use a completed local artifact.
        compose.runOnIdle { model.playEpisode(0, restart = true) }
        compose.waitUntil(30_000) { !model.state.value.preparing }
        assertNull(model.state.value.playbackError)
        assertTrue(File(model.state.value.video?.path.orEmpty()).isFile)
        compose.waitUntil(30_000) { model.state.value.firstFrameRendered || model.state.value.playbackError != null }
        assertTrue("Cached retry must render a new frame", model.state.value.firstFrameRendered)
        compose.runOnIdle { findPlayer(compose.activity.window.decorView)!!.seekTo(4_000) }
        compose.waitUntil(15_000) { model.state.value.history.firstOrNull { it.card.id == selectedId }?.positionMs?.let { it >= 4_000 } == true }
        compose.runOnIdle { model.backToSearch(); model.selectTab(AppTab.HISTORY) }
        assertTrue("History must contain an actual in-episode position", model.state.value.history.first { it.card.id == selectedId }.positionMs >= 4_000)
        compose.onNodeWithTag("history_item_0").assertExists().performClick()
        compose.waitUntil(120_000) { !model.state.value.loadingDetail && !model.state.value.preparing && model.state.value.video != null }
        compose.waitUntil(30_000) { model.state.value.firstFrameRendered || model.state.value.playbackError != null }
        assertTrue("History continue must render", model.state.value.firstFrameRendered)
        assertEquals(selectedId, model.state.value.detail!!.id)
        compose.runOnIdle { assertTrue("History must resume after the saved point", findPlayer(compose.activity.window.decorView)!!.currentPosition >= 4_000) }
        compose.runOnIdle { model.backToSearch(); model.selectTab(AppTab.FAVORITES) }
        compose.onNodeWithTag("favorite_item_0").assertExists()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun findPlayer(view: View): ExoPlayer? {
        if (view is PlayerView) return view.player as? ExoPlayer
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
