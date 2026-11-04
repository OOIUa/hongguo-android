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

class RelatedWorksTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }

    private fun row(id: String, type: Int, tag: String, episodes: Int = 80, extra: String = "") =
        """{"series_id":"$id","title":"作品$id","content_type":$type,"episode_cnt":$episodes,"cover":"https:\\/\\/p.example.invalid\\/$id.jpg","video_desc":"简介","tag_info":{"text":"$tag"}$extra}"""
    private fun plan(vararg rows: String) = """{"code":0,"data":[{"cell_name":"相关作品","show_type":547,"video_data":[${rows.joinToString(",")}]},
        {"cell_name":"猜你喜欢","show_type":506,"video_data":[${row("9", 1004, "")}]}]}"""
    private fun work(n: Int, upcoming: Boolean = false, id: String = "7$n") = RelatedWork(Series(id, "第$n", "", "", ""), "第${n}季", upcoming, n)

    @Test fun parseKeepsThePlayableRelatedCellOnly() {
        val works = RelatedWorks.parse(JSONObject(plan(row("1", 1004, "第1季"), row("2", 1, "第2季"), row("3", 1007, "第4季", 0), row("4", 1002, "第5季", 0),
            row("5", 1004, "同IP"), row("100", 1004, "第3季"), row("6", 5, "小说"), row("7", 1004, "第6季", 0), row("8", 1004, "第7季", 10, ",\"disable_play\":true"))), "100")
        assertEquals(listOf("1", "2", "3", "4", "5"), works.map { it.series.id })
        assertEquals("https://p.example.invalid/1.jpg", works[0].series.cover)
        assertEquals(listOf(1, 2, 4, 5, 0), works.map { it.seasonNumber })
        assertEquals(listOf(false, false, true, true, false), works.map { it.upcoming })
        assertEquals(80, works[0].episodeCount)
    }

    @Test fun parseRejectsAFailedReply() {
        assertThrows(AccountProtocolException::class.java) { RelatedWorks.parse(JSONObject("""{"code":1}"""), "1") }
        assertThrows(AccountProtocolException::class.java) { RelatedWorks.parse(JSONObject("""{"code":0}"""), "1") }
    }

    @Test fun nextSeasonIsTheOneAfterTheGap() {
        assertEquals(3, RelatedWorks.nextSeason(listOf(work(1), work(3), work(4)))!!.seasonNumber)
        assertEquals(2, RelatedWorks.nextSeason(listOf(work(2)))!!.seasonNumber)
        assertTrue(RelatedWorks.nextSeason(listOf(work(1), work(2), work(3, upcoming = true), work(5)))!!.seasonNumber == 5)
    }

    @Test fun nextSeasonIsNullWhenCurrentIsNewestOrAmbiguous() {
        assertNull("没有缺口说明当前已是最新一季", RelatedWorks.nextSeason(listOf(work(1), work(2))))
        assertNull("多个缺口无法判断", RelatedWorks.nextSeason(listOf(work(1), work(4))))
        assertNull(RelatedWorks.nextSeason(emptyList()))
        assertNull("同IP 不算季", RelatedWorks.nextSeason(listOf(RelatedWork(Series("1", "a"), "同IP", false))))
    }

    @Test fun likesAndWatchRecordsParse() {
        val likes = CloudAccountClient.parseLikes(JSONObject("""{"data":{"mixed_data_list":[
            {"data_type":1,"video_data":{"series_id":"701","title":"甲","cover":"https:\\/\\/p\\/1.jpg","episode_cnt":80,"user_digg_timestamp_ms":2000}},
            {"video_data":{"series_id":"702","title":"乙","create_time":3}},{"video_data":{"series_id":"bad","title":"无效"}},{"other":1}]}}"""))
        assertEquals(listOf("702", "701"), likes.map { it.series.id })
        assertEquals("全 80 集", likes[1].series.badge)
        val records = CloudAccountClient.parseReadHistory(JSONObject("""{"data":{"data_list":[
            {"book_id":"701","vid":"801","vid_index":3,"series_title":"甲","current_play_position":5000,"duration":60000,"read_timestamp_ms":1700000000000,"episode_cnt":80},
            {"book_id":"701","vid":"800","vid_index":2,"series_title":"甲","read_timestamp_ms":1600000000000},
            {"series_id":"702","item_id":"802","vid_index":1,"last_watch_time":1700000001},
            {"book_id":"703","vid":"803","is_delete":true},{"book_id":"bad","vid":"804"}]}}"""))
        assertEquals(listOf("702", "701"), records.map { it.series.id })
        assertEquals("801", records[1].episodeId)
        assertEquals(2, records[1].episodeIndex)
        assertEquals(1700000001000, records[0].updatedAt)
        assertEquals("剧集 702", records[0].series.title)
        assertThrows(AccountProtocolException::class.java) { CloudAccountClient.parseReadHistory(JSONObject("{}")) }
    }

    @Test fun requestIsSignedAnonymousAndKeepsTheVersionPlaceholder() {
        server.enqueue(MockResponse().setBody(plan(row("1", 1004, "第1季"))))
        val works = RelatedWorksClient(http, listOf(server.url("/"))).related("123")
        assertEquals(1, works.size)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/reading/bookapi/plan/v:version/?"))
        assertEquals("123", request.requestUrl!!.queryParameter("book_id"))
        assertEquals("10", request.requestUrl!!.queryParameter("scene"))
        assertNull(request.getHeader("Cookie"))
        assertNotNull(request.getHeader("x-gorgon"))
    }

    @Test fun failedHostMovesToTheNext() {
        val backup = MockWebServer(); backup.start()
        try {
            server.enqueue(MockResponse().setBody(""))
            backup.enqueue(MockResponse().setBody(plan(row("1", 1004, "第1季"))))
            assertEquals(1, RelatedWorksClient(http, listOf(server.url("/"), backup.url("/"))).related("123").size)
        } finally { backup.shutdown() }
    }

    @Test fun invalidIdIsRejectedBeforeAnyRequest() {
        assertThrows(IllegalArgumentException::class.java) { RelatedWorksClient(http, listOf(server.url("/"))).related("../x") }
        assertEquals(0, server.requestCount)
    }
}
