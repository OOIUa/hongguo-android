// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.comments

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SeriesReviewRepositoryTest {
    private val series = "7710164882192075801"

    private fun response() = JSONObject("""{"code":0,"data":{"comment_cnt":0,"comment":[],"has_more":false}}""")

    @Test fun concurrentHeadRequestsShareOneResultAndExpire() = runBlocking {
        var now = 0L
        val calls = AtomicInteger()
        val repo = SeriesReviewRepository(fetch = { _, _ -> calls.incrementAndGet(); response() }, elapsed = { now })
        val pages = List(4) { async { repo.page(series, 0) } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(1, pages.distinct().size)
        now = 120_001L
        repo.page(series, 0)
        assertEquals(2, calls.get())
    }

    @Test fun cacheIsPerSeriesAndForFirstPagesOnly() = runBlocking {
        val requests = mutableListOf<Pair<String, Int>>()
        val repo = SeriesReviewRepository(fetch = { id, offset -> requests += id to offset; response() })
        val other = "7710164882192075802"
        repo.page(series, 0)
        repo.page(other, 0)
        repo.page(series, 20)
        repo.page(series, 20)
        repo.page(series, 0)
        assertEquals(listOf(series to 0, other to 0, series to 20, series to 20), requests)
    }

    @Test fun rejectedRequestIsNotRetriedOrCached() {
        var calls = 0
        val repo = SeriesReviewRepository(fetch = { _, _ ->
            if (++calls == 1) throw IOException("剧评数据不可用") else response()
        })
        assertThrows(IOException::class.java) { runBlocking { repo.page(series, 0) } }
        assertEquals(1, calls)
        assertEquals(0, runBlocking { repo.page(series, 0) }.total)
        assertEquals(2, calls)
    }

    @Test fun invalidSeriesAndOffsetFailBeforeFetching() {
        val repo = SeriesReviewRepository(fetch = { _, _ -> throw AssertionError("unexpected network call") })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.page("123x", 0) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.page(series, -1) } }
    }
}
