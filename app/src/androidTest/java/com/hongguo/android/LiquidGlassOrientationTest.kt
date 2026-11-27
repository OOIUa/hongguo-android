package com.hongguo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidGlassOrientationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsFollowTheRailAxisAndKeepTheirSelectionWhenTheLayoutChanges() {
        var vertical by mutableStateOf(true)
        var selected by mutableStateOf(AppTab.HOME)
        val tabs = navTabs(AppThemeStyle.WARM)
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().glassBackdropSource(backdrop).background(Color.Gray))
                    LiquidGlassNavigationBar(
                        selected = selected,
                        onSelect = { selected = it },
                        tabs = tabs,
                        style = AppThemeStyle.WARM,
                        backdrop = backdrop,
                        modifier = if (vertical) Modifier.size(62.dp, 248.dp) else Modifier,
                        vertical = vertical,
                    )
                }
            }
        }

        val nodes = listOf("nav_home", "nav_category", "nav_favorites", "nav_mine").map { compose.onNodeWithTag(it) }
        val rail = nodes.map { tab ->
            val node = tab.assertHasClickAction().fetchSemanticsNode()
            assertEquals(Role.Tab, node.config[SemanticsProperties.Role])
            node.boundsInRoot.center
        }
        rail.zipWithNext().forEach { (a, b) ->
            assertEquals(a.x, b.x, 1f)
            assertTrue(b.y > a.y)
        }
        nodes.first().assertIsSelected()
        nodes[1].performClick().assertIsSelected()
        nodes.first().assertIsNotSelected()
        swipeBetween(rail[1], rail.last())
        nodes.last().assertIsSelected()
        swipeBetween(rail.last(), rail.first())
        nodes.first().assertIsSelected()

        nodes[2].performClick().assertIsSelected()
        compose.runOnIdle { vertical = false }
        nodes[2].assertIsSelected()
        val bar = nodes.map { it.fetchSemanticsNode().boundsInRoot.center }
        bar.zipWithNext().forEach { (a, b) ->
            assertEquals(a.y, b.y, 1f)
            assertTrue(b.x > a.x)
        }
        swipeBetween(bar[2], bar.last())
        nodes.last().assertIsSelected()
    }

    private fun swipeBetween(from: Offset, to: Offset) {
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput { swipe(from - origin, to - origin, durationMillis = 480) }
    }
}
