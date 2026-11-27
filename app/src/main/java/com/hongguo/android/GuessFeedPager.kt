package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCursor
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One browse round of 猜你喜欢 for one owner (null = anonymous). The server cursor stays in memory only; a new
 * round (refresh, account change) starts the server pagination from the entry again.
 *
 * The first page returns the first batch with new cards. Later pages aim for [TARGET] new cards. Both try up
 * to [MAX_REQUESTS] batches because the feed's has_more flags are unreliable. The round ends after an empty
 * page or past [MAX_OFFSET]. The client advances the same server cursor throughout the round.
 *
 * A refresh by the same owner keeps the most recent [SEEN_LIMIT] shown ids (hongguoTV 3d319a6): a rebuilt server
 * session that only returns the old batch reports that nothing new arrived instead of replaying it.
 */
internal class GuessFeedPager(
    val owner: String?,
    private val fetch: suspend (String?, GuessCursor) -> GuessBatch,
    previous: GuessFeedPager? = null,
    private val history: RecommendationHistory? = null,
    private val historyKey: String = "anonymous",
) {
    private val cursor = GuessCursor()
    private val seen: LinkedHashSet<String> = LinkedHashSet<String>().apply { if (previous != null && previous.owner == owner) addAll(previous.seen) }
    private var served = 0
    private var restored = false

    val rememberedCount get() = seen.size

    suspend fun next(): DramaPage {
        if (!restored) {
            val saved = history?.let { withContext(Dispatchers.IO) { it.recent(historyKey) } }.orEmpty()
            val merged = RecommendationHistory.normalized(saved + seen)
            seen.clear(); seen.addAll(merged); restored = true
        }
        currentCoroutineContext().ensureActive()
        val target = if (served == 0) 1 else TARGET
        val pageSeen = LinkedHashSet(seen)
        val cards = mutableListOf<DramaCard>()
        var requests = 0
        while (cards.size < target && requests < MAX_REQUESTS && cursor.offset <= MAX_OFFSET) {
            cursor.filterIds = pageSeen.toList()
            val batch = try {
                fetch(owner, cursor)
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                if (cards.isEmpty()) throw failure
                break
            }
            currentCoroutineContext().ensureActive()
            requests++
            batch.items.forEach { item ->
                if (pageSeen.add(item.series.id)) cards += DramaCard(item.series.id, item.series.title,
                    item.series.cover, item.episodeCount, item.series.description)
            }
            while (pageSeen.size > SEEN_LIMIT) pageSeen.remove(pageSeen.first())
        }
        history?.let { withContext(Dispatchers.IO) { it.remember(historyKey, pageSeen.toList()) } }
        seen.clear()
        seen.addAll(pageSeen)
        served += requests
        return DramaPage(cards, cards.isNotEmpty() && cursor.offset <= MAX_OFFSET, served)
    }

    companion object {
        const val TARGET = 6
        const val MAX_REQUESTS = 3
        const val MAX_OFFSET = 600
        const val SEEN_LIMIT = RecommendationHistory.LIMIT
    }
}

/** A refreshed round brought only works shown recently; [remembered] is the size of the dedupe window. */
class NoNewRecommendationsException(val remembered: Int) : IOException("推荐接口未返回新作品")
