// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real HTTP transport against a loopback server, never the provider or a real account. */
class AccountProtocolTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val credentials = AccountCredentials("sessionid=offline-test", "9999999999999999999", "9999999999999999998")

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    private fun cloud() = CloudAccountClient(http, server.url("/"), server.url("/service/2/device_register/"))
    private fun body(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private fun qr() = body("""{"message":"success","data":{"token":"offline-qr","qrcode":"aW1hZ2U=","expire_time":180}}""")
        .addHeader("Set-Cookie", "passport_csrf_token=offline-csrf; Path=/; HttpOnly")
    private fun webIdentity() = body("""{"message":"success","data":{"user_info":{"user_id":"8888888888888888888"}}}""")

    @Test fun qrCompletesRedirectAndValidatesWebsiteSessionBeforeExposingCredentials() {
        server.enqueue(qr())
        server.enqueue(body("""{"message":"success","data":{"status":"3","redirect_url":"/auth/complete"}}"""))
        server.enqueue(MockResponse().setBody("ok").addHeader("Set-Cookie", "sessionid=offline-test; Path=/; HttpOnly"))
        server.enqueue(body("""{"message":"success","has_login":true,"user":{"nickname":"官网测试"}}"""))
        val login = WebLogin(http, server.url("/"))
        assertArrayEquals("image".toByteArray(), login.qr().image)
        assertTrue(login.poll())
        assertTrue(login.credentials().cookie.contains("sessionid=offline-test"))
        assertEquals("/passport/web/get_qrcode/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/passport/web/check_qrconnect/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/auth/complete", server.takeRequest().requestUrl!!.encodedPath)
        val check = server.takeRequest()
        assertEquals("/passport/account/info/v2/", check.requestUrl!!.encodedPath)
        assertEquals("offline-csrf", check.getHeader("x-tt-passport-csrf-token"))
        assertTrue(check.getHeader("Cookie")!!.contains("sessionid=offline-test"))
    }

    @Test fun qrRejectsCrossOriginRedirectBeforeSendingAnyCredentials() {
        val other = MockWebServer(); other.start()
        try {
            server.enqueue(qr())
            server.enqueue(body("""{"message":"success","data":{"status":"confirmed","redirect_url":"${other.url("/steal")}"}}"""))
            val login = WebLogin(http, server.url("/"))
            login.qr()
            assertThrows(IOException::class.java) { login.poll() }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun smsUsesSameCsrfFormSessionAndConsumesEachSubmittedCodeOnlyOnce() {
        server.enqueue(qr())
        server.enqueue(body("""{"message":"success","data":{"retry_time":60}}"""))
        server.enqueue(body("""{"message":"error","data":{"error_code":1102,"description":"provider-raw-secret"}}"""))
        val login = WebLogin(http, server.url("/"))
        assertEquals("138****8000", login.send("13800138000").maskedMobile)
        val error = assertThrows(PhoneLogin.Failure::class.java) { login.verify("123456") }
        assertFalse(error.canRetryCode)
        assertFalse(error.message!!.contains("provider-raw-secret"))
        assertThrows(IllegalStateException::class.java) { login.verify("123456") }
        assertThrows(IllegalStateException::class.java) { login.send("13800138000") }
        server.takeRequest()
        val send = server.takeRequest()
        assertEquals("offline-csrf", send.getHeader("x-tt-passport-csrf-token"))
        assertEquals("POST", send.method)
        assertTrue(send.body.readUtf8().contains("mobile=${WebLogin.mix("+86 13800138000")}"))
        assertEquals("/passport/web/sms_login/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals(3, server.requestCount)
    }

    @Test fun profilePreservesNineteenDigitIdsAndRejectsAnonymousAndBusinessErrors() {
        val client = cloud()
        server.enqueue(webIdentity())
        server.enqueue(body("""{"code":0,"data":{"biz_user_id":9999999999999999997,"user_name":"离线测试"}}"""))
        assertEquals("9999999999999999997", client.profile(credentials).id)
        assertEquals("/passport/account/info/v2/", server.takeRequest().requestUrl!!.encodedPath)
        val request = server.takeRequest()
        assertEquals(credentials.deviceId, request.requestUrl!!.queryParameter("device_id"))
        assertEquals(credentials.installId, request.requestUrl!!.queryParameter("iid"))
        assertEquals("${credentials.cookie}; install_id=${credentials.installId}", request.getHeader("Cookie"))
        server.enqueue(webIdentity())
        server.enqueue(body("""{"code":0,"data":{"user_name":"匿名"}}"""))
        assertFalse(assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }.requiresLogin)
        server.enqueue(webIdentity())
        server.enqueue(body("""{"code":1003,"message":"raw-provider-secret"}"""))
        val error = assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }
        assertEquals(1003, error.errorCode); assertTrue(error.requiresLogin)
        assertFalse(error.message!!.contains("raw-provider-secret"))
    }

    @Test fun responseReaderRejectsEmptyMalformedAndUnknownLengthOversizedBodies() {
        val client = cloud()
        server.enqueue(body(""))
        assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }
        server.enqueue(body("not-json-secret"))
        val malformed = assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }
        assertFalse(malformed.message!!.contains("not-json-secret"))
        server.enqueue(MockResponse().setChunkedBody("x".repeat(2 * 1024 * 1024 + 1), 8192))
        assertTrue(assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }.message!!.contains("过大"))
    }

    @Test fun existingRegisteredCredentialsAreReusedWithoutAnyNetworkRequest() {
        assertSame(credentials, cloud().prepareCredentials(credentials))
        assertEquals(0, server.requestCount)
        assertEquals(credentials, AccountCredentials.fromJson(credentials.toJson()))
        assertFalse(credentials.toString().contains("offline-test"))
    }

    @Test fun registrationKeepsLargeIdsAndStableDeviceParametersAndNeverSendsAccountCookies() {
        val client = cloud()
        repeat(2) { server.enqueue(body("""{"device_id":9999999999999999999,"install_id_str":"9999999999999999998"}""")) }
        val first = client.prepareCredentials(AccountCredentials("sessionid=offline-test"))
        val second = client.prepareCredentials(AccountCredentials("sessionid=offline-test"))
        assertEquals("9999999999999999999", first.deviceId)
        assertEquals(first.device, second.device)
        assertTrue(first.device.getValue("cdid").isNotBlank())
        assertTrue(first.device.getValue("clientudid").isNotBlank())
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("x-gorgon"))
            assertEquals("POST", request.method)
            assertArrayEquals(byteArrayOf(0x74, 0x63, 0x05, 0x10, 0, 0), request.body.readByteArray().copyOf(6))
        }
    }

    @Test fun writesKeepExactIdsAndProgressButNeverRetryOrFollowRedirects() {
        val client = cloud()
        server.enqueue(body("""{"code":0}"""))
        client.upload(credentials, CloudProgress(Series("9999999999999999999", "测试"), "9999999999999999997", 2, 43210, 90000, 1792500000000), 8)
        val request = server.takeRequest()
        val uploaded = JSONObject(request.body.readUtf8()).getJSONArray("books").getJSONObject(0)
        assertEquals("9999999999999999999", uploaded.getString("book_id"))
        assertEquals(3, uploaded.getInt("vid_index")); assertEquals(43210L, uploaded.getLong("current_play_time"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirect-target")))
        assertThrows(AccountProtocolException::class.java) { client.setFavorite(credentials, "9999999999999999999", true) }
        assertEquals(2, server.requestCount)
        server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThrows(IOException::class.java) { client.setFavorite(credentials, "9999999999999999999", false) }
        assertEquals(3, server.requestCount)
    }

    @Test fun cancellationInterruptsOnlyOwnedInFlightHttpCall() {
        val client = cloud()
        val executor = Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val future = executor.submit<Throwable?> { try { client.profile(credentials); null } catch (error: Throwable) { error } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            client.cancelAll()
            assertTrue(future.get(3, TimeUnit.SECONDS) is IOException)
        } finally { executor.shutdownNow() }
    }

    @Test fun writesDoNotReplayEvenWhenServerExplicitlyRequestsImmediateRetry() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(body("""{"code":0}"""))
        assertThrows(AccountProtocolException::class.java) { cloud().setFavorite(credentials, "9999999999999999999", true) }
        assertEquals(1, server.requestCount)
    }

    @Test fun qrBase64IsBoundedAndSmsExpiredSessionHasSafeActionableMessage() {
        server.enqueue(body("""{"message":"success","data":{"token":"offline","qrcode":"${"A".repeat(1024 * 1024 + 1)}"}}"""))
        assertTrue(assertThrows(AccountProtocolException::class.java) { WebLogin(http, server.url("/")).qr() }.message!!.contains("过大"))
        server.enqueue(qr())
        server.enqueue(body("""{"message":"error","data":{"error_code":1003,"description":"raw-secret"}}"""))
        val error = assertThrows(PhoneLogin.Failure::class.java) { WebLogin(http, server.url("/")).send("13800138000") }
        assertEquals(1003, error.errorCode)
        assertTrue(error.message!!.contains("会话已失效")); assertFalse(error.message!!.contains("raw-secret"))
    }

    @Test fun cloudHistoryParserKeepsExactPositionAndEpochUnits() {
        val result = CloudAccountClient.parseProgress(JSONArray("""[{"series_id":"9999999999999999999","vid":"9999999999999999998","vid_index":3,"current_play_time":43210,"current_total_time":90000,"last_watch_time":1792500000}]""")).single()
        assertEquals("9999999999999999999", result.series.id)
        assertEquals(2, result.episodeIndex); assertEquals(43210L, result.position)
        assertEquals(1792500000000L, result.updatedAt)
    }

    @Test fun integerNormalizationPreservesIdsWithoutTouchingQuotedTextOrFloatingNumbers() {
        val text = """{"id":9999999999999999999,"title":"literal 9999999999999999999 and \\"quoted\\"","small":12,"fraction":1.5,"exponent":1e19}"""
        val normalized = preserveLargeIntegers(text)
        assertTrue(normalized.contains("\"id\":\"9999999999999999999\""))
        assertTrue(normalized.contains("literal 9999999999999999999"))
        assertTrue(normalized.contains("\"fraction\":1.5")); assertTrue(normalized.contains("\"exponent\":1e19"))
        assertEquals("9999999999999999999", parseObject("""{"id":9999999999999999999}""").getString("id"))
    }

    @Test fun websiteSessionIsRequiredAndTransportRejectionDoesNotInvalidateSession() {
        val client = cloud()
        server.enqueue(body("""{"message":"success","data":{"user_id":0}}"""))
        assertFalse(assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }.requiresLogin)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(403))
        assertFalse(assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }.requiresLogin)
        server.enqueue(body("""{"message":"success","data":{"has_login":false,"uid":"123456"}}"""))
        assertTrue(assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }.requiresLogin)
        validateWebsiteSession(JSONObject("""{"user_info":{"uid":"123456"}}"""))
        assertEquals(AccountVerificationStage.DEVICE,
            assertThrows(AccountProtocolException::class.java) { cloud().prepareCredentials(AccountCredentials("sessionid=")) }.verificationStage)
        assertEquals(AccountVerificationStage.DEVICE,
            assertThrows(AccountProtocolException::class.java) { cloud().prepareCredentials(AccountCredentials("sessionid=${"x".repeat(16385)}")) }.verificationStage)
    }

    @Test fun websiteSessionAcceptsOfficialHasLoginOnlyResponsesAndIgnoresUnrelatedFlags() {
        for (container in listOf("", "user_info", "user")) {
            for (flag in listOf(true, 1)) {
                val row = JSONObject().put("has_login", flag).put("is_auth", false)
                    .put("is_login", false).put("is_logged_in", false)
                val data = if (container.isEmpty()) row else JSONObject().put(container, row)
                validateWebsiteSession(data)
            }
        }
        // No public ID is required when the website explicitly confirms the session.
        validateWebsiteSession(JSONObject("""{"has_login":true,"user_id":0}"""))
        validateWebsiteSession(JSONObject("""{"is_auth":false,"is_login":false,"is_logged_in":false,"user":{"uid":"123456"}}"""))
    }

    @Test fun websiteSessionAcceptsExactNineteenDigitIdsFromOfficialNestedShapes() {
        for (container in listOf("", "user_info", "user")) {
            for (key in listOf("user_id", "user_id_str", "uid", "uid_str")) {
                for (value in listOf("9999999999999999999", "\"9999999999999999999\"")) {
                    val nested = """{"$key":$value}"""
                    val data = parseObject(if (container.isEmpty()) nested else """{"$container":$nested}""")
                    val row = if (container.isEmpty()) data else data.getJSONObject(container)
                    assertEquals("9999999999999999999", row.str(key))
                    validateWebsiteSession(data)
                }
            }
        }
    }

    @Test fun websiteSessionUnwrapsOfficialEnvelopesAndUsesRootFlagOnlyWhenDataHasNoFlag() {
        for (flag in listOf("true", "1")) {
            // The root signal must work even when the data object contains no public ID.
            validateWebsiteSession(parseObject("""{"message":"success","has_login":$flag,"data":{"user":{}}}"""))
        }
        validateWebsiteSession(parseObject("""{"message":"success","has_login":true,"data":{"user":{"user_id":9999999999999999999}}}"""))
        validateWebsiteSession(parseObject("""{"message":"success","data":{"user":{"user_id_str":"9999999999999999999"}}}"""))
        // The data-level flag owns the decision; the unused root flag cannot override it.
        validateWebsiteSession(parseObject("""{"has_login":false,"data":{"has_login":true}}"""))
        validateWebsiteSession(parseObject("""{"has_login":"malformed-unused-root","data":{"has_login":1}}"""))
        val denied = assertThrows(AccountProtocolException::class.java) {
            validateWebsiteSession(parseObject("""{"has_login":true,"data":{"has_login":false,"user":{"uid":"123456"}}}"""))
        }
        assertTrue(denied.requiresLogin)
        val malformed = assertThrows(AccountProtocolException::class.java) {
            validateWebsiteSession(parseObject("""{"has_login":true,"data":{"has_login":null,"user":{"uid":"123456"}}}"""))
        }
        assertFalse(malformed.requiresLogin)
        val anonymousFallback = assertThrows(AccountProtocolException::class.java) {
            validateWebsiteSession(parseObject("""{"has_login":0,"data":{"user":{"uid":"123456"}}}"""))
        }
        assertTrue(anonymousFallback.requiresLogin)
        // A root-level ID is not an identity field of an explicitly supplied data object.
        assertThrows(AccountProtocolException::class.java) {
            validateWebsiteSession(parseObject("""{"user_id":"123456","data":{}}"""))
        }
    }

    @Test fun websiteSessionRejectsAnonymousFlagsEvenWithStaleIdsAndConflictingPositiveFlags() {
        for (container in listOf("", "user_info", "user")) {
            for (flag in listOf(false, 0)) {
                val row = JSONObject().put("has_login", flag).put("uid", "9999999999999999999")
                val data = if (container.isEmpty()) row else JSONObject().put("has_login", true).put(container, row)
                val error = assertThrows(AccountProtocolException::class.java) { validateWebsiteSession(data) }
                assertTrue("An explicit anonymous flag must invalidate the session", error.requiresLogin)
            }
        }
    }

    @Test fun websiteSessionRejectsMalformedExplicitSignalsWithoutTreatingThemAsConfirmedExpiry() {
        val malformed = listOf(JSONObject.NULL, "true", "false", "1", "0", "", 2, -1, .5, JSONObject(), JSONArray())
        for (container in listOf("", "user_info", "user")) {
            for (flag in malformed) {
                val row = JSONObject().put("has_login", flag).put("user_id", "9999999999999999999")
                val data = if (container.isEmpty()) row else JSONObject().put("has_login", true).put(container, row)
                val error = assertThrows(AccountProtocolException::class.java) { validateWebsiteSession(data) }
                assertFalse("A malformed response is not proof that the saved session expired", error.requiresLogin)
            }
        }
    }

    @Test fun websiteSessionRejectsEmptyAndNonNumericAnonymousIdentifiers() {
        for (data in listOf("{}", """{"is_auth":true}""", """{"is_auth":false}""",
            """{"is_login":true}""", """{"is_logged_in":true}""",
            """{"user":{}}""", """{"user_info":{}}""")) {
            assertThrows(AccountProtocolException::class.java) { validateWebsiteSession(parseObject(data)) }
        }
        val invalidIds = listOf(JSONObject.NULL, "", "0", "0000000000000000000", "anonymous", "-1", "1.5", "1e19", 0, -1, 1.5, true, JSONObject(), JSONArray())
        for (container in listOf("", "user_info", "user")) {
            for (id in invalidIds) {
                val row = JSONObject().put("user_id", id)
                val data = if (container.isEmpty()) row else JSONObject().put(container, row)
                assertThrows(AccountProtocolException::class.java) { validateWebsiteSession(data) }
            }
        }
    }

    @Test fun hasLoginOnlyWebsiteResponseAllowsProfileButNeverReplacesAppBusinessIdentity() {
        val client = cloud()
        server.enqueue(body("""{"message":"success","data":{"has_login":true,"is_auth":false}}"""))
        server.enqueue(body("""{"code":0,"data":{"biz_user_id":9999999999999999997,"user_name":"本人账号"}}"""))
        assertEquals("9999999999999999997", client.profile(credentials).id)
        for (businessId in listOf("", "0", credentials.deviceId, credentials.installId)) {
            server.enqueue(body("""{"message":"success","data":{"has_login":1,"user":{"uid":"8888888888888888888"}}}"""))
            server.enqueue(body("""{"code":0,"data":{"biz_user_id":"$businessId","user_id":"8888888888888888888","user_name":"不能替代业务账号"}}"""))
            assertThrows(AccountProtocolException::class.java) { client.profile(credentials) }
        }
    }
}
