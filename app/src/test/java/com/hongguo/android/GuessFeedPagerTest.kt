package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCard
import com.hongguo.account.protocol.GuessCursor
import com.hongguo.account.protocol.Series
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuessFeedPagerTest {
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
}
