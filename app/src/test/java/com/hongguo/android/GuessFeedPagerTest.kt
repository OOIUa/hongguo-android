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
