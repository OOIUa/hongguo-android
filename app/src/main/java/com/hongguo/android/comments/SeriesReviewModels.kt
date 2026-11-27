// SPDX-License-Identifier: GPL-3.0-only
// Protocol fields and pagination verified 2026-11-25 with anonymous live requests to
// /reading/ugc/novel_comment/book/v1/ (offset 0/20/40, has_more false on the last page).
// Cross-checked against lvguo-android CommentsDialog and hongguoTV AppCommentPages; no code copied.
package com.hongguo.android.comments

import com.hongguo.account.protocol.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One series-level review shown on the drama's detail page. */
data class SeriesReview(
    val id: String,
    val author: String,
    val text: String,
    /** Server timestamp in seconds; 0 when the payload carries none. */
    val createdAt: Long,
    val likes: Long,
    /** The reviewer's score, normally 0..10; 0 when absent. */
    val score: Double,
)

/** One page of series reviews plus the server's continuation cursor. */
data class SeriesReviewPage(
    val items: List<SeriesReview>,
    val total: Int?,
    val averageScore: Double,
    val bookName: String,
    val nextOffset: Int,
    val hasMore: Boolean,
)

/**
 * Parses the series-review payload. Kept separate from the transport so the pagination rules
 * (status filter, series check, cursor progress, dedupe) can be tested without a network.
 */
internal object SeriesReviewPages {
    private const val MAX_TEXT = 2000
    private const val MAX_ITEMS = 50
    private val ID = Regex("[0-9]{6,30}")

    fun validate(seriesId: String, offset: Int) {
        require(seriesId.matches(ID) && seriesId.any { it != '0' }) { "剧集编号无效" }
        require(offset in 0..1_000_000) { "评论页码无效" }
    }

    fun parse(root: JSONObject, seriesId: String, offset: Int): SeriesReviewPage {
        validate(seriesId, offset)
        val code = root.optInt("code", -1)
        if (code != 0) throw IOException("剧评接口返回 $code")
        val data = root.optJSONObject("data")
            ?: throw IOException("剧评数据缺失")

        val seen = LinkedHashSet<String>()
        val items = ArrayList<SeriesReview>()
        val rows = data.optJSONArray("comment") ?: JSONArray()
        for (index in 0 until rows.length()) {
            if (items.size >= MAX_ITEMS) break
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("status", 1) != 1) continue
            val id = row.str("comment_id")
            if (id.isEmpty() || id.length > 120 || id in seen) continue
            val group = row.str("group_id")
            if (group.isNotEmpty() && group != seriesId) continue
            val text = text(row).take(MAX_TEXT).trim()
            // Rich/image-only reviews have no text here; showing an empty bullet is worse than
            // leaving them out until image rendering is supported.
            if (text.isEmpty()) continue
            seen.add(id)
            val user = row.optJSONObject("user_info")
            val author = user?.str("user_name").orEmpty().take(60)
            val created = row.optLong("create_timestamp", 0L).let { if (it > 10_000_000_000L) it / 1000 else it }
            items += SeriesReview(
                id = id,
                author = author.ifEmpty { "红果用户" },
                text = text,
                createdAt = created.coerceAtLeast(0L),
                likes = row.optLong("digg_count", 0L).coerceAtLeast(0L),
                score = score(row),
            )
        }

        val book = data.optJSONObject("book_info")
        val total = data.optLong("comment_cnt", -1L).takeIf { it >= 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
        val next = data.optInt("next_offset", offset + rows.length())
        val progressed = next in (offset + 1)..1_000_000 && items.isNotEmpty()
        val hasMore = data.optBoolean("has_more", false) && progressed
        return SeriesReviewPage(
            items = items,
            total = total,
            averageScore = book?.let(::score) ?: 0.0,
            bookName = book?.str("book_name").orEmpty().take(120),
            nextOffset = if (progressed) next else offset,
            hasMore = hasMore,
        )
    }

    private fun score(row: JSONObject): Double = row.optDouble("score").takeIf { it in 0.0..10.0 } ?: 0.0

    /** UGC text may be a plain string or a serialized rich-text object/array. */
    fun text(row: JSONObject): String {
        for (key in listOf("text", "content", "comment_text", "comment_content")) {
            val value = readText(row.opt(key))
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun readText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString("") { readText(value.opt(it)) }
        is JSONObject -> listOf("text", "content", "description")
            .firstNotNullOfOrNull { key -> readText(value.opt(key)).takeIf { it.isNotBlank() } }
            .orEmpty()
        is String -> {
            val trimmed = value.trim()
            when {
                trimmed.startsWith("{") -> runCatching { readText(JSONObject(trimmed)) }.getOrDefault(trimmed)
                trimmed.startsWith("[{") -> runCatching { readText(JSONArray(trimmed)) }.getOrDefault(trimmed)
                else -> trimmed
            }
        }
        else -> value.toString()
    }
}

/** Solid badge tiers for the score shown in place of an avatar. */
internal enum class ReviewScoreTier { GRAY, YELLOW, ORANGE, RED }

/** Display helpers are pure so they can be checked on the JVM. */
internal object SeriesReviewFormat {
    private val AUTO_NAME = Regex("^用户(\\d+)$")

    /** 1–4 gray, 5–6 yellow, 7–8 orange, 9–10 red. */
    fun scoreTier(score: Double): ReviewScoreTier = when {
        score >= 9.0 -> ReviewScoreTier.RED
        score >= 7.0 -> ReviewScoreTier.ORANGE
        score >= 5.0 -> ReviewScoreTier.YELLOW
        else -> ReviewScoreTier.GRAY
    }

    /** The App endpoint anonymizes names to 用户12345678; show 红果用户 5678 instead. */
    fun displayName(author: String): String {
        val name = author.trim()
        val match = AUTO_NAME.matchEntire(name) ?: return name
        return "红果用户 ${match.groupValues[1].takeLast(4)}"
    }

    fun score(value: Double): String =
        if (!value.isFinite() || value <= 0.0) "" else DecimalFormat("0.#").format(value)

    /** Detail-page counters: 8480 万, 59 万; the caller adds the metric name. */
    fun compactWan(value: Long): String {
        if (value <= 0L) return ""
        return when {
            value >= 100_000_000L -> "${(value + 50_000_000L) / 100_000_000L} 亿"
            value >= 10_000L -> "${(value + 5_000L) / 10_000L} 万"
            else -> value.toString()
        }
    }

    fun time(seconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000L): String {
        if (seconds <= 0L) return ""
        val diff = (nowSeconds - seconds).coerceAtLeast(0L)
        return when {
            diff < 60L -> "刚刚"
            diff < 3_600L -> "${diff / 60L} 分钟前"
            diff < 86_400L -> "${diff / 3_600L} 小时前"
            diff < 86_400L * 30L -> "${diff / 86_400L} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(seconds * 1000L))
        }
    }
}
