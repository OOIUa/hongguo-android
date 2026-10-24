package com.hongguo.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        compose.onNodeWithTag("nav_search").assertDoesNotExist()
        compose.onNodeWithTag("nav_category").performClick()
        compose.onNodeWithTag("category_search").performClick()
        compose.onNodeWithTag("search_input").assertExists()
        compose.onNodeWithTag("nav_home").assertDoesNotExist()
        compose.runOnIdle { model.selectTab(AppTab.ACCOUNT) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AppTab.SEARCH, model.state.value.tab) }
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.CATEGORY, model.state.value.tab) }
        compose.onNodeWithTag("nav_favorites").performClick()
        compose.onNodeWithTag("library_source_account").performClick()
        compose.runOnIdle { model.selectTab(AppTab.ACCOUNT) }
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AppTab.HISTORY, model.state.value.tab)
            assertTrue("Account return must keep the selected library source", model.state.value.libraryCloud)
        }
        compose.onNodeWithTag("cloud_library").assertExists()
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.CATEGORY, model.state.value.tab) }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("home_search").performClick()
        pressBack()
        compose.runOnIdle { assertEquals(AppTab.HOME, model.state.value.tab) }
    }
}
