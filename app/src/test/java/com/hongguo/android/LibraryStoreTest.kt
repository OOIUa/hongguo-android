package com.hongguo.android

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class LibraryStoreTest {
    private class Preferences(var raw: String?) : SharedPreferences {
        var writes = 0
        override fun getString(key: String?, defValue: String?) = raw ?: defValue
        override fun getAll(): MutableMap<String, *> = mutableMapOf("snapshot" to raw)
        override fun contains(key: String?) = raw != null
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            var pending = raw
            override fun putString(key: String?, value: String?) = apply { pending = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putLong(key: String?, value: Long) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
            override fun remove(key: String?) = apply { pending = null }
            override fun clear() = apply { pending = null }
            override fun commit(): Boolean { raw = pending; writes++; return true }
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
}
