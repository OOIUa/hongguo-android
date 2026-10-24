package com.hongguo.account.protocol

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudAccountDeviceCookieTest {
    private val websiteCookie = "sessionid=private-fixture; uid_tt=account-fixture; install_id=old-web-install; store-region=old-region"

    @Test fun appMergePreservesSessionAndUsesCurrentRegisteredInstallationOnlyOnce() {
        val credentials = AccountCredentials(websiteCookie, "7100000000000000001", "7200000000000000001",
            appDeviceCookie = "install_id=stale-app-install; ttreq=registered-request; store-region=cn-sh; store-region-src=did; sessionid=must-not-replace")
        val merged = appRequestCookie(credentials)
        assertTrue(merged.contains("sessionid=private-fixture"))
        assertFalse(merged.contains("must-not-replace"))
        assertTrue(merged.contains("uid_tt=account-fixture"))
        assertTrue(merged.contains("install_id=7200000000000000001"))
        assertTrue(merged.contains("ttreq=registered-request"))
        assertTrue(merged.contains("store-region=cn-sh"))
        assertEquals(1, merged.split(';').count { it.trim().startsWith("install_id=") })
        assertEquals(websiteCookie, credentials.cookie)
    }

    @Test fun legacyEncryptedCredentialJsonDerivesAppInstallationWithoutChangingWebsiteCookie() {
        val legacy = """{"cookie":"sessionid=private-fixture; install_id=old-web-install","deviceId":"7100000000000000001","installId":"7200000000000000001","device":{}}"""
        val credentials = AccountCredentials.fromJson(legacy)
        assertEquals("", credentials.appDeviceCookie)
        assertEquals("sessionid=private-fixture; install_id=old-web-install", credentials.cookie)
        assertEquals("sessionid=private-fixture; install_id=7200000000000000001", appRequestCookie(credentials))
    }

    @Test fun registrationCookiesRespectDomainPathAndKnownDeviceNames() {
        val registration = "https://log5-applog-lf.fqnovel.com/service/2/device_register/".toHttpUrl()
        val api = "https://api5-normal-sinfonlinea.fqnovel.com/".toHttpUrl()
        val headers = Headers.Builder()
            .add("Set-Cookie", "store-region=cn-sh; Domain=.fqnovel.com; Path=/; Secure")
            .add("Set-Cookie", "store-region-src=did; Domain=.fqnovel.com; Path=/; Secure")
            .add("Set-Cookie", "ttreq=host-only; Path=/; Secure")
            .add("Set-Cookie", "ttreq=profile-path-only; Domain=.fqnovel.com; Path=/reading/user/; Secure")
            .add("Set-Cookie", "install_id=wrong-path; Domain=.fqnovel.com; Path=/service/; Secure")
            .add("Set-Cookie", "sessionid=unrelated-register-session; Domain=.fqnovel.com; Path=/; Secure")
            .build()
        assertEquals("store-region=cn-sh; store-region-src=did", registeredAppDeviceCookies(registration, api, headers))
        assertEquals("", registeredAppDeviceCookies(registration, "https://hongguoduanju.com/".toHttpUrl(), headers))
    }

    @Test fun preparedCredentialsPersistScopedDeviceCookiesAndRetainOriginalWebsiteSession() {
        val server = MockWebServer(); server.start()
        val http = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("""{"device_id":"7100000000000000001","install_id":"7200000000000000001"}""")
                .addHeader("Set-Cookie", "install_id=7200000000000000001; Path=/")
                .addHeader("Set-Cookie", "ttreq=registration-fixture; Path=/")
                .addHeader("Set-Cookie", "store-region=cn-sh; Path=/")
                .addHeader("Set-Cookie", "sessionid=must-not-copy; Path=/"))
            val client = CloudAccountClient(http, server.url("/"), server.url("/service/2/device_register/"))
            val prepared = client.prepareCredentials(AccountCredentials(websiteCookie))
            assertEquals(websiteCookie, prepared.cookie)
            assertEquals("install_id=7200000000000000001; ttreq=registration-fixture; store-region=cn-sh", prepared.appDeviceCookie)
            assertEquals(prepared, AccountCredentials.fromJson(prepared.toJson()))
            assertEquals(1, server.requestCount)
            assertSame(prepared, client.prepareCredentials(prepared))
        } finally {
            server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }

    @Test fun registeredCookiesGoToAppCallsWhileWebsiteCredentialsStayUnmodified() {
        val server = MockWebServer(); server.start()
        val http = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("""{"code":0,"data":{"video_shelf_info":[]}}"""))
            val credentials = AccountCredentials(websiteCookie, "7100000000000000001", "7200000000000000001",
                appDeviceCookie = "ttreq=registered-request; store-region=cn-sh; store-region-src=did")
            val client = CloudAccountClient(http, server.url("/"), server.url("/service/2/device_register/"))
            assertTrue(client.favorites(credentials).isEmpty())
            val request = server.takeRequest()
            assertEquals("7200000000000000001", request.requestUrl!!.queryParameter("iid"))
            assertEquals(appRequestCookie(credentials), request.getHeader("Cookie"))
            assertEquals(websiteCookie, credentials.cookie)
            assertFalse(credentials.toString().contains("registered-request"))
        } finally {
            server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
