// SPDX-License-Identifier: GPL-3.0-only
// Compatibility models adapted from tsukiyo-zero/hongguoTV 34c7930; SMS uses only WebLogin.
package com.hongguo.account.protocol

/** SMS never probes alternate endpoints or automatically resubmits a verification code. */
object PhoneLogin {
    class Failure(message: String, val errorCode: Int? = null, val canRetryCode: Boolean = false) : Exception(message)
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
