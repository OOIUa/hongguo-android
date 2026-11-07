// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV kotlin-tv/core RecommendationFeed.kt at
// 97c5b1d15d (2026-10-30). Path and card shape referenced there from Xanerix/guoapp.
// Signed-in query and host rotation synchronized from the same file at f22bb3f (2026-11-03, TV 0.1.19).
// Offset stride from hongguoTV PR #59 (open on 2026-11-04), rechecked here with an anonymous request.
// Signed registered identity, 7.4.3.32 context and the bookmall/cell/change continuation synchronized from
// hongguoTV TV 0.1.26, commits 3d319a6 and 62910e0 (2026-11-06), GPL-3.0-only — RecommendationFeed.kt.
package com.hongguo.account.protocol

import com.hongguo.api.service.AppSource
import com.hongguo.api.service.PlaybackDevice
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class GuessCard(val series: Series, val episodeCount: Int)

/** 官方单列推荐首屏声明的续刷单元；只含协议分页标识，不含凭据。 */
data class GuessFlow(val cellId: String, val planId: String, val versionTag: String)

/**
 * Server pagination of one browse round, in memory only. [GuessFeedClient.page] advances it by the protocol in
 * use: the entry tab by the rows it served, the cell continuation by the server's own next_offset.
 * [identity] is the registered anonymous identity the session belongs to; when it changes the entry is read again.
 */
class GuessCursor {
    var session = ""
    var offset = 0
    var flow: GuessFlow? = null
    /** A signed request was answered with an explicit 110: keep the original unsigned form for this round. */
    var unsigned = false
    /** Recently shown ids, sent as filter_ids on the cell continuation. */
    var filterIds: List<String> = emptyList()
    internal var identity: PlaybackDevice.Identity? = null
    val hasProgress get() = session.isNotEmpty() || offset > 0 || flow != null

    fun restartServer() { session = ""; offset = 0; flow = null }
}

/**
 * One server batch. [restarted] means the server pagination was dropped (rejected, host rotated or identity
 * changed), so this batch is a new offset-0 round. [served] counts the rows the server sent before filtering.
 */
data class GuessBatch(val items: List<GuessCard>, val session: String, val restarted: Boolean = false, val served: Int = 0)

/**
 * Official App 书城「猜你喜欢」流（bookmall tab_type=16）。No exposure/click/finish reporting.
 *
 * Signed form (hongguoTV 0.1.26): the registered anonymous identity shared by this process's recommendations,
 * the full App signature, version 7.4.3.32 and the registration cookies. Signed in, the account's own login cookie
 * replaces the registration cookies. An explicit 110 on a signed request switches the round back to the original
 * unsigned form (account cookie only; hongguoTV measured on 2026-11-03 that the account's own device_id / iid
 * is answered with 110). Without a registered identity the unsigned form is used directly.
 *
 * When the entry declares `bottom_unlimited` with template 7 and a valid cell and plan, later batches use
 * `bookmall/cell/change/v1/` with the server's next_offset, as the official 7.4.3.32 single-column feed does.
 *
 * A non-zero `code` is retried on the next host from offset 0. HTTP and network failures are not retried.
 */
