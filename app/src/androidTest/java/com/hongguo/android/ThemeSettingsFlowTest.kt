package com.hongguo.android

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeSettingsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsEntryOpensThemePickerAndChoicePersists() {
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        val originalStyle = model.state.value.themeStyle
        val originalFollow = model.state.value.darkFollowsSystem
        try {
            compose.runOnIdle { model.setThemeStyle(AppThemeStyle.WARM) }
            compose.onNodeWithTag("nav_mine").performClick()
            compose.onNodeWithTag("my_settings_icon").performClick()
            compose.onNodeWithTag("settings_screen").assertExists()
            compose.onNodeWithTag("settings_theme").performScrollTo().performClick()
            compose.onNodeWithTag("theme_screen").assertExists()
            compose.onNodeWithTag("theme_option_warm").assertIsSelected()

            compose.onNodeWithTag("theme_option_immersive").performClick()
            compose.runOnIdle { assertEquals(AppThemeStyle.IMMERSIVE, model.state.value.themeStyle) }
            compose.onNodeWithTag("theme_option_immersive").assertIsSelected()
            compose.onNodeWithTag("theme_option_warm").assertIsNotSelected()

            compose.onNodeWithTag("theme_option_cards").performClick()
            compose.onNodeWithTag("settings_theme_dark_follow").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(AppThemeStyle.CARDS, model.state.value.themeStyle)
                assertEquals(!originalFollow, model.state.value.darkFollowsSystem)
            }

            val app = compose.activity.application
            assertTrue(app.getSharedPreferences("hongguo_local", 0).edit().commit())
            val store = ViewModelStore()
            try {
                compose.runOnIdle {
                    val fresh = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[HongguoViewModel::class.java]
                    assertEquals(AppThemeStyle.CARDS, fresh.state.value.themeStyle)
                    assertEquals(!originalFollow, fresh.state.value.darkFollowsSystem)
                }
            } finally { compose.runOnIdle { store.clear() } }

            pressBack()
            compose.onNodeWithTag("settings_screen").assertExists()
            compose.onNodeWithTag("settings_back").performScrollTo().performClick()
            compose.onNodeWithTag("my_screen").assertExists()
        } finally {
            compose.runOnIdle {
                model.setThemeStyle(originalStyle)
                model.setDarkFollowsSystem(originalFollow)
            }
        }
    }
}
