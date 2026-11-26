package com.hongguo.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.account.AccountSection
import com.hongguo.account.AccountViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullScreenSearchAndAccountReturnToTheirOriginalTabs() {
        lateinit var model: HongguoViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        val isImmersive = model.state.value.themeStyle == AppThemeStyle.IMMERSIVE
        val categorySearch = if (isImmersive) "theater_search" else "category_search"
        val homeSearch = if (isImmersive) "immersive_search" else "home_search"
        compose.onNodeWithTag("nav_search").assertDoesNotExist()
        compose.onNodeWithTag("nav_category").performClick()
        compose.onNodeWithTag(categorySearch).performClick()
        compose.onNodeWithTag("search_input").assertExists()
        compose.onNodeWithTag("nav_home").assertDoesNotExist()
        compose.runOnIdle { model.selectTab(AppTab.ACCOUNT) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppTab.SEARCH, model.state.value.tab) }
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.CATEGORY, model.state.value.tab) }
        compose.onNodeWithTag("nav_favorites").performClick()
        compose.onNodeWithTag("favorites_list").assertExists()
        compose.runOnIdle { model.selectTab(AppTab.ACCOUNT) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.onNodeWithTag("favorites_list").assertExists()
        compose.onNodeWithText("云片单").assertDoesNotExist()
        compose.onNodeWithTag("library_history").performClick()
        compose.onNodeWithTag("history_list").assertExists()
        compose.onNodeWithTag("cloud_library").assertDoesNotExist()
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.CATEGORY, model.state.value.tab) }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag(homeSearch).performClick()
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.HOME, model.state.value.tab) }
    }

    @Test fun bottomChaseOpensFavoritesOnReselectAndOnReturnForBothNavStyles() {
        val model = compose.runOnIdle { ViewModelProvider(compose.activity)[HongguoViewModel::class.java] }
        val original = model.state.value.navBarStyle
        try {
            for (style in listOf(NavBarStyle.CLASSIC, NavBarStyle.GLASS)) {
                compose.runOnIdle { model.setNavBarStyle(style) }
                compose.onNodeWithTag("nav_mine").performClick()
                compose.onNodeWithTag("nav_favorites").performClick()
                compose.onNodeWithTag("favorites_list").assertExists()
                compose.onNodeWithTag("library_history").performClick()
                compose.onNodeWithTag("history_list").assertExists()
                compose.onNodeWithTag("nav_favorites").performClick()
                compose.onNodeWithTag("favorites_list").assertExists()
                compose.onNodeWithTag("library_history").performClick()
                compose.onNodeWithTag("nav_mine").performClick()
                compose.onNodeWithTag("nav_favorites").performClick()
                compose.onNodeWithTag("favorites_list").assertExists()
            }
        } finally {
            compose.runOnIdle { model.setNavBarStyle(original) }
        }
    }

    @Test fun accountSectionsUseOnePageAndReturnToMine() {
        lateinit var model: HongguoViewModel
        lateinit var account: AccountViewModel
        compose.runOnIdle {
            val provider = ViewModelProvider(compose.activity)
            model = provider[HongguoViewModel::class.java]
            account = provider[AccountViewModel::class.java]
        }
        compose.onNodeWithTag("nav_mine").performClick()
        compose.runOnIdle { model.openAccountSection(AccountSection.RESERVATIONS) }
        compose.onNodeWithTag("account_screen").assertExists()
        compose.onNodeWithTag("cloud_library").assertDoesNotExist()
        compose.onNodeWithTag("nav_favorites").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(AppTab.ACCOUNT, model.state.value.tab)
            assertEquals(AccountSection.RESERVATIONS, account.state.value.selectedSection)
        }
        pressBack()
        compose.onNodeWithTag("my_screen").assertExists()
        compose.onNodeWithTag("my_account_card").performScrollTo().performClick()
        compose.onNodeWithTag("account_screen").assertExists()
        compose.runOnIdle { assertEquals(AccountSection.FAVORITES, account.state.value.selectedSection) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.onNodeWithTag("my_screen").assertExists()
    }
}
