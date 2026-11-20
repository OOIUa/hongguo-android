// SPDX-License-Identifier: GPL-3.0-only
// Ported from hongguoTV (HGPlayer) core/src/main/kotlin/com/hongguotv/core/DanmakuRepository.kt;
// endpoint, payload and risk-control handling are unchanged, signing now uses this app's own
// api.playback.Signer so the request carries the same device identity as playback.
package com.hongguo.android.danmaku

import com.hongguo.api.playback.Signer
import com.hongguo.api.playback.VendorConstants
import com.hongguo.api.service.AppSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One official bullet comment, anchored to a playback offset within its episode. */
data class Danmaku(val id: String, val offsetMs: Long, val text: String, val diggCount: Int)

/**
 * 评论接口的地址与请求体字段集中在这里。接口本身变化（路径、字段、主机）时只改这一个对象，
 * 渲染、加载与缓存逻辑都不用动。
 */
internal object CommentProtocol {
    /** 评论列表路径模板。 */
    private const val PATH = "/novel/commentapi/comment/list/%s/v1/"

    /** 历史可用主机；与播放共用同一网关，主程序换域时优先跟随它。 */
    private const val PRIMARY_HOST = "https://api5-normal-sinfonlinea.fqnovel.com"
    private const val FALLBACK_HOST = "https://api5-normal-hl.fqnovel.com"

    const val COMMENT_SOURCE = 601
    const val SERVER_CHANNEL = 1000
    const val GROUP_TYPE = 30
    const val COMMENT_TYPE = 20
    const val SORT = 1
    const val COUNT = 90
    const val AID = 8662
    const val COMPLIANCE_STATUS = 0

    fun path(vid: String): String = PATH.format(vid)

    /**
     * 依次尝试的主机：历史主域在前，随后是播放当前使用的主域（`AppSource.VIDEO_API_HOST`，
     * 上游换域时这里自动跟着变），最后是历史备用域。
     */
    fun hosts(): List<String> = listOf(
        PRIMARY_HOST,
        "https://" + AppSource.VIDEO_API_HOST,
        FALLBACK_HOST,
    ).distinct()
}

/** 请求签名形态：App 形态（完整签名头）与播放器形态（v5 三头）。 */
private enum class SignatureMode { APP, PLAYER }

/**
 * Pulls the platform's official time-anchored comments (弹幕) for one episode, one 30-second
 * window per request. All requests are serialized and throttled because the endpoint is
 * gateway-policed; a risk-control reply carries a server-dictated cooldown that is honored
 * before retrying. Empty or censored replies degrade to an empty window instead of failing.
 *
 * 主机与签名形态会在失败或空正文时自动轮换：上游只改其中一维（换域或换签名族）时，
 * 弹幕不需要改代码就能继续工作。
 */