class GuessFeedClient internal constructor(
    http: OkHttpClient,
    private val hosts: List<HttpUrl>,
    private val identities: () -> PlaybackDevice.Identity? = PlaybackDevice::recommendation,
    private val forget: (PlaybackDevice.Identity) -> Unit = PlaybackDevice::forgetRecommendation,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(http: OkHttpClient) : this(http, HOSTS.map { it.toHttpUrl() })
    private val transport = ProtocolTransport(http)

    fun cancelAll() = transport.cancelAll()

    fun page(credentials: AccountCredentials?, cursor: GuessCursor): GuessBatch {
        require(cursor.offset in 0..MAX_OFFSET) { "猜你喜欢分页超出范围" }
        credentials?.let { checkCookie(it.cookie) }
        var restarted = false
        hosts.forEachIndexed { attempt, host ->
            if (attempt > 0) { cursor.restartServer(); restarted = true }
            val identity = if (cursor.unsigned) null else identities()
            // 服务端分页会话随注册身份使用；换身份后重读入口，去重记录由调用方保留。
            if (identity !== cursor.identity && cursor.hasProgress) { cursor.restartServer(); restarted = true }
            cursor.identity = identity
            val flow = cursor.flow
            val path = if (flow == null) PATH else CELL_PATH
            val params = params(credentials, cursor.session, cursor.offset, signed = identity != null)
            if (flow != null) params.putAll(cellParams(flow, cursor.filterIds))
            val request = Request.Builder().header("X-XS-From-Web", "0").header("Sdk-Version", "2")
            if (identity == null) {
                val url = host.resolve(path)!!.newBuilder()
                params.forEach { (key, value) -> url.addQueryParameter(key, value) }
                request.url(url.build()).header("User-Agent", USER_AGENT).header("Accept", "application/json")
                credentials?.let { request.header("Cookie", it.cookie) }
            } else {
                val signed = AppSource.signRecommendation(host.resolve(path)!!.toString(), identity, params,
                    credentials?.cookie, clock())
                request.url(signed.url)
                signed.headers.forEach { (key, value) -> request.header(key, value) }
            }
            val result = try {
                transport.execute(request.build()) { parseObject(cloudResponse(it, RESPONSE_LIMIT)) }
            } catch (error: AccountProtocolException) {
                // 签名请求被软拦截（200/0B）时换掉这个推荐身份，从入口在下一主机重读。
                if (identity == null || !error.emptyResponse || attempt == hosts.lastIndex) throw error
                forget(identity)
                return@forEachIndexed
            }
            val code = result.optInt("code", -1)
            if (code != 0 && attempt < hosts.lastIndex) {
                if (identity != null && code == 110) cursor.unsigned = true
                return@forEachIndexed
            }
            val batch = if (flow == null) parse(result) else parseCell(result)
            when {
                flow != null -> {
                    val next = result.optJSONObject("data")?.optLong("next_offset", -1L) ?: -1L
                    if (next <= cursor.offset || next > 1_000_000)
                        throw AccountProtocolException("猜你喜欢续页游标未前进，请刷新")
                    cursor.offset = next.toInt()
                }
                else -> {
                    val discovered = cellFlow(result)
                    if (discovered != null) { cursor.flow = discovered; cursor.offset = 0 }
                    else cursor.offset += maxOf(1, batch.served)
                }
            }
            cursor.session = batch.session.ifBlank { cursor.session }
            return batch.copy(restarted = restarted)
        }
        throw AccountProtocolException("猜你喜欢暂不可用，请稍后刷新")
    }

    companion object {
        internal const val PATH = "/reading/bookapi/bookmall/tab/v1/"
        internal const val CELL_PATH = "/reading/bookapi/bookmall/cell/change/v1/"
        internal const val MAX_OFFSET = 600
        /** filter_ids stays well inside common URL limits; older ids are still filtered locally. */
        internal const val FILTER_LIMIT = 120
        private val HOSTS = listOf(
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
        )
        private const val USER_AGENT = "com.dragon.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C)"
        /** The mall tab ships full episode lists per card (≈1.4 MiB for 3 cards); the 2 MiB account cap is too small. */
        private const val RESPONSE_LIMIT = 16 * 1024 * 1024
        private val DEVICE_KEYS = setOf("channel", "device_platform", "os", "ssmix", "device_type", "device_brand",
            "language", "os_api", "os_version", "resolution", "dpi", "ac")
        private val NUMERIC_ID = Regex("[1-9][0-9]{0,29}")

        internal fun params(credentials: AccountCredentials?, session: String, offset: Int, signed: Boolean = false) =
            linkedMapOf(
                "aid" to "8662", "app_name" to "novelread", "channel" to "update_64", "device_platform" to "android",
                "os" to "android", "ssmix" to "a", "device_type" to "25053RT47C", "device_brand" to "Redmi",
                "language" to "zh", "os_api" to "36", "os_version" to "16", "resolution" to "1280*2772",
                "dpi" to "520", "ac" to "wifi",
            ).apply {
                credentials?.let {
                    putAll(it.device.filterKeys { key -> key in DEVICE_KEYS })
                }
                val version = if (signed) AppSource.RECOMMENDATION_VERSION_CODE else "73532"
                put("version_code", version)
                put("version_name", if (signed) AppSource.RECOMMENDATION_VERSION_NAME else "7.3.5.32")
                put("manifest_version_code", version); put("update_version_code", version)
                put("tab_type", "16"); put("bottom_tab_type", "7"); put("landing_bottom_tab_type", "7")
                put("offset", offset.toString())
                put("client_req_type", if (offset == 0) "1" else "2")
                put("_rticket", System.currentTimeMillis().toString())
                if (session.isNotEmpty()) put("session_id", session)
            }

        internal fun cellParams(flow: GuessFlow, filterIds: List<String>) = linkedMapOf(
            "cell_id" to flow.cellId, "plan_id" to flow.planId, "version_tag" to flow.versionTag,
            "client_template" to "7", "change_type" to "0", "client_req_type" to "2",
            "filter_ids" to filterIds.takeLast(FILTER_LIMIT).joinToString(","),
        )

        private fun recommendTab(result: JSONObject) =
            result.optJSONObject("data")?.optJSONArray("tab_item")?.objects()?.firstOrNull { it.str("tab_type") == "16" }

        /** Only an explicitly enabled single-column continuation with complete identifiers is used. */
        internal fun cellFlow(result: JSONObject): GuessFlow? {
            val tab = recommendTab(result) ?: return null
            if (!tab.optBoolean("bottom_unlimited") || tab.optInt("client_template", -1) != 7) return null
            val cellId = tab.optJSONArray("cell_data")?.optJSONObject(0)?.let { it.str("cell_id_str").ifBlank { it.str("cell_id") } }.orEmpty()
            val planId = tab.str("bookstore_id")
            val version = tab.str("version_tag")
            if (!cellId.matches(NUMERIC_ID) || !planId.matches(NUMERIC_ID) || version.length > 4096 ||
                version.any { it == '\r' || it == '\n' || it.code < 32 }) return null
            return GuessFlow(cellId, planId, version)
        }

        /** Normalizes a cell continuation reply into the entry shape so parsing and episode hints stay the same. */
        internal fun parseCell(result: JSONObject): GuessBatch {
            val code = result.optInt("code", -1)
            if (code != 0) throw AccountProtocolException("猜你喜欢续刷暂不可用（错误码 $code），请稍后刷新", code)
            val data = result.optJSONObject("data") ?: throw AccountProtocolException("猜你喜欢续刷数据格式已变化")
            val view = data.optJSONObject("cell_view") ?: throw AccountProtocolException("猜你喜欢续刷单元缺失")
            val cell = if (view.has("cell_data")) view else JSONObject().put("cell_data", JSONArray().put(view))
            val tab = JSONObject().put("tab_type", 16).put("session_id", data.str("session_id"))
                .put("cell_data", JSONArray().put(cell))
            // has_more=false on this reply still brings new works; the pager's request cap and dedupe end the round.
            return parse(JSONObject().put("code", 0).put("data", JSONObject().put("tab_item", JSONArray().put(tab))))
        }

        internal fun parse(result: JSONObject): GuessBatch {
            val code = result.optInt("code", -1)
            if (code != 0) throw AccountProtocolException("猜你喜欢暂不可用（错误码 $code），请稍后刷新", code)
            val tab = recommendTab(result) ?: throw AccountProtocolException("猜你喜欢数据格式已变化")
            val session = tab.str("session_id")
            if (session.length > 4096 || session.any { it == '\r' || it == '\n' || it.code < 32 })
                throw AccountProtocolException("猜你喜欢分页标记无效")
            val rows = tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
                cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                    card.optJSONArray("video_data")?.objects().orEmpty().map { it.optJSONObject("video_detail") ?: it }
                }
            }
            // No 30-card cap: a large batch keeps every valid work after the thirtieth (hongguoTV 3d319a6).
            val items = rows.mapNotNull { row ->
                val id = row.str("series_id").ifBlank { row.str("book_id") }
                val title = row.str("series_title").ifBlank { row.str("book_name") }.ifBlank { row.str("title") }
                if (!id.matches(Regex("[0-9]{1,24}")) || title.isBlank()) null
                else GuessCard(Series(id, title, cleanUrl(row.str("series_cover").ifBlank { row.str("cover") }),
                    row.str("series_intro").ifBlank { row.str("video_desc") }), row.optInt("episode_cnt").coerceAtLeast(0))
            }.distinctBy { it.series.id }
            return GuessBatch(items, session, served = rows.size)
        }
    }
}
