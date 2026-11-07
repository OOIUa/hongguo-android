// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import com.hongguo.api.service.PlaybackDevice
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
    /** Unsigned unless a registered identity is given; never registers over the network. */
    private fun client(vararg hosts: okhttp3.HttpUrl = arrayOf(server.url("/")), identity: () -> PlaybackDevice.Identity? = { null },
        forget: (PlaybackDevice.Identity) -> Unit = {}) = GuessFeedClient(http, hosts.toList(), identity, forget)
    private fun cursor(session: String = "", offset: Int = 0) = GuessCursor().also { it.session = session; it.offset = offset }
    private val registered = PlaybackDevice.Identity("3333333333333333", "4444444444444444", "cdid-test", "install_id=4444444444444444")
    private fun feed(session: String = "srv-session") = MockResponse().setHeader("Content-Type", "application/json").setBody("""
        {"code":0,"message":"SUCCESS","data":{"tab_item":[{"tab_type":3,"cell_data":[]},{"tab_type":16,"session_id":"$session",
         "cell_data":[{"cell_name":"猜你喜欢","use_recommend":true,"cell_data":[{"video_data":[
           {"video_detail":{"series_id":"7698294694886788121","series_title":"测试短剧甲","series_cover":"https:\/\/p3.example.invalid\/a.jpg","series_intro":"简介甲","episode_cnt":80}},
           {"book_id":"7697622096326708286","book_name":"测试短剧乙","cover":"https://p3.example.invalid/b.jpg","episode_cnt":100},
           {"series_id":"7698294694886788121","series_title":"重复"},
           {"series_id":"not-a-number","series_title":"无效"}]}]}]}]}}""")

    @Test fun signedInRequestCarriesOnlyTheAccountCookie() {
        server.enqueue(feed())
        val batch = client().page(credentials, cursor())
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
        client().page(null, cursor("srv-session", 2))
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
            assertThrows(AccountProtocolException::class.java) { client().page(credentials, cursor()) }
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun accountWithoutRegisteredDeviceStillGetsTheFeed() {
        server.enqueue(feed())
        assertEquals(2, client().page(AccountCredentials("sessionid=offline-test"), cursor()).items.size)
    }

    @Test fun nonZeroCodeRetriesTheNextHostFromTheStart() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":110,"message":"ILLEGAL_ACCESS"}"""))
            backup.enqueue(feed("new-session"))
            val batch = client(server.url("/"), backup.url("/")).page(credentials, cursor("stale", 3))
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
                client(server.url("/"), backup.url("/")).page(null, cursor())
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
                client(server.url("/"), backup.url("/")).page(credentials, cursor())
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
        assertEquals(2, client().page(null, cursor()).items.size)
    }

    private fun infiniteFeed(session: String = "srv-session") = feed(session).setBody(feed(session).getBody()!!.readUtf8()
        .replace("\"tab_type\":16,", "\"tab_type\":16,\"bottom_unlimited\":true,\"client_template\":7,\"bookstore_id\":\"8001\",\"version_tag\":\"fixture-v7\",")
        .replace("\"cell_name\":\"猜你喜欢\"", "\"cell_id\":\"9001\",\"cell_name\":\"猜你喜欢\""))

    private fun cellReply(id: String, nextOffset: Int) = MockResponse().setBody("""
        {"code":0,"data":{"next_offset":$nextOffset,"session_id":"cell-session","has_more":false,
         "cell_view":{"cell_data":[{"video_data":[{"series_id":"$id","series_title":"续刷$id","episode_cnt":20}]}]}}}""")

    @Test fun largeBatchKeepsWorksAfterTheThirtieth() {
        val rows = (0 until 45).joinToString(",") { """{"series_id":"${7600000000000000000L + it}","series_title":"作品$it"}""" }
        server.enqueue(MockResponse().setBody("""{"code":0,"data":{"tab_item":[{"tab_type":16,"cell_data":[{"cell_data":[{"video_data":[$rows]}]}]}]}}"""))
        val batch = client().page(null, cursor())
        assertEquals(45, batch.items.size)
        assertEquals(45, batch.served)
    }

    @Test fun signedRequestUsesTheRegisteredIdentityAndItsCookie() {
        server.enqueue(feed())
        val c = cursor()
        client(identity = { registered }).page(null, c)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertNotNull(request.getHeader("x-medusa"))
        assertNotNull(request.getHeader("x-gorgon"))
        assertEquals("3333333333333333", url.queryParameter("device_id"))
        assertEquals("4444444444444444", url.queryParameter("iid"))
        assertEquals("74332", url.queryParameter("version_code"))
        assertEquals("7.4.3.32", url.queryParameter("version_name"))
        assertEquals("74332", url.queryParameter("pv_player"))
        assertEquals(1, url.queryParameterValues("_rticket").size)
        assertTrue(request.getHeader("User-Agent")!!.startsWith("com.phoenix.read/74332"))
        assertEquals("install_id=4444444444444444", request.getHeader("Cookie"))
        assertEquals("entry offset advances by rows served", 4, c.offset)
    }

    @Test fun signedInSignedRequestCarriesTheAccountCookieAndFallsBackOnExplicit110() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody("""{"code":110,"message":"ILLEGAL_ACCESS"}"""))
            backup.enqueue(feed())
            val c = cursor()
            val batch = client(server.url("/"), backup.url("/"), identity = { registered }).page(credentials, c)
            assertEquals(2, batch.items.size)
            assertTrue(c.unsigned)
            val signed = server.takeRequest()
            assertEquals("sessionid=offline-test", signed.getHeader("Cookie"))
            assertEquals("3333333333333333", signed.requestUrl!!.queryParameter("device_id"))
            assertNotNull(signed.getHeader("x-medusa"))
            val fallback = backup.takeRequest()
            assertNull(fallback.getHeader("x-medusa"))
            assertNull(fallback.requestUrl!!.queryParameter("device_id"))
            assertEquals("73532", fallback.requestUrl!!.queryParameter("version_code"))
            assertEquals("sessionid=offline-test", fallback.getHeader("Cookie"))
        } finally { backup.shutdown() }
    }

    @Test fun emptySignedReplyDropsTheRecommendationIdentity() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody(""))
            backup.enqueue(feed())
            val forgotten = mutableListOf<PlaybackDevice.Identity>()
            val batch = client(server.url("/"), backup.url("/"), identity = { registered }, forget = { forgotten += it })
                .page(null, cursor("stale", 3))
            assertTrue(batch.restarted)
            assertEquals(listOf(registered), forgotten)
            assertEquals("0", backup.takeRequest().requestUrl!!.queryParameter("offset"))
        } finally { backup.shutdown() }
    }

    @Test fun unlimitedEntrySwitchesToCellContinuationWithTheServerCursor() {
        server.enqueue(infiniteFeed())
        server.enqueue(cellReply("7600000000000000102", 6))
        server.enqueue(cellReply("7600000000000000103", 12))
        val c = cursor()
        val client = client()
        client.page(null, c)
        assertEquals(GuessFlow("9001", "8001", "fixture-v7"), c.flow)
        assertEquals(0, c.offset)
        c.filterIds = listOf("7698294694886788121", "7697622096326708286")
        assertEquals("7600000000000000102", client.page(null, c).items.single().series.id)
        assertEquals(6, c.offset)
        assertEquals("cell-session", c.session)
        client.page(null, c)
        assertEquals(12, c.offset)
        val entry = server.takeRequest().requestUrl!!
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        assertEquals(GuessFeedClient.PATH, entry.encodedPath)
        assertEquals(GuessFeedClient.CELL_PATH, first.encodedPath)
        assertEquals("0", first.queryParameter("offset"))
        assertEquals("9001", first.queryParameter("cell_id"))
        assertEquals("8001", first.queryParameter("plan_id"))
        assertEquals("fixture-v7", first.queryParameter("version_tag"))
        assertEquals("7", first.queryParameter("client_template"))
        assertEquals("0", first.queryParameter("change_type"))
        assertEquals("2", first.queryParameter("client_req_type"))
        assertEquals("7698294694886788121,7697622096326708286", first.queryParameter("filter_ids"))
        assertEquals("6", second.queryParameter("offset"))
        assertEquals("cell-session", second.queryParameter("session_id"))
    }

    @Test fun cellContinuationRejectsAStalledCursor() {
        server.enqueue(infiniteFeed())
        server.enqueue(cellReply("7600000000000000102", 0))
        val c = cursor()
        client().page(null, c)
        val error = assertThrows(AccountProtocolException::class.java) { client().page(null, c) }
        assertTrue(error.message!!.contains("游标未前进"))
    }

    @Test fun entryWithoutCompleteContextKeepsTabPaging() {
        server.enqueue(feed().setBody(infiniteFeed().getBody()!!.readUtf8().replace("\"8001\"", "\"not-a-plan\"")))
        val c = cursor()
        client().page(null, c)
        assertNull(c.flow)
        assertEquals(4, c.offset)
    }

    @Test fun changedIdentityRereadsTheEntry() {
        server.enqueue(infiniteFeed())
        server.enqueue(feed("again"))
        val other = PlaybackDevice.Identity("5555555555555555", "6666666666666666", "cdid-2", "")
        var current = registered
        val c = cursor()
        val client = client(identity = { current })
        client.page(null, c)
        current = other
        assertTrue(client.page(null, c).restarted)
        server.takeRequest()
        val reread = server.takeRequest().requestUrl!!
        assertEquals(GuessFeedClient.PATH, reread.encodedPath)
        assertEquals("0", reread.queryParameter("offset"))
        assertEquals("5555555555555555", reread.queryParameter("device_id"))
    }
}
