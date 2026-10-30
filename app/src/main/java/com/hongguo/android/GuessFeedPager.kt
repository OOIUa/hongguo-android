package com.hongguo.android

import com.hongguo.account.protocol.GuessBatch

/**
 * One browse round of 猜你喜欢 for one owner (null = anonymous). The server session and offset stay in
 * memory only; a new round (refresh, account change) starts a new pager from offset 0.
 *
 * The feed returns only a few cards per batch and its has_more flags are unreliable, so a page reads
 * up to [MAX_REQUESTS] batches until [TARGET] new cards arrive, and the round ends after a page that
 * brought nothing new or at [MAX_OFFSET].
 */
internal class GuessFeedPager(val owner: String?, private val fetch: suspend (String?, String, Int) -> GuessBatch) {
    private var session = ""
    private var offset = 0
    private val seen = LinkedHashSet<String>()

    suspend fun next(): DramaPage {
        val cards = mutableListOf<DramaCard>()
        var requests = 0
        while (cards.size < TARGET && requests < MAX_REQUESTS && offset <= MAX_OFFSET) {
            val batch = fetch(owner, session, offset)
            requests++
            session = batch.session.ifBlank { session }
            offset++
            batch.items.forEach { item ->
                if (seen.add(item.series.id)) cards += DramaCard(item.series.id, item.series.title,
                    item.series.cover, item.episodeCount, item.series.description)
            }
            while (seen.size > SEEN_LIMIT) seen.remove(seen.first())
        }
        return DramaPage(cards, cards.isNotEmpty() && offset <= MAX_OFFSET, offset)
    }

    companion object {
        const val TARGET = 6
        const val MAX_REQUESTS = 3
        const val MAX_OFFSET = 100
        const val SEEN_LIMIT = 540
    }
}
