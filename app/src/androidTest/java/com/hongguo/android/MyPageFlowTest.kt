package com.hongguo.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyPageFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun myPageOwnsHistoryUpdatesAndPersistentSettings() {
        lateinit var model: HongguoViewModel
        lateinit var updater: UpdateViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java]
            updater = ViewModelProvider(compose.activity)[UpdateViewModel::class.java]
        }
        val originalAutoplay = model.state.value.autoPlayNext
        val originalPreload = model.state.value.preloadNext
        val originalAutomatic = updater.state.value.automaticChecksEnabled
        try {
            compose.onNodeWithTag("update_check").assertDoesNotExist()
            compose.onNodeWithTag("nav_history").assertDoesNotExist()
            compose.onNodeWithTag("nav_mine").performClick()
            compose.onNodeWithTag("my_screen").assertExists()
            compose.onNodeWithTag("my_history").performScrollTo().performClick()
            assertEquals(AppTab.HISTORY, model.state.value.tab)
            pressBack()
            compose.waitUntil(5_000) { model.state.value.tab == AppTab.MINE }
            compose.onNodeWithTag("my_settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings_autoplay").performScrollTo().performClick()
            compose.onNodeWithTag("settings_preload").performScrollTo().performClick()
            compose.onNodeWithTag("settings_auto_update").performScrollTo().performClick()
            assertEquals(!originalAutoplay, model.state.value.autoPlayNext)
            assertEquals(!originalPreload, model.state.value.preloadNext)
            assertEquals(!originalAutomatic, updater.state.value.automaticChecksEnabled)
            val app = compose.activity.application
            assertTrue(app.getSharedPreferences("hongguo_local", 0).edit().commit())
            assertTrue(app.getSharedPreferences("app_updates", 0).edit().commit())
            val store = ViewModelStore()
            try {
                compose.runOnIdle {
                    val factory = ViewModelProvider.AndroidViewModelFactory(app)
                    val fresh = ViewModelProvider(store, factory)[HongguoViewModel::class.java]
                    val freshUpdater = ViewModelProvider(store, factory)[UpdateViewModel::class.java]
                    assertEquals(!originalAutoplay, fresh.state.value.autoPlayNext)
                    assertEquals(!originalPreload, fresh.state.value.preloadNext)
                    assertEquals(!originalAutomatic, freshUpdater.state.value.automaticChecksEnabled)
                }
            } finally { compose.runOnIdle { store.clear() } }
            compose.onNodeWithTag("settings_back").performScrollTo().performClick()
            compose.onNodeWithTag("update_check").performScrollTo().performClick()
            compose.onNodeWithTag("update_dialog").assertExists()
            compose.runOnIdle { updater.dismiss() }
        } finally {
            compose.runOnIdle {
                updater.dismiss()
                updater.setAutomaticChecksEnabled(originalAutomatic)
                model.setAutoPlayNext(originalAutoplay)
                model.setPreloadNext(originalPreload)
            }
        }
    }
}
