// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV at 34c793055fa897075d9061dc7d62ee84031ef855.
// SPDX-License-Identifier: GPL-3.0-only
// Account endpoint shapes referenced from charlie901208/HGTV feat/phone-login, 2026-10-21.
package com.hongguo.account.protocol

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException

data class AccountCredentials(val cookie: String, val deviceId: String = "", val installId: String = "",
    val device: Map<String,String> = emptyMap(), val appDeviceCookie: String = "") {
    fun toJson() = JSONObject().put("cookie",cookie).put("deviceId",deviceId).put("installId",installId)
        .put("device",JSONObject(device)).put("appDeviceCookie", normalizeAppDeviceCookie(appDeviceCookie)).toString()
    override fun toString() = "AccountCredentials([redacted])"
    companion object {
        fun fromJson(value: String): AccountCredentials {
            require(value.length <= 131072) { "账号凭据过大" }
            val json=parseObject(value); val device=json.optJSONObject("device") ?: JSONObject()
            return AccountCredentials(json.str("cookie"),json.str("deviceId"),json.str("installId"),
                device.keys().asSequence().associateWith { device.str(it) }, normalizeAppDeviceCookie(json.str("appDeviceCookie")))
                .also { checkCookie(it.cookie) }
        }
    }
}

private val APP_DEVICE_COOKIE_NAMES = listOf("install_id", "ttreq", "store-region", "store-region-src")

/** Website cookies and app registration cookies have independent owners and persistence fields. */
internal fun normalizeAppDeviceCookie(value: String): String {
    require(value.length <= 16384 && value.none { it.code < 32 || it.code == 127 }) { "账号设备凭据格式无效" }
    val pairs = linkedMapOf<String, String>()
    value.split(';').forEach { part ->
        val name = part.substringBefore('=').trim()
        val token = part.substringAfter('=', "").trim()
        if (name in APP_DEVICE_COOKIE_NAMES && token.isNotEmpty()) pairs[name] = token
    }
    return APP_DEVICE_COOKIE_NAMES.mapNotNull { name -> pairs[name]?.let { "$name=$it" } }.joinToString("; ")
}

/** Flatten only root-path cookies that can be reused across every app account endpoint. */
internal fun registeredAppDeviceCookies(registrationUrl: HttpUrl, apiUrl: HttpUrl, headers: Headers): String {
    val target = apiUrl.resolve("/reading/user/info/v1/") ?: return ""
    return normalizeAppDeviceCookie(Cookie.parseAll(registrationUrl, headers).filter {
        it.name in APP_DEVICE_COOKIE_NAMES && it.path == "/" && it.matches(target) && it.expiresAt > System.currentTimeMillis()
    }.joinToString("; ") { "${it.name}=${it.value}" })
}

