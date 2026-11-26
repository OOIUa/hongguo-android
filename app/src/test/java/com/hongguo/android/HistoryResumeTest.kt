package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryResumeTest {
    private val episodes = listOf(Episode("e1", 1, ""), Episode("e2", 2, ""), Episode("e3", 3, ""))
    private val watched = LibraryEntry(DramaCard("s", "短剧", "", 3, ""), "e2", 2, 12_000)

    @Test fun resumesAtTheLastPositionEvenWhenTheEpisodeIdChanged() {
        assertEquals(HistoryResume(1, 12_000, false), historyResume(episodes, watched))
        assertEquals(HistoryResume(1, 12_000, false), historyResume(episodes, watched.copy(episodeId = "old-id")))
        assertEquals(HistoryResume(1, 12_000, false), historyResume(episodes, watched.copy(episodeIndex = 3)))
        assertNull(historyResume(episodes, watched.copy(episodeId = "removed", episodeIndex = 7)))
        assertNull(historyResume(emptyList(), watched))
    }

    @Test fun finishingAnEpisodeContinuesAtTheNextAvailableEpisode() {
        assertEquals(HistoryResume(2, 0, false), historyResume(episodes, watched.copy(completed = true)))
        assertEquals(HistoryResume(1, 0, true), historyResume(episodes.take(2), watched.copy(completed = true)))
    }

    @Test fun aLatePlayerPositionCannotUndoCompletionUntilPlaybackActuallyRestarts() {
        assertFalse(EpisodeWatchState.COMPLETED.canResumeAt(59_980, 60_000))
        val replay = EpisodeWatchState.COMPLETED.onPlaying()
        assertTrue(replay.canResumeAt(300, 60_000))
        assertTrue(replay.canResumeAt(300, 0))
        assertFalse(replay.canResumeAt(60_000, 60_000))
    }

    @Test fun staleLibraryClicksResolveOnlyTheCurrentAccountsProgressOrTheDeviceFavorite() {
        val guest = watched.copy(episodeId = "e1", episodeIndex = 1)
        val a = HongguoUiState(historyOwnerId = "a", history = listOf(watched), favorites = listOf(guest))
        val b = a.copy(historyOwnerId = "b", history = emptyList())
        assertEquals(watched, a.libraryResumeFor(watched.card.id))
        assertEquals(guest, b.libraryResumeFor(watched.card.id))
        assertNull(b.copy(favorites = emptyList()).libraryResumeFor(watched.card.id))
    }
}
