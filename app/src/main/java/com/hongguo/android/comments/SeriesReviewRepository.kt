// SPDX-License-Identifier: GPL-3.0-only
// Series reviews are read anonymously: a per-process registered device identity, the app's own
// v5 GET signer, and no account cookie. Endpoint and pagination verified 2026-11-25.
package com.hongguo.android.comments

import com.hongguo.account.protocol.DeviceRegister
import com.hongguo.account.protocol.VendorConstants
import com.hongguo.account.protocol.registeredAppDeviceCookies
import com.hongguo.api.playback.Signer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val FIRST_PAGE_TTL_MS = 120_000L

/** One registered anonymous identity for the comment gateway. */
internal data class ReviewIdentity(
    val deviceId: String,
    val installId: String,
    val device: Map<String, String>,
    val cookies: Map<String, String>,
)

/** Endpoint, request constants and hosts in one place, like the danmaku protocol object. */
internal object SeriesReviewProtocol {
    const val API_HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    const val BACKUP_HOST = "https://api3-normal-sinfonlinea.fqnovel.com"
    const val PATH = "/reading/ugc/novel_comment/book/v1/"
    const val COUNT = 20
    const val SORT = 1
    const val USER_AGENT =
        "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; 23127PN0CC)"
    const val MAX_BYTES = 2 * 1024 * 1024
    val hosts: List<String> = listOf(API_HOST, BACKUP_HOST)
    val seriesId = Regex("[0-9]{6,30}")
}

internal class EmptyReviewResponse : IOException("剧评接口返回空响应")

/**
 * Reads the platform's series reviews, one page per call. The anonymous device is registered on
 * first use and cached for the process; an empty gateway reply rotates it once before giving up.
 * Requests are serialized by the UI (one page in flight), so the repository has no throttle.
 */
