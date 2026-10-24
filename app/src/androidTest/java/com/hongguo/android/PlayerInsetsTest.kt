package com.hongguo.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real window insets and local chrome only: no metadata, media, account or network request. */
@RunWith(AndroidJUnit4::class)
class PlayerInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitTopControlsStaySafeWhenStatusBarsHideAndReappear() = assertStableSystemInsets(
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT,
    )

    @Test fun landscapeTopControlsStaySafeWhenStatusBarsHideAndReappear() = assertStableSystemInsets(
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE,
    )

    private fun assertStableSystemInsets(requestedOrientation: Int, expectedOrientation: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requestedOrientation }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == expectedOrientation
        }
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        var backClicks = 0
        var episodeClicks = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_insets_fixture")) {
                    PlayerTopBar(3, 86, { backClicks++ }, { episodeClicks++ })
                }
            }
        }
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
        try {
            compose.runOnIdle {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.show(statusBars)
            }
            awaitStatusBars(visible = true)
            val shown = controlBounds()
            assertWithinPlatformSafeArea()

            compose.runOnIdle { controller.hide(statusBars) }
            awaitStatusBars(visible = false)
            assertWithinPlatformSafeArea()
            assertSameBounds(shown, controlBounds(), "Hiding status bars")

            compose.runOnIdle { controller.show(statusBars) }
            awaitStatusBars(visible = true)
            assertWithinPlatformSafeArea()
            assertSameBounds(shown, controlBounds(), "Showing status bars again")
            compose.onNodeWithTag("player_back").performClick()
            compose.onNodeWithTag("player_episode_picker").performClick()
            compose.runOnIdle {
                assertEquals("The safe-area back action must remain usable", 1, backClicks)
                assertEquals("The only episode picker must remain usable", 1, episodeClicks)
            }
        } finally {
            compose.runOnIdle { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    @Test fun cutoutProtectsTopAndBothSidesWithoutAddingBottomInsetToTheHeader() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
        // Deliberately asymmetric pixel fixtures catch left/right and max-versus-add mistakes.
        // Their dimensions are independent of the device's real status-bar height.
        var cutout by mutableStateOf(WindowInsets(63, 89, 27, 0))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("player_insets_fixture")) {
                    PlayerTopBar(3, 86, {}, {}, statusBarInsets = WindowInsets(17, 41, 9, 0), cutoutInsets = cutout)
                }
            }
        }
        val root = bounds("player_insets_fixture")
        val initial = controlBounds()
        initial.forEach { (tag, rect) ->
            assertTrue("$tag must clear the top cutout", rect.top >= root.top + 89)
            assertTrue("$tag must clear the left cutout", rect.left >= root.left + 63)
            assertTrue("$tag must clear the right cutout", rect.right <= root.right - 27)
        }
        val initialHeader = bounds("player_top_bar")
        compose.runOnIdle { cutout = WindowInsets(63, 89, 27, 301) }
        compose.waitForIdle()
        assertSameBounds(initial, controlBounds(), "A bottom cutout")
        assertEquals("A bottom inset must not enlarge this top-only header", initialHeader.height,
            bounds("player_top_bar").height, 1f)

        // Removing the notch must move controls by the excess cutout only, not add both insets.
        compose.runOnIdle { cutout = WindowInsets(0, 0, 0, 0) }
        compose.waitForIdle()
        val withoutCutout = controlBounds()
        initial.keys.forEach { tag ->
            assertEquals("$tag must use the larger top safe area, without double padding", 48f,
                initial.getValue(tag).top - withoutCutout.getValue(tag).top, 1f)
        }
        assertEquals("The back action must clear the larger left safe area", 46f,
            initial.getValue("player_back").left - withoutCutout.getValue("player_back").left, 1f)
        assertEquals("The episode action must clear the larger right safe area", 18f,
            withoutCutout.getValue("player_episode_picker").right - initial.getValue("player_episode_picker").right, 1f)
    }

    private fun awaitStatusBars(visible: Boolean) {
        compose.waitUntil(5_000) {
            var matched = false
            compose.runOnIdle {
                matched = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.statusBars()) == visible
            }
            matched
        }
        compose.waitForIdle()
    }

    private fun assertWithinPlatformSafeArea() {
        val safe = compose.runOnIdle {
            requireNotNull(ViewCompat.getRootWindowInsets(compose.activity.window.decorView))
                .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())
        }
        val root = bounds("player_insets_fixture")
        controlBounds().forEach { (tag, rect) ->
            assertTrue("$tag must remain below the platform's safe top edge", rect.top >= root.top + safe.top)
            assertTrue("$tag must clear the platform's safe left edge", rect.left >= root.left + safe.left)
            assertTrue("$tag must clear the platform's safe right edge", rect.right <= root.right - safe.right)
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun controlBounds(): Map<String, Rect> = listOf("player_back", "player_episode_label", "player_episode_picker")
        .associateWith(::bounds)

    private fun assertSameBounds(expected: Map<String, Rect>, actual: Map<String, Rect>, operation: String) {
        expected.forEach { (tag, before) ->
            val after = actual.getValue(tag)
            assertEquals("$operation must not move $tag horizontally", before.left, after.left, 1f)
            assertEquals("$operation must not move $tag vertically", before.top, after.top, 1f)
            assertEquals("$operation must not resize $tag", before.width, after.width, 1f)
        }
    }
}
