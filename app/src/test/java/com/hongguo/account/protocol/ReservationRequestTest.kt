package com.hongguo.account.protocol

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReservationRequestTest {
    @Test fun rejectionKeepsItsBusinessCodeAndDoesNotReplayTheAuthenticatedWrite() {
        val server = MockWebServer(); server.start()
        val http = OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("""{"code":101144,"message":"HIT_SHARK_RULE"}"""))
            val credentials = AccountCredentials("sessionid=fixture; install_id=stale", "7100000000000000001", "7200000000000000001")
            val client = CloudAccountClient(http, server.url("/"), server.url("/register/"))
            val work = RelatedWork(Series("7300000000000000001", "预约测试"), "第2季", true)
            val error = assertThrows(AccountProtocolException::class.java) { client.reserve(credentials, work) }
            assertEquals(101144, error.errorCode)
            assertTrue(error.businessRefusal)
            assertFalse(error.requiresLogin)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/reading/bookapi/search/uncover_subscribe/v1/", request.requestUrl!!.encodedPath)
            assertEquals(credentials.deviceId, request.requestUrl!!.queryParameter("device_id"))
            assertEquals(credentials.installId, request.requestUrl!!.queryParameter("iid"))
            assertEquals(appRequestCookie(credentials), request.getHeader("Cookie"))
            assertEquals("gzip", request.getHeader("Content-Encoding"))
            val wire = request.body.readByteArray()
            val digest = MessageDigest.getInstance("MD5").digest(wire).joinToString("") { "%02X".format(it.toInt() and 255) }
            assertEquals(digest, request.getHeader("x-ss-stub"))
            val body = JSONObject(GZIPInputStream(ByteArrayInputStream(wire)).bufferedReader().use { it.readText() })
            assertEquals(work.series.id, body.get("item_id").toString())
            assertEquals(3, body.getInt("item_type"))
            assertEquals(1, body.getInt("op_type"))
            assertEquals(0, body.getInt("subscribe_source"))
        } finally {
            server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
        }
    }
}
