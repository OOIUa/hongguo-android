package com.hongguo.account.protocol

import java.util.concurrent.CancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AccountVerificationTest {
    private val credentials = AccountCredentials("sessionid=synthetic; passport_csrf_token_default=fallback=token", "123456", "654321")

    @Test fun flatWebsiteSuccessIsAcceptedByTheActualProfileFlow() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"message":"success","has_login":true,"user":{"nickname":"fixture"}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_user_id":"8888888888888888888"}}"""))
            val http = OkHttpClient()
            try {
                assertEquals("8888888888888888888", CloudAccountClient(http, server.url("/"), server.url("/register"))
                    .profile(credentials).id)
                assertEquals(2, server.requestCount)
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun websiteHeadersMatchOfficialSdkAndDeviceCookiesStayOnTheAppRequest() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"message":"success","has_login":true,"data":{"user":{"nickname":"fixture"}}}"""))
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_user_id":"8888888888888888888"}}"""))
            val http = OkHttpClient()
            try {
                val client = CloudAccountClient(http, server.url("/"), server.url("/register"))
                val session = credentials.copy(appDeviceCookie = "store-region=cn; install_id=stale")
                assertEquals("8888888888888888888", client.profile(session).id)
                val web = server.takeRequest()
                assertEquals(credentials.cookie, web.getHeader("Cookie"))
                assertEquals("fallback=token", web.getHeader("x-tt-passport-csrf-token"))
                assertEquals(WebLogin.USER_AGENT, web.getHeader("User-Agent"))
                val app = server.takeRequest()
                assertTrue(app.getHeader("Cookie")!!.contains("store-region=cn"))
                assertTrue(app.getHeader("Cookie")!!.contains("install_id=654321"))
                assertNull(app.getHeader("x-tt-passport-csrf-token"))
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun expiredWebsiteSessionHasItsOwnStageAndDoesNotReachTheApp() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"message":"error","data":{"error_code":13,"description":"raw-session-secret"}}"""))
            val http = OkHttpClient()
            try {
                val error = assertThrows(AccountProtocolException::class.java) {
                    CloudAccountClient(http, server.url("/"), server.url("/register")).profile(credentials)
                }
                assertEquals(AccountVerificationStage.WEBSITE, error.verificationStage)
                assertEquals(13, error.errorCode)
                assertTrue(error.requiresLogin)
                assertFalse(error.message!!.contains("raw-session-secret"))
                assertEquals(1, server.requestCount)
            } finally { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
        }
    }

    @Test fun csrfPrefersPrimaryAndNeverRewritesOpaqueValues() {
        assertEquals("primary+/=", passportCsrfToken("passport_csrf_token_default=fallback; passport_csrf_token=primary+/="))
        assertEquals("fallback", passportCsrfToken("passport_csrf_token=; passport_csrf_token_default=fallback"))
        assertNull(passportCsrfToken("sessionid=synthetic"))
    }

    @Test fun stageWrapperPreservesCancellationAndDropsUnknownExceptionSecrets() {
        val cancelled = CancellationException("cancelled")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            verifyAccountStage(AccountVerificationStage.DEVICE) { throw cancelled }
        })
        val error = assertThrows(AccountProtocolException::class.java) {
            verifyAccountStage(AccountVerificationStage.DEVICE) { throw IllegalStateException("sessionid=private") }
        }
        assertEquals(AccountVerificationStage.DEVICE, error.verificationStage)
        assertFalse(error.message!!.contains("private"))
        assertNull(error.cause)
    }
}
