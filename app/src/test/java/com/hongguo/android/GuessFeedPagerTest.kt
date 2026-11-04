package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCard
import com.hongguo.account.protocol.Series
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GuessFeedPagerTest {
    private fun card(id: Int) = GuessCard(Series("73000000000000$id", "剧$id"), 10)

    @Test fun pageReadsBatchesUntilTargetAndThreadsSession() = runBlocking {
        val calls = mutableListOf<Triple<String?, String, Int>>()
        val pager = GuessFeedPager("owner:1") { owner, session, offset ->
            calls += Triple(owner, session, offset)
            GuessBatch((0 until 3).map { card(offset * 3 + it) }, if (offset == 0) "srv" else "")
        }
        val first = pager.next()
        assertEquals(6, first.items.size)
        assertEquals(2, first.nextOffset)
        assertTrue(first.hasMore)
        assertEquals(listOf(Triple("owner:1", "", 0), Triple("owner:1", "srv", 1)), calls)
        val second = pager.next()
        assertEquals(4, second.nextOffset)
        assertEquals("空 session 不覆盖已有的服务端会话", "srv", calls.last().second)
    }

    @Test fun offsetAdvancesByTheRowsTheServerSent() = runBlocking {
        val offsets = mutableListOf<Int>()
        val pager = GuessFeedPager(null) { _, _, offset ->
            offsets += offset
            GuessBatch((0 until 3).map { card(offset * 10 + it) }, "srv", served = if (offset == 0) 3 else 6)
        }
        val page = pager.next()
        assertEquals(listOf(0, 3), offsets)
        assertEquals(9, page.nextOffset)
    }

    @Test fun restartedBatchReplacesRejectedPaginationAndKeepsShownCardsOut() = runBlocking {
        val calls = mutableListOf<Pair<String, Int>>()
        val pager = GuessFeedPager(null) { _, session, offset ->
            calls += session to offset
            when (calls.size) {
                1 -> GuessBatch((0 until 6).map { card(it) }, "old")
                2 -> GuessBatch(listOf(card(0), card(6), card(7), card(8), card(9), card(10), card(11)), "", restarted = true)
                else -> GuessBatch(listOf(card(12)), "new")
            }
        }
        pager.next()
        val second = pager.next()
        assertEquals("已展示的卡片不重复", 6, second.items.size)
        assertEquals("重启后偏移从 0 重新计", 1, second.nextOffset)
        pager.next()
        assertEquals("被拒的 session 不再使用", listOf("" to 0, "old" to 1, "" to 1), calls.take(3))
    }

    @Test fun duplicateOnlyBatchesEndTheRoundAfterThreeRequests() = runBlocking {
        var requests = 0
        val pager = GuessFeedPager(null) { _, _, _ -> requests++; GuessBatch(listOf(card(1), card(2)), "srv") }
        assertEquals(2, pager.next().items.size)
        assertEquals(3, requests)
        val tail = pager.next()
        assertTrue(tail.items.isEmpty())
        assertFalse(tail.hasMore)
        assertEquals(6, requests)
    }
}
