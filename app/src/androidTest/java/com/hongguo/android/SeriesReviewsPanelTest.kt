// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.android.comments.SeriesReview
import com.hongguo.android.comments.SeriesReviewPage
import java.io.IOException
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 剧评 panel: the last loaded review reaching the bottom loads the next batch; empty/error states. */
@RunWith(AndroidJUnit4::class)
class SeriesReviewsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun review(index: Int) = SeriesReview(
        id = "r$index",
        author = "用户$index",
        text = "这是第 $index 条剧评",
        createdAt = 1_795_461_371L + index,
        likes = index.toLong(),
        score = 10.0,
    )

    private fun page(start: Int, count: Int, hasMore: Boolean, next: Int) = SeriesReviewPage(
        items = (start until start + count).map { review(it) },
        total = 24,
        averageScore = 8.6,
        bookName = "测试剧",
        nextOffset = next,
        hasMore = hasMore,
    )

    @Test
    fun scrollingToTheTwentiethReviewLoadsTheNextBatch() {
        val requested = Collections.synchronizedList(mutableListOf<Int>())
        val loader: suspend (Int) -> SeriesReviewPage = { offset ->
            requested += offset
            if (offset == 0) page(1, 20, hasMore = true, next = 20)
            else page(21, 4, hasMore = false, next = 24)
        }
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SeriesReviewsContent(
                        seriesTitle = "测试剧",
                        loadPage = loader,
                        modifier = Modifier.fillMaxWidth(),
                        maxListHeight = 420.dp,
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("review_r1").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reviews_list").performScrollToIndex(20)
        compose.waitUntil(10_000) { requested.contains(20) }
        compose.waitForIdle()
        compose.onNodeWithTag("reviews_list").performScrollToIndex(24)
        compose.onNodeWithText("没有更多剧评了").assertExists()
        assertEquals(listOf(0, 20), requested.toList())
    }

    @Test
    fun emptyAndErrorStatesOfferRetry() {
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val loader: suspend (Int) -> SeriesReviewPage = {
            if (fail.get()) throw IOException("offline")
            SeriesReviewPage(emptyList(), 0, 0.0, "", 0, false)
        }
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SeriesReviewsContent(
                        seriesTitle = "测试剧",
                        loadPage = loader,
                        modifier = Modifier.fillMaxWidth(),
                        maxListHeight = 420.dp,
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("reviews_retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("reviews_retry").performClick()
        fail.set(false)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("reviews_retry").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("这部剧还没有剧评").assertExists()
    }

    @Test
    fun statsRowShowsCountersAndOpensReviews() {
        var opened = false
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                SeriesReviewStats(
                    detail = DramaDetail(
                        id = "7710164882192075801",
                        title = "测试剧",
                        cover = "",
                        summary = "",
                        episodes = emptyList(),
                        hotScore = 84_802_243,
                        favorites = 587_483,
                    ),
                    reviewCount = 42,
                    onReviews = { opened = true },
                    landscape = false,
                )
            }
        }
        compose.onNodeWithText("8480 万热度").assertExists()
        compose.onNodeWithText("59 万收藏").assertExists()
        compose.onNodeWithText("42 条剧评").assertExists()
        compose.onNodeWithTag("detail_reviews").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun landscapeUsesTheRightSidePanelAndTheDimmedPageClosesIt() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        var open by mutableStateOf(true)
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    SeriesReviewsOverlay(
                        seriesId = "7710164882192075801",
                        seriesTitle = "测试剧",
                        visible = open,
                        onDismiss = { open = false },
                        pageLoader = { offset ->
                            if (offset == 0) page(1, 20, hasMore = true, next = 20)
                            else page(21, 4, hasMore = false, next = 24)
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("detail_reviews_panel").assertExists()
        compose.onNodeWithTag("detail_reviews_panel_scrim").performTouchInput {
            click(Offset(width * .1f, height / 2f))
        }
        compose.waitUntil(5_000) { !open }
    }
}
