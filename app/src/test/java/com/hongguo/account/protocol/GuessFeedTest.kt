// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 猜你喜欢 against a loopback server; card shape follows the anonymous probe of 2026-10-30. */
class GuessFeedTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val credentials = AccountCredentials("sessionid=offline-test", "9999999999999999999", "9999999999999999998",
        device = mapOf("device_type" to "OfflinePhone", "cdid" to "not-forwarded"), appDeviceCookie = "ttreq=offline")

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    private fun client() = GuessFeedClient(http, listOf(server.url("/")))
    private fun feed(session: String = "srv-session") = MockResponse().setHeader("Content-Type", "application/json").setBody("""
        {"code":0,"message":"SUCCESS","data":{"tab_item":[{"tab_type":3,"cell_data":[]},{"tab_type":16,"session_id":"$session",
         "cell_data":[{"cell_name":"猜你喜欢","use_recommend":true,"cell_data":[{"video_data":[
           {"video_detail":{"series_id":"7698294694886788121","series_title":"测试短剧甲","series_cover":"https:\/\/p3.example.invalid\/a.jpg","series_intro":"简介甲","episode_cnt":80}},
           {"book_id":"7697622096326708286","book_name":"测试短剧乙","cover":"https://p3.example.invalid/b.jpg","episode_cnt":100},
           {"series_id":"7698294694886788121","series_title":"重复"},
           {"series_id":"not-a-number","series_title":"无效"}]}]}]}]}}""")

    @Test fun signedInRequestCarriesOnlyTheAccountCookie() {
        server.enqueue(feed())
        val batch = client().page(credentials, "", 0)
        assertEquals(listOf("7698294694886788121", "7697622096326708286"), batch.items.map { it.series.id })
        assertEquals("https://p3.example.invalid/a.jpg", batch.items[0].series.cover)
        assertEquals(80, batch.items[0].episodeCount)
        assertEquals("srv-session", batch.session)
        assertEquals("offset 按服务端下发条数推进，不按过滤后条数", 4, batch.served)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals(GuessFeedClient.PATH, url.encodedPath)
        assertEquals("16", url.queryParameter("tab_type"))
        assertEquals("1", url.queryParameter("client_req_type"))
        assertNull(url.queryParameter("session_id"))
        assertNull("账号态带 device_id 会被判 110", url.queryParameter("device_id"))
        assertNull("账号态带 iid 会被判 110", url.queryParameter("iid"))
        assertEquals("OfflinePhone", url.queryParameter("device_type"))
        assertNull("只转发白名单设备字段", url.queryParameter("cdid"))
        assertEquals("sessionid=offline-test", request.getHeader("Cookie"))
        assertNull(request.getHeader("X-Gorgon"))
    }

    @Test fun anonymousRequestHasNoCookieOrAccountDevice() {
        server.enqueue(feed())
        client().page(null, "srv-session", 2)
        val request = server.takeRequest()
        assertNull(request.getHeader("Cookie"))
        assertNull(request.requestUrl!!.queryParameter("device_id"))
        assertEquals("srv-session", request.requestUrl!!.queryParameter("session_id"))
        assertEquals("2", request.requestUrl!!.queryParameter("client_req_type"))
        assertEquals("2", request.requestUrl!!.queryParameter("offset"))
    }

    @Test fun redirectIsNotFollowedWithTheAccountCookie() {
        val other = MockWebServer(); other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/steal")))
            assertThrows(AccountProtocolException::class.java) { client().page(credentials, "", 0) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun accountWithoutRegisteredDeviceStillGetsTheFeed() {
        server.enqueue(feed())
        assertEquals(2, client().page(AccountCredentials("sessionid=offline-test"), "", 0).items.size)
    }

    @Test fun nonZeroCodeRetriesTheNextHostFromTheStart() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":110,"message":"ILLEGAL_ACCESS"}"""))
            backup.enqueue(feed("new-session"))
            val batch = GuessFeedClient(http, listOf(server.url("/"), backup.url("/"))).page(credentials, "stale", 3)
            assertTrue(batch.restarted)
            assertEquals("new-session", batch.session)
            assertEquals(2, batch.items.size)
            val first = server.takeRequest().requestUrl!!
            assertEquals("stale", first.queryParameter("session_id"))
            assertEquals("3", first.queryParameter("offset"))
            val retry = backup.takeRequest()
            assertNull(retry.requestUrl!!.queryParameter("session_id"))
            assertEquals("0", retry.requestUrl!!.queryParameter("offset"))
            assertEquals("1", retry.requestUrl!!.queryParameter("client_req_type"))
            assertEquals("sessionid=offline-test", retry.getHeader("Cookie"))
        } finally { backup.shutdown() }
    }

    @Test fun nonZeroCodeOnEveryHostIsReported() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":110}"""))
            backup.enqueue(MockResponse().setBody("""{"code":110}"""))
            val error = assertThrows(AccountProtocolException::class.java) {
                GuessFeedClient(http, listOf(server.url("/"), backup.url("/"))).page(null, "", 0)
            }
            assertEquals(110, error.errorCode)
            assertEquals(1, backup.requestCount)
        } finally { backup.shutdown() }
    }

    @Test fun httpFailureDoesNotRotateHosts() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setResponseCode(500))
            assertThrows(AccountProtocolException::class.java) {
                GuessFeedClient(http, listOf(server.url("/"), backup.url("/"))).page(credentials, "", 0)
            }
            assertEquals(0, backup.requestCount)
        } finally { backup.shutdown() }
    }

    @Test fun parseRejectsErrorsAndHeaderInjectionInSession() {
        assertThrows(AccountProtocolException::class.java) { GuessFeedClient.parse(JSONObject("""{"code":1}""")) }
        assertThrows(AccountProtocolException::class.java) { GuessFeedClient.parse(JSONObject("""{"code":0,"data":{"tab_item":[]}}""")) }
        assertThrows(AccountProtocolException::class.java) {
            GuessFeedClient.parse(JSONObject("""{"code":0,"data":{"tab_item":[{"tab_type":16,"session_id":"a\nb"}]}}"""))
        }
        assertTrue(GuessFeedClient.parse(JSONObject("""{"code":0,"data":{"tab_item":[{"tab_type":16}]}}""")).items.isEmpty())
    }

    @Test fun largeFeedResponseIsAccepted() {
        server.enqueue(feed().setBody(feed().getBody()!!.readUtf8().replace("\"简介甲\"", "\"${"简".repeat(1_200_000)}\"")))
        assertEquals(2, client().page(null, "", 0).items.size)
    }
}
