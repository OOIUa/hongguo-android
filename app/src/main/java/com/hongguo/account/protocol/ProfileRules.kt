// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import java.net.URI
import org.json.JSONObject

/** Name and avatar shown for a website session; neither grants an account identity. */
data class WebsiteProfile(val nickname: String? = null, val avatarUrl: String? = null)

/** Same rules as iOS AccountRules.avatarURL / displayName / safeImageURL. */
internal object ProfileRules {
    private val avatarKeys = listOf("avatar_url", "avatar_large_url", "avatar_large", "avatar_medium", "avatar_thumb", "avatar")
    private val nameKeys = listOf("screen_name", "nickname", "user_name", "name")

    private fun nodes(data: JSONObject) = listOfNotNull(data, data.optJSONObject("user"), data.optJSONObject("user_info"))

    /** First usable avatar: a plain URL string or a `{url_list: [...]}` image object. */
    fun avatarUrl(data: JSONObject): String? {
        for (node in nodes(data)) for (key in avatarKeys) {
            val value = node.opt(key)
            val candidates: List<Any?> = when (value) {
                is String -> listOf(value)
                is JSONObject -> value.optJSONArray("url_list")?.let { list -> (0 until list.length()).map(list::opt) }.orEmpty()
                else -> emptyList()
            }
            candidates.firstNotNullOfOrNull(::safeImageUrl)?.let { return it }
        }
        return null
    }

    /** Website display name (`screen_name` and friends), trimmed and bounded. */
    fun displayName(data: JSONObject): String? {
        for (node in nodes(data)) for (key in nameKeys) {
            val value = (node.opt(key) as? String)?.trim() ?: continue
            if (value.isNotEmpty() && value.codePointCount(0, value.length) <= 64 && value.none(Char::isISOControl)) return value
        }
        return null
    }

    /** Only https (upgraded from http or scheme-relative), without user info or whitespace. */
    fun safeImageUrl(raw: Any?): String? {
        var value = raw as? String ?: return null
        if (value.isEmpty() || value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        if (value.startsWith("//")) value = "https:$value"
        else if (value.startsWith("http://", ignoreCase = true)) value = "https://" + value.substring(7)
        val uri = try { URI(value) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return uri.toString()
    }
}
