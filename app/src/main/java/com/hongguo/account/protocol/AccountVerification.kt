package com.hongguo.account.protocol

import java.util.concurrent.CancellationException

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
    throw AccountProtocolException(error.message ?: "账号验证未完成", error.errorCode, error.requiresLogin, stage)
} catch (error: PhoneLogin.Failure) {
    throw AccountProtocolException(error.message ?: "官网登录未完成", error.errorCode,
        error.errorCode in setOf(13, 1003), stage)
} catch (_: Exception) {
    throw AccountProtocolException("请求未完成，请检查网络后重试", verificationStage = stage)
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
