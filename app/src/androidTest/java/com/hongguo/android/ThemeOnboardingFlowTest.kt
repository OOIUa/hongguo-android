package com.hongguo.android

import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeOnboardingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var preferences: SharedPreferences
    private var savedStyle: String? = null
    private var savedFollow = false

    @Before fun resetOnboarding() {
        preferences = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            .getSharedPreferences(ThemePreferences.FILE, 0)
        savedStyle = preferences.getString(ThemePreferences.STYLE, null)
        savedFollow = preferences.getBoolean(ThemePreferences.DARK_FOLLOWS_SYSTEM, false)
        preferences.edit().remove(ThemePreferences.STYLE).putBoolean(ThemePreferences.DARK_FOLLOWS_SYSTEM, false)
            .putBoolean(ThemePreferences.ONBOARDING_DONE, false).remove(ThemePreferences.ONBOARDING_VERSION).commit()
    }

    @After fun restorePreferences() {
        preferences.edit().apply {
            if (savedStyle == null) remove(ThemePreferences.STYLE) else putString(ThemePreferences.STYLE, savedStyle)
            putBoolean(ThemePreferences.DARK_FOLLOWS_SYSTEM, savedFollow)
            putBoolean(ThemePreferences.ONBOARDING_DONE, true)
            putInt(ThemePreferences.ONBOARDING_VERSION, ThemePreferences.CURRENT_ONBOARDING_VERSION)
        }.commit()
    }

    private fun awaitPicker() = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag("theme_onboarding")).fetchSemanticsNodes().isNotEmpty()
    }

    private fun model(scenario: ActivityScenario<MainActivity>): HongguoViewModel {
        lateinit var model: HongguoViewModel
        scenario.onActivity { model = ViewModelProvider(it)[HongguoViewModel::class.java] }
        return model
    }

    @Test fun choosingAThemeSavesItConfirmsAndNeverAsksAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPicker()
            compose.onNodeWithTag("theme_option_warm").assertIsSelected()
            // Previewing does not save anything yet.
            compose.onNodeWithTag("theme_option_immersive").performClick()
            compose.onNodeWithTag("theme_option_immersive").assertIsSelected()
            assertEquals(AppThemeStyle.WARM, model(scenario).state.value.themeStyle)
            assertFalse(preferences.getBoolean(ThemePreferences.ONBOARDING_DONE, true))

            compose.onNodeWithTag("theme_onboarding_confirm").performScrollTo().performClick()
            compose.onNodeWithTag("theme_onboarding").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(themeAppliedMessage(AppThemeStyle.IMMERSIVE, chosen = true)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val state = model(scenario).state.value
            assertEquals(AppThemeStyle.IMMERSIVE, state.themeStyle)
            assertFalse(state.themeOnboardingPending)
            assertEquals("immersive", preferences.getString(ThemePreferences.STYLE, null))
            assertTrue(preferences.getBoolean(ThemePreferences.ONBOARDING_DONE, false))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav_home").assertExists()
            compose.onNodeWithTag("theme_onboarding").assertDoesNotExist()
        }
    }

    @Test fun answerFrom073IsAskedAgainOnceWithItsChoicePreselected() {
        preferences.edit().putBoolean(ThemePreferences.ONBOARDING_DONE, true).putString(ThemePreferences.STYLE, "cards")
            .remove(ThemePreferences.ONBOARDING_VERSION).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitPicker()
            compose.onNodeWithTag("theme_option_cards").assertIsSelected()
            compose.onNodeWithTag("theme_onboarding_confirm").performScrollTo().performClick()
            compose.onNodeWithTag("theme_onboarding").assertDoesNotExist()
            assertEquals(ThemePreferences.CURRENT_ONBOARDING_VERSION, preferences.getInt(ThemePreferences.ONBOARDING_VERSION, 0))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("nav_home").assertExists()
            compose.onNodeWithTag("theme_onboarding").assertDoesNotExist()
        }
    }

    @Test fun backPressKeepsTheDefaultAndStillPointsToSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPicker()
            compose.onNodeWithTag("theme_option_cards").performClick()
            pressBack()
            compose.onNodeWithTag("theme_onboarding").assertDoesNotExist()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(themeAppliedMessage(AppThemeStyle.WARM, chosen = false)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(AppThemeStyle.WARM, model(scenario).state.value.themeStyle)
            assertTrue(preferences.getBoolean(ThemePreferences.ONBOARDING_DONE, false))
        }
    }
}
