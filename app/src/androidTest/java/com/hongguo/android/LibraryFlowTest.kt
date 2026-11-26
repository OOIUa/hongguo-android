package com.hongguo.android

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.account.AccountViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localListsPersistAndClearIndependentlyThroughUi() {
        val context = compose.activity.applicationContext
        val preferences = context.getSharedPreferences("hongguo_library", Context.MODE_PRIVATE)
        val original = preferences.getString("snapshot", null)
        val store = LibraryStore(context)
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        val card = DramaCard("18446744073709551615", "本地数据验证", "", 10, "")
        val second = DramaCard("18446744073709551614", "收藏删除验证", "", 8, "")
        try {
            store.clearFavorites(); store.clearHistory()
            store.recordPlayback(card, "18446744073709551613", 3, 12_345)
            store.toggleFavorite(card); store.toggleFavorite(second)
            // Force pending preference changes to disk before constructing another store.
            assertTrue(preferences.edit().commit())
            val reloaded = LibraryStore(context).state.value
            assertEquals(2, reloaded.favorites.size)
            assertEquals(card.id, reloaded.history.single().card.id)
            assertEquals(12_345L, reloaded.history.single().positionMs)
            compose.onNodeWithTag("nav_favorites").performClick()
            compose.waitUntil(5_000) { model.state.value.favorites.size == 2 }
            compose.onNodeWithTag("favorite_item_0").assertExists()
            compose.onNodeWithTag("library_manage").performClick()
            compose.onNodeWithTag("favorite_remove_0").performClick()
            compose.waitUntil(5_000) { model.state.value.favorites.size == 1 }
            assertEquals(1, model.state.value.history.size)
            compose.onNodeWithTag("clear_favorites").performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals(1, model.state.value.favorites.size)
            compose.onNodeWithTag("clear_favorites").performClick()
            compose.onNodeWithTag("confirm_clear_favorites").performClick()
            compose.waitUntil(5_000) { model.state.value.favorites.isEmpty() }
            assertEquals(1, model.state.value.history.size)
            compose.onNodeWithTag("nav_mine").performClick()
            compose.onNodeWithTag("my_history").performScrollTo().performClick()
            compose.onNodeWithTag("history_item_0").assertExists()
            compose.onNodeWithTag("library_manage").performClick()
            compose.onNodeWithTag("history_remove_0").performClick()
            compose.waitUntil(5_000) { model.state.value.history.isEmpty() }
            store.recordPlayback(card, "18446744073709551613", 3, 12_345)
            store.toggleFavorite(card)
            compose.waitUntil(5_000) { model.state.value.history.size == 1 }
            compose.onNodeWithTag("clear_history").performClick()
            compose.onNodeWithTag("confirm_clear_history").performClick()
            compose.waitUntil(5_000) { model.state.value.history.isEmpty() }
            assertEquals(1, model.state.value.favorites.size)
            store.updatePosition(card, "18446744073709551613", 3, 14_000)
            assertTrue(store.state.value.history.isEmpty())
            assertEquals(14_000L, store.state.value.favorites.single().positionMs)
        } finally {
            val editor = preferences.edit()
            if (original == null) editor.remove("snapshot") else editor.putString("snapshot", original)
            assertTrue(editor.commit())
            LibraryStore(context) // Synchronize shared observable state with the restored disk snapshot.
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ChaseFavoritesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun chaseWallDeduplicatesAcrossSourcesAndShowsOnlyTheCurrentAccountsFavorites() {
        val shared = LibraryEntry(DramaCard("601", "两边收藏", "", 8, ""), updatedAt = 10)
        val deviceOnly = LibraryEntry(DramaCard("602", "本机独有", "", 12, ""), updatedAt = 20)
        val accountOnly = LibraryEntry(DramaCard("603", "账号独有", "", 6, ""), updatedAt = 40)
        val secondOnly = LibraryEntry(DramaCard("604", "第二个账号的收藏", "", 4, ""), updatedAt = 50)
        val local = listOf(deviceOnly, shared)
        val accounts = mapOf("101" to listOf(accountOnly, shared.copy(updatedAt = 30)), "102" to listOf(secondOnly))
        val owner = mutableStateOf<String?>("101")
        lateinit var model: HongguoViewModel
        lateinit var account: AccountViewModel
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            model = provider[HongguoViewModel::class.java]
            account = provider[AccountViewModel::class.java]
        }
        compose.setContent {
            val activeOwner = owner.value
            val cloud = activeOwner?.let { accounts.getValue(it) }.orEmpty()
            val state = HongguoUiState(tab = AppTab.FAVORITES, favorites = local, historyOwnerId = activeOwner,
                chaseFavorites = LibraryCodec.mergedFavorites(local, cloud))
            HongguoTheme(applyToWindow = false) { CinemaChaseScreen(state, model, account) }
        }
        compose.onNodeWithText("全部在追（3）").assertExists()
        compose.onAllNodesWithText("两边收藏").assertCountEquals(1)
        compose.onNodeWithText("本机独有").assertExists()
        compose.onNodeWithText("账号独有").assertExists()
        compose.onNodeWithText("第二个账号的收藏").assertDoesNotExist()

        compose.onNodeWithTag("favorite_item_1").assertTextContains("两边收藏")
        compose.onNodeWithTag("library_manage").performClick()
        compose.onNodeWithTag("favorite_remove_1").performClick()
        compose.onNodeWithTag("chase_remove_local").assertExists()
        compose.onNodeWithTag("account_remove_favorite_confirm").assertExists()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("chase_remove_local").assertDoesNotExist()
        compose.onNodeWithTag("clear_favorites").performClick()
        compose.onNodeWithText("清空本机收藏？").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("全部在追（3）").assertExists()

        compose.runOnIdle { owner.value = "102" }
        compose.onNodeWithText("全部在追（3）").assertExists()
        compose.onNodeWithText("第二个账号的收藏").assertExists()
        compose.onNodeWithText("账号独有").assertDoesNotExist()

        compose.runOnIdle { owner.value = null }
        compose.onNodeWithText("全部在追（2）").assertExists()
        compose.onNodeWithText("本机独有").assertExists()
        compose.onNodeWithText("两边收藏").assertExists()
        compose.onNodeWithText("第二个账号的收藏").assertDoesNotExist()
        compose.onNodeWithText("账号独有").assertDoesNotExist()
    }
}
