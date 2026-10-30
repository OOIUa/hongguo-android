// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv/core RecommendationFeed.kt at
// 97c5b1d15d (2026-10-30). Path and card shape referenced there from Xanerix/guoapp.
package com.hongguo.account.protocol

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GuessCard(val series: Series, val episodeCount: Int)

/** One server batch. [session] is the server pagination token; keep it in memory only. */
data class GuessBatch(val items: List<GuessCard>, val session: String)

/**
 * Official App 书城「猜你喜欢」流（bookmall tab_type=16）。Unsigned GET. With [AccountCredentials]
 * it carries the account cookie and the account's registered device so the server can
 * personalise; without them it is the anonymous feed. No exposure/click/finish reporting.
 */
class GuessFeedClient internal constructor(http: OkHttpClient, private val apiBase: HttpUrl) {
    constructor(http: OkHttpClient) : this(http, "https://api5-normal-sinfonlinea.fqnovel.com".toHttpUrl())
    private val transport = ProtocolTransport(http)

    fun cancelAll() = transport.cancelAll()

    fun page(credentials: AccountCredentials?, session: String, offset: Int): GuessBatch {
        require(offset in 0..MAX_OFFSET) { "猜你喜欢分页超出范围" }
        if (credentials != null && (!validIdentifier(credentials.deviceId) || !validIdentifier(credentials.installId)))
            throw AccountProtocolException("账号设备身份无效，请重新登录", requiresLogin = true)
        val url = apiBase.resolve(PATH)!!.newBuilder()
        params(credentials, session, offset).forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build())
            .header("User-Agent", USER_AGENT).header("Accept", "application/json")
            .header("X-XS-From-Web", "0").header("Sdk-Version", "2")
        credentials?.let { request.header("Cookie", appRequestCookie(it)) }
        return transport.execute(request.build()) { parse(parseObject(cloudResponse(it, RESPONSE_LIMIT))) }
    }

    companion object {
        internal const val PATH = "/reading/bookapi/bookmall/tab/v1/"
        internal const val MAX_OFFSET = 100
        private const val USER_AGENT = "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)"
        /** The mall tab ships full episode lists per card (≈1.4 MiB for 3 cards); the 2 MiB account cap is too small. */
        private const val RESPONSE_LIMIT = 16 * 1024 * 1024
        private val DEVICE_KEYS = setOf("channel", "device_platform", "os", "ssmix", "device_type", "device_brand",
            "language", "os_api", "os_version", "resolution", "dpi", "ac")

        internal fun params(credentials: AccountCredentials?, session: String, offset: Int): Map<String, String> =
            linkedMapOf(
                "aid" to "8662", "app_name" to "novelread", "channel" to "update_64", "device_platform" to "android",
                "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
                "language" to "zh", "os_api" to "36", "os_version" to "16", "resolution" to "1280*2772",
                "dpi" to "520", "ac" to "wifi",
            ).apply {
                credentials?.let {
                    putAll(it.device.filterKeys { key -> key in DEVICE_KEYS })
                    put("device_id", it.deviceId)
                    put("iid", it.installId)
                }
                put("version_code", "73532"); put("version_name", "7.3.5.32")
                put("manifest_version_code", "73532"); put("update_version_code", "73532")
                put("tab_type", "16"); put("bottom_tab_type", "7"); put("landing_bottom_tab_type", "7")
                put("offset", offset.toString())
                put("client_req_type", if (offset == 0) "1" else "2")
                put("_rticket", System.currentTimeMillis().toString())
                if (session.isNotEmpty()) put("session_id", session)
            }

        internal fun parse(result: JSONObject): GuessBatch {
            val code = result.optInt("code", -1)
            if (code != 0) throw AccountProtocolException("猜你喜欢暂不可用（错误码 $code），请稍后刷新", code)
            val tab = result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()?.firstOrNull { it.str("tab_type") == "16" }
                ?: throw AccountProtocolException("猜你喜欢数据格式已变化")
            val session = tab.str("session_id")
            if (session.length > 4096 || session.any { it == '\r' || it == '\n' || it.code < 32 })
                throw AccountProtocolException("猜你喜欢分页标记无效")
            val items = tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
                cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                    card.optJSONArray("video_data")?.objects().orEmpty().map { it.optJSONObject("video_detail") ?: it }
                }
            }.mapNotNull { row ->
                val id = row.str("series_id").ifBlank { row.str("book_id") }
                val title = row.str("series_title").ifBlank { row.str("book_name") }.ifBlank { row.str("title") }
                if (!id.matches(Regex("[0-9]{1,24}")) || title.isBlank()) null
                else GuessCard(Series(id, title, cleanUrl(row.str("series_cover").ifBlank { row.str("cover") }),
                    row.str("series_intro").ifBlank { row.str("video_desc") }), row.optInt("episode_cnt").coerceAtLeast(0))
            }.distinctBy { it.series.id }.take(30)
            return GuessBatch(items, session)
        }
    }
}
