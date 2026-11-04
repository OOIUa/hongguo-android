// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** App passport SMS login against a loopback server; no number, code or session here is real. */
class AppPhoneLoginTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private var now = 1_000_000L

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    private fun login() = AppPhoneLogin(http, server.url("/")) { now }
    private fun ok(data: String = "{}") = MockResponse().setBody("""{"message":"success","data":$data}""")
    private fun error(code: Int) = MockResponse().setBody("""{"message":"error","data":{"error_code":$code,"description":"raw-secret"}}""")
    private val sentReply get() = ok("""{"mobile":"138******00","retry_time":45,"mobile_ticket":"tk1"}""")
    private fun sentLogin() = login().also { server.enqueue(sentReply); it.send("13800138000"); server.takeRequest() }

    @Test fun sendUsesPassportWithoutDeviceInQueryOrCookies() {
        server.enqueue(sentReply)
        val sent = login().send("138 0013-8000")
        assertEquals("138****8000", sent.maskedMobile)
        assertEquals(45, sent.retrySeconds)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("GET", request.method)
        assertEquals("/passport/mobile/send_code/v1/", url.encodedPath)
        assertEquals("大陆号码不带区号", "13800138000", url.queryParameter("mobile"))
        assertEquals("24", url.queryParameter("type"))
        assertEquals("73932", url.queryParameter("version_code"))
        assertNull(url.queryParameter("device_id"))
        assertNull(url.queryParameter("iid"))
        assertNull(request.getHeader("Cookie"))
        assertNotNull(request.getHeader("x-gorgon"))
    }

    @Test fun sliderRequestSendsTheUserToTheWebsiteWithoutRawText() {
        server.enqueue(MockResponse().setBody("""{"message":"error","data":{"error_code":1105,"verify_center_decision_conf":"{}","description":"raw-secret"}}"""))
        val failure = assertThrows(PhoneLogin.Failure::class.java) { login().send("13800138000") }
        assertTrue(failure.needsWebsite)
        assertFalse(failure.canRetryCode)
        assertTrue(failure.message!!.contains("滑块"))
        assertFalse(failure.message!!.contains("raw-secret"))
    }

    @Test fun captchaRequestAlsoSendsTheUserToTheWebsite() {
        server.enqueue(MockResponse().setBody("""{"message":"error","data":{"error_code":1101,"captcha":"image-data"}}"""))
        val failure = assertThrows(PhoneLogin.Failure::class.java) { login().send("13800138000") }
        assertTrue(failure.needsWebsite)
        assertFalse(failure.message!!.contains("image-data"))
    }

    @Test fun cooldownBlocksAnEarlyResend() {
        val login = sentLogin()
        assertThrows(IllegalStateException::class.java) { login.send("13800138000") }
        now += 45_000
        server.enqueue(sentReply)
        login.send("13800138000")
    }

    @Test fun smsLoginReturnsCredentialsBoundToTheFixedDevice() {
        val login = sentLogin()
        server.enqueue(ok().addHeader("Set-Cookie", "sessionid=abc; Path=/; HttpOnly").addHeader("Set-Cookie", "uid_tt=u; Path=/"))
        val credentials = login.verify("123456")
        assertEquals("sessionid=abc; uid_tt=u", credentials.cookie)
        assertEquals(AppPhoneLogin.DEVICE_ID, credentials.deviceId)
        assertEquals(AppPhoneLogin.INSTALL_ID, credentials.installId)
        assertEquals(AccountCredentialSource.APP, credentials.source)
        assertEquals(1, server.requestCount - 1)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/passport/mobile/sms_login/", request.requestUrl!!.encodedPath)
        val form = request.body.readUtf8()
        assertTrue(form.contains("code=123456"))
        assertTrue(form.contains("mobile_ticket=tk1"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertNull(request.requestUrl!!.queryParameter("code"))
    }

    @Test fun cookiesFromSendAreNotSentBackOrTreatedAsASession() {
        val login = login()
        server.enqueue(sentReply.addHeader("Set-Cookie", "passport_csrf_token=x; Path=/"))
        login.send("13800138000")
        server.takeRequest()
        server.enqueue(ok().addHeader("Set-Cookie", "sessionid=abc; Path=/"))
        assertTrue(login.verify("123456").cookie.contains("sessionid=abc"))
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun inapplicableEndpointsAreTriedInOrderWithTheLatestTicket() {
        val login = sentLogin()
        server.enqueue(MockResponse().setBody("""{"message":"error","data":{"error_code":1,"mobile_ticket":"tk2"}}"""))
        server.enqueue(ok("""{"error_code":0}""").addHeader("Set-Cookie", "sessionid=abc; Path=/"))
        login.verify("123456")
        assertTrue(server.takeRequest().body.readUtf8().contains("mobile_ticket=tk1"))
        val second = server.takeRequest()
        assertEquals("/passport/mobile/sms_login_only/", second.requestUrl!!.encodedPath)
        assertTrue(second.body.readUtf8().contains("mobile_ticket=tk2"))
    }

    @Test fun everyEndpointExpiredEndsTheAttempt() {
        val login = sentLogin()
        repeat(5) { server.enqueue(error(1)) }
        val failure = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertEquals(1, failure.errorCode)
        assertFalse(failure.canRetryCode)
        assertEquals(6, server.requestCount)
        assertThrows(IllegalStateException::class.java) { login.verify("654321") }
    }

    @Test fun wrongCodeStopsAtOnceAndAllowsOnlyADifferentCode() {
        val login = sentLogin()
        server.enqueue(error(1202))
        val wrong = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertTrue(wrong.canRetryCode)
        assertFalse(wrong.message!!.contains("raw-secret"))
        assertEquals("错误的验证码不再换端点提交", 2, server.requestCount)
        val again = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertTrue(again.message!!.contains("已提交过"))
        assertEquals(2, server.requestCount)
        server.enqueue(ok().addHeader("Set-Cookie", "sessionid=abc; Path=/"))
        assertEquals(AccountCredentialSource.APP, login.verify("654321").source)
    }

    @Test fun tooManyAttemptsEndTheAttempt() {
        val login = sentLogin()
        server.enqueue(error(1203))
        val failure = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertFalse(failure.canRetryCode)
        assertEquals(1203, failure.errorCode)
        assertThrows(IllegalStateException::class.java) { login.verify("654321") }
    }

    @Test fun redirectIsNotFollowedWithTheCode() {
        val other = MockWebServer(); other.start()
        try {
            val login = sentLogin()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            assertThrows(AccountProtocolException::class.java) { login.verify("123456") }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }
}
