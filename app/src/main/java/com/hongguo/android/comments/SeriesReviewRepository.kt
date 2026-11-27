// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.comments

import com.hongguo.api.service.Client
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** The counter on the detail screen and the open panel share the same first page. */
internal class SeriesReviewRepository(
    private val fetch: (String, Int) -> JSONObject = ::fetchReviews,
    private val elapsed: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    private data class Cached(val time: Long, val page: SeriesReviewPage)
    private val firstPages = LinkedHashMap<String, Cached>(16, .75f, true)
    private val firstPageMutex = Mutex()

    suspend fun page(seriesId: String, offset: Int): SeriesReviewPage {
        SeriesReviewPages.validate(seriesId, offset)
        if (offset != 0) return load(seriesId, offset)
        return firstPageMutex.withLock {
            val cached = firstPages[seriesId]
            if (cached != null && elapsed() - cached.time < 120_000L) return@withLock cached.page
            val result = load(seriesId, offset)
            firstPages[seriesId] = Cached(elapsed(), result)
            if (firstPages.size > 16) firstPages.remove(firstPages.keys.first())
            result
        }
    }

    private suspend fun load(seriesId: String, offset: Int): SeriesReviewPage = runInterruptible(Dispatchers.IO) {
        SeriesReviewPages.parse(fetch(seriesId, offset), seriesId, offset)
    }
}

private fun fetchReviews(seriesId: String, offset: Int): JSONObject {
    val query = mapOf<String, Any>("book_id" to seriesId, "group_id" to seriesId,
        "offset" to offset, "count" to 20, "sort" to 1)
    return JSONObject(Client.api("GET", "/reading/ugc/novel_comment/book/v1/", null, query, 1, Client.SIGN_LIST))
}

internal object SeriesReviewApi {
    val repository = SeriesReviewRepository()
}
