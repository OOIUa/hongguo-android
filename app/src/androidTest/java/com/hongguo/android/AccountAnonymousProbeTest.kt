package com.hongguo.android

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hongguo.account.protocol.CloudAccountClient
import com.hongguo.account.protocol.WebLogin
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in anonymous probes only: never sends SMS, scans a code or uses a person's credentials. */
@RunWith(AndroidJUnit4::class)
class AccountAnonymousProbeTest {
    private fun enabled() = assumeTrue(InstrumentationRegistry.getArguments().getString("accountLiveProbe") == "true")
    private fun http() = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).build()

    @Test fun officialQrCanBeFetchedAndIsNotAnAuthenticatedSession() {
        enabled()
        val login = WebLogin(http())
        try {
            val qr = login.qr()
            assertTrue(qr.expiresIn > 0)
            val bitmap = BitmapFactory.decodeByteArray(qr.image, 0, qr.image.size)
            assertNotNull("Official QR image must decode", bitmap)
            bitmap?.recycle()
            try { login.credentials(); fail("An unscanned QR is not a personal account") }
            catch (_: Exception) { /* Explicitly unverified. */ }
        } finally { login.cancel() }
    }

    @Test fun anonymousDeviceRegistrationReturnsUsableIdentity() {
        enabled()
        val client = CloudAccountClient(http())
        try {
            val device = client.registerDevice()
            assertNotNull("Registration must supply valid install and device identities", device)
            assertTrue(device!!.deviceId.matches(Regex("[0-9]+")) && device.deviceId.any { it != '0' })
            assertTrue(device.installId.matches(Regex("[0-9]+")) && device.installId.any { it != '0' })
        } finally { client.cancelAll() }
    }
}
