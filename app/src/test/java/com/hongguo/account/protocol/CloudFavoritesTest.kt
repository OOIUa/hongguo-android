// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Issue #6: accounts on the light shelf logic get `data: null` from video/info. */
class CloudFavoritesTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val replies = mutableMapOf<String, String>()
    private val credentials = AccountCredentials("sessionid=private-fixture", "7100000000000000001", "7200000000000000001")

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(replies[request.requestUrl!!.encodedPath] ?: """{"code":0,"data":null}""")
        }
        server.start()
    }
    @After fun stop() { server.shutdown(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }

    private fun client() = CloudAccountClient(http, server.url("/"), server.url("/service/2/device_register/"))
    private fun paths() = (0 until server.requestCount).map { server.takeRequest().requestUrl!!.encodedPath }

    @Test fun lightListIsReadAndNamedFromTheDetailBatch() {
        replies[LIST] = """{"code":0,"data":{"max_bookshelf_cnt":10000,"video_shelf_info":[
            {"series_id":7702763145030290457,"book_type":2,"collect_time":1762000000},
            {"series_id":"7702000000000000002","book_type":2,"collect_time":1763000000},
            {"series_id":"bad"}]}}"""
        replies[DETAIL] = """{"code":0,"data":[{"book_id":"7702763145030290457","book_name":"万妖图录传","thumb_url":"https://p.example.invalid/a.jpg","episode_cnt":80}]}"""
        val rows = client().favorites(credentials)
        assertEquals(listOf("7702000000000000002", "7702763145030290457"), rows.map { it.series.id })
        assertEquals(1763000000000L, rows[0].collectedAt)
        assertEquals("剧集 7702000000000000002", rows[0].series.title)
        assertEquals("万妖图录传", rows[1].series.title)
        assertEquals("全 80 集", rows[1].series.badge)
        assertEquals(listOf(LIST, DETAIL), paths())
    }

    @Test fun emptyLightListTrustsANullLegacyShelfAsEmpty() {
        replies[LIST] = """{"code":0,"data":{"video_shelf_info":[],"max_bookshelf_cnt":10000}}"""
        assertTrue(client().favorites(credentials).isEmpty())
        assertEquals(listOf(LIST, INFO), paths())
    }

    @Test fun emptyLightListStillShowsAnOldLogicShelf() {
        replies[LIST] = """{"code":0,"data":{"video_shelf_info":null}}"""
        replies[INFO] = """{"code":0,"data":{"video_shelf_info":[{"video_data":{"series_id":"701","title":"甲"},"collect_time":5}]}}"""
        assertEquals(listOf("701"), client().favorites(credentials).map { it.series.id })
    }

    @Test fun failedLightListFallsBackToTheLegacyShelf() {
        replies[LIST] = """{"code":4,"message":"unknown"}"""
        replies[INFO] = """{"code":0,"data":{"video_shelf_info":[{"video_data":{"series_id":"701","title":"甲"},"collect_time":5}]}}"""
        assertEquals(listOf("701"), client().favorites(credentials).map { it.series.id })
        // Without a light answer a null legacy shelf is still a format error, so the cached shelf is kept.
        replies[INFO] = """{"code":0,"data":null}"""
        assertThrows(java.io.IOException::class.java) { client().favorites(credentials) }
    }

    @Test fun expiredSessionOnTheLightListIsNotMaskedByTheFallback() {
        replies[LIST] = """{"code":1003}"""
        val error = assertThrows(AccountProtocolException::class.java) { client().favorites(credentials) }
        assertTrue(error.requiresLogin)
        assertEquals(listOf(LIST), paths())
    }

    private companion object {
        const val LIST = "/reading/bookapi/bookshelf/video/list/v1/"
        const val INFO = "/reading/bookapi/bookshelf/video/info/v1/"
        const val DETAIL = "/reading/bookapi/multi-detail/v1/"
    }
}
