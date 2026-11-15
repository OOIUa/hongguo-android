package com.hongguo.android

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hongguo.account.ReservationResult
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReservationResultUiTest(private val style: AppThemeStyle) {
    @get:Rule val compose = createComposeRule()

    @Test fun resultRemainsVisibleWhenTheDetailPageChangesAndCanBeDismissed() {
        val result = mutableStateOf<ReservationResult?>(null)
        val detail = mutableStateOf(true)
        compose.setContent {
            HongguoTheme(style, applyToWindow = false) {
                Text(if (detail.value) "详情" else "首页")
                ReservationResultDialog(result.value) { result.value = null }
            }
        }
        compose.runOnIdle {
            result.value = ReservationResult("7300000000000000001", "待上线作品", "红果风控拒绝了本次预约（错误码 101144），未写入账号")
        }
        compose.onNodeWithTag("reservation_result_message").assertIsDisplayed()
            .assertTextContains("101144", substring = true).assertTextContains("待上线作品", substring = true)
        compose.runOnIdle { detail.value = false }
        compose.onNodeWithTag("reservation_result_message").assertIsDisplayed().assertTextContains("未写入账号", substring = true)
        compose.onNodeWithTag("reservation_result_dismiss").performClick()
        compose.onNodeWithTag("reservation_result").assertDoesNotExist()
        compose.runOnIdle { assertNull(result.value) }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun themes() = AppThemeStyle.entries.toList()
    }
}
