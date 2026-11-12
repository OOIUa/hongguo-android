// SPDX-License-Identifier: GPL-3.0-only
// Rank boards and search suggestions adapted from tsukiyo-zero/hongguoTV kotlin-tv/core RankBrowser.kt, ComicRank.kt
// and ContentRepository.kt at f22bb3f (2026-11-03). Only the page's serialized data is read; no script runs.
package com.hongguo.account.protocol

import java.util.UUID
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

enum class RankBoard(val label: String, val route: String) {
    ALL("综合", "hot-drama"), REAL("真人剧", "hot-real-drama"), COMIC("漫剧", "hot-comic-drama"), AI("AI 剧", "hot-ai-drama");
    internal val loader get() = "rank_$route/page"
}

data class RankEntry(val series: Series, val rank: Int, val heat: String, val episodeCount: Int)
data class RankPage(val entries: List<RankEntry>, val page: Int, val totalPages: Int)

/** Public website data. Anonymous: no account cookie is ever sent here and redirects are not followed. */
class WebsiteCatalogClient internal constructor(http: OkHttpClient, private val base: HttpUrl) {
    constructor(http: OkHttpClient) : this(http, WebLogin.BASE.toHttpUrl())
    private val transport = ProtocolTransport(http)
    private val webId = UUID.randomUUID().toString()

    fun rank(board: RankBoard, page: Int): RankPage {
        require(page in 1..100) { "榜单页码无效" }
        // The site answers ?page=1 with a redirect to the bare address, and redirects are not followed here.
        val url = base.resolve("/rank/${board.route}")!!.newBuilder().apply { if (page > 1) addQueryParameter("page", page.toString()) }.build()
        return transport.execute(request(url, "text/html,*/*")) { WebsiteCatalog.parseRank(cloudResponse(it), board, page) }
    }

    fun suggestions(query: String): List<String> {
        val clean = query.trim()
        if (clean.isEmpty() || clean.length > 80) return emptyList()
        val url = base.resolve("/incent_resource/suggestion")!!.newBuilder().addQueryParameter("app_id", "8662")
            .addQueryParameter("web_id", webId).addQueryParameter("query", clean).build()
        return transport.execute(request(url, "application/json")) { WebsiteCatalog.parseSuggestions(parseObject(cloudResponse(it))) }
    }

    private fun request(url: HttpUrl, accept: String) = Request.Builder().url(url)
        .header("User-Agent", WebLogin.USER_AGENT).header("Accept", accept).build()
}

internal object WebsiteCatalog {
    private const val ROUTER_DATA = "_ROUTER_DATA = "
    private val ARGUMENTS = listOf(Regex("data-fn-args='([^']*)'"), Regex("data-fn-args=\"([^\"]*)\""))

    fun parseRank(html: String, board: RankBoard, page: Int): RankPage {
        inlineRankContent(html, board)?.let { return rankPage(it, page) }
        for (pattern in ARGUMENTS) for (match in pattern.findAll(html)) {
            val args = try { JSONArray(decodeEntities(match.groupValues[1])) } catch (_: Exception) { continue }
            if (args.optString(0) != board.loader || args.optString(1) != "content") continue
            val payload = when (val raw = args.opt(2)) {
                is JSONObject -> raw
                is String -> try { JSONObject(raw) } catch (_: Exception) { continue }
                else -> continue
            }
            return rankPage(payload, page)
        }
        throw AccountProtocolException("榜单格式已变化")
    }

    /** Since 2026-11-11 the site renders the whole loader result inline as `_ROUTER_DATA = {...}`
     *  (loaderData["rank_<route>/page"].content); the streamed data-fn-args payload is gone. */
    private fun inlineRankContent(html: String, board: RankBoard): JSONObject? {
        var at = html.indexOf(ROUTER_DATA)
        while (at >= 0) {
            val root = try { JSONTokener(html.substring(at + ROUTER_DATA.length)).nextValue() as? JSONObject } catch (_: Exception) { null }
            root?.optJSONObject("loaderData")?.optJSONObject(board.loader)?.optJSONObject("content")?.let { return it }
            at = html.indexOf(ROUTER_DATA, at + ROUTER_DATA.length)
        }
        return null
    }

    private fun rankPage(payload: JSONObject, page: Int): RankPage {
        if (!payload.optBoolean("isSuccess")) throw AccountProtocolException("榜单暂不可用")
        val rows = payload.optJSONArray("rankList") ?: throw AccountProtocolException("榜单格式已变化")
        val paging = payload.optJSONObject("pagination") ?: throw AccountProtocolException("榜单分页信息缺失")
        val total = paging.optInt("totalPages", 0)
        if (total < 1 || paging.optInt("pageNum", page) != page) throw AccountProtocolException("榜单分页信息异常")
        val entries = rows.objects().mapNotNull { row ->
            val id = row.str("seriesId").ifEmpty { row.str("id") }
            val title = row.str("title")
            if (!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
            val count = row.optInt("episodeCount", row.optJSONArray("episodeVids")?.length() ?: 0).coerceAtLeast(0)
            RankEntry(Series(id, title, cleanUrl(row.str("cover")), row.str("description"),
                if (count > 0) "全 $count 集" else row.str("heatText")), row.optInt("rank").coerceAtLeast(0), row.str("heatText"), count)
        }.distinctBy { it.series.id }
        return RankPage(entries, page, minOf(total, 100))
    }

    fun parseSuggestions(root: JSONObject): List<String> =
        root.optJSONArray("suggest_list")?.objects().orEmpty().map { row ->
            row.str("name").ifBlank { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
        }.filter { it.isNotBlank() && it.length <= 80 }.distinct().take(8)

    private fun decodeEntities(value: String) = value.replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
