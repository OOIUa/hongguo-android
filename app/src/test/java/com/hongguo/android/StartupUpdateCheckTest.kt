package com.hongguo.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercise the real update model with offline manifests and isolated preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateCheckTest {
    private val main = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val installedVersion = 11L

    @Before fun useTestMain() { Dispatchers.setMain(main) }
    @After fun clearModels() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    private class TestApplication : Application() {
        override fun getApplicationContext(): Context = this
    }

    private fun update(version: Long = 12L, sdk: Int = 26) = AppUpdate(
        version, "fixture-$version", "https://github.com/OOIUa/hongguo-android/releases/download/vfixture/app.apk",
        "a".repeat(64), 4096, sdk, "合成更新说明",
    )

    private fun provider(
        preferences: SharedPreferences,
        check: suspend (UpdateSource, (UpdateSource) -> Unit) -> AppUpdate,
    ): ViewModelProvider {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(TestApplication(), preferences, check, installedVersion, 36) as T
        }
        return ViewModelProvider(store, factory)
    }

    private fun model(preferences: SharedPreferences = MemoryPreferences(),
                      check: suspend (UpdateSource, (UpdateSource) -> Unit) -> AppUpdate): UpdateViewModel =
        provider(preferences, check)[UpdateViewModel::class.java]

    @Test fun everyColdStartCanFindAReleasePublishedAfterAnEarlierCheckOnTheSameDay() = runTest {
        val preferences = MemoryPreferences()
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        var calls = 0
        val first = model(preferences) { _, _ -> calls++; update(installedVersion) }
        runCurrent()
        assertEquals(1, calls)
        assertTrue(first.state.value.automaticChecksEnabled)
        assertFalse(first.state.value.visible)

        val nextColdStart = model(preferences) { _, _ -> calls++; update() }
        runCurrent()
        assertEquals(2, calls)
        assertTrue(nextColdStart.state.value.visible)
        assertEquals(12L, nextColdStart.state.value.update!!.versionCode)
    }

    @Test fun aRetainedModelAndForegroundCallbacksDoNotRepeatTheStartupRequest() = runTest {
        var calls = 0
        val provider = provider(MemoryPreferences()) { _, _ -> calls++; update(installedVersion) }
        val retained = provider[UpdateViewModel::class.java]
        runCurrent()
        repeat(3) {
            assertSame(retained, provider[UpdateViewModel::class.java])
            retained.onResume(Activity())
        }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(retained.state.value.visible)
    }

    @Test fun anExplicitDisabledPreferenceBlocksOnlyAutomaticChecksAndPersistsAcrossColdStarts() = runTest {
        val preferences = MemoryPreferences()
        preferences.edit().putBoolean("automatic_checks", false).apply()
        var calls = 0
        val first = model(preferences) { _, _ -> calls++; update() }
        val second = model(preferences) { _, _ -> calls++; update() }
        runCurrent()
        assertEquals(0, calls)
        assertFalse(first.state.value.automaticChecksEnabled)
        assertFalse(second.state.value.checking)

        first.checkForUpdates()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(first.state.value.visible)
        assertNotNull(first.state.value.update)
    }

    @Test fun enablingThePreferenceAppliesToTheNextColdStartWithoutCreatingAnExtraCurrentRequest() = runTest {
        val preferences = MemoryPreferences()
        preferences.edit().putBoolean("automatic_checks", false).apply()
        var calls = 0
        val first = model(preferences) { _, _ -> calls++; update(installedVersion) }
        first.setAutomaticChecksEnabled(true)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(first.state.value.automaticChecksEnabled)

        model(preferences) { _, _ -> calls++; update() }
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun manualTapsPromoteTheInFlightStartupCheckWithoutCancellationOrDuplicateRequests() = runTest {
        val response = CompletableDeferred<AppUpdate>()
        var calls = 0
        var cancellations = 0
        val model = model { _, onSource ->
            calls++
            onSource(UpdateSource.DIRECT)
            try { response.await() }
            catch (cancelled: CancellationException) { cancellations++; throw cancelled }
        }
        runCurrent()
        assertTrue(model.state.value.checking)
        assertFalse(model.state.value.visible)
        repeat(3) { model.checkForUpdates() }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(0, cancellations)
        assertTrue(model.state.value.visible)

        // No newer version must still produce a visible result after the user's manual tap.
        response.complete(update(installedVersion))
        runCurrent()
        assertFalse(model.state.value.checking)
        assertTrue(model.state.value.visible)
        assertNull(model.state.value.update)
        assertEquals(UpdateSource.DIRECT, model.state.value.activeSource)
    }

    @Test fun backgroundFailuresAndUnsupportedReleasesStayQuietButManualFailuresRemainVisible() = runTest {
        var calls = 0
        val failure = model { _, _ -> calls++; throw IOException("UPDATE_CHECK_NETWORK_FAILED") }
        runCurrent()
        assertEquals(1, calls)
        assertFalse(failure.state.value.visible)
        assertFalse(failure.state.value.checking)
        assertNotNull(failure.state.value.error)

        failure.checkForUpdates()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(failure.state.value.visible)
        assertNotNull(failure.state.value.error)

        val unsupported = model { _, _ -> update(sdk = 99) }
        runCurrent()
        assertFalse(unsupported.state.value.visible)
        assertNull(unsupported.state.value.update)
    }

    @Test fun disablingAutomaticChecksCancelsOnlyASilentRequestAndManualCheckingStillWorks() = runTest {
        val firstResponse = CompletableDeferred<AppUpdate>()
        var calls = 0
        var cancellations = 0
        val model = model { _, _ ->
            calls++
            if (calls == 1) {
                try { firstResponse.await() }
                catch (cancelled: CancellationException) { cancellations++; throw cancelled }
            } else update()
        }
        runCurrent()
        model.setAutomaticChecksEnabled(false)
        runCurrent()
        assertEquals(1, cancellations)
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.checking)
        firstResponse.complete(update())
        runCurrent()
        assertNull(model.state.value.update)

        model.checkForUpdates()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(model.state.value.visible)
        assertEquals(12L, model.state.value.update!!.versionCode)
    }

    @Test fun disablingTheAutomaticPreferenceDoesNotCancelARequestAlreadyPromotedByTheUser() = runTest {
        val response = CompletableDeferred<AppUpdate>()
        var calls = 0
        val model = model { _, _ -> calls++; response.await() }
        runCurrent()
        model.checkForUpdates()
        model.setAutomaticChecksEnabled(false)
        response.complete(update())
        runCurrent()
        assertEquals(1, calls)
        assertFalse(model.state.value.automaticChecksEnabled)
        assertTrue(model.state.value.visible)
        assertEquals(12L, model.state.value.update!!.versionCode)
    }

    @Test fun aSourceChangeKeepsItsOwnResultWhenAnOldCancelledSourceReturnsLate() = runTest {
        val preferences = MemoryPreferences()
        val oldResponse = CompletableDeferred<AppUpdate>()
        val newResponse = CompletableDeferred<AppUpdate>()
        var calls = 0
        val model = model(preferences) { source, onSource ->
            calls++
            if (source == UpdateSource.AUTO) withContext(NonCancellable) {
                val result = oldResponse.await()
                onSource(UpdateSource.DIRECT)
                result
            } else {
                onSource(source)
                newResponse.await()
            }
        }
        runCurrent()
        model.selectSource(UpdateSource.PROXY_PRIMARY)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(UpdateSource.PROXY_PRIMARY.name, preferences.getString("source", null))
        newResponse.complete(update(14))
        runCurrent()
        oldResponse.complete(update(12))
        runCurrent()
        assertEquals(14L, model.state.value.update!!.versionCode)
        assertEquals(UpdateSource.PROXY_PRIMARY, model.state.value.activeSource)
        assertTrue(model.state.value.visible)
    }

    /** In-memory Android preferences; no app settings, timestamps or files are changed. */
    private class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any>()
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearFirst = false
            private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }
            override fun commit(): Boolean {
                if (clearFirst) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
