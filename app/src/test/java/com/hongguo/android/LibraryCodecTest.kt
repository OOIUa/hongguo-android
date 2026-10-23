package com.hongguo.android

import com.hongguo.api.util.Json
import org.junit.Assert.*
import org.junit.Test

class LibraryCodecTest {
    private fun card(id: String = "a", title: String = "故事 $id") = DramaCard(id, title, "https://example.invalid/$id.jpg", 30, "简介")
    private fun entry(id: String, time: Long, episode: String = "e-$id", index: Int = 1, position: Long = 0) =
        LibraryEntry(card(id), episode, index, position, time)

    @Test fun longIdsAndUnicodeRoundTripWithoutNumericConversion() {
        val series = "184467440737095516159007199254740993"
        val episode = "90071992547409939999999999"
        val original = LibrarySnapshot(history = listOf(LibraryEntry(card(series, "红果 \"故事\" 🍊"), episode, 12, 123456, 99)))
        val encoded = LibraryCodec.encode(original)
        assertEquals(original, LibraryCodec.decode(encoded))
        val parsed = Json.parseObject(encoded)
        val row = Json.optObj(Json.optArr(parsed["history"])[0])
        assertTrue(Json.optObj(row["card"])["id"] is String)
        assertTrue(row["episode_id"] is String)
    }

    @Test fun corruptRootAndDeepNestingRecoverWithoutThrowing() {
        for (json in listOf("{", "[]", "null", "{\"history\":", "{\"version\":2}", "[".repeat(100) + "]".repeat(100))) {
            assertEquals(LibrarySnapshot(), LibraryCodec.decode(json))
        }
        assertEquals(LibrarySnapshot(), LibraryCodec.decode(null))
    }

    @Test fun malformedRowsDoNotEraseIndependentValidRows() {
        val valid = Json.optArr(Json.parseObject(LibraryCodec.encode(LibrarySnapshot(history = listOf(entry("valid", 4)))))["history"])[0]
        val numericId = linkedMapOf("card" to linkedMapOf("id" to 9007199254740993L))
        val invalidEpisode = linkedMapOf("card" to linkedMapOf("id" to "wrong-episode"), "episode_id" to 42L)
        val input = Json.stringify(linkedMapOf("version" to 1, "favorites" to "broken-list",
            "history" to listOf(null, "bad-row", numericId, invalidEpisode, valid)))
        val decoded = LibraryCodec.decode(input)
        assertTrue(decoded.favorites.isEmpty())
        assertEquals(listOf("valid"), decoded.history.map { it.card.id })
    }

    @Test fun newestDuplicateWinsAndEqualTimesKeepInputOrder() {
        val snapshot = LibrarySnapshot(history = listOf(entry("a", 1), entry("b", 4), entry("a", 5, "new", 2, 60),
            entry("c", 4), entry("b", 4, "duplicate", 9)))
        val normalized = LibraryCodec.decode(LibraryCodec.encode(snapshot))
        assertEquals(listOf("a", "b", "c"), normalized.history.map { it.card.id })
        assertEquals("new", normalized.history[0].episodeId)
        assertEquals("e-b", normalized.history[1].episodeId)
    }

    @Test fun favoritesAndHistoryHaveIndependentTwoHundredEntryLimits() {
        val entries = (0..220).map { entry("id-$it", it.toLong()) }
        val clean = LibraryCodec.decode(LibraryCodec.encode(LibrarySnapshot(entries, entries)))
        assertEquals(200, clean.history.size)
        assertEquals(200, clean.favorites.size)
        assertEquals("id-220", clean.history.first().card.id)
        assertEquals("id-21", clean.history.last().card.id)
        assertEquals(clean.favorites, LibraryCodec.clearHistory(clean).favorites)
        assertTrue(LibraryCodec.clearHistory(clean).history.isEmpty())
        assertEquals(clean.history, LibraryCodec.clearFavorites(clean).history)
        assertTrue(LibraryCodec.clearFavorites(clean).favorites.isEmpty())
    }

    @Test fun firstFrameMovesOnlyHistoryAndRefreshesFavoriteProgress() {
        val initial = LibrarySnapshot(favorites = listOf(entry("b", 20), entry("a", 10)),
            history = listOf(entry("b", 100), entry("a", 90)))
        val recorded = LibraryCodec.recordPlayback(initial, card("a"), "a-episode-2", 2, 700, 200)
        assertEquals(listOf("a", "b"), recorded.history.map { it.card.id })
        assertEquals(200, recorded.history.first().updatedAt)
        assertEquals(listOf("b", "a"), recorded.favorites.map { it.card.id })
        val favorite = recorded.favorites.last()
        assertEquals("a-episode-2", favorite.episodeId)
        assertEquals(700, favorite.positionMs)
        assertEquals(10, favorite.updatedAt)
    }

