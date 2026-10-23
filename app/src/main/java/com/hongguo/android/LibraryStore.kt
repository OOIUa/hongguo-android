package com.hongguo.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.WeakHashMap

/** One bounded local JSON snapshot. Shared instances use one lock and one observable state. */
class LibraryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState: MutableStateFlow<LibrarySnapshot>
    val state: StateFlow<LibrarySnapshot>

    init {
        synchronized(LOCK) {
            val raw = try { preferences.getString(SNAPSHOT_KEY, null) } catch (_: ClassCastException) { "" }
            val loaded = LibraryCodec.decode(raw)
            mutableState = STATES.getOrPut(preferences) { MutableStateFlow(loaded) }
            mutableState.value = loaded
            // Repair malformed, partially valid, or pre-canonical data without keeping raw inputs/logs.
            val canonical = LibraryCodec.encode(loaded)
            if (raw != null && raw != canonical) preferences.edit().putString(SNAPSHOT_KEY, canonical).apply()
        }
        state = mutableState.asStateFlow()
    }

    fun toggleFavorite(card: DramaCard) = change { LibraryCodec.toggleFavorite(it, card, System.currentTimeMillis()) }

    /** Call only after a real rendered first frame, never after selecting/preparing an episode. */
    fun recordPlayback(card: DramaCard, episodeId: String, episodeIndex: Int, positionMs: Long) =
        change { LibraryCodec.recordPlayback(it, card, episodeId, episodeIndex, positionMs, System.currentTimeMillis()) }

    /** Updates only matching existing episodes; does not resurrect a deleted history entry. */
    fun updatePosition(card: DramaCard, episodeId: String, episodeIndex: Int, positionMs: Long) =
        change { LibraryCodec.updatePosition(it, card, episodeId, episodeIndex, positionMs) }

    fun deleteHistory(id: String) = change { LibraryCodec.deleteHistory(it, id) }
    fun clearHistory() = change(LibraryCodec::clearHistory)
    fun clearFavorites() = change(LibraryCodec::clearFavorites)
    fun refreshCard(card: DramaCard) = change { LibraryCodec.refreshCard(it, card) }

    private fun change(transform: (LibrarySnapshot) -> LibrarySnapshot) {
        synchronized(LOCK) {
            val current = mutableState.value
            val next = transform(current)
            if (next == current) return
            // One preference value commits both lists together; apply updates its memory synchronously.
            preferences.edit().putString(SNAPSHOT_KEY, LibraryCodec.encode(next)).apply()
            mutableState.value = next
        }
    }

    companion object {
        internal const val PREFERENCES = "hongguo_library"
        internal const val SNAPSHOT_KEY = "snapshot"
        private val LOCK = Any()
        private val STATES = WeakHashMap<SharedPreferences, MutableStateFlow<LibrarySnapshot>>()
    }
}
