package com.hongguo.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose pointer dispatch, pager arbitration and lifecycle cleanup, without a media URL. */
@RunWith(AndroidJUnit4::class)
class PlayerHoldSpeedGestureTest {
    @get:Rule val compose = createComposeRule()
    private val longPressMillis get() = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    private val holdMillis get() = longPressMillis + 150L

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun owner() = compose.runOnUiThread { Owner().apply { lifecycle.currentState = Lifecycle.State.RESUMED } }

    private fun beginHold(tag: String = "gesture_layer", left: Boolean = true) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(Offset(width * if (left) .25f else .75f, center.y))
        }
        compose.mainClock.advanceTimeBy(holdMillis)
    }

    @Test fun leftHoldStartsOnlyAfterTimeoutAndReleaseDoesNotAlsoTap() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("gesture_layer").performTouchInput { down(Offset(width * .25f, center.y)) }
        compose.mainClock.advanceTimeBy((longPressMillis / 3).coerceAtLeast(1))
        compose.runOnIdle { assertTrue(changes.isEmpty()) }
        compose.mainClock.advanceTimeBy(holdMillis)
        compose.runOnIdle { assertEquals(listOf(true), changes) }
        compose.onNodeWithTag("gesture_layer").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(true, false), changes)
            assertEquals(0, taps)
        }
    }

    @Test fun rightHoldAlsoPlaysAtDoubleSpeedWithoutATap() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        compose.setContent { PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add) }
        compose.mainClock.autoAdvance = false
        beginHold(left = false)
        compose.runOnIdle { assertEquals(listOf(true), changes) }
        compose.onNodeWithTag("gesture_layer").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(true, false), changes)
            assertEquals(0, taps)
        }
    }

    /** Long press until 2×, then move sideways: the 2× ends and the drag moves the progress (issue #3). */
    @Test fun dragAfterLongPressSeeksWithoutTappingOrPaging() {
        val phases = mutableListOf<Pair<SeekDragPhase, Float>>()
        var taps = 0
        val holds = mutableListOf<Boolean>()
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                pager = rememberPagerState(pageCount = { 3 })
                VerticalPager(pager, Modifier.fillMaxSize()) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = holds::add,
                        onSeekDrag = { phase, fraction -> phases += phase to fraction; true })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("gesture_layer").performTouchInput { down(Offset(width * .2f, center.y)) }
        compose.mainClock.advanceTimeBy(holdMillis)
        compose.runOnIdle { assertEquals(listOf(true), holds) }
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            for (step in 1..10) moveTo(Offset(width * (.2f + .05f * step), center.y))
            up()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(SeekDragPhase.START, phases.first().first)
            assertEquals(SeekDragPhase.END, phases.last().first)
            assertTrue(phases.last().second in .35f..0.55f)
            assertEquals("the seek ends 2× as it starts", listOf(true, false), holds.distinct())
            assertFalse(holds.last())
            assertEquals(0, taps)
            assertEquals(0, pager.currentPage)
        }
    }

    /** Without the long press, a sideways swipe neither seeks nor taps, and an upward swipe still changes the page. */
    @Test fun swipeWithoutLongPressNeverSeeks() {
        val phases = mutableListOf<SeekDragPhase>()
        var taps = 0
        val holds = mutableListOf<Boolean>()
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                pager = rememberPagerState(pageCount = { 3 })
                VerticalPager(pager, Modifier.fillMaxSize()) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = holds::add,
                        onSeekDrag = { phase, _ -> phases += phase; true })
                }
            }
        }
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            swipe(Offset(width * .2f, center.y), Offset(width * .7f, center.y), 400)
        }
        compose.runOnIdle {
            assertTrue(phases.isEmpty())
            assertEquals(0, taps)
            assertFalse(holds.any { it })
            assertEquals(0, pager.currentPage)
        }
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            swipe(Offset(center.x, height * .8f), Offset(center.x, height * .2f), 300)
        }
        compose.runOnIdle {
            assertTrue("an upward swipe still changes the episode", phases.isEmpty())
            assertEquals(1, pager.currentPage)
        }
    }

    /**
     * A thumb swipe often starts with a short sideways arc before going up. That opening must not cost the pager the
     * swipe: the page still changes, and the gesture neither taps, holds nor seeks.
     */
    @Test fun swipeThatStartsSidewaysStillChangesThePage() {
        val phases = mutableListOf<SeekDragPhase>()
        var taps = 0
        val holds = mutableListOf<Boolean>()
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                pager = rememberPagerState(pageCount = { 3 })
                VerticalPager(pager, Modifier.fillMaxSize()) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = holds::add,
                        onSeekDrag = { phase, _ -> phases += phase; true })
                }
            }
        }
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            val slop = viewConfiguration.touchSlop
            val start = Offset(width * .4f, height * .8f)
            down(start)
            // Sideways first: well past the touch slop with almost no vertical movement.
            for (step in 1..4) moveTo(start + Offset(slop * .8f * step, -slop * .1f * step))
            // Then a long upward swipe.
            for (step in 1..12) moveTo(start + Offset(slop * 3.5f, -height * .05f * step))
            up()
        }
        compose.runOnIdle {
            assertEquals("the swipe changes the episode", 1, pager.currentPage)
            assertTrue(phases.isEmpty())
            assertEquals(0, taps)
            assertFalse(holds.any { it })
        }
    }

    @Test fun refusedDragNeitherSeeksNorTaps() {
        val phases = mutableListOf<SeekDragPhase>()
        var taps = 0
        compose.setContent {
            PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = {},
                onSeekDrag = { phase, _ -> phases += phase; false })
        }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            for (step in 1..10) moveTo(Offset(width * (.25f + .05f * step), center.y))
            up()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(listOf(SeekDragPhase.START), phases)
            assertEquals(0, taps)
        }
    }

    @Test fun ordinaryTouchAndAccessibilityClicksStillTogglePlayback() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        compose.setContent { PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add) }
        compose.onNodeWithTag("gesture_layer").performTouchInput {
            click(Offset(width * .25f, center.y))
            click(Offset(width * .75f, center.y))
        }
        compose.onNodeWithTag("gesture_layer").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle {
            assertEquals(3, taps)
            assertFalse(changes.any { it })
        }
    }

    @Test fun touchCancellationReleasesHoldAndNextGestureStillWorks() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        compose.setContent { PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add) }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.runOnIdle { assertEquals(listOf(true), changes) }
        compose.onNodeWithTag("gesture_layer").performTouchInput { cancel() }
        compose.onNodeWithTag("gesture_layer").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(listOf(true, false), changes)
            assertEquals(1, taps)
        }
    }

    @Test fun disablingTheLayerReleasesCurrentHoldAndBlocksFurtherClicks() {
        val changes = mutableListOf<Boolean>()
        var enabled by mutableStateOf(true)
        var taps = 0
        compose.setContent { PlayerInteractionLayer(Modifier.testTag("gesture_layer"), enabled, { taps++ }, changes::add) }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.runOnIdle { assertEquals(listOf(true), changes); enabled = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("gesture_layer").assertIsNotEnabled().performTouchInput { up(); click(center) }
        compose.runOnIdle {
            assertEquals(listOf(true, false), changes)
            assertEquals(0, taps)
        }
    }

    @Test fun removingTheLayerCannotLeaveTemporarySpeedEnabled() {
        val changes = mutableListOf<Boolean>()
        var visible by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("fixture")) {
                if (visible) PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = {}, onHoldSpeedChange = changes::add)
            }
        }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.runOnIdle { assertEquals(listOf(true), changes); visible = false }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("fixture").performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test fun leavingForegroundReleasesHoldAndResumeRequiresANewPress() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        val owner = owner()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add)
            }
        }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.runOnIdle {
            assertEquals(listOf(true), changes)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf(true, false), changes); owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(holdMillis)
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
        compose.onNodeWithTag("gesture_layer").performTouchInput { up() }
        beginHold()
        compose.onNodeWithTag("gesture_layer").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(listOf(true, false, true, false), changes)
            assertEquals(0, taps)
        }
    }

    @Test fun callbacksCanChangeDuringAnActiveHoldWithoutRestartingIt() {
        val changes = mutableListOf<Pair<Int, Boolean>>()
        val taps = mutableListOf<Int>()
        var generation by mutableIntStateOf(1)
        compose.setContent {
            val capturedGeneration = generation
            PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps += capturedGeneration },
                onHoldSpeedChange = { changes += capturedGeneration to it })
        }
        compose.mainClock.autoAdvance = false
        beginHold()
        compose.runOnIdle { assertEquals(listOf(1 to true), changes); generation = 2 }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf(1 to true), changes) }
        compose.onNodeWithTag("gesture_layer").performTouchInput { up(); click(center) }
        compose.runOnIdle {
            assertEquals(listOf(1 to true, 2 to false), changes)
            assertEquals(listOf(2), taps)
        }
    }

    @Test fun verticalSwipeBeforeLongPressTimeoutStillChangesPagerPage() {
        val changes = mutableListOf<Boolean>()
        var taps = 0
        lateinit var pager: PagerState
        compose.setContent {
            pager = rememberPagerState { 2 }
            Box(Modifier.fillMaxSize().padding(32.dp)) {
                VerticalPager(pager, Modifier.fillMaxSize().testTag("gesture_pager")) { page ->
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer_$page"), onTap = { taps++ }, onHoldSpeedChange = changes::add)
                }
            }
        }
        compose.onNodeWithTag("gesture_pager").performTouchInput {
            swipe(Offset(width * .25f, height * .8f), Offset(width * .25f, height * .2f), durationMillis = 200)
        }
        compose.runOnIdle {
            assertEquals(1, pager.currentPage)
            assertEquals(0, taps)
            assertFalse(changes.any { it })
        }
    }

    @Test fun overlaidButtonReceivesItsOwnTapWithoutVideoOrHoldCallbacks() {
        var videoTaps = 0
        var buttonTaps = 0
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { videoTaps++ }, onHoldSpeedChange = changes::add)
                    Button(onClick = { buttonTaps++ }, modifier = Modifier.align(Alignment.TopStart).size(120.dp, 64.dp).testTag("overlay_button")) {
                        Text("顶部按钮")
                    }
                }
            }
        }
        compose.onNodeWithTag("overlay_button").performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, buttonTaps)
            assertEquals(0, videoTaps)
            assertTrue(changes.isEmpty())
        }
    }

    @Test fun overlaidSeekBarReceivesDragWithoutTappingOrHoldingTheVideo() {
        var value by mutableFloatStateOf(.2f)
        var finished = 0
        var taps = 0
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(32.dp)) {
                    PlayerInteractionLayer(Modifier.testTag("gesture_layer"), onTap = { taps++ }, onHoldSpeedChange = changes::add)
                    PlayerSeekBar(value, .9f, { value = it }, { finished++ }, true,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).testTag("overlay_seek"))
                }
            }
        }
        compose.onNodeWithTag("overlay_seek").performTouchInput {
            swipe(Offset(width * .2f, center.y), Offset(width * .8f, center.y), durationMillis = 200)
        }
        compose.runOnIdle {
            assertTrue(value > .7f)
            assertEquals(1, finished)
            assertEquals(0, taps)
            assertTrue(changes.isEmpty())
        }
    }
}

