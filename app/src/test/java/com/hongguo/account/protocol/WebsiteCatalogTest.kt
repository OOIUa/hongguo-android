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

class WebsiteCatalogTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }

    private fun escape(json: String) = json.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#x27;")
    private fun page(route: String, total: Int = 5, number: Int = 1, success: Boolean = true) = """<html><script async data-fn-name="mergeLoaderData" data-fn-args="${escape("""["rank_$route/page",[{"key":"content"}]]""")}"></script>
        <script async data-fn-name="r" data-fn-args='${escape("""["rank_$route/page","content",{"isSuccess":$success,"rankList":[
        {"id":"701","seriesId":"701","rank":21,"title":"甲&乙","cover":"https://p.example.invalid/1.jpg","heatText":"9万热度","description":"it's","episodeCount":80},
        {"seriesId":"702","rank":22,"title":"丙","episodeVids":["1","2"]},{"seriesId":"bad","title":"无效"},{"seriesId":"701","title":"重复"}],
        "pagination":{"pageNum":$number,"totalPages":$total}}]""")}'></script></html>"""

    @Test fun parsesTheEscapedRouterPayload() {
        val result = WebsiteCatalog.parseRank(page("hot-drama"), RankBoard.ALL, 1)
        assertEquals(listOf("701", "702"), result.entries.map { it.series.id })
        assertEquals("甲&乙", result.entries[0].series.title)
        assertEquals("it's", result.entries[0].series.description)
        assertEquals(21, result.entries[0].rank)
        assertEquals(80, result.entries[0].episodeCount)
        assertEquals(2, result.entries[1].episodeCount)
        assertEquals(5, result.totalPages)
    }

    /** Shape served since 2026-11-11 (issue #8): loader data inline, the fn-args call carries no payload. */
    private fun inlinePage(route: String, number: Int = 1) = """<html><script async="" data-script-src="modern-inline">_ROUTER_DATA = {"loaderData":{"rank_layout":null,
        "rank_${route}/page":{"pageNum":$number,"content":{"isSuccess":true,"rankList":[
        {"id":"801","seriesId":"801","rank":1,"title":"甲</b>","cover":"https://p.example.invalid/1.jpg","heatText":"8835万热度","episodeCount":120},
        {"seriesId":"802","rank":2,"title":"乙"}],"pagination":{"pageNum":$number,"totalPages":5}}}},"errors":null};
        function runWindowFn(){}</script><script async="" data-fn-name="mergeLoaderData" data-fn-args="[&quot;rank_$route/page&quot;,[]]">runWindowFn();</script></html>"""

    @Test fun parsesTheInlineRouterData() {
        val result = WebsiteCatalog.parseRank(inlinePage("hot-comic-drama", 2), RankBoard.COMIC, 2)
        assertEquals(listOf("801", "802"), result.entries.map { it.series.id })
        assertEquals("https://p.example.invalid/1.jpg", result.entries[0].series.cover)
        assertEquals(120, result.entries[0].episodeCount)
        assertEquals(5, result.totalPages)
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank(inlinePage("hot-drama"), RankBoard.COMIC, 1) }
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank(inlinePage("hot-comic-drama", 3), RankBoard.COMIC, 1) }
    }

    @Test fun rejectsAnotherBoardFailureOrPageMismatch() {
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank(page("hot-ai-drama"), RankBoard.ALL, 1) }
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank(page("hot-drama", success = false), RankBoard.ALL, 1) }
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank(page("hot-drama", number = 3), RankBoard.ALL, 1) }
        assertThrows(AccountProtocolException::class.java) { WebsiteCatalog.parseRank("<html></html>", RankBoard.ALL, 1) }
    }

    @Test fun rankRequestIsAnonymous() {
        server.enqueue(MockResponse().setBody(page("hot-real-drama", number = 2)))
        val result = WebsiteCatalogClient(http, server.url("/")).rank(RankBoard.REAL, 2)
        assertEquals(2, result.page)
        val request = server.takeRequest()
        assertEquals("/rank/hot-real-drama?page=2", request.path)
        assertNull(request.getHeader("Cookie"))
        server.enqueue(MockResponse().setBody(page("hot-drama")))
        WebsiteCatalogClient(http, server.url("/")).rank(RankBoard.ALL, 1)
        assertEquals("第 1 页不带参数，否则官网会跳转", "/rank/hot-drama", server.takeRequest().path)
    }

    @Test fun suggestionsPreferNameAndStayBounded() {
        val rows = (1..12).joinToString(",") { """{"name":"剧$it"}""" }
        assertEquals(8, WebsiteCatalog.parseSuggestions(JSONObject("""{"suggest_list":[$rows]}""")).size)
        assertEquals(listOf("甲", "乙"), WebsiteCatalog.parseSuggestions(JSONObject("""{"suggest_list":[{"name":"甲"},{"display_words":["乙"]},{"name":"甲"},{"name":" "}]}""")))
        assertTrue(WebsiteCatalog.parseSuggestions(JSONObject("{}")).isEmpty())
    }

    @Test fun blankSuggestionQuerySendsNothing() {
        assertTrue(WebsiteCatalogClient(http, server.url("/")).suggestions("  ").isEmpty())
        assertEquals(0, server.requestCount)
    }
}
