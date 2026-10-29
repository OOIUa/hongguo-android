// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Mirrors hongguo-ios Tests/AccountRulesSelfTest avatar and name cases. */
class ProfileRulesTest {
    private fun json(text: String) = JSONObject(text)

    @Test fun avatarUrls() {
        assertEquals("https://p3.example/a.jpeg", ProfileRules.avatarUrl(json("""{"avatar_url":"https://p3.example/a.jpeg"}""")))
        assertEquals("https://p6.example/t.webp",
            ProfileRules.avatarUrl(json("""{"user":{"avatar_thumb":{"url_list":["","http://p6.example/t.webp"]}}}""")))
        assertEquals("https://p9.example/x.png", ProfileRules.avatarUrl(json("""{"user_info":{"avatar":"//p9.example/x.png"}}""")))
        assertNull(ProfileRules.avatarUrl(json("""{"avatar_url":"javascript:alert(1)","avatar":"file:///etc/passwd"}""")))
        assertNull(ProfileRules.avatarUrl(json("""{"avatar_url":"https://user@p3.example/a.jpeg"}""")))
        assertNull(ProfileRules.avatarUrl(json("""{"avatar_url":"https://p3.example/a b.jpeg"}""")))
        assertNull(ProfileRules.avatarUrl(json("""{"avatar_url":true}""")))
        assertNull(ProfileRules.avatarUrl(json("""{"user_name":"红果用户"}""")))
    }

    @Test fun displayNames() {
        assertEquals("果果", ProfileRules.displayName(json("""{"user":{"screen_name":"  果果  "}}""")))
        assertEquals("备用", ProfileRules.displayName(json("""{"name":"a\u0000b","user_info":{"nickname":"备用"}}""")))
        assertNull(ProfileRules.displayName(json("""{"screen_name":"","name":123}""")))
        assertNull(ProfileRules.displayName(JSONObject().put("name", "长".repeat(65))))
    }
}