/** Keep the original session; registered device values replace stale names on app requests only. */
internal fun appRequestCookie(credentials: AccountCredentials): String {
    checkCookie(credentials.cookie)
    val pairs = linkedMapOf<String, String>()
    credentials.cookie.split(';').forEach { part ->
        val name = part.substringBefore('=').trim()
        val token = part.substringAfter('=', "").trim()
        if (name.isNotEmpty() && token.isNotEmpty()) pairs.putIfAbsent(name, token)
    }
    normalizeAppDeviceCookie(credentials.appDeviceCookie).split(';').forEach { part ->
        val name = part.substringBefore('=').trim()
        if (name in APP_DEVICE_COOKIE_NAMES) pairs[name] = part.substringAfter('=', "").trim()
    }
    // Old encrypted snapshots did not keep registration cookies. Their stable iid is authoritative.
    if (validIdentifier(credentials.installId)) pairs["install_id"] = credentials.installId
    return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
internal object AccountCredentialsParser {
    fun parseHeader(value: String) = AccountCredentials(value)
}
data class CloudProfile(val id: String,val nickname: String)
data class CloudProgress(val series: Series,val episodeId: String,val episodeIndex: Int,val position: Long,val duration: Long,val updatedAt: Long,val episodeCount: Int=0)

data class CloudFavorite(val series: Series,val collectedAt: Long)

data class CloudReservation(val series: Series,val online: Boolean,val publishAt: Long,val publishText: String="")

class CloudAccountClient internal constructor(http: OkHttpClient, private val apiBase: HttpUrl, private val registrationUrl: HttpUrl, private val websiteBase: HttpUrl = apiBase) {
    constructor(http: OkHttpClient) : this(http, "https://api5-normal-sinfonlinea.fqnovel.com".toHttpUrl(), DeviceRegister.url().toHttpUrl(), WebLogin.BASE.toHttpUrl())
    private val transport = ProtocolTransport(http)
    private val registrationDevice = VendorConstants.device.toMutableMap().apply {
        put("cdid", java.util.UUID.randomUUID().toString())
        put("openudid", java.util.UUID.randomUUID().toString().replace("-", "").take(16))
        put("clientudid", java.util.UUID.randomUUID().toString())
        put("apk_first_install_time", System.currentTimeMillis().toString())
    }.toMap()
    fun cancelAll() = transport.cancelAll()

    /** Reuse a persisted registered identity; never rotate an authenticated account's device. */
    fun prepareCredentials(web: AccountCredentials): AccountCredentials = verifyAccountStage(AccountVerificationStage.DEVICE) {
        checkCookie(web.cookie)
        if (validIdentifier(web.deviceId) && validIdentifier(web.installId)) return@verifyAccountStage web
        val registered = registerDevice() ?: throw AccountProtocolException("账号设备初始化失败，请稍后重试")
        web.copy(deviceId = registered.deviceId, installId = registered.installId, device = registered.device,
            appDeviceCookie = normalizeAppDeviceCookie(registered.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }))
    }
    fun registerDevice(): DeviceRegister.Registered? {
        val body=DeviceRegister.payload(registrationDevice)
        val request=Request.Builder().url(registrationUrl)
        DeviceRegister.headers(body,registrationDevice).forEach { (k,v) -> request.header(k,v) }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()).oneShot())
        return transport.execute(request.build()) {
            val appCookies = registeredAppDeviceCookies(registrationUrl, apiBase, it.headers)
            val cookieMap = appCookies.split(';').mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                if (name in APP_DEVICE_COOKIE_NAMES) name to part.substringAfter('=', "").trim() else null
            }.toMap()
            DeviceRegister.parse(cloudResponse(it), emptyList())?.copy(device = registrationDevice, cookies = cookieMap)
        }
    }
    private fun json(credentials: AccountCredentials,path: String,params: Map<String,String> = emptyMap(),body: ByteArray? = null,gzip: Boolean=false): JSONObject {
        checkCookie(credentials.cookie)
        if (!validIdentifier(credentials.deviceId) || !validIdentifier(credentials.installId))
            throw AccountProtocolException("账号设备身份无效，请重新登录", requiresLogin = true)
        val device=VendorConstants.device.toMutableMap().apply {
            putAll(credentials.device); put("device_id",credentials.deviceId); put("iid",credentials.installId)
            for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) put(key,if(body!=null) "73932" else "72232")
            put("version_name",if(body!=null) "7.3.9.32" else "7.2.2.32")
        }
        val wireBody=if(gzip && body!=null) java.io.ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write(body) }
        }.toByteArray() else body
        val signed=Signer(device=device).sign(apiBase.resolve(path)!!.toString(),device+params,wireBody)
        val request=Request.Builder().url(signed.url).header("Cookie",appRequestCookie(credentials))
        signed.headers.forEach { (k,v)->request.header(k,v) }
        if(wireBody!=null) request.post(wireBody.toRequestBody("application/json; charset=utf-8".toMediaType()).oneShot())
        if(gzip) request.header("Content-Encoding","gzip")
        return transport.execute(request.build()) { response ->
            val json=parseObject(cloudResponse(response))
            val code=json.optInt("code",-1)
            if(code!=0) throw AccountProtocolException("账号接口不可用（错误码 $code），请重新登录或稍后重试", code, code == 1003)
            json
        }
    }
    private fun validateWebsite(credentials: AccountCredentials) = verifyAccountStage(AccountVerificationStage.WEBSITE) {
        checkCookie(credentials.cookie)
        val url = websiteBase.resolve("/passport/account/info/v2/")!!.newBuilder()
            .addQueryParameter("aid", "513755").addQueryParameter("passport_jssdk_version", "3.4.10")
            .addQueryParameter("passport_jssdk_type", "normal").addQueryParameter("account_sdk_source", "web")
            .addQueryParameter("is_from_ttaccountsdk", "1").addQueryParameter("language", "zh").build()
        val request = Request.Builder().url(url).header("Cookie", credentials.cookie)
            .header("User-Agent", WebLogin.USER_AGENT)
            .header("Referer", websiteBase.toString()).header("Origin", websiteBase.toString().trimEnd('/'))
            .header("Accept", "application/json, text/javascript")
        passportCsrfToken(credentials.cookie)?.let { request.header("x-tt-passport-csrf-token", it) }
        transport.execute(request.build()) { response ->
            val reply = parseObject(cloudResponse(response))
            if (reply.str("message") != "success") {
                val data = reply.optJSONObject("data") ?: reply
                val code = data.optInt("error_code", -1)
                val expired = code in setOf(13, 1003)
                throw AccountProtocolException(if (expired) "官网登录会话已失效，请重新登录（错误码 $code）"
                    else "官网登录态未通过验证（错误码 $code）", code, expired)
            }
            validateWebsiteSession(reply)
        }
    }
    fun profile(credentials: AccountCredentials): CloudProfile {
        validateWebsite(credentials)
        return verifyAccountStage(AccountVerificationStage.PROFILE) {
            val data=json(credentials,"/reading/user/info/v1/").optJSONObject("data")
                ?: throw AccountProtocolException("官网登录已确认，但红果未返回账号资料，请稍后重试")
            val id=data.str("biz_user_id")
            if(!validIdentifier(id) || id == credentials.deviceId || id == credentials.installId)
                throw AccountProtocolException("官网登录已确认，但红果未返回有效账号身份，请重新验证")
            CloudProfile(id,data.str("user_name").ifBlank { "红果用户" })
        }
    }
    fun history(credentials: AccountCredentials): List<CloudProgress> {
        val data=json(credentials,"/reading/bookapi/read_progress/list/v1/",mapOf("book_types" to "2","limit" to "50"))
        val rows=data.optJSONArray("video_data") ?: data.optJSONObject("data")?.optJSONArray("video_data")
            ?: throw IOException("云端历史数据格式已变化")
        val progress=parseProgress(rows)
        if(progress.isEmpty()) return emptyList()
        val details=json(credentials,"/reading/bookapi/multi-detail/v1/",mapOf("book_id" to progress.map { it.series.id }.joinToString(","),"book_type" to "2"))
            .optJSONArray("data") ?: throw IOException("云端剧集资料格式已变化")
        val series=details.objects().associate { row ->
            row.str("book_id") to Series(row.str("book_id"),row.str("book_name"),cleanUrl(row.str("thumb_url")),row.str("abstract"),episodeBadge(row))
        }
        return progress.map { it.copy(series=series[it.series.id] ?: it.series.copy(title="剧集 ${it.series.id}")) }
    }
    /** Read-only video shelf. Protocol reference: charlie901208/HGTV AccountParser.favorites. */
    fun favorites(credentials: AccountCredentials): List<CloudFavorite> =
        parseFavorites(json(credentials,"/reading/bookapi/bookshelf/video/info/v1/"))
    /** Video shelf only (novel bookshelf/add can acknowledge without changing video favorites).
     * Protocol reference: HGTV videoShelf public request model.
     * No automatic retry: callers must read the shelf back before reporting success.
     */
    fun setFavorite(credentials: AccountCredentials,seriesId: String,add: Boolean) {
        require(seriesId.matches(Regex("[0-9]{1,30}"))) { "剧集编号无效" }
        val entry=JSONObject().put("book_id",seriesId).put("book_type",2)
            .put("modify_time",System.currentTimeMillis()).put("video_shelf_operate_type",if(add) 0 else 1)
        if(!add) entry.put("group_name","")
        val body=JSONObject().put("is_cancelled",false).put("update_bookshelf_video_list",JSONArray().put(entry))
        if(add) body.put("shark_extra",JSONObject().put("enter_from","MainFragmentActivity")
            .put("previous_page","").put("page_list","MainFragmentActivity").put("is_active_behavior","true").put("inactive_type","0"))
        json(credentials,"/reading/bookapi/bookshelf/video/update/v1/",body=body.toString().toByteArray())
    }
    /** Protocol reference: charlie901208/HGTV feat/phone-login, accountReserves.
     * Keep the two upstream categories separate; an unpublished series is never playable.
     * The upstream pagination contract is unverified, so expose only this requested batch.
     */
    fun reservations(credentials: AccountCredentials,online: Boolean): List<CloudReservation> {
        val raw=json(credentials,"/reading/user/subscribe/list/v0/",mapOf(
            "offset" to "0","limit" to "20","is_online" to online.toString(),"tab_type" to "13",
            "subscribe_order_type" to "0","swipe_type" to "0","need_calendar_schema" to "false","subscribe_offset" to "0"))
        val rows=parseReservations(raw,online)
        if(!online) return rows
        val missing=rows.filter { EpisodeSummary.total(it.series.badge)==0 }.map { it.series.id }
        if(missing.isEmpty()) return rows
        // Optional batch metadata: its failure must not hide a valid reservation list.
        val totals=runCatching {
            json(credentials,"/reading/bookapi/multi-detail/v1/",mapOf("book_id" to missing.joinToString(","),"book_type" to "2"))
                .optJSONArray("data")?.objects().orEmpty().associate { it.str("book_id") to episodeBadge(it) }
        }.getOrDefault(emptyMap())
        return rows.map { row -> totals[row.series.id]?.takeIf { it.isNotBlank() }?.let { row.copy(series=row.series.copy(badge=it)) } ?: row }
    }
    /** SearchUncoverSubscribeRequest fields come from the official RPC model.
     * code=0 only acknowledges the write; account-list confirmation is a separate operation.
     */
    fun reserve(credentials: AccountCredentials,work: RelatedWork) {
        require(work.upcoming && work.series.id.matches(Regex("[0-9]{1,19}")))
        val id=java.math.BigInteger(work.series.id)
        val body=JSONObject().put("item_id",id).put("item_type",3).put("op_type",1)
            .put("subscribe_source",0).toString().toByteArray()
        json(credentials,"/reading/bookapi/search/uncover_subscribe/v1/",body=body,gzip=true)
    }
    fun upload(credentials: AccountCredentials,progress: CloudProgress,episodeCount: Int) {
        require(progress.series.id.matches(Regex("[0-9]{1,30}")) && progress.episodeId.matches(Regex("[0-9]{1,30}")))
        require(progress.episodeIndex in 0 until Int.MAX_VALUE) { "分集位置无效" }
        val entry=JSONObject().put("book_id",progress.series.id).put("item_id",progress.episodeId)
            .put("vid_index",progress.episodeIndex+1).put("current_play_time",progress.position.coerceAtLeast(0))
            .put("current_total_time",progress.duration.coerceAtLeast(0)).put("episode_count",episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms",progress.updatedAt.coerceAtLeast(0)).put("book_type",2)
            .put("progress_rate",if(progress.duration>0) (progress.position.toDouble()/progress.duration).coerceIn(0.0,1.0).toString() else "0")
            .put("player_cumulative_total_duration",progress.position.coerceAtLeast(0))
        val body=JSONObject().put("books",JSONArray().put(entry)).put("write_mode",1).toString().toByteArray()
        json(credentials,"/reading/bookapi/read_progress/upload/v1/",body=body)
    }
    companion object {
        private fun episodeBadge(row: JSONObject): String {
            val count=listOf("episode_cnt","episode_count","drama_chapter_number").map { row.optInt(it) }.firstOrNull { it>0 } ?: 0
            return if(count>0) "全 $count 集" else ""
        }
        fun parseFavorites(raw: JSONObject): List<CloudFavorite> {
            val data=raw.optJSONObject("data") ?: throw IOException("账户收藏数据格式已变化")
            if(!data.has("video_shelf_info")) throw IOException("账户收藏列表格式已变化")
            val rows=if(data.isNull("video_shelf_info")) JSONArray() else data.optJSONArray("video_shelf_info")
                ?: throw IOException("账户收藏列表格式已变化")
            val result=rows.objects().mapNotNull { row ->
                val video=row.optJSONObject("video_data") ?: row
                val id=video.str("series_id").ifBlank { row.str("series_id") }.ifBlank { row.str("book_id") }
                val title=row.str("series_title").ifBlank { video.str("title") }.ifBlank { row.str("book_name") }
                if(!id.matches(Regex("[0-9]{1,30}")) || title.isBlank()) return@mapNotNull null
                val cover=row.str("series_cover_url").ifBlank { video.str("cover") }.ifBlank { video.str("cover_url") }.ifBlank { video.str("thumb_url") }.ifBlank { row.str("thumb_url") }
                val count=video.optInt("episode_cnt",video.optInt("episode_count",row.optInt("episode_cnt")))
                val tags=runCatching { JSONArray(row.str("category_schema")).objects().map { it.str("name") }.filter(String::isNotBlank).take(5).joinToString(" · ") }.getOrDefault("").ifBlank { video.str("category") }
                val stamp=row.optLong("collect_time").coerceAtLeast(0).let { if(it in 1..9999999999L) it*1000 else it }
                CloudFavorite(Series(id,title,cleanUrl(cover),row.str("series_intro").ifBlank { video.str("video_desc") }.ifBlank { row.str("abstract") },if(count>0) "全 $count 集" else "",tags),stamp)
            }
            if(rows.length()>0 && result.isEmpty()) throw IOException("账户收藏条目格式已变化")
            return result.sortedByDescending { it.collectedAt }.distinctBy { it.series.id }
        }
        fun parseReservations(raw: JSONObject,online: Boolean): List<CloudReservation> {
            val data=raw.optJSONObject("data") ?: throw IOException("预约数据格式已变化，请稍后重试")
            // A null list is the upstream's empty category; missing/non-array data is an error.
            if(!data.has("subscribe_items")) throw IOException("预约列表格式已变化，请稍后重试")
            val rows=if(data.isNull("subscribe_items")) JSONArray() else data.optJSONArray("subscribe_items")
                ?: throw IOException("预约列表格式已变化，请稍后重试")
            val result=rows.objects().mapNotNull { row ->
                val video=row.optJSONObject("subscribe_data") ?: row.optJSONObject("video_data") ?: row
                val seriesId=video.str("series_id").ifBlank { row.str("series_id") }.ifBlank { row.str("book_id") }
                // Unpublished entries use a flat reservation item, without a playable series ID.
                // Namespace that key so it can never be mistaken for a playback ID.
                val id=if(seriesId.matches(Regex("[0-9]{1,30}"))) seriesId
                    else if(!online && video===row && row.str("item_id").matches(Regex("[0-9]{1,30}"))) "reservation:"+row.str("item_id")
                    else return@mapNotNull null
                val title=row.str("series_title").ifBlank { video.str("title") }.ifBlank { video.str("book_name") }.ifBlank { row.str("name") }
                if(title.isBlank()) return@mapNotNull null
                if(row.has("is_online") && row.optBoolean("is_online")!=online) return@mapNotNull null
                val cover=row.str("series_cover_url").ifBlank { video.str("cover") }.ifBlank { video.str("cover_url") }.ifBlank { video.str("thumb_url") }
                val stamp=row.optLong("schedule_publish_time").coerceAtLeast(0).let { if(it in 1..9999999999L) it*1000 else it }
                val series=Series(id,title,cleanUrl(cover),video.str("video_desc").ifBlank { row.str("abstract") }.ifBlank { row.str("item_desc") },if(online) episodeBadge(video).ifBlank { episodeBadge(row) }.ifBlank { "已上线" } else "待上线",video.str("category"))
                CloudReservation(series,online,stamp,row.str("expected_rec_text"))
            }.distinctBy { it.series.id }
            if(rows.length()>0 && result.isEmpty()) throw IOException("预约条目格式已变化，请稍后重试")
            return result
        }
        fun parseProgress(rows: JSONArray): List<CloudProgress> {
            val parsed = rows.objects().mapNotNull { row ->
            val id=row.str("series_id").ifEmpty { row.str("book_id") }; val episode=row.str("vid")
            if(!id.matches(Regex("[0-9]{1,30}")) || !episode.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
            val stamp=row.optLong("last_watch_time").let { if(it in 1..9999999999L) it*1000 else it }
            CloudProgress(Series(id,""),episode,(row.optInt("vid_index",1)-1).coerceAtLeast(0),
                row.optLong("current_play_time").coerceAtLeast(0),row.optLong("current_total_time").coerceAtLeast(0),stamp,row.optInt("episode_count").coerceAtLeast(0))
            }.distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
            if (rows.length() > 0 && parsed.isEmpty()) throw AccountProtocolException("云端历史条目格式已变化")
            return parsed
        }
    }
}
