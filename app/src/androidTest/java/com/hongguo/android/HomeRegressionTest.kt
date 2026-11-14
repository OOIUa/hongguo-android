package com.hongguo.android

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun warmBannerDoesNotRepeatInThePosterGrid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = ViewModelStore()
        lateinit var model: HongguoViewModel
        instrumentation.runOnMainSync {
            model = HongguoViewModel(context.applicationContext as Application)
            store.put("home-regression", model)
        }
        try {
            val cards = (1..8).map { DramaCard("730000000000000000$it", "首页回归第${it}部", "", 10, "") }
            val state = HongguoUiState(home = FeedState(items = cards, loaded = true))
            compose.setContent { HongguoTheme(applyToWindow = false) { HomeScreen(state, model) } }
            compose.onNodeWithTag("home_featured").assertTextContains(cards[0].title)
            compose.onNodeWithTag("home_result_0").assertTextContains(cards[1].title)
            compose.onAllNodesWithText(cards[0].title).assertCountEquals(1)
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.getExternalFilesDir(null)?.resolve("home-regression-warm.png")?.outputStream()?.use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun damagedAndroidPreferencesRemainUntouchedUntilRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "library-regression-" + UUID.randomUUID()
        val preferences = context.getSharedPreferences(name, 0)
        try {
            assertTrue(preferences.edit().putString("snapshot", "{damaged").commit())
            val store = LibraryStore(preferences)
            store.toggleFavorite(DramaCard("1", "回归", "", 10, ""))
            store.clearFavorites(); store.clearHistory()
            assertNotNull(store.state.value.storageError)
            assertEquals("{damaged", preferences.getString("snapshot", null))
            assertTrue(preferences.edit().putString("snapshot", LibraryCodec.encode(LibrarySnapshot())).commit())
            store.reload()
            store.toggleFavorite(DramaCard("1", "回归", "", 10, ""))
            assertTrue(preferences.edit().commit())
            assertEquals("1", LibraryStore(preferences).state.value.favorites.single().card.id)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun anonymousComicContinuationHasNoOverlap() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val first = com.hongguo.api.service.Client.rankPage("recommend", 18, 0)
        val next = com.hongguo.api.service.Client.rankPage("recommend", 18, first.nextOffset)
        fun ids(rows: List<Any>) = rows.map { com.hongguo.api.util.Json.optStr(com.hongguo.api.util.Json.optObj(it)["series_id"], "") }.toSet()
        assertEquals(18, first.items.size)
        assertEquals(18, next.items.size)
        assertTrue(ids(first.items).intersect(ids(next.items)).isEmpty())
        assertTrue(next.nextOffset > first.nextOffset)
    }

    @Test fun recommendationDiskCacheSurvivesPagerRecreation() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.cacheDir.resolve("recommendation-regression-" + UUID.randomUUID())
        fun card(id: Int) = com.hongguo.account.protocol.GuessCard(com.hongguo.account.protocol.Series("73000000000000000$id", "回归$id"), 10)
        try {
            val first = GuessFeedPager(null, { _, cursor -> cursor.offset++; com.hongguo.account.protocol.GuessBatch((1..6).map(::card), "") },
                history = RecommendationHistory(directory))
            assertEquals(6, first.next().items.size)
            val recreated = GuessFeedPager(null, { _, cursor -> cursor.offset++; com.hongguo.account.protocol.GuessBatch((1..12).map(::card), "") },
                history = RecommendationHistory(directory))
            assertEquals((7..12).map { card(it).series.id }, recreated.next().items.map { it.id })
        } finally { directory.deleteRecursively() }
    }
}
