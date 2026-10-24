package com.hongguo.android

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguo.account.AccountDisk
import com.hongguo.account.AccountSessionStatus
import com.hongguo.account.AccountStore
import com.hongguo.account.AccountViewModel
import com.hongguo.account.protocol.AccountCredentials
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in a fresh instrumentation process so the application has not initialized its lazy session. */
@RunWith(AndroidJUnit4::class)
class AccountWebsiteSessionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedWebsiteSessionRestoresWithoutClaimingCloudLoginAndCanBeExplicitlyCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore(context)
        val previous = store.load()
        val guest = LibraryStore(context).state.value
        val website = AccountCredentials("sessionid=synthetic-website-only", "123456789", "987654321")
        try {
            store.save(AccountDisk(websiteCredentials = website))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: AccountViewModel
                scenario.onActivity { model = ViewModelProvider(it)[AccountViewModel::class.java] }
                compose.waitUntil(10_000) { model.state.value.sessionStatus == AccountSessionStatus.WEBSITE_ONLY }
                assertNull(model.ownerKey)
                compose.onNodeWithTag("nav_mine").performClick()
                compose.onNodeWithTag("my_account_card").performScrollTo().performClick()
                compose.onNodeWithTag("account_website_only").assertExists()
                compose.onNodeWithTag("account_profile").assertDoesNotExist()
                compose.onNodeWithTag("account_sync_switch").assertDoesNotExist()
                compose.onNodeWithTag("account_section_history").assertDoesNotExist()
                assertEquals(website, store.load().websiteCredentials)
                compose.onNodeWithTag("account_cookie_expand").performScrollTo().performClick()
                compose.onNodeWithTag("account_cookie_source_web").performScrollTo().assertIsSelected()
                compose.onNodeWithTag("account_cookie_input").performScrollTo().performTextInput("sessionid=synthetic-unused")
                compose.onNodeWithTag("account_cookie_source_app").performScrollTo().performClick().assertIsSelected()
                compose.onNodeWithTag("account_cookie_submit").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("account_website_logout").performScrollTo().performClick()
                compose.onNodeWithTag("account_logout_confirm").performClick()
                compose.waitUntil(5_000) { model.state.value.sessionStatus == AccountSessionStatus.SIGNED_OUT }
                assertNull(store.load().websiteCredentials)
                assertNull(model.ownerKey)
                assertEquals(guest, LibraryStore(context).state.value)
            }
        } finally { store.save(previous) }
    }
}
