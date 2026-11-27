package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCard
import com.hongguo.account.protocol.GuessCursor
import com.hongguo.account.protocol.Series
import java.io.IOException
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

    @Test fun firstBatchIsVisibleBeforeFetchingMoreAndNextPageFillsUsingTheSameCursor() = runBlocking {
        val cursors = mutableListOf<GuessCursor>()
        val filters = mutableListOf<List<String>>()
        val pager = GuessFeedPager("owner:1", { owner, cursor ->
            assertEquals("owner:1", owner)
            cursors += cursor
            filters += cursor.filterIds
            val batch = GuessBatch((0 until 3).map { card(cursor.offset + it) }, "srv", served = 3)
            cursor.offset += 3
            batch
        })
        val first = pager.next()
        assertEquals((0..2).map { card(it).series.id }, first.items.map { it.id })
        assertEquals(1, cursors.size)
        assertTrue(first.hasMore)
        assertEquals(1, first.nextOffset)

        val second = pager.next()
        assertEquals((3..8).map { card(it).series.id }, second.items.map { it.id })
        assertEquals(3, second.nextOffset)
        assertEquals(3, cursors.size)
        assertTrue(cursors.all { it === cursors.first() })
        assertEquals(first.items.map { it.id }, filters[1])
        assertEquals((0..5).map { card(it).series.id }, filters[2])
    }

    @Test fun duplicateOnlyBatchesEndTheRoundAfterThreeRequests() = runBlocking {
        var requests = 0
        val pager = GuessFeedPager(null, { _, cursor -> requests++; cursor.offset++; GuessBatch(listOf(card(1), card(2)), "srv") })
        assertEquals(2, pager.next().items.size)
        assertEquals(1, requests)
        val tail = pager.next()
        assertTrue(tail.items.isEmpty())
        assertFalse(tail.hasMore)
        assertEquals(4, requests)
    }

    @Test fun firstPageContinuesPastEmptyAndDuplicateBatchesUntilItHasNewCards() = runBlocking {
        val previous = GuessFeedPager(null, { _, _ -> GuessBatch(listOf(card(1)), "") })
        previous.next()
        val batches = listOf(emptyList(), listOf(card(1)), listOf(card(1), card(2), card(3)))
        var requests = 0
        val pager = GuessFeedPager(null, { _, cursor ->
            cursor.offset++
            GuessBatch(batches[requests++], "")
        }, previous = previous)
        val first = pager.next()
        assertEquals(listOf(card(2).series.id, card(3).series.id), first.items.map { it.id })
        assertEquals(3, requests)
        assertTrue(first.hasMore)
    }

    @Test fun partialPageSurvivesNetworkFailureAndCanContinueWithoutRepeatingCards() = runBlocking {
        val history = RecommendationHistory(temporary.newFolder())
        val failure = IOException("offline")
        var attempts = 0
        val pager = GuessFeedPager(null, { _, cursor ->
            val cards = when (++attempts) {
                1 -> listOf(card(1))
                2 -> listOf(card(1), card(2))
                3, 4 -> throw failure
                else -> (2..8).map(::card)
            }
            cursor.offset += cards.size
            GuessBatch(cards, "")
        }, history = history)
        pager.next()
        val partial = pager.next()
        assertEquals(listOf(card(2).series.id), partial.items.map { it.id })
        assertTrue(partial.hasMore)
        assertEquals(2, partial.nextOffset)
        assertEquals((1..2).map { card(it).series.id }, history.recent("anonymous"))

        try { pager.next(); fail("A failure before any new cards must reach the caller") }
        catch (caught: IOException) { assertSame(failure, caught) }
        val retried = pager.next()
        assertEquals((3..8).map { card(it).series.id }, retried.items.map { it.id })
        assertEquals(3, retried.nextOffset)
    }

    @Test fun cancelledContinuationDoesNotPublishItsPartialBatchOrRememberItsCards() = runBlocking {
        val history = RecommendationHistory(temporary.newFolder())
        var requests = 0
        val pager = GuessFeedPager(null, { _, cursor ->
            if (++requests == 3) throw CancellationException("left the feed")
            cursor.offset++
            GuessBatch(listOf(card(requests)), "")
        }, history = history)
        pager.next()
        try { pager.next(); fail("A cancellation after a partial batch must reach the caller") }
        catch (_: CancellationException) { }
        assertEquals(1, pager.rememberedCount)
        assertEquals(listOf(card(1).series.id), history.recent("anonymous"))
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
