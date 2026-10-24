// SPDX-License-Identifier: GPL-3.0-only
// Adapted from tsukiyo-zero/hongguoTV at 34c793055fa897075d9061dc7d62ee84031ef855.
package com.hongguo.account.protocol

import java.io.InterruptedIOException
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One official website authorization session. Never forwards cookies across origins. */
class WebLogin internal constructor(http: OkHttpClient, private val base: HttpUrl) {
    constructor(http: OkHttpClient) : this(http, BASE.toHttpUrl())
    private val transport = ProtocolTransport(http)
    private val cookies = mutableListOf<Cookie>()
    @Volatile private var cancelled = false
    private var token = ""
    private var mobile = ""
    private var sent = false
    private var nextSmsAt = 0L

    data class Qr(val image: ByteArray, val expiresIn: Int)

    fun cancel() { cancelled = true; sent = false; transport.cancelAll() }

    private fun execute(request: Request): Pair<String, HttpUrl?> {
        if (cancelled) throw InterruptedIOException("登录已取消")
        check(sameOrigin(request.url)) { "不支持的登录地址" }
        val matching = cookies.filter { it.matches(request.url) && it.expiresAt > System.currentTimeMillis() }
        val scoped = request.newBuilder().apply {
            if (matching.isNotEmpty()) header("Cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
        }.build()
        return transport.execute(scoped) { response ->
            if (cancelled) throw InterruptedIOException("登录已取消")
            Cookie.parseAll(request.url, response.headers).forEach { cookie ->
                cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) cookies += cookie
            }
            if (response.isRedirect) {
                val next = response.header("Location")?.let(request.url::resolve)
                    ?: throw AccountProtocolException("登录确认地址无效")
                if (!sameOrigin(next)) throw AccountProtocolException("登录确认跳转到不支持的站点")
                "" to next
            } else cloudResponse(response) to null
        }
    }

    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    private fun call(path: String, data: Map<String, String>? = null, params: Map<String, String> = emptyMap()): JSONObject {
        val url = base.resolve(path)!!.newBuilder()
        (mapOf("aid" to "513755", "passport_jssdk_version" to "3.4.10", "passport_jssdk_type" to "normal",
            "account_sdk_source" to "web", "is_from_ttaccountsdk" to "1", "language" to "zh") + params)
            .forEach { (key, value) -> url.addQueryParameter(key, value) }
        val request = Request.Builder().url(url.build()).header("User-Agent", UA).header("Referer", base.toString())
            .header("Origin", base.toString().trimEnd('/')).header("Accept", "application/json, text/javascript")
        cookies.firstOrNull { it.name == "passport_csrf_token" && it.matches(url.build()) }
            ?.let { request.header("x-tt-passport-csrf-token", it.value) }
        if (data != null) request.post(FormBody.Builder().apply { data.forEach { (key, value) -> add(key, value) } }.build().oneShot())
        val result = execute(request.build())
        if (result.second != null) throw AccountProtocolException("登录接口返回了意外跳转")
        return parseObject(result.first)
    }

    private fun checked(reply: JSONObject): JSONObject {
        val data = reply.optJSONObject("data") ?: throw AccountProtocolException("官网登录返回格式异常")
        if (reply.optString("message") != "success") {
            val code = data.optInt("error_code", -1)
            val message = if (data.has("verify_center_decision_conf")) "红果要求完成滑块验证，请改用扫码登录"
                else when (code) {
                    1003 -> "登录会话已失效，请重新扫码或重新获取验证码"
                    1102 -> "验证码有误，请重新获取后再试"
                    1103, 1104 -> "验证码已失效，请重新获取后再试"
                    1105 -> "红果要求额外验证，请改用扫码登录"
                    1106, 1107 -> "请求过于频繁，请稍后再试"
                    else -> "官网登录失败，请稍后重试"
                }
            throw PhoneLogin.Failure("$message（错误码 $code）", code, false)
        }
        return data
    }

