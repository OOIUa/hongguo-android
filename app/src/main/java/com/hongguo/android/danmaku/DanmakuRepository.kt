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


/**
 * Pulls the platform's official time-anchored comments (弹幕) for one episode, one 30-second
 * window per request. All requests are serialized and throttled because the endpoint is
 * gateway-policed; a risk-control reply carries a server-dictated cooldown that is honored
 * before retrying. Empty or censored replies degrade to an empty window instead of failing.
 *
 * 遇到空正文或失败时换一个主机重试；签名形态固定为 App 形态（评论接口只认这一种）。
 */
internal class DanmakuRepository(
    private val http: OkHttpClient,
    /**
     * Device identity written into both the query and the medusa plaintext. Comments are an
     * anonymous surface, so a per-process install id is used instead of the playback identity;
     * the two must always match or the gateway drops the request.
     */
    private var device: Map<String, String> = VendorConstants.device,
    private var signer: Signer = Signer(device = device),

    /**
     * 身份择优用的候选身份生成器；为 null 时不启用（测试默认关闭）。
     * 服务端按匿名身份分配弹幕子集，同一窗口换身份可差 3~6 倍。
     */
    private val identityCandidates: (() -> Map<String, String>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val random: () -> Double = Math::random,
) {
    // Negative means "no request yet": the first call must not sleep. Starting at 0 or
    // Long.MIN_VALUE breaks that — the former throttles a non-existent previous request,
    // the latter overflows the subtraction and sleeps effectively forever.
    private var lastRequestAt = -1L

    /** 当前使用的主机；成功后保持不变，失败或空正文时换下一个。 */
    private var hostIndex = 0


    /** 每个进程只做一次身份比较，避免反复抽奖。 */
    private var identityChecked = false

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
            var page = fetchWithRetry(seriesId, vid, durationMs, startMs, cursor)
            if (pages == 0) page = maybeImproveIdentity(seriesId, vid, durationMs, startMs, cursor, page)
            comments += page.comments
            if (nextStart < 0) nextStart = page.nextStartMs
            cursor = page.cursor
            pages++
            if (!page.hasMore || cursor.isEmpty() || pages >= MAX_PAGES_PER_WINDOW) break
        }
        val deduped = dedupe(comments)
        return Window(deduped, if (nextStart > startMs) nextStart else startMs + WINDOW_MS)
    }

    /**
     * 身份择优：首次遇到"有弹幕但不多"的窗口时，用同一个窗口再请求一个候选身份，
     * 留条数多的那个。服务端按匿名身份分配弹幕子集，同一窗口换身份可差 3~6 倍。
     *
     * 只在 1..[IDENTITY_COMPARE_MAX] 条之间比较：0 条说明该集没人发（比也没意义），
     * 超过上限说明当前身份已经够密。每个进程只比较一次，身份不落盘，重启重来。
     */
    private fun maybeImproveIdentity(
        seriesId: String,
        vid: String,
        durationMs: Long,
        startMs: Long,
        cursor: String,
        page: Page,
    ): Page {
        val provider = identityCandidates ?: return page
        if (identityChecked) return page
        val current = page.comments.size
        if (current <= 0) return page          // 这集没人发弹幕，留到下一个有弹幕的窗口再判断
        if (current > IDENTITY_COMPARE_MAX) {
            identityChecked = true             // 已经够密，不再比较
            return page
        }
        identityChecked = true
        val candidate = try {
            provider()
        } catch (failure: Exception) {
            return page
        }
        val candidatePage = try {
            val text = sendWith(
                dev = candidate,
                appSigner = Signer(device = candidate),
                vid = vid,
                body = body(seriesId, vid, durationMs, startMs, cursor),
            )
            if (text.isBlank()) return page
            parse(text, startMs)
        } catch (failure: Exception) {
            return page
        }
        return if (candidatePage.comments.size > current) {
            adoptIdentity(candidate)
            candidatePage
        } else {
            page
        }
    }

    private fun adoptIdentity(next: Map<String, String>) {
        device = next
        signer = Signer(device = next)
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
     * 取一页评论。先用当前主机；遇到空正文（网关软拦截的固定表现）或传输失败时，
     * 换下一个主机再试一次。
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
            advanceHost()
        }
        firstFailure?.let { throw it }
        return ""
    }

    private fun send(vid: String, body: ByteArray): String =
        sendWith(device, signer, vid, body)

    /** 用指定身份与签名器发一次请求；身份择优时用来试候选身份。 */
    private fun sendWith(
        dev: Map<String, String>,
        appSigner: Signer,
        vid: String,
        body: ByteArray,
    ): String {
        val host = CommentProtocol.hosts()[hostIndex]
        val base = host + CommentProtocol.path(vid)
        val signed = appSigner.sign(base, dev, body)
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

    /** 空正文或失败时换下一个主机：协议与签名不变，只换域名。 */
    private fun advanceHost() {
        hostIndex = (hostIndex + 1) % CommentProtocol.hosts().size
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
        /** 身份择优的比较上限：1..19 条才值得比较，≥20 视为当前身份已经够密。 */
        private const val IDENTITY_COMPARE_MAX = 19
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
     * fresh install ids instead of the playback identity; query and medusa must carry the same
     * ids.
     *
     * 服务端按匿名标识分配弹幕子集（同一时间窗不同身份可差 3 倍），这里选择每个进程随机：
     * 不做持久化，也不提供"重新分配"入口——数量随身份波动属于服务端策略，不是故障。
     */
    private val device: Map<String, String> by lazy {
        val iid = randomInstallId()
        val deviceId = randomInstallId()
        VendorConstants.device.toMutableMap().apply {
            put("iid", iid)
            put("device_id", deviceId)
        }
    }

    val repository: DanmakuRepository by lazy {
        DanmakuRepository(
            http = http,
            device = device,
            signer = Signer(device = device),
            identityCandidates = ::candidateDevice,
        )
    }

    /** 择优用的候选身份：同一套设备参数，只换 iid/device_id。 */
    private fun candidateDevice(): Map<String, String> = VendorConstants.device.toMutableMap().apply {
        put("iid", randomInstallId())
        put("device_id", randomInstallId())
    }

    private fun randomInstallId(): String = buildString(16) {
        append('1')
        repeat(15) { append(kotlin.random.Random.nextInt(10)) }
    }
}
