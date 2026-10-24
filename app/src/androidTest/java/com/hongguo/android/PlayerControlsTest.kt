package com.hongguo.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local controls need no network source or running decoder to verify touch and accessibility. */
@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun touchingTrackStartCanReachBeginning() = assertTouchReachesEdge(end = false)

    @Test fun touchingTrackEndCanReachEnd() = assertTouchReachesEdge(end = true)

    private fun assertTouchReachesEdge(end: Boolean) {
        var value by mutableFloatStateOf(.5f)
        var finished = 0
        var layoutBounds = Rect.Zero
        compose.setContent {
            ControlsFixture {
                PlayerSeekBar(value, .8f, { value = it }, { finished++ }, true,
                    Modifier.testTag("seek_control").onGloballyPositioned { layoutBounds = it.boundsInRoot() })
            }
        }

        // Material3 expands slider semantics horizontally beyond the pointer input layout.
        // Convert the drawn thumb's endpoint from actual layout bounds to test-node coordinates.
        val semanticsBounds = compose.onNodeWithTag("seek_control").fetchSemanticsNode().boundsInRoot
        val halfThumb = with(compose.density) { 8.dp.toPx() }
        val endpoint = Offset(
            (if (end) layoutBounds.right - halfThumb else layoutBounds.left + halfThumb) - semanticsBounds.left,
            layoutBounds.center.y - semanticsBounds.top,
        )
        compose.onNodeWithTag("seek_control").performTouchInput {
            click(endpoint)
        }
        compose.runOnIdle {
            assertEquals("The ${if (end) "end" else "beginning"} must be reachable by touch", if (end) 1f else 0f, value, .001f)
            assertEquals(1, finished)
        }
    }

    @Test fun accessibilityCanSetAndFinishProgress() {
        var value by mutableFloatStateOf(.1f)
        var finished = 0
        compose.setContent {
            ControlsFixture {
                PlayerSeekBar(value, .8f, { value = it }, { finished++ }, true,
                    Modifier.testTag("seek_control"))
            }
        }

        compose.onNodeWithTag("seek_control").performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("The accessibility progress action must accept a new position", setProgress(.75f))
        }
        compose.runOnIdle {
            assertEquals(.75f, value, .001f)
            assertEquals("Accessibility seeking must commit the target", 1, finished)
        }
    }

    @Test fun disabledControlDoesNotChangeOrFinishProgress() {
        var changes = 0
        var finished = 0
        compose.setContent {
            ControlsFixture {
                PlayerSeekBar(.4f, .8f, { changes++ }, { finished++ }, false,
                    Modifier.testTag("seek_control"))
            }
        }

        compose.onNodeWithTag("seek_control").assertIsNotEnabled().performTouchInput {
            click(Offset(width - 1f, center.y))
            swipe(start = Offset(width * .2f, center.y), end = Offset(width * .8f, center.y), durationMillis = 500)
        }
        compose.runOnIdle {
            assertEquals("Disabled progress must ignore touch", 0, changes)
            assertEquals("Disabled progress must not commit a seek", 0, finished)
        }
    }

    @Test fun canceledDragAllowsTheNextDragToFinish() {
        var value by mutableFloatStateOf(.2f)
        var finished = 0
        compose.setContent {
            ControlsFixture {
                PlayerSeekBar(value, .8f, { value = it }, { finished++ }, true,
                    Modifier.testTag("seek_control"))
            }
        }

        compose.onNodeWithTag("seek_control").performTouchInput {
            down(Offset(width * .2f, center.y))
            moveTo(Offset(width * .5f, center.y), delayMillis = 100)
            cancel()
        }
        var finishedAfterCancel = 0
        compose.runOnIdle {
            assertEquals("Canceling a drag must finish the interaction so the pager can be used again", 1, finished)
            finishedAfterCancel = finished
        }
        compose.onNodeWithTag("seek_control").performTouchInput {
            swipe(start = Offset(width * .5f, center.y), end = Offset(width * .8f, center.y), durationMillis = 500)
        }
        compose.runOnIdle {
            assertTrue("A subsequent drag must still update progress", value in 0.7f..0.9f)
            assertEquals("A subsequent drag must still commit once", finishedAfterCancel + 1, finished)
        }
    }

    @Composable
    private fun ControlsFixture(content: @Composable () -> Unit) {
        MaterialTheme {
            // The test activity draws edge to edge on current Android. Keep pointer tests clear
            // of system bars, screen edges and their gesture exclusion areas.
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}