/** A pinch decides once per gesture and takes the touch from the pager and the tap below it. */
@RunWith(AndroidJUnit4::class)
class PlayerPinchGestureTest {
    @get:Rule val compose = createComposeRule()

    @Test fun spreadThenSqueezeReportOnceEachWithoutTappingOrPaging() {
        val decided = mutableListOf<Boolean>()
        var taps = 0
        lateinit var pager: PagerState
        compose.setContent {
            MaterialTheme {
                pager = rememberPagerState(pageCount = { 3 })
                Box(Modifier.fillMaxSize().testTag("pinch_root").playerPinch(true, decided::add)) {
                    VerticalPager(pager, Modifier.fillMaxSize()) {
                        PlayerInteractionLayer(onTap = { taps++ }, onHoldSpeedChange = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("pinch_root").performTouchInput {
            pinch(center - Offset(30f, 60f), center - Offset(150f, 300f), center + Offset(30f, 60f), center + Offset(150f, 300f), 300)
        }
        compose.runOnIdle { assertEquals(listOf(true), decided) }
        compose.onNodeWithTag("pinch_root").performTouchInput {
            pinch(center - Offset(150f, 300f), center - Offset(30f, 60f), center + Offset(150f, 300f), center + Offset(30f, 60f), 300)
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), decided)
            assertEquals(0, taps)
            assertEquals(0, pager.currentPage)
        }
    }
}
