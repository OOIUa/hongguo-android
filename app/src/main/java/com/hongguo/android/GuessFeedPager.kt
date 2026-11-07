package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch
import com.hongguo.account.protocol.GuessCursor

/**
 * One browse round of 猜你喜欢 for one owner (null = anonymous). The server cursor stays in memory only; a new
 * round (refresh, account change) starts the server pagination from the entry again.
 *
 * The feed returns only a few cards per batch and its has_more flags are unreliable, so a page reads up to
 * [MAX_REQUESTS] batches until [TARGET] new cards arrive, and the round ends after a page that brought nothing
 * new or past [MAX_OFFSET]. The client advances the server cursor (rows served on the entry, the server's
 * next_offset on the cell continuation).
 *
 * A refresh by the same owner keeps the most recent [SEEN_LIMIT] shown ids (hongguoTV 3d319a6): a rebuilt server
 * session that only returns the old batch reports that nothing new arrived instead of replaying it.
 */
internal class GuessFeedPager(
    val owner: String?,
    private val fetch: suspend (String?, GuessCursor) -> GuessBatch,
    previous: GuessFeedPager? = null,
) {
    private val cursor = GuessCursor()
    private val seen: LinkedHashSet<String> = LinkedHashSet<String>().apply { if (previous != null && previous.owner == owner) addAll(previous.seen) }
    private var served = 0

    val rememberedCount get() = seen.size

    suspend fun next(): DramaPage {
        val cards = mutableListOf<DramaCard>()
        var requests = 0
        while (cards.size < TARGET && requests < MAX_REQUESTS && cursor.offset <= MAX_OFFSET) {
            cursor.filterIds = seen.toList()
            val batch = fetch(owner, cursor)
            requests++
            served++
            batch.items.forEach { item ->
                if (seen.add(item.series.id)) cards += DramaCard(item.series.id, item.series.title,
                    item.series.cover, item.episodeCount, item.series.description)
            }
            while (seen.size > SEEN_LIMIT) seen.remove(seen.first())
        }
        return DramaPage(cards, cards.isNotEmpty() && cursor.offset <= MAX_OFFSET, served)
    }

    companion object {
        const val TARGET = 6
        const val MAX_REQUESTS = 3
        const val MAX_OFFSET = 600
        const val SEEN_LIMIT = 540
    }
}

/** A refreshed round brought only works shown recently; [remembered] is the size of the dedupe window. */
class NoNewRecommendationsException(val remembered: Int) : java.io.IOException("推荐接口未返回新作品")
