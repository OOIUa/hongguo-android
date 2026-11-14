package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCard
import com.hongguo.account.protocol.GuessCursor
import com.hongguo.account.protocol.Series
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import org.junit.Test

class GuessFeedPagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun card(id: Int) = GuessCard(Series("73000000000000$id", "剧$id"), 10)

    @Test fun pageReadsBatchesUntilTargetAndSharesOneCursor() = runBlocking {
        val cursors = mutableListOf<GuessCursor>()
        val pager = GuessFeedPager("owner:1", { owner, cursor ->
            assertEquals("owner:1", owner)
            cursors += cursor
            val batch = GuessBatch((0 until 3).map { card(cursor.offset * 3 + it) }, "srv", served = 3)
            cursor.offset += 3
            batch
        })
        val first = pager.next()
        assertEquals(6, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        assertSame(cursors[0], cursors[1])
        assertEquals("seen ids go out as filter_ids", 3, cursors[1].filterIds.size)
        assertTrue(pager.next().nextOffset > first.nextOffset)
    }

    @Test fun duplicateOnlyBatchesEndTheRoundAfterThreeRequests() = runBlocking {
        var requests = 0
        val pager = GuessFeedPager(null, { _, cursor -> requests++; cursor.offset++; GuessBatch(listOf(card(1), card(2)), "srv") })
        assertEquals(2, pager.next().items.size)
        assertEquals(3, requests)
        val tail = pager.next()
        assertTrue(tail.items.isEmpty())
        assertFalse(tail.hasMore)
        assertEquals(6, requests)
    }

    @Test fun roundStopsPastTheOffsetLimit() = runBlocking {
        var requests = 0
        val pager = GuessFeedPager(null, { _, cursor -> requests++; cursor.offset = GuessFeedPager.MAX_OFFSET + 1
            GuessBatch(listOf(card(requests)), "srv") })
        val page = pager.next()
        assertEquals(1, requests)
        assertFalse(page.hasMore)
    }

    @Test fun refreshBySameOwnerKeepsDedupeButAnotherOwnerStartsClean() = runBlocking {
        val fetch: suspend (String?, GuessCursor) -> GuessBatch = { _, cursor -> cursor.offset++; GuessBatch(listOf(card(1)), "srv") }
        val first = GuessFeedPager("a", fetch)
        assertEquals(1, first.next().items.size)
        val refreshed = GuessFeedPager("a", fetch, previous = first)
        assertTrue("旧批次不当新推荐重播", refreshed.next().items.isEmpty())
        assertEquals(1, refreshed.rememberedCount)
        assertEquals(1, GuessFeedPager("b", fetch, previous = refreshed).next().items.size)
    }

    @Test fun newPagerRestoresDiskHistoryWithAStableAccountKey() = runBlocking {
        val directory = temporary.newFolder()
        val history = RecommendationHistory(directory)
        val first = GuessFeedPager("a:session1", { _, cursor -> cursor.offset++; GuessBatch((1..6).map(::card), "") },
            history = history, historyKey = "account:a")
        assertEquals(6, first.next().items.size)
        val restarted = GuessFeedPager("a:session2", { _, cursor ->
            assertTrue(cursor.filterIds.contains(card(1).series.id))
            cursor.offset++; GuessBatch((1..12).map(::card), "")
        }, history = RecommendationHistory(directory), historyKey = "account:a")
        assertEquals((7..12).map { card(it).series.id }, restarted.next().items.map { it.id })
        val other = GuessFeedPager("b:session1", { _, cursor -> cursor.offset++; GuessBatch((1..6).map(::card), "") },
            history = RecommendationHistory(directory), historyKey = "account:b")
        assertEquals(6, other.next().items.size)
    }

    @Test fun cancelledResponseDoesNotConsumeOrPersistRecommendations() = runBlocking {
        val history = RecommendationHistory(temporary.newFolder())
        val request = async {
            GuessFeedPager(null, { _, _ -> currentCoroutineContext().cancel(); GuessBatch(listOf(card(1)), "") }, history = history).next()
        }
        try { request.await(); fail("Cancelled batch was accepted") } catch (_: CancellationException) { }
        assertTrue(history.recent("anonymous").isEmpty())
    }
}
