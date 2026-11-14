package com.hongguo.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.WeakHashMap

/** One bounded local JSON snapshot. Shared instances use one lock and one observable state. */
class LibraryStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))
    private val mutableState: MutableStateFlow<LibrarySnapshot>
    val state: StateFlow<LibrarySnapshot>

    init {
        synchronized(LOCK) {
            mutableState = STATES.getOrPut(preferences) { MutableStateFlow(LibrarySnapshot()) }
        }
        state = mutableState.asStateFlow()
        reload()
    }

    fun reload() = synchronized(LOCK) {
        try { mutableState.value = LibraryCodec.decodeChecked(preferences.getString(SNAPSHOT_KEY, null)) }
        catch (_: Exception) {
            mutableState.value = mutableState.value.copy(storageError = "本机片单读取失败，原数据已保留，已暂停写入。请稍后重试读取。")
        }
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
            if (current.storageError != null) return
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
