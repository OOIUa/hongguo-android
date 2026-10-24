package com.hongguo.android

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguo.account.AccountDisk
import com.hongguo.account.AccountStore
import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.CloudProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AccountSecurityDeviceTest {
    @Test fun crossProcessLoginResultIsOneUseAndHasNoCookieExtra() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<AccountHandoffHostActivity>(Intent(context, AccountHandoffHostActivity::class.java)).use { scenario ->
            lateinit var completed: CountDownLatch
            scenario.onActivity { completed = it.completed }
            assertTrue("Cross-process result must arrive", completed.await(15, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val data = activity.handoffResult
                assertNotNull(data)
                assertFalse(data!!.extras!!.keySet().any { data.extras?.get(it) is String })
                assertEquals("sessionid=synthetic-device-test", OfficialLoginActivity.consumeCookie(data))
                assertNull(OfficialLoginActivity.consumeCookie(data))
            }
        }
    }

    @Test fun keystoreSnapshotHasNoPlaintextAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "account-encryption-device-test").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getNoBackupFilesDir() = directory
        }
        val store = AccountStore(isolated)
        val disk = AccountDisk(AccountCredentials("sessionid=synthetic-encryption-test", "123456789", "987654321",
            appDeviceCookie = "store-region=synthetic-device-cookie"),
            CloudProfile("18446744073709551615", "加密验证账号"))
        try {
            store.save(disk)
            assertEquals(disk.credentials, store.load().credentials)
            assertEquals(disk.profile, store.load().profile)
            val encrypted = File(directory, "hongguo-account.enc")
            assertFalse(String(encrypted.readBytes(), Charsets.ISO_8859_1).contains("synthetic-encryption-test"))
            assertFalse(String(encrypted.readBytes(), Charsets.ISO_8859_1).contains("synthetic-device-cookie"))
            val corrupted = encrypted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            encrypted.writeBytes(corrupted)
            try { store.load(); fail("Authenticated encryption must reject modified ciphertext") }
            catch (_: java.io.IOException) { assertTrue(encrypted.exists()) }
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun officialLoginNavigationKeepsAnExactHttpsOrigin() {
        assertTrue(OfficialLoginActivity.isOfficialNavigation(Uri.parse("https://hongguoduanju.com/")))
        listOf("http://hongguoduanju.com/", "https://hongguoduanju.com.example.com/",
            "https://hongguoduanju.com@evil.example/", "https://hongguoduanju.com:8443/",
            "file:///data/data/com.hongguo.android/", "javascript:alert(1)").forEach {
            assertFalse(it, OfficialLoginActivity.isOfficialNavigation(Uri.parse(it)))
        }
        assertTrue(OfficialLoginActivity.isAllowedFrameNavigation(Uri.parse("https://verify.example/challenge"), false))
        assertFalse(OfficialLoginActivity.isAllowedFrameNavigation(Uri.parse("https://verify.example/challenge"), true))
        assertFalse(OfficialLoginActivity.isAllowedFrameNavigation(Uri.parse("http://verify.example/challenge"), false))
    }
}
