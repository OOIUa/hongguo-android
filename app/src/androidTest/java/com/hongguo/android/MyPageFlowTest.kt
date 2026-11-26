package com.hongguo.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.account.AccountSection
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
            compose.onNodeWithText("收藏").assertDoesNotExist()
            compose.onNodeWithText("看过").assertDoesNotExist()
            compose.onNodeWithTag("my_favorites").assertTextContains("本机收藏")
                .assertTextContains("${model.state.value.favorites.size} 部").performScrollTo().performClick()
            assertEquals(AppTab.LOCAL_FAVORITES, model.state.value.tab)
            pressBack()
            compose.waitUntil(5_000) { model.state.value.tab == AppTab.MINE }
            compose.onNodeWithTag("my_history").assertTextContains("观看历史")
                .assertTextContains("${model.state.value.history.size} 部").performScrollTo().performClick()
            assertEquals(AppTab.PLAY_HISTORY, model.state.value.tab)
            compose.onNodeWithTag("play_history_screen").assertExists()
            compose.onNodeWithTag("play_history_local").assertDoesNotExist()
            compose.onNodeWithTag("play_history_account_tab").assertDoesNotExist()
            pressBack()
            compose.waitUntil(5_000) { model.state.value.tab == AppTab.MINE }
            compose.onNodeWithTag("my_settings").performScrollTo().performClick()
            compose.onNodeWithTag("settings_account").performScrollTo().performClick()
            compose.onNodeWithTag("account_settings_screen").assertExists()
            compose.onNodeWithTag("account_sync_switch").assertExists()
            compose.onNodeWithTag("account_settings_open_account").performScrollTo().performClick()
            compose.onNodeWithTag("account_screen").assertExists()
            compose.onNodeWithTag("account_back").performClick()
            compose.onNodeWithTag("account_settings_screen").assertExists()
            compose.onNodeWithTag("account_settings_back").performClick()
            compose.onNodeWithTag("settings_playback").performScrollTo().performClick()
            compose.onNodeWithTag("settings_autoplay").performScrollTo().performClick()
            compose.onNodeWithTag("settings_preload").performScrollTo().performClick()
            compose.onNodeWithTag("playback_back").performScrollTo().performClick()
            compose.onNodeWithTag("settings_back").performScrollTo().performClick()
            compose.onNodeWithTag("my_about").performScrollTo().performClick()
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

@RunWith(AndroidJUnit4::class)
class MyPageShortcutsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unifiedHistoryShowsCompletedEpisodesAndDismissesClearWhenOwnerChanges() {
        val card = DramaCard("800", "重逢", "", 6, "")
        val entry = LibraryEntry(card, episodeId = "83", episodeIndex = 3, completed = true)
        val history = mutableStateOf(HongguoUiState(tab = AppTab.PLAY_HISTORY, historyOwnerId = "100",
            history = listOf(entry, entry.copy(card = card.copy(id = "900", title = "归途"), episodeIndex = 6))))
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        compose.setContent { HongguoTheme(applyToWindow = false) { PlayHistoryScreen(history.value, model) } }

        compose.onNodeWithTag("history_item_0").assertTextContains("接着看第 4 集")
        compose.onNodeWithTag("history_item_1").assertTextContains("已看完")
        compose.onNodeWithTag("library_manage").performClick()
        compose.onNodeWithText("从本设备移除").assertExists()
        compose.onNodeWithTag("clear_history").performClick()
        compose.onNodeWithTag("confirm_clear_history").assertExists()
        compose.runOnIdle { history.value = history.value.copy(historyOwnerId = "200", history = emptyList()) }
        compose.onNodeWithTag("confirm_clear_history").assertDoesNotExist()
        compose.onNodeWithText("还没有观看记录").assertExists()
    }

    @Test fun accountCardAndReservationShortcutOpenTheSelectedAccountSection() {
        val signedIn = mutableStateOf(false)
        val opened = mutableListOf<AccountSection>()
        lateinit var model: HongguoViewModel
        lateinit var updater: UpdateViewModel
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            model = provider[HongguoViewModel::class.java]
            updater = provider[UpdateViewModel::class.java]
        }
        compose.setContent {
            HongguoTheme(applyToWindow = false) {
                MyScreen(HongguoUiState(tab = AppTab.MINE), model, UpdateUiState(), updater,
                    accountSignedIn = signedIn.value, onOpenAccountSection = { opened.add(it) })
            }
        }
        compose.onNodeWithTag("my_account_card").assertExists()
        compose.onNodeWithText("云片单").assertDoesNotExist()
        compose.onNodeWithText("我的预约").assertDoesNotExist()

        compose.runOnIdle { signedIn.value = true }
        compose.onNodeWithTag("my_reservations").assertTextContains("我的预约").performScrollTo().performClick()
        compose.onNodeWithTag("my_cloud_library").assertDoesNotExist()
        compose.onNodeWithTag("my_account_card").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(AccountSection.RESERVATIONS, AccountSection.FAVORITES), opened) }
    }
}
