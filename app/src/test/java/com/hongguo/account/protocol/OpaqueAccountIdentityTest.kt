package com.hongguo.account.protocol

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic identities only: business-account strings are separate from numeric content IDs. */
class OpaqueAccountIdentityTest {
    private val website = AccountCredentials(
        "sessionid=opaque-identity-fixture", "7100000000000000001", "7200000000000000001",
    )
    private val app = website.copy(source = AccountCredentialSource.APP)
    private val opaqueId = "Biz_00Aa:synthetic-Account+/=."

    private fun withServer(block: (MockWebServer, CloudAccountClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
        try {
            block(server, CloudAccountClient(http, server.url("/"), server.url("/register")))
        } finally {
            server.shutdown()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    private fun profileResponse(rawId: Any?, gatewayLogin: String? = "1"): MockResponse {
        val data = JSONObject().put("biz_user_id", rawId ?: JSONObject.NULL)
            .put("user_name", "合成测试账号").put("has_set_username", true)
            .put("req_id", "7300000000000000001")
        return MockResponse().setBody(JSONObject().put("code", 0).put("data", data).toString()).apply {
            if (gatewayLogin != null) setHeader("X-Tt-Agw-Login", gatewayLogin)
        }
    }

    private fun websiteSuccess() = MockResponse()
        .setBody("""{"message":"success","data":{"has_login":true,"is_auth":false}}""")

    @Test fun websiteSuccessAndAuthenticatedOpaqueBusinessIdMatchTheReportedResponseStructure() = withServer { server, client ->
        server.enqueue(websiteSuccess())
        server.enqueue(profileResponse(opaqueId))

        val profile = client.profile(website)

        assertEquals(opaqueId, profile.id)
        assertEquals("合成测试账号", profile.nickname)
        assertEquals(2, server.requestCount)
        assertEquals("/passport/account/info/v2/", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/reading/user/info/v1/", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun explicitlyImportedAppSessionAcceptsOpaqueBusinessIdWithoutAWebsiteRequest() = withServer { server, client ->
        server.enqueue(profileResponse(opaqueId))

        assertEquals(opaqueId, client.profile(app).id)
        assertEquals(1, server.requestCount)
        assertEquals("/reading/user/info/v1/", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun unicodePunctuationCaseAndLeadingZeroesStayExactThroughTheActualProfileResponse() = withServer { server, client ->
        val ids = listOf(
            "000Biz_AbC-09", "账号:云端-甲", "Biz-\uD842\uDFB7", "A\u030A", "\u00C5", "x".repeat(1024),
        )
        for (id in ids) {
            server.enqueue(profileResponse(id))
            assertEquals(id, client.profile(app).id)
        }
        assertEquals(ids.size, server.requestCount)
    }

    @Test fun legacyNumericIdsKeepTheirExactValueWithoutRequiringTheNewGatewaySignal() = withServer { server, client ->
        for (id in listOf("9999999999999999997", "000066831371270")) {
            server.enqueue(profileResponse(id, null))
            assertEquals(id, client.profile(app).id)
        }
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"biz_user_id":9999999999999999997}}"""))
        assertEquals("9999999999999999997", client.profile(app).id)
        assertEquals(3, server.requestCount)
    }

    @Test fun aNumericLookingIdentityBeyondTheLegacyLengthUsesTheAuthenticatedOpaqueRoute() = withServer { server, client ->
        val id = "0".repeat(31) + "9"
        server.enqueue(profileResponse(id))
        assertEquals(id, client.profile(app).id)

        server.enqueue(profileResponse(id, null))
        assertThrows(AccountProtocolException::class.java) { client.profile(app) }
    }

    @Test fun opaqueBusinessIdCannotAuthorizeAnUnknownOrAnonymousGatewayResponse() = withServer { server, client ->
        for (gateway in listOf(null, "0", "2", "01", "untrusted-header-fixture")) {
            server.enqueue(profileResponse(opaqueId, gateway))
            val error = assertThrows(AccountProtocolException::class.java) { client.profile(app) }
            assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
            assertFalse(error.requiresLogin)
            assertFalse(error.message.orEmpty().contains(opaqueId))
            assertFalse(error.diagnostic.orEmpty().contains(opaqueId))
            assertFalse(error.diagnostic.orEmpty().contains("untrusted-header-fixture"))
            assertFalse(error.diagnostic.orEmpty().contains("合成测试账号"))
        }
    }

    @Test fun aWebsiteSessionMustStillPassItsOwnGateBeforeAnOpaqueAppProfileCanBeRead() = withServer { server, client ->
        server.enqueue(MockResponse().setBody("""{"message":"success","data":{"has_login":false}}"""))

        val error = assertThrows(AccountProtocolException::class.java) { client.profile(website) }

        assertEquals(AccountVerificationStage.WEBSITE, error.verificationStage)
        assertTrue(error.requiresLogin)
        assertEquals(1, server.requestCount)
        assertEquals("/passport/account/info/v2/", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun malformedJsonIdentityShapesCannotBeCoercedIntoAnOpaqueBusinessId() = withServer { server, client ->
        val invalid = listOf(
            JSONObject.NULL, true, false, 0, -1, 1.25,
            JSONObject().put("nested", opaqueId), JSONArray().put(opaqueId),
        )
        for (raw in invalid) {
            assertNull(businessAccountId(raw))
            server.enqueue(profileResponse(raw))
            val error = assertThrows(AccountProtocolException::class.java) { client.profile(app) }
            assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
        }
        assertNull(businessAccountId(null))
    }

    @Test fun largeNegativeJsonNumbersDoNotBecomeOpaqueStringIdentities() = withServer { server, client ->
        for (raw in listOf("-9999999999999999", "-99999999999999999999999999999999")) {
            server.enqueue(MockResponse().setHeader("X-Tt-Agw-Login", "1")
                .setBody("""{"code":0,"data":{"biz_user_id":$raw,"has_set_username":true}}"""))
            assertThrows(AccountProtocolException::class.java) { client.profile(app) }
        }
        // A provider-supplied string stays a string; no numeric coercion is applied to it.
        server.enqueue(profileResponse("-opaque-Account:09"))
        assertEquals("-opaque-Account:09", client.profile(app).id)
    }

    @Test fun passportReqAndNestedUserIdsNeverReplaceTheRequiredTopLevelBusinessId() = withServer { server, client ->
        val data = JSONObject().put("user_id", "9999999999999999997")
            .put("req_id", "7300000000000000001").put("has_set_username", true)
            .put("user", JSONObject().put("uid", "9999999999999999997").put("biz_user_id", opaqueId))
        server.enqueue(MockResponse().setHeader("X-Tt-Agw-Login", "1")
            .setBody(JSONObject().put("code", 0).put("data", data).toString()))

        val error = assertThrows(AccountProtocolException::class.java) { client.profile(app) }

        assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
        assertFalse(error.diagnostic.orEmpty().contains(opaqueId))
        assertFalse(error.diagnostic.orEmpty().contains("7300000000000000001"))
        assertFalse(error.diagnostic.orEmpty().contains("9999999999999999997"))
    }

    @Test fun businessIdentityStillCannotBeTheRegisteredDeviceOrInstallation() = withServer { server, client ->
        for (id in listOf(app.deviceId, app.installId)) {
            server.enqueue(profileResponse(id))
            val error = assertThrows(AccountProtocolException::class.java) { client.profile(app) }
            assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
            assertTrue(error.diagnostic.orEmpty().contains("class=DEVICE_IDENTITY"))
            assertFalse(error.diagnostic.orEmpty().contains(id))
        }
    }

    @Test fun opaqueIdentityValidationBoundsAndMalformedUnicodeDoNotNormalizeValues() {
        val accepted = listOf(opaqueId, "0AbC", "0abc", "账号", "A\u030A", "\u00C5", "\uD842\uDFB7".repeat(512), "a".repeat(1024))
        for (id in accepted) {
            assertTrue(validBusinessAccountId(id))
            assertEquals(id, businessAccountId(id))
        }
        val rejected = listOf(
            "", "0", "0000", "null", "undefined", "NULL", "Undefined", "a".repeat(1025), "\uD842\uDFB7".repeat(513),
            " leading", "trailing ", "inner space", "inner\tspace", "a\r\nb", "a\u0000b", "a\u007Fb", "a\u0085b",
            "a\u00A0b", "a\u200Db", "a\u200Eb", "a\u2028b", "a\u2029b", "a\uFEFFb", "a\uD842", "a\uDFB7",
        )
        for (id in rejected) {
            assertFalse(validBusinessAccountId(id))
            assertNull(businessAccountId(id))
        }
    }

    @Test fun profileParsingRejectsWhitespaceRatherThanSilentlyTrimmingTheBusinessIdentity() = withServer { server, client ->
        for (id in listOf(" $opaqueId", "$opaqueId ", "account\nidentity", "null", "0", "a".repeat(1025))) {
            server.enqueue(profileResponse(id))
            val error = assertThrows(AccountProtocolException::class.java) { client.profile(app) }
            assertEquals(AccountVerificationStage.PROFILE, error.verificationStage)
        }
    }

    @Test fun acceptingOpaqueAccountsDoesNotAllowOpaqueContentIdsInAnyCloudWrite() = withServer { server, client ->
        val invalidSeries = Series(opaqueId, "合成剧集")
        val invalidProgress = CloudProgress(invalidSeries, "7300000000000000001", 0, 1000, 2000, 1700000000000)
        assertThrows(IllegalArgumentException::class.java) { client.setFavorite(app, opaqueId, true) }
        assertThrows(IllegalArgumentException::class.java) { client.setFavorite(app, opaqueId, false) }
        assertThrows(IllegalArgumentException::class.java) { client.reserve(app, RelatedWork(invalidSeries, "1", true)) }
        assertThrows(IllegalArgumentException::class.java) { client.upload(app, invalidProgress, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            client.upload(app, invalidProgress.copy(series = Series("7300000000000000001", "合成剧集"), episodeId = opaqueId), 1)
        }
        assertEquals(0, server.requestCount)
    }
}