internal class SeriesReviewRepository(
    private val http: OkHttpClient = defaultHttp(),
    private val register: () -> ReviewIdentity = { registerIdentity(http) },
) {
    @Volatile private var cachedIdentity: ReviewIdentity? = null
    private val firstPages = LinkedHashMap<String, Pair<Long, SeriesReviewPage>>()

    @Synchronized
    private fun identity(): ReviewIdentity = cachedIdentity ?: register().also { cachedIdentity = it }

    suspend fun page(seriesId: String, offset: Int): SeriesReviewPage {
        if (offset == 0) cachedFirst(seriesId)?.let { return it }
        val page = runInterruptible(Dispatchers.IO) { pageBlocking(seriesId, offset) }
        if (offset == 0) cacheFirst(seriesId, page)
        return page
    }

    private fun cachedFirst(seriesId: String): SeriesReviewPage? = synchronized(firstPages) {
        val hit = firstPages[seriesId] ?: return@synchronized null
        if (System.currentTimeMillis() - hit.first > FIRST_PAGE_TTL_MS) {
            firstPages.remove(seriesId)
            null
        } else {
            hit.second
        }
    }

    private fun cacheFirst(seriesId: String, page: SeriesReviewPage) = synchronized(firstPages) {
        firstPages.remove(seriesId)
        firstPages[seriesId] = System.currentTimeMillis() to page
        while (firstPages.size > 16) firstPages.remove(firstPages.keys.first())
    }

    private fun pageBlocking(seriesId: String, offset: Int): SeriesReviewPage {
        require(seriesId.matches(SeriesReviewProtocol.seriesId)) { "剧集编号无效" }
        require(offset in 0..1_000_000) { "评论页码无效" }
        var who = identity()
        var rotated = false
        while (true) {
            var empty = false
            var failure: Exception? = null
            for (host in SeriesReviewProtocol.hosts) {
                try {
                    return SeriesReviewPages.parse(read(who, host, seriesId, offset), seriesId, offset)
                } catch (problem: EmptyReviewResponse) {
                    empty = true
                    failure = problem
                } catch (problem: IOException) {
                    failure = problem
                }
            }
            if (empty && !rotated) {
                // The gateway soft-blocks low-quality or stale device identities with 200 + 0 bytes.
                rotated = true
                cachedIdentity = null
                who = identity()
                continue
            }
            throw failure ?: IOException("剧评加载失败，请稍后重试")
        }
    }

    private fun read(who: ReviewIdentity, host: String, seriesId: String, offset: Int): JSONObject {
        val now = System.currentTimeMillis()
        val values = query(who, seriesId, offset, now)
        val signed = Signer(device = values, mobileV5 = true)
            .signPlaybackGet(host + SeriesReviewProtocol.PATH, values, now)
        val builder = Request.Builder().url(signed.url)
            .header("User-Agent", SeriesReviewProtocol.USER_AGENT)
            .header("Accept", "application/json")
            .header("sdk-version", "2")
            .header("x-xs-from-web", "0")
            .header("x-tt-dt", "")
            .header("x-tt-store-region", "cn")
            .header("x-tt-store-region-src", "did")
            .header("x-reading-request", "$now-${(Math.random() * Int.MAX_VALUE).toInt()}")
        if (who.cookies.isNotEmpty()) {
            builder.header("Cookie", who.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        signed.headers.forEach { (key, value) -> builder.header(key, value) }
        return http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("剧评请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw EmptyReviewResponse()
            if (body.contentLength() > SeriesReviewProtocol.MAX_BYTES) throw IOException("剧评响应过大")
            val text = body.string()
            if (text.isBlank()) throw EmptyReviewResponse()
            if (text.length > SeriesReviewProtocol.MAX_BYTES) throw IOException("剧评响应过大")
            try {
                JSONObject(text)
            } catch (problem: Exception) {
                throw IOException("剧评响应格式异常", problem)
            }
        }
    }

    private fun query(who: ReviewIdentity, seriesId: String, offset: Int, now: Long): Map<String, String> =
        linkedMapOf(
            "iid" to who.installId,
            "device_id" to who.deviceId,
            "ac" to "wifi",
            "channel" to "xiaomi_8662_64",
            "aid" to "8662",
            "app_name" to "novelread",
            "cdid" to who.device["cdid"].orEmpty(),
            "version_code" to "73932",
            "version_name" to "7.3.9.32",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "device_type" to "23127PN0CC",
            "device_brand" to "Xiaomi",
            "language" to "zh",
            "os_api" to "34",
            "os_version" to "14",
            "manifest_version_code" to "73932",
            "resolution" to "1200*2670",
            "dpi" to "460",
            "update_version_code" to "73932",
            "host_abi" to "arm64-v8a",
            "dragon_device_type" to "phone",
            "pv_player" to "73932",
            "compliance_status" to "0",
            "need_personal_recommend" to "1",
            "player_so_load" to "1",
            "is_android_pad_screen" to "0",
            "openudid" to who.device["openudid"].orEmpty(),
            "book_id" to seriesId,
            "group_id" to seriesId,
            "offset" to offset.toString(),
            "count" to SeriesReviewProtocol.COUNT.toString(),
            "sort" to SeriesReviewProtocol.SORT.toString(),
            "ts" to (now / 1000L).toString(),
        )

}

private fun registerIdentity(http: OkHttpClient): ReviewIdentity {
    val device = VendorConstants.device.toMutableMap().apply {
        put("cdid", UUID.randomUUID().toString())
        put("openudid", UUID.randomUUID().toString().replace("-", "").take(16))
        put("clientudid", UUID.randomUUID().toString())
        put("apk_first_install_time", System.currentTimeMillis().toString())
    }
    val body = DeviceRegister.payload(device)
    val builder = Request.Builder().url(DeviceRegister.url())
    DeviceRegister.headers(body, device).forEach { (key, value) -> builder.header(key, value) }
    builder.post(body.toRequestBody("application/octet-stream".toMediaType()))
    return http.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("剧评设备初始化失败")
        val text = response.body?.string().orEmpty()
        if (text.isBlank()) throw IOException("剧评设备初始化失败")
        val registered = DeviceRegister.parse(text, emptyList())
            ?: throw IOException("剧评设备初始化失败")
        val cookieText = registeredAppDeviceCookies(
            response.request.url,
            SeriesReviewProtocol.API_HOST.toHttpUrl(),
            response.headers,
        )
        val cookies = cookieText.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }.toMap()
        ReviewIdentity(registered.deviceId, registered.installId, device, cookies)
    }
}

private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** One shared repository (and one anonymous identity) for the process. */
internal object SeriesReviewApi {
    val repository: SeriesReviewRepository by lazy { SeriesReviewRepository() }
}
