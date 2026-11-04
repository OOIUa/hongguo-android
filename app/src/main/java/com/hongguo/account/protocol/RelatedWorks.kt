// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv/core RelatedWorks.kt and ContentRepository.kt at f22bb3f (2026-11-03).
package com.hongguo.account.protocol

import java.io.InterruptedIOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** The 相关作品 cell of the reading plan: other seasons and same-IP works. Anonymous, signed and read-only. */
class RelatedWorksClient internal constructor(http: OkHttpClient, private val hosts: List<HttpUrl>) {
    constructor(http: OkHttpClient) : this(http, HOSTS.map { it.toHttpUrl() })
    private val transport = ProtocolTransport(http)

    fun cancelAll() = transport.cancelAll()

    fun related(id: String): List<RelatedWork> {
        require(id.matches(ID)) { "作品编号无效" }
        var failure: Exception? = null
        for (host in hosts) {
            try {
                return RelatedWorks.parse(parseObject(read(host, id)), id)
            } catch (cancelled: InterruptedIOException) {
                throw cancelled
            } catch (problem: AccountProtocolException) {
                failure = problem
            }
        }
        throw failure ?: AccountProtocolException("相关作品暂不可用")
    }

    private fun read(host: HttpUrl, id: String): String {
        val device = LinkedHashMap(VendorConstants.device).apply {
            put("device_id", AppPhoneLogin.DEVICE_ID); put("iid", AppPhoneLogin.INSTALL_ID)
            for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) put(key, "72232")
            put("version_name", "7.2.2.32")
        }
        val signed = Signer(device = device).sign(host.toString().trimEnd('/') + PATH, device + mapOf("scene" to "10",
            "from" to "detail_page_more_related", "book_id" to id, "new_search_middle_page" to "false",
            "bookstore_tab_type" to "0", "query_history_removed" to "false", "user_is_login" to "0", "bookstore_tab" to "0",
            "search_middle_page_version" to "0", "pad_column_detail" to "0", "hot_word_exchange" to "false",
            "device_level" to "3", "limit" to "0", "offset" to "0", "is_horizontal_screen" to "false",
            "reader_single_col_from_ip_strategy" to "false"))
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        return transport.execute(request.build()) { cloudResponse(it, RESPONSE_LIMIT) }
    }

    companion object {
        internal const val PATH = "/reading/bookapi/plan/v:version/"
        private const val RESPONSE_LIMIT = 8 * 1024 * 1024
        private val ID = Regex("[0-9]{1,30}")
        private val HOSTS = listOf("https://api5-normal-sinfonlinec.fqnovel.com", "https://api5-normal-hl.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com")
    }
}

object RelatedWorks {
    /** Observed on 2026-11-04: 1004/1007 and 1/1002 are the on-line/upcoming pairs of the two playable drama families. */
    private val ONLINE_TYPES = setOf(1, 1004)
    private val UPCOMING_TYPES = setOf(1002, 1007)
    private val SEASON = Regex("第(\\d{1,3})季")

    internal fun parse(root: JSONObject, currentId: String): List<RelatedWork> {
        if (root.optInt("code", -1) != 0) throw AccountProtocolException("相关作品暂不可用")
        val cells = root.optJSONArray("data") ?: throw AccountProtocolException("相关作品数据格式已变化")
        return cells.objects().filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { it.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                val upcoming = type in UPCOMING_TYPES
                val episodes = row.optInt("episode_cnt").coerceAtLeast(0)
                if (id == currentId || !id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                if (!upcoming && (type !in ONLINE_TYPES || row.optBoolean("disable_play") || episodes == 0)) return@mapNotNull null
                val tag = row.optJSONObject("tag_info")?.str("text").orEmpty()
                val season = tag.ifBlank { row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty() }
                RelatedWork(Series(id, title, cleanUrl(row.str("cover")), row.str("video_desc"),
                    if (upcoming) "待上线" else "全 $episodes 集"), season, upcoming,
                    SEASON.find(season)?.groupValues?.get(1)?.toIntOrNull() ?: 0, episodes)
            }.distinctBy { it.series.id }.take(30)
    }

    /**
     * The season after the current work. The plan lists every season except the current one, so the current
     * season is the single gap in the numbering. No gap means the current work is the newest; several gaps are
     * ambiguous and return null instead of guessing.
     */
    fun nextSeason(works: List<RelatedWork>): RelatedWork? {
        val seasons = works.filter { it.seasonNumber > 0 }.associateBy { it.seasonNumber }
        val newest = seasons.keys.maxOrNull() ?: return null
        val current = (1..newest).filter { it !in seasons }.singleOrNull() ?: return null
        return seasons[current + 1]
    }
}
