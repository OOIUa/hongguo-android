package com.hongguo.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public production constructor only; no remote response or APK download is needed. */
@RunWith(AndroidJUnit4::class)
class StartupUpdateWiringTest {
    @Test fun publicFactoryStartsOnceDespiteRecentAttemptAndRespectsTheDisabledPreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val hadAutomatic = preferences.contains("automatic_checks")
        val originalAutomatic = preferences.getBoolean("automatic_checks", true)
        val hadSource = preferences.contains("source")
        val originalSource = preferences.getString("source", null)
        val hadAttempt = preferences.contains("last_attempt")
        val originalAttempt = preferences.getLong("last_attempt", 0)
        val stores = mutableListOf<ViewModelStore>()
        try {
            assertTrue(preferences.edit().putBoolean("automatic_checks", true)
                .putString("source", UpdateSource.DIRECT.name)
                .putLong("last_attempt", System.currentTimeMillis()).commit())
            instrumentation.runOnMainSync {
                val store = ViewModelStore().also(stores::add)
                val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                val model = provider[UpdateViewModel::class.java]
                assertTrue("A recent attempt must not suppress a new cold-start request", model.state.value.checking)
                assertSame(model, provider[UpdateViewModel::class.java])
                store.clear()
            }

            assertTrue(preferences.edit().putBoolean("automatic_checks", false).commit())
            instrumentation.runOnMainSync {
                val store = ViewModelStore().also(stores::add)
                val provider = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))
                val model = provider[UpdateViewModel::class.java]
                assertFalse("The persisted switch must disable the startup request", model.state.value.checking)
                model.checkForUpdates()
                assertTrue(model.state.value.checking)
                assertTrue("Explicit checking remains visible while automatic checking is disabled", model.state.value.visible)
                store.clear()
            }
        } finally {
            instrumentation.runOnMainSync { stores.forEach { it.clear() } }
            val restore = preferences.edit()
            if (hadAutomatic) restore.putBoolean("automatic_checks", originalAutomatic) else restore.remove("automatic_checks")
            if (hadSource) restore.putString("source", originalSource) else restore.remove("source")
            if (hadAttempt) restore.putLong("last_attempt", originalAttempt) else restore.remove("last_attempt")
            assertTrue("Restore only the update settings touched by this test", restore.commit())
        }
    }
}
