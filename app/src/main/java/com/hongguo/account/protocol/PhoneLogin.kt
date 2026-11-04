// SPDX-License-Identifier: GPL-3.0-only
// Models adapted from tsukiyo-zero/hongguoTV 34c7930. AppPhoneLogin adapted from hongguoTV PhoneLogin.kt at f22bb3f
// (2026-11-03, TV 0.1.17–0.1.19), itself adapted from charlie901208/HGTV feat/phone-login.
package com.hongguo.account.protocol

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object PhoneLogin {
    /**
     * [canRetryCode]: the server definitely rejected this code, so the user may type a different one in the same session.
     * [needsWebsite]: the platform asks for a verification only the official page can show, such as a slider.
     */
    class Failure(message: String, val errorCode: Int? = null, val canRetryCode: Boolean = false,
                  val needsWebsite: Boolean = false) : Exception(message)
    data class Sent(val maskedMobile: String, val retrySeconds: Int)

    fun format(countryCode: String, number: String): String {
        val country = countryCode.trim().removePrefix("+")
        val digits = number.filterNot { it.isWhitespace() || it == '-' }
        require(country.matches(Regex("[0-9]{1,4}")) && digits.matches(Regex("[0-9]{5,15}"))) { "请输入有效手机号" }
        return "+$country $digits"
    }

    fun parse(raw: String): String {
        val value = raw.trim().replace(Regex("[()\\-]"), "")
        if (value.startsWith("+")) {
            val parts = value.split(Regex("\\s+"), limit = 2)
            require(parts.size == 2) { "国际手机号请使用 +区号 手机号 格式" }
            return format(parts[0], parts[1])
        }
        val number = value.filterNot(Char::isWhitespace)
        require(number.matches(Regex("1[3-9][0-9]{9}"))) { "请输入有效手机号" }
        return format("86", number)
    }
}

/**
 * SMS login over the official App's passport channel; the website channel now demands a slider before sending.
 * One instance is one login attempt. Nothing here solves a platform verification: a slider request is reported as
 * [PhoneLogin.Failure.needsWebsite] so the user can pass it on the official page.
 *
 * The verification tries the passport's login endpoints in order with the same code, because the endpoint that
 * accepts a signed-out session is not declared anywhere. It stops at the first definite answer. The user can never
 * submit the same code twice in one attempt, and nothing is resent automatically.
 */
