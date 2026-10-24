// SPDX-License-Identifier: GPL-3.0-only
// Models adapted from tsukiyo-zero/hongguoTV at 34c793055fa897075d9061dc7d62ee84031ef855.
package com.hongguo.account.protocol

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("cover", cover)
        .put("description", description).put("badge", badge).put("tags", tags)
    companion object {
        fun fromJson(value: JSONObject) = Series(value.str("id"), value.str("title"), value.str("cover"), value.str("description"), value.str("badge"), value.str("tags"))
    }
}

data class RelatedWork(val series: Series, val season: String, val upcoming: Boolean)

/** Contains only safe, locally generated messages; no raw provider response or signed URL. */
class AccountProtocolException(message: String, val errorCode: Int? = null, val requiresLogin: Boolean = false) : IOException(message)

internal fun validIdentifier(value: String): Boolean = value.matches(Regex("[0-9]{1,30}")) && value.any { it != '0' }
internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else opt(key)?.toString()?.trim().orEmpty()
internal fun cleanUrl(value: String) = value.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true).replace("\\u0026", "&").replace("&amp;", "&")

internal object EpisodeSummary {
    fun total(badge: String): Int = Regex("(?:全|共|更新至)\\s*(\\d+)\\s*集").find(badge)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 0
}

/** Android JSONTokener otherwise rounds integer IDs above Long.MAX_VALUE through Double. */
internal fun preserveLargeIntegers(text: String): String {
    val out = StringBuilder(text.length)
    var at = 0
    var inString = false
    var escaped = false
    while (at < text.length) {
        val ch = text[at]
        if (inString) {
            out.append(ch)
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
            at++
        } else if (ch == '"') {
            inString = true; out.append(ch); at++
        } else if (ch == '-' || ch in '0'..'9') {
            val start = at++
            while (at < text.length && (text[at] in '0'..'9' || text[at] in ".eE+-")) at++
            val token = text.substring(start, at)
            if (token.matches(Regex("-?[0-9]{16,}"))) out.append('"').append(token).append('"') else out.append(token)
        } else { out.append(ch); at++ }
    }
    return out.toString()
}

internal fun parseObject(text: String): JSONObject = try { JSONObject(preserveLargeIntegers(text)) }
catch (_: Exception) { throw AccountProtocolException("账号接口返回格式异常") }

internal fun checkCookie(value: String) {
    require(value.length <= 65536 && value.none { it == '\r' || it == '\n' || it.code < 32 || it.code == 127 }) { "账号凭据格式无效" }
    require(value.split(';').any { part ->
        val name = part.substringBefore('=').trim()
        val token = part.substringAfter('=', "").trim()
        name in setOf("sessionid", "sessionid_ss") && token.isNotBlank() && token.length <= 16384
    }) { "缺少有效登录态，请重新登录" }
}

internal fun websiteAccountId(data: JSONObject): String {
    val candidates = listOfNotNull(data, data.optJSONObject("user_info"))
    if (candidates.any { row -> listOf("is_auth", "is_login", "is_logged_in").any { row.has(it) && !row.isNull(it) && !row.optBoolean(it, true) } })
        throw AccountProtocolException("官网登录态已失效，请重新登录", requiresLogin = true)
    return candidates.asSequence().flatMap { row -> sequenceOf("user_id", "user_id_str", "uid", "uid_str").map(row::str) }
        .firstOrNull(::validIdentifier) ?: throw AccountProtocolException("官网登录资料未能确认账号身份，请重新验证")
}
