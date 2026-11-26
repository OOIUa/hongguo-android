package com.hongguo.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live list sources and draft/applied filtering must agree with displayed content. */
@RunWith(AndroidJUnit4::class)
class DiscoveryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recommendationsAndCategoryFiltersUseLiveData() {
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        compose.onNodeWithTag("nav_home").performClick()
        compose.waitUntil(120_000) { model.state.value.home.loaded && !model.state.value.home.loading }
        assertNull(model.state.value.home.error)
        assertTrue("Home recommendations must contain live short dramas", model.state.value.home.items.isNotEmpty())
        compose.onNodeWithTag("home_result_0").assertExists()
        if (model.state.value.home.hasMore) {
            val previous = model.state.value.home
            compose.runOnIdle { model.loadMoreHome() }
            compose.waitUntil(120_000) { !model.state.value.home.loadingMore }
            assertNull(model.state.value.home.error)
            assertTrue(model.state.value.home.nextOffset > previous.nextOffset || !model.state.value.home.hasMore)
            assertEquals(model.state.value.home.items.size, model.state.value.home.items.distinctBy { it.id }.size)
        }
        compose.onNodeWithTag("home_feed_latest").performClick()
        compose.waitUntil(120_000) { model.state.value.home.loaded && !model.state.value.home.loading }
        assertNull(model.state.value.home.error)
        assertTrue("Latest short dramas must be real data", model.state.value.home.items.isNotEmpty())
        compose.onNodeWithTag("home_feed_comic").performClick()
        compose.waitUntil(120_000) { model.state.value.home.loaded && !model.state.value.home.loading }
        assertNull(model.state.value.home.error)
        assertTrue("Comic ranking must be real data", model.state.value.home.items.isNotEmpty())
        compose.onNodeWithTag("nav_category").performClick()
        compose.waitUntil(120_000) { model.state.value.category.loaded && !model.state.value.category.loading && !model.state.value.filtersLoading }
        assertNull(model.state.value.category.error)
        assertNull(model.state.value.filtersError)
        assertTrue("Live category filters must have a sort dimension", model.state.value.filterGroups.any { it.key == "sort" })
        assertTrue("Short dramas must expose real theme choices", model.state.value.filterGroups.any { it.key == "theme" && it.options.size > 1 })
        assertTrue(model.state.value.category.items.isNotEmpty())
        compose.onNodeWithTag("toggle_filters").performClick()
        // A draft edit must not silently relabel already displayed results.
        compose.runOnIdle { model.selectFilter("sort", "hot_score") }
        assertNotEquals(model.state.value.browseDraft, model.state.value.browseApplied)
        compose.onNodeWithTag("apply_filters").performClick()
        compose.waitUntil(120_000) { !model.state.value.category.loading }
        assertEquals(model.state.value.browseDraft, model.state.value.browseApplied)
        assertEquals("hot_score", model.state.value.browseApplied.options["sort"])
        assertNull(model.state.value.category.error)
        assertTrue(model.state.value.category.items.isNotEmpty())
        // Switching genre resets stale conditions and applies the matching filter schema.
        compose.onNodeWithTag("genre_comic_series").performClick()
        compose.waitUntil(120_000) { !model.state.value.category.loading && !model.state.value.filtersLoading }
        assertEquals("comic_series", model.state.value.browseApplied.genre)
        assertTrue(model.state.value.browseApplied.options.isEmpty())
        assertNull(model.state.value.category.error)
        assertTrue("Comic genre must be real data too", model.state.value.category.items.isNotEmpty())
        compose.onNodeWithTag("genre_short_play").performClick()
        compose.waitUntil(120_000) { !model.state.value.category.loading && !model.state.value.filtersLoading }
        assertEquals("short_play", model.state.value.browseApplied.genre)
        assertNull(model.state.value.category.error)
    }
}

@RunWith(AndroidJUnit4::class)
class HomeChannelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyPhoneThemeOffersLatestComicsAndRecommendations() {
        val theme = mutableStateOf(AppThemeStyle.WARM)
        val state = HongguoUiState()
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        compose.setContent {
            HongguoTheme(style = theme.value, applyToWindow = false) {
                when (theme.value) {
                    AppThemeStyle.WARM -> HomeScreen(state, model)
                    AppThemeStyle.IMMERSIVE -> ImmersiveHome(state, model)
                    AppThemeStyle.CARDS -> CardsHome(state, model)
                }
            }
        }
        for ((style, prefix) in listOf(AppThemeStyle.WARM to "home", AppThemeStyle.IMMERSIVE to "immersive", AppThemeStyle.CARDS to "cards")) {
            compose.runOnIdle { theme.value = style }
            compose.onNodeWithTag("${prefix}_feed_latest").assertExists()
            compose.onNodeWithTag("${prefix}_feed_comic").assertExists()
            compose.onNodeWithTag("${prefix}_feed_guess").assertIsSelected()
            compose.onNodeWithTag("${prefix}_feed_recommend").assertDoesNotExist()
        }
    }
}