internal class AppPhoneLogin(http: OkHttpClient, private val base: HttpUrl = PASSPORT.toHttpUrl(),
    private val clock: () -> Long = System::currentTimeMillis) {
    private val transport = ProtocolTransport(http)
    private val cookies = linkedMapOf<String, String>()
    private val submittedCodes = mutableSetOf<String>()
    private var mobile = ""
    private var ticket = ""
    private var sent = false
    private var nextSmsAt = 0L

    fun cancel() { sent = false; transport.cancelAll() }

    fun send(number: String): PhoneLogin.Sent {
        check(clock() >= nextSmsAt) { "请等待短信冷却后再试" }
        mobile = PhoneLogin.parse(number).removePrefix("+86 ")
        sent = false
        ticket = ""
        cookies.clear()
        submittedCodes.clear()
        val reply = call("/passport/mobile/send_code/v1/", mapOf("mobile" to mobile, "type" to LOGIN_CODE_TYPE), form = false)
        val data = reply.optJSONObject("data") ?: JSONObject()
        if (data.has(VERIFY_FIELD) || reply.str("message") != "success") throw failure(data)
        ticket = data.str("mobile_ticket")
        val retry = data.optInt("retry_time", 60).coerceIn(1, 3600)
        nextSmsAt = clock() + retry * 1000L
        sent = true
        val digits = mobile.substringAfter(' ')
        val masked = if (digits.length >= 7) digits.take(3) + "****" + digits.takeLast(4) else "你的手机"
        return PhoneLogin.Sent(masked, retry)
    }

    fun verify(code: String): AccountCredentials {
        check(sent) { "请先重新获取验证码" }
        require(code.matches(Regex("[0-9]{4,8}"))) { "请输入 4—8 位验证码" }
        if (!submittedCodes.add(code)) throw PhoneLogin.Failure("这个验证码已提交过，请输入新的验证码", canRetryCode = true)
        sent = false
        var sawExpired = false
        for ((path, form) in VERIFY_ATTEMPTS) {
            val reply = call(path, mapOf("mobile" to mobile, "code" to code, "type" to LOGIN_CODE_TYPE, "mobile_ticket" to ticket), form)
            if (hasSession()) return credentials()
            val data = reply.optJSONObject("data") ?: continue
            // The passport issues a new ticket after each attempt; later endpoints use the latest one.
            data.str("mobile_ticket").takeIf { it.isNotBlank() }?.let { ticket = it }
            when (data.optInt("error_code", 0)) {
                // 1: this endpoint does not apply to a signed-out session, so the next one is tried.
                0 -> continue
                1 -> { sawExpired = true; continue }
                else -> {
                    val failure = failure(data)
                    sent = failure.canRetryCode
                    throw failure
                }
            }
        }
        throw PhoneLogin.Failure(if (sawExpired) "登录会话已失效，请重新获取验证码" else "登录没有成功，平台没有下发登录态",
            if (sawExpired) 1 else null)
    }

    private fun hasSession() = cookies["sessionid"]?.isNotBlank() == true || cookies["sessionid_ss"]?.isNotBlank() == true

    private fun credentials() = AccountCredentials(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        DEVICE_ID, INSTALL_ID, mapOf("device_id" to DEVICE_ID, "iid" to INSTALL_ID), source = AccountCredentialSource.APP)

    /** Raw provider text is never shown. */
    private fun failure(data: JSONObject): PhoneLogin.Failure {
        val code = data.optInt("error_code", -1)
        val verification = data.has(VERIFY_FIELD)
        val captcha = data.str("captcha").isNotBlank()
        val needsWebsite = verification || captcha || code == 1105
        val message = if (verification) "红果要求完成滑块验证，请到官网登录页面完成"
            else if (captcha) "红果要求额外验证，请到官网登录页面完成"
            else when (code) {
                1102, 1202 -> "验证码有误，请检查后重新输入"
                1103, 1104 -> "验证码已失效，请重新获取后再试"
                1105 -> "红果要求额外验证，请到官网登录页面完成"
                1106, 1107 -> "请求过于频繁，请稍后再试"
                1203 -> "错误次数过多或验证码已过期，请重新获取验证码"
                else -> "手机号登录失败，请稍后重试"
            }
        return PhoneLogin.Failure("$message（错误码 $code）", code, !needsWebsite && (code == 1102 || code == 1202), needsWebsite)
    }

    private fun call(path: String, params: Map<String, String>, form: Boolean): JSONObject {
        val device = LinkedHashMap(VendorConstants.device).apply {
            put("device_id", DEVICE_ID); put("iid", INSTALL_ID)
            for (key in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) put(key, "73932")
            put("version_name", "7.3.9.32")
        }
        val passport = mapOf("aid" to "8662", "app_name" to "novelread", "device_platform" to "android") + params.filterValues { it.isNotBlank() }
        val payload = if (form) Signer.encode(passport).toByteArray() else null
        // The official App identifies the device outside the query; sending these two here is judged an abnormal device.
        val query = device.filterKeys { it != "device_id" && it != "iid" }
        val signed = Signer(device = device).sign(base.resolve(path)!!.toString(), if (form) query else query + passport, payload)
        val request = Request.Builder().url(signed.url)
        signed.headers.forEach { (key, value) -> request.header(key, value) }
        // Cookies are deliberately not sent back: replaying send_code's cookies on sms_login is answered with 2046.
        if (payload != null) request.post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()).oneShot())
        return transport.execute(request.build()) { response ->
            response.headers("Set-Cookie").forEach { line ->
                val pair = line.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
            }
            parseObject(cloudResponse(response))
        }
    }

    companion object {
        /** The official App's passport host; `is.snssdk.com` answers SMS login with 2046. */
        private const val PASSPORT = "https://security.snssdk.com"
        /** The official App's SMS login uses this device from its table; a newly registered one is answered with 2046. */
        internal const val DEVICE_ID = "1905892595378490"
        internal const val INSTALL_ID = "1905892595382586"
        private const val LOGIN_CODE_TYPE = "24"
        private const val VERIFY_FIELD = "verify_center_decision_conf"
        /** `validate_code` is for signed-in phone checks and answers a signed-out session with error_code 1, so it is last. */
        private val VERIFY_ATTEMPTS = listOf(
            "/passport/mobile/sms_login/" to true,
            "/passport/mobile/sms_login_only/" to true,
            "/passport/mobile/sms_login_continue/" to true,
            "/passport/mobile/validate_code/v1/" to false,
            "/passport/mobile/validate_code/v1/" to true,
        )
    }
}
