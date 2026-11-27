package com.hongguo.android

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountUiFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun rotatingAccountKeepsTheLoginDraftAndSelectedMethod() {
        fun rotate(orientation: Int, requested: Int) {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
            compose.waitUntil(8_000) { compose.activity.resources.configuration.orientation == orientation }
            compose.waitForIdle()
        }

        rotate(Configuration.ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[AccountViewModel::class.java].logout()
            ViewModelProvider(compose.activity)[HongguoViewModel::class.java].selectTab(AppTab.ACCOUNT)
        }
        compose.onNodeWithTag("account_wide_identity").assertDoesNotExist()
        val phone = compose.onNodeWithTag("account_phone")
        phone.performScrollTo().performTextInput("123")
        rotate(Configuration.ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        compose.onNodeWithTag("account_wide_identity").assertExists()
        phone.performScrollTo().assertTextContains("123")
        compose.onNodeWithTag("account_method_qr").performScrollTo().performClick()
        compose.onNodeWithTag("account_method_qr").assertIsSelected()
        rotate(Configuration.ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        compose.onNodeWithTag("account_wide_identity").assertDoesNotExist()
        compose.onNodeWithTag("account_method_qr").performScrollTo().assertIsSelected()
    }

    @Test fun accountEntryDoesNotMoveGuestRecordsOrSendAnInvalidPhone() {
        lateinit var model: AccountViewModel
        val context = compose.activity.application
        val library = LibraryStore(context)
        val before = library.state.value
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[AccountViewModel::class.java]; model.logout() }
        compose.onNodeWithTag("nav_mine").performClick()
        compose.onNodeWithTag("my_account_card").performScrollTo().performClick()
        compose.onNodeWithTag("account_screen").assertExists()
        compose.onNodeWithTag("account_method_sms").assertIsSelected()
        compose.onNodeWithTag("account_method_web").assertDoesNotExist()
        compose.onNodeWithTag("account_cookie_expand").assertDoesNotExist()
        compose.onNodeWithTag("account_method_qr").performScrollTo().performClick()
        compose.onNodeWithTag("account_qr_refresh").performScrollTo().assertExists()
        compose.onNodeWithTag("account_method_sms").performScrollTo().performClick()
        compose.onNodeWithTag("account_phone").performScrollTo().performTextInput("123")
        compose.onNodeWithTag("account_sms_send").performScrollTo().assertIsNotEnabled()
        assertEquals(AccountSessionStatus.SIGNED_OUT, model.state.value.sessionStatus)
        assertEquals(before, library.state.value)
        compose.onNodeWithTag("account_back").performScrollTo().performClick()
        compose.onNodeWithTag("my_screen").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
    }
}
