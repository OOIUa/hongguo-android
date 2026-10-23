package com.hongguo.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.Locale

/** Live public-manifest requests only: no APK downloads, installer launches, or permission changes. */
@RunWith(AndroidJUnit4::class)
class UpdateSourceUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var preferences: SharedPreferences

    // Snapshot before Activity creation, so its normal startup check cannot change the baseline.
    private val preferenceGuard = object : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
                val hadSource = preferences.contains("source")
                val originalSource = preferences.getString("source", null)
                val hadAttempt = preferences.contains("last_attempt")
                val originalAttempt = preferences.getLong("last_attempt", 0)
                try {
                    assertTrue(preferences.edit().putString("source", UpdateSource.AUTO.name)
                        .putLong("last_attempt", System.currentTimeMillis()).commit())
                    base.evaluate()
                } finally {
                    val restore = preferences.edit()
                    if (hadSource) restore.putString("source", originalSource) else restore.remove("source")
                    if (hadAttempt) restore.putLong("last_attempt", originalAttempt) else restore.remove("last_attempt")
                    assertTrue("Restore update preferences after Activity/model teardown", restore.commit())
                }
            }
        }
    }

    @get:Rule val rules: TestRule = RuleChain.outerRule(preferenceGuard).around(compose)

    @Test fun proxySelectionPersistsAndBothPublicManifestRoutesWork() {
        lateinit var model: UpdateViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[UpdateViewModel::class.java] }
        try {
            compose.onNodeWithTag("nav_mine").performClick()
            compose.onNodeWithTag("update_check").performScrollTo().performClick()
            compose.onNodeWithTag("update_dialog").assertExists()

            selectAndVerify(model, UpdateSource.PROXY_PRIMARY, checkLiveManifest = true)
            selectAndVerify(model, UpdateSource.PROXY_BACKUP, checkLiveManifest = true)
            // AUTO preference is checked through UI and a fresh instance; do not start another long network run.
            selectAndVerify(model, UpdateSource.AUTO, checkLiveManifest = false)
        } finally {
            compose.runOnIdle { model.dismiss() }
        }
    }

    private fun selectAndVerify(model: UpdateViewModel, source: UpdateSource, checkLiveManifest: Boolean) {
        val tag = "update_source_${source.name.lowercase(Locale.ROOT)}"
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag(tag).assertIsSelected()
        assertEquals(source, model.state.value.source)
        // commit waits for preceding apply writes, so this also exercises durable preference writes.
        assertTrue(preferences.edit().commit())
        assertEquals(source.name, preferences.getString("source", null))

        if (checkLiveManifest) {
            compose.waitUntil(30_000) {
                val state = model.state.value
                !state.checking && state.activeSource == source
            }
            val checked = model.state.value
            assertNull("${source.label} must return a valid public update manifest", checked.error)
            assertEquals(source, checked.activeSource)
            assertFalse(checked.checking)
            assertFalse(checked.downloading)
            assertNull(checked.downloadedFile)
            // update may be null when the installed test build is newer than the public release.
        }

        val restoredStore = ViewModelStore()
        try {
            compose.runOnIdle {
                val application = compose.activity.application as Application
                val restored = ViewModelProvider(restoredStore,
                    ViewModelProvider.AndroidViewModelFactory(application))[UpdateViewModel::class.java]
                assertNotSame(model, restored)
                assertEquals(source, restored.state.value.source)
                restored.dismiss()
            }
        } finally {
            compose.runOnIdle { restoredStore.clear() }
        }
        if (!checkLiveManifest) compose.runOnIdle { model.dismiss() }
    }

}
