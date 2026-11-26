// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.comments

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesReviewPagesTest {
    private fun row(
        id: String = "1",
        series: String = "7710164882192075801",
        status: Int = 1,
        text: String = "好看",
        author: String = "观众甲",
        likes: Long = 3,
        score: String = "10",
        created: Long = 1_795_461_371,
    ) = JSONObject()
        .put("comment_id", id)
        .put("group_id", series)
        .put("status", status)
        .put("text", text)
        .put("create_timestamp", created)
        .put("digg_count", likes)
        .put("score", score)
        .put("user_info", JSONObject().put("user_name", author))

    private fun payload(
        rows: JSONArray,
        total: Long = 42,
        hasMore: Boolean = true,
        next: Int = 20,
        bookScore: String = "8.6",
        bookName: String = "满院亲戚全是上古大妖第八季",
    ) = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONObject()
                .put("comment_cnt", total)
                .put("score_cnt", total)
                .put("has_more", hasMore)
                .put("next_offset", next)
                .put("comment", rows)
                .put(
                    "book_info",
                    JSONObject().put("score", bookScore).put("book_name", bookName),
                ),
        )

    @Test
    fun parsesReviewsAndPagination() {
        val rows = JSONArray()
            .put(row(id = "a"))
            .put(row(id = "b", author = "", likes = 0, score = "0"))
        val page = SeriesReviewPages.parse(payload(rows), "7710164882192075801", 0)
        assertEquals(2, page.items.size)
        assertEquals(42, page.total)
        assertEquals(8.6, page.averageScore, 0.0001)
        assertEquals("满院亲戚全是上古大妖第八季", page.bookName)
        assertEquals(20, page.nextOffset)
        assertTrue(page.hasMore)
        assertEquals("观众甲", page.items[0].author)
        assertEquals(3, page.items[0].likes)
        assertEquals("红果用户", page.items[1].author)
        assertEquals(0.0, page.items[1].score, 0.0001)
    }

    @Test
    fun filtersRejectedMismatchedBlankAndDuplicateRows() {
        val rows = JSONArray()
            .put(row(id = "a"))
            .put(row(id = "a"))
            .put(row(id = "b", status = 0))
            .put(row(id = "c", series = "9999999999999999999"))
            .put(row(id = "d", text = "  "))
        val page = SeriesReviewPages.parse(payload(rows), "7710164882192075801", 0)
        assertEquals(listOf("a"), page.items.map { it.id })
    }

    @Test
    fun stopsWhenCursorDoesNotAdvance() {
        val page = SeriesReviewPages.parse(
            payload(JSONArray().put(row()), hasMore = true, next = 0),
            "7710164882192075801",
            0,
        )
        assertFalse(page.hasMore)
        assertEquals(0, page.nextOffset)
    }

    @Test
    fun emptyDataIsAnEmptyFinalPage() {
        val page = SeriesReviewPages.parse(
            JSONObject().put("code", 0).put("data", JSONObject.NULL),
            "7710164882192075801",
            20,
        )
        assertTrue(page.items.isEmpty())
        assertEquals(20, page.nextOffset)
        assertFalse(page.hasMore)
    }

    @Test
    fun acceptsRichTextPayloadsAndDropsImageOnlyRows() {
        val rich = JSONObject().put(
            "text",
            JSONObject().put("content", JSONArray().put(JSONObject().put("text", "第一段")).put(JSONObject().put("text", "第二段"))),
        )
        val rows = JSONArray()
            .put(row(id = "rich").put("text", rich.toString()))
            .put(row(id = "image").put("text", ""))
        val page = SeriesReviewPages.parse(payload(rows), "7710164882192075801", 0)
        assertEquals(listOf("rich"), page.items.map { it.id })
        assertEquals("第一段第二段", page.items.single().text)
    }

    @Test
    fun formatsRelativeTimesAndScores() {
        val now = 1_800_000_000L
        assertEquals("", SeriesReviewFormat.time(0, now))
        assertEquals("刚刚", SeriesReviewFormat.time(now - 5, now))
        assertEquals("3 分钟前", SeriesReviewFormat.time(now - 180, now))
        assertEquals("2 小时前", SeriesReviewFormat.time(now - 7_200, now))
        assertEquals("4 天前", SeriesReviewFormat.time(now - 4 * 86_400, now))
        assertTrue(SeriesReviewFormat.time(now - 40L * 86_400, now).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        assertEquals("8.6", SeriesReviewFormat.score(8.6))
        assertEquals("10", SeriesReviewFormat.score(10.0))
        assertEquals("", SeriesReviewFormat.score(0.0))
        assertEquals("", SeriesReviewFormat.score(Double.NaN))
        assertEquals("8480 万", SeriesReviewFormat.compactWan(84_802_243))
        assertEquals("59 万", SeriesReviewFormat.compactWan(587_483))
        assertEquals("1 亿", SeriesReviewFormat.compactWan(100_000_000))
        assertEquals("9999", SeriesReviewFormat.compactWan(9_999))
        assertEquals("", SeriesReviewFormat.compactWan(0))
        assertEquals("红果用户 5538", SeriesReviewFormat.displayName("用户24875538"))
        assertEquals("红果用户 4230", SeriesReviewFormat.displayName("用户97844230"))
        assertEquals("红果用户 123", SeriesReviewFormat.displayName("用户123"))
        assertEquals("我不喜欢吃糖", SeriesReviewFormat.displayName("我不喜欢吃糖"))
        assertEquals(ReviewScoreTier.RED, SeriesReviewFormat.scoreTier(10.0))
        assertEquals(ReviewScoreTier.RED, SeriesReviewFormat.scoreTier(9.0))
        assertEquals(ReviewScoreTier.ORANGE, SeriesReviewFormat.scoreTier(8.9))
        assertEquals(ReviewScoreTier.ORANGE, SeriesReviewFormat.scoreTier(7.0))
        assertEquals(ReviewScoreTier.YELLOW, SeriesReviewFormat.scoreTier(6.9))
        assertEquals(ReviewScoreTier.YELLOW, SeriesReviewFormat.scoreTier(5.0))
        assertEquals(ReviewScoreTier.GRAY, SeriesReviewFormat.scoreTier(4.9))
        assertEquals(ReviewScoreTier.GRAY, SeriesReviewFormat.scoreTier(4.0))
        assertEquals(ReviewScoreTier.GRAY, SeriesReviewFormat.scoreTier(0.0))
    }

    @Test
    fun rejectsErrorPayloadAndInvalidSeries() {
        val error = JSONObject().put("code", 8).put("message", "busy")
        try {
            SeriesReviewPages.parse(error, "7710164882192075801", 0)
            throw AssertionError("accepted error payload")
        } catch (_: java.io.IOException) {}
        try {
            SeriesReviewPages.parse(JSONObject().put("code", 0), "abc", 0)
            throw AssertionError("accepted invalid series id")
        } catch (_: IllegalArgumentException) {}
    }
}
