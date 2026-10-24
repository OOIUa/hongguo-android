package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Distinguish an authenticated website from an App-authorized account without inventing identity. */
class AppCredentialVerificationTest {
    private val website = AccountCredentials("sessionid=fixture-private", "7100000000000000001", "7200000000000000001")

    private fun withServer(block: (MockWebServer, CloudAccountClient) -> Unit) {
        val server = MockWebServer(); server.start()
        val http = OkHttpClient()
        try { block(server, CloudAccountClient(http, server.url("/"), server.url("/service/2/device_register/"))) }
        finally { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    @Test fun legacyAndUnknownSourceRemainWebsiteWhileExplicitAppSourceRoundTrips() {
        val legacy = JSONObject(website.toJson()).apply { remove("source") }.toString()
        assertEquals(AccountCredentialSource.WEBSITE, AccountCredentials.fromJson(legacy).source)
        val unknown = JSONObject(website.toJson()).put("source", "unrecognized-import").toString()
        assertEquals(AccountCredentialSource.WEBSITE, AccountCredentials.fromJson(unknown).source)
        val app = website.copy(source = AccountCredentialSource.APP)
        assertEquals(app, AccountCredentials.fromJson(app.toJson()))
        assertFalse(app.toString().contains("fixture-private"))
    }

    @Test fun explicitAppImportRequiresBusinessIdentityButDoesNotDemandWebsiteSession() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_user_id":"066831371270","user_name":"测试账号"}}"""))
        val profile = client.profile(website.copy(source = AccountCredentialSource.APP))
        assertEquals("066831371270", profile.id)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("/reading/user/info/v1/", request.requestUrl!!.encodedPath)
        assertNull(request.getHeader("X-Tt-Token"))
    }

    @Test fun confirmedWebsiteDoesNotAuthorizeAnAnonymousAppProfileOrInventAUserId() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"message":"success","data":{"has_login":true}}"""))
        server.enqueue(MockResponse().setHeader("X-Tt-Agw-Login", "0").setBody("""{"code":0,"data":{"user_name":"private-user-name","req_id":"9999999999999999991","has_set_username":false,"unknown-secret-field":"private-cookie-value"}}"""))
        val error = assertThrows(AccountProtocolException::class.java) { client.profile(website) }
        assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
        assertFalse(error.requiresLogin)
        val diagnostic = error.diagnostic.orEmpty()
        assertTrue(diagnostic.contains("source=WEBSITE"))
        assertTrue(diagnostic.contains("class=ANONYMOUS_INDICATORS"))
        assertTrue(diagnostic.contains("agw=0"))
        assertTrue(diagnostic.contains("req_id:string"))
        assertTrue(diagnostic.contains("biz_user_id:missing"))
        assertFalse(diagnostic.contains("9999999999999999991"))
        assertFalse(diagnostic.contains("private-user-name"))
        assertFalse(diagnostic.contains("private-cookie-value"))
        assertFalse(diagnostic.contains("unknown-secret-field"))
        assertEquals(2, server.requestCount)
    }

    @Test fun missingBusinessIdWithoutAnonymousSignalsIsClassifiedAsUnknownNotAnonymous() = withServer { server, client ->
        server.enqueue(MockResponse().setHeader("X-Tt-Agw-Login", "1").setBody("""{"code":0,"data":{"user_name":"private-user","user":{"uid":"9999999999999999991"}}}"""))
        val error = assertThrows(AccountProtocolException::class.java) { client.profile(website.copy(source = AccountCredentialSource.APP)) }
        assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
        assertTrue(error.diagnostic.orEmpty().contains("class=BUSINESS_ID_MISSING"))
        assertTrue(error.diagnostic.orEmpty().contains("user:object"))
        assertTrue(error.diagnostic.orEmpty().contains("agw=1"))
        assertFalse(error.diagnostic.orEmpty().contains("ANONYMOUS"))
        assertFalse(error.diagnostic.orEmpty().contains("9999999999999999991"))
    }

    @Test fun deviceIdentityAndUntrustedHeaderValuesCannotAuthenticateAnAppImport() = withServer { server, client ->
        server.enqueue(MockResponse().setHeader("X-Tt-Agw-Login", "raw-sensitive-header").setBody("""{"code":0,"data":{"biz_user_id":"7100000000000000001","user_name":"private-user"}}"""))
        val error = assertThrows(AccountProtocolException::class.java) { client.profile(website.copy(source = AccountCredentialSource.APP)) }
        assertTrue(error.diagnostic.orEmpty().contains("class=DEVICE_IDENTITY"))
        assertTrue(error.diagnostic.orEmpty().contains("agw=unavailable"))
        assertFalse(error.diagnostic.orEmpty().contains("raw-sensitive-header"))
        assertFalse(error.diagnostic.orEmpty().contains("7100000000000000001"))
    }

    @Test fun websiteFailureIsNotBypassedByBusinessPayloadAndExistingStageDiagnosticsArePreserved() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"message":"error","data":{"error_code":13}}"""))
        val error = assertThrows(AccountProtocolException::class.java) { client.profile(website) }
        assertEquals(AccountVerificationStage.WEBSITE, error.verificationStage)
        assertTrue(error.requiresLogin)
        assertEquals(1, server.requestCount)
        val original = AccountProtocolException("safe", 101108, false, diagnostic = "profile-v1;class=BUSINESS_ID_MISSING")
        val wrapped = assertThrows(AccountProtocolException::class.java) {
            verifyAccountStage(AccountVerificationStage.PROFILE) { throw original }
        }
        assertEquals(original.diagnostic, wrapped.diagnostic)
        assertEquals(AccountVerificationStage.PROFILE, wrapped.verificationStage)
    }

    @Test fun profileStructureDiagnosticsOnlyExposeAllowlistedTypesAndBooleanState() {
        val reply = JSONObject("""{"code":0,"data":{"biz_user_id":null,"has_set_username":true,"user_id":["secret"],"avatar_url":"https://private.invalid/avatar","unknown-secret-key":"secret-value"},"unknown-root":"secret-root"}""")
        val diagnostic = appProfileDiagnostic(reply, website, "1000")
        assertTrue(diagnostic.contains("class=BUSINESS_ID_MISSING"))
        assertTrue(diagnostic.contains("biz_user_id:null"))
        assertTrue(diagnostic.contains("user_id:array"))
        assertTrue(diagnostic.contains("has_set_username=true"))
        assertTrue(diagnostic.contains("agw=unavailable"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private.invalid"))
    }
}