    fun qr(): Qr {
        val data = checked(call("/passport/web/get_qrcode/", params = mapOf("next" to base.toString())))
        token = data.str("token").takeIf { it.isNotBlank() } ?: throw AccountProtocolException("登录二维码信息缺失")
        val encoded = data.str("qrcode").substringAfter("base64,")
        if (encoded.length > 1024 * 1024) throw AccountProtocolException("登录二维码过大")
        val image = try { decode64(encoded) } catch (_: Exception) { throw AccountProtocolException("登录二维码格式无效") }
        if (image.isEmpty()) throw AccountProtocolException("登录二维码为空")
        val expiry = data.optLong("expire_time", 180)
        val seconds = if (expiry > 1_000_000_000) (expiry - System.currentTimeMillis() / 1000).coerceIn(0, 600).toInt()
            else expiry.coerceIn(0, 600).toInt()
        return Qr(image, seconds)
    }

    fun poll(): Boolean {
        check(token.isNotBlank()) { "请先获取二维码" }
        val data = checked(call("/passport/web/check_qrconnect/", params = mapOf("next" to base.toString(), "token" to token)))
        val status = data.str("status")
        when (status) {
            "5", "expired" -> throw AccountProtocolException("二维码已过期，请重新获取")
            "3", "confirmed" -> data.str("redirect_url").takeIf { it.isNotBlank() }?.let(::finishRedirect)
        }
        return status in setOf("3", "confirmed") && hasSession()
    }

    private fun finishRedirect(value: String) {
        var url = base.resolve(value) ?: throw AccountProtocolException("登录确认地址无效")
        repeat(5) {
            if (!sameOrigin(url)) throw AccountProtocolException("登录确认跳转到不支持的站点")
            val result = execute(Request.Builder().url(url).header("User-Agent", UA).header("Referer", base.toString()).build())
            val next = result.second ?: return
            url = next
        }
        throw AccountProtocolException("登录确认跳转次数过多")
    }

    fun send(number: String): PhoneLogin.Sent {
        check(System.currentTimeMillis() >= nextSmsAt) { "请等待短信冷却后再试" }
        mobile = PhoneLogin.parse(number)
        sent = false
        if (cookies.none { it.name == "passport_csrf_token" && it.expiresAt > System.currentTimeMillis() }) qr()
        val data = checked(call("/passport/web/send_code/", mapOf("mobile" to mix(mobile), "type" to mix("24"), "mix_mode" to "1", "fixed_mix_mode" to "1")))
        val retry = data.optInt("retry_time", 60).coerceIn(1, 3600)
        nextSmsAt = System.currentTimeMillis() + retry * 1000L
        sent = true
        val digits = mobile.substringAfter(' ')
        val masked = if (digits.length >= 7) digits.take(3) + "****" + digits.takeLast(4) else "你的手机"
        return PhoneLogin.Sent(masked, retry)
    }

    fun verify(code: String): AccountCredentials {
        check(sent) { "请先重新获取验证码" }
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4—8 位验证码" }
        // A submitted code is consumed locally even if the response is lost. Never replay it automatically.
        sent = false
        checked(call("/passport/web/sms_login/", mapOf("mobile" to mix(mobile), "code" to mix(code), "login_only" to "true", "mix_mode" to "1", "fixed_mix_mode" to "1")))
        return credentials()
    }

    private fun hasSession() = cookies.any { it.name in setOf("sessionid", "sessionid_ss") && it.matches(base) && it.expiresAt > System.currentTimeMillis() }
    fun credentials(): AccountCredentials {
        check(hasSession()) { "官网尚未下发登录态，请先在手机确认登录" }
        websiteAccountId(checked(call("/passport/account/info/v2/")))
        return AccountCredentials(cookies.filter { it.matches(base) && it.expiresAt > System.currentTimeMillis() }.joinToString("; ") { "${it.name}=${it.value}" })
    }

    companion object {
        const val BASE = "https://hongguoduanju.com"
        private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        internal fun mix(value: String) = value.toByteArray(Charsets.UTF_8).joinToString("") { ((it.toInt() and 255) xor 5).toString(16) }
    }
}
