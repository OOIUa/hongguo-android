package com.hongguo.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Full-screen panels: 详情 slides in at the left edge, 选集 at the right; the dimmed picture closes them. */
@RunWith(AndroidJUnit4::class)
class PlayerSidePanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun detailPanelOpensFromTheLeftAndEpisodesFromTheRight() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        var info by mutableStateOf(true)
        var episodes by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("panel_fixture")) {
                    PlayerSidePanel(info, { info = false }, "info_panel", fromStart = true) { Text("剧集详情") }
                    PlayerSidePanel(episodes, { episodes = false }, "episode_panel") { Text("选集") }
                }
            }
        }
        compose.waitForIdle()
        val screen = compose.onNodeWithTag("panel_fixture").getUnclippedBoundsInRoot()
        val left = compose.onNodeWithTag("info_panel").getUnclippedBoundsInRoot()
        assertEquals("详情 must sit at the left edge", screen.left.value, left.left.value, 1f)
        assertTrue("详情 covers about half the width", left.width >= 340.dp && left.width < screen.width)

        // The dimmed picture to the right of the panel closes it.
        compose.onNodeWithTag("info_panel_scrim").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .9f, height / 2f))
        }
        compose.waitUntil(5_000) { !info }

        compose.runOnIdle { episodes = true }
        compose.waitForIdle()
        val right = compose.onNodeWithTag("episode_panel").getUnclippedBoundsInRoot()
        assertEquals("选集 must sit at the right edge", screen.right.value, right.right.value, 1f)
        compose.onNodeWithTag("episode_panel_scrim").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * .1f, height / 2f))
        }
        compose.waitUntil(5_000) { !episodes }
    }
}
