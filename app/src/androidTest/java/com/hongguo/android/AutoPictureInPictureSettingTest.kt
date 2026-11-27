package com.hongguo.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoPictureInPictureSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingPreferenceDefaultsOffAndBothChoicesSurviveReopening() {
        val app = compose.activity.application
        val preferences = app.getSharedPreferences(ThemePreferences.FILE, 0)
        val key = "auto_picture_in_picture"
        val previous = if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        fun assertRestored(expected: Boolean) = compose.runOnIdle {
            val store = ViewModelStore()
            try {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[HongguoViewModel::class.java]
                assertEquals(expected, model.state.value.autoPictureInPicture)
            } finally { store.clear() }
        }

        try {
            preferences.edit().remove(key).apply()
            val model = compose.runOnIdle { ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
            compose.setContent {
                HongguoTheme(applyToWindow = false) {
                    PlaybackSettingsScreen(model.state.collectAsState().value, model, onBack = {})
                }
            }
            compose.onNodeWithText("返回桌面时自动进入画中画").performScrollTo().assertExists()
            val toggle = compose.onNodeWithTag("settings_auto_pip")
            toggle.performScrollTo().assertIsOff().performClick().assertIsOn()
            assertRestored(true)
            toggle.performClick().assertIsOff()
            assertRestored(false)
        } finally {
            val edit = preferences.edit()
            if (previous == null) edit.remove(key) else edit.putBoolean(key, previous)
            edit.apply()
        }
    }
}