    @Test fun delayedOldPlayerCannotReplaceNewEpisodeOrChangeHistoryTime() {
        val initial = LibrarySnapshot(favorites = listOf(entry("a", 2, "old", 1, 100)),
            history = listOf(entry("b", 30), entry("a", 20, "old", 1, 100)))
        val recorded = LibraryCodec.recordPlayback(initial, card("a"), "new", 2, 200, 40)
        val stale = LibraryCodec.updatePosition(recorded, card("a"), "old", 1, 9000)
        assertEquals(recorded, stale)
        val progressed = LibraryCodec.updatePosition(stale, card("a"), "new", 2, 1500)
        assertEquals(listOf("a", "b"), progressed.history.map { it.card.id })
        assertEquals(40, progressed.history.first().updatedAt)
        assertEquals(1500, progressed.history.first().positionMs)
        assertEquals(1500, progressed.favorites.first().positionMs)
        assertEquals(2, progressed.favorites.first().updatedAt)
    }

    @Test fun deletingHistoryKeepsFavoriteAndPositionUpdatesCannotRecreateHistory() {
        val initial = LibrarySnapshot(favorites = listOf(entry("a", 1)), history = listOf(entry("a", 10, position = 300)))
        val deleted = LibraryCodec.deleteHistory(initial, "a")
        assertTrue(deleted.history.isEmpty())
        assertEquals(1, deleted.favorites.size)
        val progressed = LibraryCodec.updatePosition(deleted, card("a"), "e-a", 1, 500)
        assertTrue(progressed.history.isEmpty())
        assertEquals(500, progressed.favorites.single().positionMs)
        val unrelated = LibraryCodec.updatePosition(progressed, card("unseen"), "e-unseen", 1, 999)
        assertEquals(progressed, unrelated)
    }

    @Test fun togglingFavoriteCopiesLatestHistoryThenRemovesOnlyFavorite() {
        val history = LibrarySnapshot(history = listOf(entry("a", 10, "watched", 5, 1234)))
        val favorited = LibraryCodec.toggleFavorite(history, card("a"), 20)
        assertEquals("watched", favorited.favorites.single().episodeId)
        assertEquals(1234, favorited.favorites.single().positionMs)
        assertEquals(20, favorited.favorites.single().updatedAt)
        assertEquals(history, LibraryCodec.toggleFavorite(favorited, card("a"), 30))
    }

    @Test fun refreshingCardDoesNotReorderOrChangePositionsAndTimes() {
        val initial = LibrarySnapshot(favorites = listOf(entry("b", 20), entry("a", 10, position = 30)),
            history = listOf(entry("a", 40, position = 700), entry("b", 30)))
        val normalized = LibraryCodec.normalize(initial)
        val refreshed = LibraryCodec.refreshCard(initial, card("a", "新剧名"))
        assertEquals(normalized.favorites.map { it.card.id }, refreshed.favorites.map { it.card.id })
        assertEquals(normalized.history.map { it.card.id }, refreshed.history.map { it.card.id })
        assertEquals(normalized.history.first().copy(card = card("a", "新剧名")), refreshed.history.first())
        assertEquals("新剧名", refreshed.favorites.last().card.title)
    }

    @Test fun invalidCountersAreClampedAndEmptyEpisodesCannotKeepPhantomPositions() {
        val json = """{"favorites":[{"card":{"id":"a","episode_count":-1},"episode_id":"","episode_index":9,"position_ms":999,"updated_at":-7}],"history":[{"card":{"id":"b"},"episode_id":"ep","episode_index":-2,"position_ms":1.5,"updated_at":"wrong"}]}"""
        val clean = LibraryCodec.decode(json)
        assertEquals(0, clean.favorites.single().episodeIndex)
        assertEquals(0, clean.favorites.single().positionMs)
        assertEquals(0, clean.favorites.single().updatedAt)
        assertEquals(0, clean.favorites.single().card.episodeCount)
        assertEquals(0, clean.history.single().episodeIndex)
        assertEquals(0, clean.history.single().positionMs)
        assertEquals(0, clean.history.single().updatedAt)
    }

    @Test fun clockRollbackStillMovesMostRecentPlaybackToFront() {
        val initial = LibrarySnapshot(history = listOf(entry("b", 100), entry("a", 80)))
        val next = LibraryCodec.recordPlayback(initial, card("a"), "new", 2, 0, 50)
        assertEquals(listOf("a", "b"), next.history.map { it.card.id })
        assertEquals(100, next.history.first().updatedAt)
    }
}
