// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android.comments

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesReviewRepositoryTest {
    private val series = "7710164882192075801"
    private val identity = ReviewIdentity(
        deviceId = "1000000000000001",
        installId = "1000000000000002",
        device = mapOf("cdid" to "cid", "openudid" to "oid"),
        cookies = mapOf(
            "install_id" to "1000000000000002",
            "ttreq" to "token",
            "store-region" to "cn",
            "store-region-src" to "did",
        ),
    )

    private fun pageJson(offset: Int = 0, hasMore: Boolean = true): String {
        val rows = JSONArray()
            .put(
                JSONObject()
                    .put("comment_id", "c-$offset-1")
                    .put("group_id", series)
                    .put("status", 1)
                    .put("text", "好看")
                    .put("create_timestamp", 1_795_461_371)
                    .put("digg_count", 3)
                    .put("score", "10")
                    .put("user_info", JSONObject().put("user_name", "观众甲")),
            )
            .put(
                JSONObject()
                    .put("comment_id", "c-$offset-2")
                    .put("group_id", series)
                    .put("status", 1)
                    .put("text", "也不错")
                    .put("create_timestamp", 1_795_461_372)
                    .put("digg_count", 1)
                    .put("score", "8")
                    .put("user_info", JSONObject().put("user_name", "观众乙")),
            )
        return JSONObject()
            .put("code", 0)
            .put(
                "data",
                JSONObject()
                    .put("comment_cnt", 42)
                    .put("score_cnt", 42)
                    .put("has_more", hasMore)
                    .put("next_offset", offset + 20)
                    .put("comment", rows)
                    .put("book_info", JSONObject().put("score", "8.6").put("book_name", "测试剧")),
            )
            .toString()
    }

    private fun repository(
        registrations: IntArray = intArrayOf(0),
        respond: (Request, Int) -> Response,
    ): Pair<SeriesReviewRepository, MutableList<Request>> {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            respond(request, requests.size)
        }.build()
        val repo = SeriesReviewRepository(
            http = http,
            register = {
                registrations[0]++
                identity
            },
        )
        return repo to requests
    }

    private fun response(request: Request, code: Int = 200, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "err")
            .body(body.toResponseBody())
            .build()

    @Test
    fun sendsTheVerifiedQueryAndHeadersAndParsesThePage() {
        val (repo, requests) = repository { request, _ -> response(request, body = pageJson()) }
        val page = runBlocking { repo.page(series, 0) }
        assertEquals(2, page.items.size)
        assertEquals(42, page.total)
        assertEquals(8.6, page.averageScore, 0.0001)
        assertEquals("测试剧", page.bookName)
        assertEquals(20, page.nextOffset)
        assertTrue(page.hasMore)

        val request = requests.single()
        val url = request.url
        assertEquals("/reading/ugc/novel_comment/book/v1/", url.encodedPath)
        assertEquals(series, url.queryParameter("book_id"))
        assertEquals(series, url.queryParameter("group_id"))
        assertEquals("0", url.queryParameter("offset"))
        assertEquals("20", url.queryParameter("count"))
        assertEquals("1", url.queryParameter("sort"))
        assertEquals("1000000000000001", url.queryParameter("device_id"))
        assertEquals("1000000000000002", url.queryParameter("iid"))
        assertNotNull(url.queryParameter("ts"))
        assertNotNull(url.queryParameter("_rticket"))

        assertNotNull(request.header("x-khronos"))
        assertNotNull(request.header("x-helios"))
        assertNotNull(request.header("x-medusa"))
        assertTrue(request.header("x-reading-request").orEmpty().isNotBlank())
        assertTrue(request.header("Cookie").orEmpty().contains("install_id=1000000000000002"))
        assertTrue(request.header("User-Agent").orEmpty().startsWith("com.phoenix.read"))
    }

    @Test
    fun offsetIsForwardedForTheNextBatch() {
        val (repo, requests) = repository { request, _ -> response(request, body = pageJson(offset = 20)) }
        val page = runBlocking { repo.page(series, 20) }
        assertEquals(40, page.nextOffset)
        assertEquals("20", requests.single().url.queryParameter("offset"))
    }

    @Test
    fun emptyGatewayReplyRotatesTheIdentityOnceAndRecovers() {
        val registrations = intArrayOf(0)
        val (repo, requests) = repository(registrations) { request, call ->
            if (call <= 2) response(request, body = "") else response(request, body = pageJson())
        }
        val page = runBlocking { repo.page(series, 0) }
        assertEquals(2, page.items.size)
        assertEquals(2, registrations[0])
        assertEquals(3, requests.size)
    }

    @Test
    fun secondHostRecoversWhenTheFirstFails() {
        val (repo, requests) = repository { request, call ->
            if (call == 1) response(request, code = 500, body = "error") else response(request, body = pageJson())
        }
        val page = runBlocking { repo.page(series, 0) }
        assertEquals(2, page.items.size)
        assertEquals(listOf("api5-normal-sinfonlinea.fqnovel.com", "api3-normal-sinfonlinea.fqnovel.com"),
            requests.map { it.url.host })
    }

    @Test
    fun businessErrorIsReportedWithoutRotatingTheIdentity() {
        val registrations = intArrayOf(0)
        val (repo, requests) = repository(registrations) { request, _ ->
            response(request, body = JSONObject().put("code", 8).put("message", "busy").toString())
        }
        val failure = runCatching { runBlocking { repo.page(series, 0) } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure?.message.orEmpty().isBlank())
        assertEquals(1, registrations[0])
        assertEquals(2, requests.size)
    }

    @Test
    fun invalidIdentityAndCursorAreRejectedBeforeAnyRequest() {
        val (repo, requests) = repository { request, _ -> response(request, body = pageJson()) }
        assertTrue(runCatching { runBlocking { repo.page("abc", 0) } }.isFailure)
        assertTrue(runCatching { runBlocking { repo.page(series, -1) } }.isFailure)
        assertTrue(requests.isEmpty())
    }
}
