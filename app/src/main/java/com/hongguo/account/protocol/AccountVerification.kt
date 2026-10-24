package com.hongguo.account.protocol

import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Safe local stages, never provider response bodies or credential values. */
enum class AccountVerificationStage(val label: String) {
    DEVICE("账号设备初始化"), WEBSITE("官网登录验证"), PROFILE("红果账号资料验证"),
}

internal inline fun <T> verifyAccountStage(stage: AccountVerificationStage, block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: AccountProtocolException) {
    if (error.verificationStage != null) throw error
    throw AccountProtocolException(error.message ?: "账号验证未完成", error.errorCode, error.requiresLogin, stage, error.diagnostic)
} catch (error: PhoneLogin.Failure) {
    throw AccountProtocolException(error.message ?: "官网登录未完成", error.errorCode,
        error.errorCode in setOf(13, 1003), stage)
} catch (_: Exception) {
    throw AccountProtocolException("请求未完成，请检查网络后重试", verificationStage = stage)
}

/** No arbitrary header value enters a diagnosis, even if the provider or a proxy returns one. */
internal fun safeGatewayLogin(value: String?): String? = value?.takeIf { it.matches(Regex("[0-9]{1,3}")) }

/** Structure-only failure evidence. Never print identifiers, names, tokens or unknown field names. */
internal fun appProfileDiagnostic(reply: JSONObject, credentials: AccountCredentials, gatewayLogin: String?): String {
    val data = reply.optJSONObject("data")
    val rawId = data?.opt("biz_user_id")
    val id = businessAccountId(rawId)
    val missingId = rawId == null || rawId === JSONObject.NULL || (rawId is String && rawId.isBlank())
    val nameFlag = data?.opt("has_set_username") as? Boolean
    val classification = when {
        data == null -> "PROFILE_STRUCTURE"
        id != null && (id == credentials.deviceId || id == credentials.installId) -> "DEVICE_IDENTITY"
        missingId && nameFlag == false -> "ANONYMOUS_INDICATORS"
        missingId -> "BUSINESS_ID_MISSING"
        id == null -> "BUSINESS_ID_INVALID"
        !validIdentifier(id) && safeGatewayLogin(gatewayLogin) != "1" -> "ACCOUNT_AUTH_UNCONFIRMED"
        else -> "BUSINESS_ID_PRESENT"
    }
    fun type(row: JSONObject?, key: String): String = when {
        row == null || !row.has(key) -> "missing"
        row.isNull(key) -> "null"
        else -> when (val value = row.opt(key)) {
            is String -> if (value.isBlank()) "empty-string" else "string"
            is Boolean -> "boolean"
            is Number -> "number"
            is JSONObject -> "object"
            is JSONArray -> "array"
            else -> "other"
        }
    }
    val roots = listOf("code", "data", "message").joinToString(",") { "$it:${type(reply, it)}" }
    val fields = listOf("biz_user_id", "req_id", "user_id", "user_id_str", "uid", "uid_str", "user_name", "avatar_url",
        "has_set_username", "has_login", "is_login", "is_logged_in", "user_info", "user")
        .joinToString(",") { "$it:${type(data, it)}" }
    val flag = when (nameFlag) { true -> "true"; false -> "false"; null -> "unavailable" }
    return "profile-v1;source=${credentials.source.name};class=$classification;agw=${safeGatewayLogin(gatewayLogin) ?: "unavailable"};root={$roots};data={$fields};has_set_username=$flag"
}

/** Same fallback used by the official Passport SDK; split once to preserve token values. */
internal fun passportCsrfToken(cookie: String): String? {
    val values = cookie.split(';').mapNotNull { part ->
        val name = part.substringBefore('=').trim()
        val value = part.substringAfter('=', "").trim()
        if (value.isBlank()) null else name to value
    }.toMap()
    return values["passport_csrf_token"] ?: values["passport_csrf_token_default"]
}