internal class DanmakuRepository(
    private val http: OkHttpClient,
    /**
     * Device identity written into both the query and the medusa plaintext. Comments are an
     * anonymous surface, so a per-process install id is used instead of the playback identity;
     * the two must always match or the gateway drops the request.
     */
    private val device: Map<String, String> = VendorConstants.device,
    private val signer: Signer = Signer(device = device),
    /** 播放器形态（v5）签名器：评论接口若被迁到 player 签名族，靠它自动接上。 */
    private val playerSigner: Signer = Signer(device = device, mobileV5 = true),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: () -> Double = Math::random,
) {
    // Negative means "no request yet": the first call must not sleep. Starting at 0 or
    // Long.MIN_VALUE breaks that — the former throttles a non-existent previous request,
    // the latter overflows the subtraction and sleeps effectively forever.
    private var lastRequestAt = -1L

    /** 当前使用的主机与签名形态；成功后保持不变，失败或空正文时轮换。 */
    private var hostIndex = 0
    private var modeIndex = 0

    /** Comments of one 30-second window plus the server-suggested start of the next window. */
    data class Window(val comments: List<Danmaku>, val nextStartMs: Long)

    private data class Page(val comments: List<Danmaku>, val nextStartMs: Long, val cursor: String, val hasMore: Boolean)
    private class Cooldown(val ms: Long) : IOException()

    /** Fetches the window starting at [startMs], following in-window pagination (bounded). */
    @Synchronized
    fun window(seriesId: String, vid: String, durationMs: Long, startMs: Long): Window {
        val comments = mutableListOf<Danmaku>()
        var cursor = ""
        var nextStart = -1L
        var pages = 0
        while (true) {
            val page = fetchWithRetry(seriesId, vid, durationMs, startMs, cursor)
            comments += page.comments
            if (nextStart < 0) nextStart = page.nextStartMs
            cursor = page.cursor
            pages++
            if (!page.hasMore || cursor.isEmpty() || pages >= MAX_PAGES_PER_WINDOW) break
        }
        return Window(dedupe(comments), if (nextStart > startMs) nextStart else startMs + WINDOW_MS)
    }

    private fun fetchWithRetry(seriesId: String, vid: String, durationMs: Long, startMs: Long, cursor: String): Page {
        var attempt = 0
        while (true) {
            try {
                return parse(post(vid, body(seriesId, vid, durationMs, startMs, cursor)), startMs)
            } catch (cooldown: Cooldown) {
                if (attempt >= MAX_COOLDOWN_RETRIES) throw IOException("弹幕接口风控中，请稍后再试")
                attempt++
                sleep(cooldown.ms + (random() * 1000).toLong())
            }
        }
    }

    private fun body(seriesId: String, vid: String, durationMs: Long, startMs: Long, cursor: String): ByteArray {
        val business = JSONObject()
            .put("book_id", seriesId)
            .put("start_offset_time", startMs.coerceAtLeast(0))
            .put("playlet_item_duration", durationMs.coerceAtLeast(0))
            .put("need_danmaku_guide_type", JSONArray(listOf(1, 3, 4, 2)))
        return JSONObject()
            .put("comment_source", CommentProtocol.COMMENT_SOURCE)
            .put("server_channel", CommentProtocol.SERVER_CHANNEL)
            .put("group_id", vid)
            .put("group_type", CommentProtocol.GROUP_TYPE)
            .put("comment_type", CommentProtocol.COMMENT_TYPE)
            .put("sort", CommentProtocol.SORT)
            .put("business_param", business)
            .put("count", CommentProtocol.COUNT)
            .put("cursor", cursor)
            .put("aid", CommentProtocol.AID)
            .put("compliance_status", CommentProtocol.COMPLIANCE_STATUS)
            .toString().toByteArray(Charsets.UTF_8)
    }

    private fun throttle() {
        val now = clock()
        if (lastRequestAt >= 0) {
            val wait = THROTTLE_MS + (random() * THROTTLE_JITTER_MS).toLong() - (now - lastRequestAt)
            if (wait > 0) sleep(wait)
        }
        lastRequestAt = clock()
    }

    /**
     * 取一页评论。先用当前主机与签名形态；遇到空正文（网关软拦截的固定表现）或传输失败时，
     * 轮换到下一个主机／签名形态再试一次——上游只改其中一维时无需改代码即可自愈。
     */
    private fun post(vid: String, body: ByteArray): String {
        throttle()
        var firstFailure: IOException? = null
        repeat(MAX_TRANSPORT_TRIES) {
            try {
                val text = send(vid, body)
                if (text.isNotBlank()) return text
            } catch (failure: IOException) {
                if (firstFailure == null) firstFailure = failure
            }
            advanceTransport()
        }
        firstFailure?.let { throw it }
        return ""
    }

    private fun send(vid: String, body: ByteArray): String {
        val base = CommentProtocol.hosts()[hostIndex] + CommentProtocol.path(vid)
        val signed = when (SignatureMode.entries[modeIndex]) {
            SignatureMode.APP -> signer.sign(base, device, body)
            SignatureMode.PLAYER -> playerSigner.signPlayback(base, device, body)
        }
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("comment-source", CommentProtocol.COMMENT_SOURCE.toString())
            .header("server-channel", CommentProtocol.SERVER_CHANNEL.toString())
            .apply { signed.headers.forEach { (key, value) -> header(key, value) } }
            .post((signed.body ?: body).toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("弹幕请求失败（HTTP ${response.code}）")
            // The gateway silently drops intercepted requests with an HTTP 200 empty body;
            // an empty reply must surface as "no comments", never as a parse crash.
            val payload = response.body ?: return@use ""
            if (payload.contentLength() > MAX_BYTES) throw IOException("弹幕响应过大")
            val bytes = payload.bytes()
            if (bytes.size > MAX_BYTES) throw IOException("弹幕响应过大")
            String(bytes, Charsets.UTF_8)
        }
    }

    /** 主机用完再换签名形态：两个维度都轮换，任一维度变化都能自愈。 */
    private fun advanceTransport() {
        hostIndex++
        if (hostIndex >= CommentProtocol.hosts().size) {
            hostIndex = 0
            modeIndex = (modeIndex + 1) % SignatureMode.entries.size
        }
    }

    private fun parse(raw: String, startMs: Long): Page {
        if (raw.isBlank()) return Page(emptyList(), startMs + WINDOW_MS, "", false)
        val payload = try {
            JSONObject(raw)
        } catch (invalid: Exception) {
            // Risk-control interceptions sometimes answer plain text; treat them as a dead window.
            return Page(emptyList(), startMs + WINDOW_MS, "", false)
        }
        if (payload.optInt("code", 0) != 0) {
            val cooldown = cooldownMs(payload.optString("message", payload.optString("msg", "")))
            if (cooldown > 0) throw Cooldown(cooldown)
            throw IOException("弹幕接口暂不可用")
        }
        val data = payload.optJSONObject("data") ?: return Page(emptyList(), startMs + WINDOW_MS, "", false)
        val rows = data.optJSONArray("data_list") ?: JSONArray()
        val comments = rows.objects().mapNotNull { item ->
            val comment = item.optJSONObject("comment") ?: return@mapNotNull null
            val text = comment.optJSONObject("common")?.optJSONObject("content")?.optString("text", "")?.trim().orEmpty()
            val offset = comment.optJSONObject("expand")?.optLong("offset_time", -1) ?: -1
            if (text.isEmpty() || offset < 0) return@mapNotNull null
            Danmaku(
                comment.optString("comment_id", ""), offset.coerceAtLeast(0L), text,
                (comment.optJSONObject("stat")?.optLong("digg_count", 0) ?: 0).coerceAtLeast(0L).toInt(),
            )
        }
        val listInfo = data.optJSONObject("common_list_info")
        var nextStart = data.optJSONObject("extra")?.optLong("next_query_danmaku_list_time", -1) ?: -1
        if (nextStart <= startMs) nextStart = startMs + WINDOW_MS
        return Page(comments, nextStart, listInfo?.optString("cursor", "").orEmpty(), listInfo?.optBoolean("has_more", false) ?: false)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        /** 每次请求最多尝试两套主机／签名组合，避免失败时连发请求触发风控。 */
        private const val MAX_TRANSPORT_TRIES = 2
        private const val MAX_PAGES_PER_WINDOW = 3
        private const val MAX_COOLDOWN_RETRIES = 2
        private const val THROTTLE_MS = 180L
        private const val THROTTLE_JITTER_MS = 240L
        private const val MAX_BYTES = 2 * 1024 * 1024
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** The risk-control reply message embeds a JSON payload with ban seconds. */
        internal fun cooldownMs(message: String): Long {
            val detail = try {
                JSONObject(message)
            } catch (invalid: Exception) {
                return 0L
            }
            val min = detail.optLong("min_ban_time", 0)
            val max = detail.optLong("max_ban_time", 0)
            return (if (min > 0) min else max).coerceAtLeast(0L) * 1000
        }

        internal fun dedupe(rows: List<Danmaku>): List<Danmaku> {
            val seen = LinkedHashMap<String, Danmaku>()
            for (row in rows) seen[if (row.id.isNotEmpty()) "id:${row.id}" else "anonymous:${row.offsetMs}:${row.text}"] = row
            return seen.values.sortedWith(compareBy({ it.offsetMs }, { it.id }, { it.text }))
        }
    }
}

/** Walks a JSON array of objects without throwing away rows of an unexpected type. */
private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/** One shared HTTP client and repository for every episode the player opens. */
internal object DanmakuApi {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
    /**
     * Anonymous comment identity. The platform bans identities it sees abused, so comments get
     * fresh install ids once per process instead of the playback identity; query and medusa must
     * carry the same ids.
     */
    private val device: Map<String, String> by lazy {
        VendorConstants.device.toMutableMap().apply {
            put("iid", randomInstallId())
            put("device_id", randomInstallId())
        }
    }
    val repository: DanmakuRepository by lazy { DanmakuRepository(http, device, Signer(device = device)) }

    private fun randomInstallId(): String = buildString(16) {
        append('1')
        repeat(15) { append(kotlin.random.Random.nextInt(10)) }
    }
}
