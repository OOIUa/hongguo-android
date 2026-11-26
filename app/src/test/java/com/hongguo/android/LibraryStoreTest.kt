package com.hongguo.android

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class LibraryStoreTest {
    @Test fun removingAnAlreadyRemovedDeviceFavoriteCannotReaddItOrClearHistory() {
        val storage = LibraryStore(Preferences(null))
        val card = DramaCard("19", "剧名", "", 4, "")
        storage.toggleFavorite(card)
        storage.recordPlayback(card, "22", 1, 4000)
        val before = storage.state.value.history
        storage.removeFavorite(card.id)
        storage.removeFavorite(card.id)
        assertTrue(storage.state.value.favorites.isEmpty())
        assertEquals(before, storage.state.value.history)
    }

    private class Preferences(snapshot: String?) : SharedPreferences {
        private val values = mutableMapOf<String, String?>()
        var raw: String? get() = values[LibraryStore.SNAPSHOT_KEY]
            set(value) { values[LibraryStore.SNAPSHOT_KEY] = value }
        init { raw = snapshot }
        var writes = 0
        override fun getString(key: String?, defValue: String?) = values[key] ?: defValue
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String?) = values[key] != null
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, String?>()
            private var clear = false
            override fun putString(key: String?, value: String?) = apply { pending[requireNotNull(key)] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = putString(key, null)
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { if (clear) values.clear(); values.putAll(pending); writes++; return true }
            override fun apply() { commit() }
        }
    }

    @Test fun damagedSnapshotSurvivesReloadMutationsAndAnotherStore() {
        val preferences = Preferences("{damaged")
        val store = LibraryStore(preferences)
        val card = DramaCard("1", "测试", "", 5, "")
        store.toggleFavorite(card)
        store.recordPlayback(card, "2", 1, 3000)
        store.clearFavorites(); store.clearHistory()
        assertNotNull(store.state.value.storageError)
        assertEquals("{damaged", preferences.raw)
        assertEquals(0, preferences.writes)
        assertNotNull(LibraryStore(preferences).state.value.storageError)
        assertEquals("{damaged", preferences.raw)
        preferences.raw = LibraryCodec.encode(LibrarySnapshot())
        store.reload()
        assertNull(store.state.value.storageError)
        store.toggleFavorite(card)
        assertEquals("1", LibraryStore(preferences).state.value.favorites.single().card.id)
        assertEquals(1, preferences.writes)
    }

    @Test fun failedReloadPreservesTheLastSuccessfullyReadLists() {
        val entry = LibraryEntry(DramaCard("1", "测试", "", 5, ""))
        val preferences = Preferences(LibraryCodec.encode(LibrarySnapshot(favorites = listOf(entry))))
        val store = LibraryStore(preferences)
        preferences.raw = "not-json"
        store.reload()
        assertEquals(listOf(entry), store.state.value.favorites)
        assertNotNull(store.state.value.storageError)
        assertEquals("not-json", preferences.raw)
    }

    @Test fun oldGuestAndTwoAccountsKeepSeparateWatchPositionsAndDeviceRemovalPersists() {
        val card = DramaCard("1", "测试", "", 12, "")
        val guest = LibraryEntry(card, "ep1", 1, 2500, 1)
        val preferences = Preferences(LibraryCodec.encode(LibrarySnapshot(history = listOf(guest))))
        val library = LibraryStore(preferences)
        val first = library.forHistoryOwner("a")
        val second = library.forHistoryOwner("b")
        first.recordPlayback(card, "ep8", 8, 120)
        second.recordPlayback(card, "ep2", 2, 380)
        assertEquals(listOf(guest), library.state.value.history)
        val mine = LibraryCodec.mergedHistory(library.state.value, first.state.value)
        assertEquals("ep8", mine.single().episodeId)
        first.deleteHistory(mine.single())
        val reopened = LibraryStore(preferences)
        assertTrue(LibraryCodec.mergedHistory(reopened.state.value, reopened.forHistoryOwner("a").state.value, mine).isEmpty())
        assertEquals("ep2", LibraryCodec.mergedHistory(reopened.state.value, reopened.forHistoryOwner("b").state.value).single().episodeId)
        assertEquals(listOf(guest), reopened.forHistoryOwner(null).state.value.history)
    }
}
