// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ActorRankCatalogTest {
    private fun actor(userId: String, name: String) = JSONObject(
        """{
            "user_info": {
                "user_id": "$userId",
                "base_info": {
                    "user_id": "$userId", "actor_id_str": "7000000000000919",
                    "user_name": "$name", "user_avatar": "http://example.com/portrait.jpg"
                }
            },
            "rec_text_item": {"RecommendText": "700万人气"},
            "intro": "《春来时》",
            "sub_title_list": [{"content": "12部作品"}]
        }"""
    )

    private fun response(
        vararg actors: JSONObject,
        more: Boolean,
        nextOffset: Int,
        board: String = "ranklist_series_actor",
    ): JSONObject {
        val cells = JSONArray()
        for (person in actors) {
            cells.put(JSONObject().put("show_type", 521).put("ugc_user_data", JSONArray().put(person)))
        }
        val view = JSONObject(
            """{"cell_selector":{"outer_row":{"items":[
                {"selector_item_id":"ranklist_celebrity","is_selected":true,"show_name":"演员",
                 "sub_cell_selector":{"outer_row":{"items":[{"selector_item_id":"$board","is_selected":true}]}}}
            ]}}}"""
        ).put("cell_data", cells)
        return JSONObject().put("code", 0).put("data", JSONObject()
            .put("cell_view", view).put("has_more", more).put("next_offset", nextOffset)
            .put("session_id", "fixture-page"))
    }

    @Test fun actorRowUsesPublicUserIdPopularityAndRepresentativeWork() {
        val page = ActorRankCatalog.parse(response(actor("9007199254740993", "林夏"), more = true, nextOffset = 1))
        val entry = page.entries.single()

        assertEquals("9007199254740993", entry.id)
        assertEquals("林夏", entry.name)
        assertEquals("https://example.com/portrait.jpg", entry.avatar)
        assertEquals(1, entry.rank)
        assertEquals("700万人气", entry.heat)
        assertEquals("《春来时》", entry.description)
        assertEquals(ActorRankCursor(1, "fixture-page"), page.next)
    }

    @Test fun continuationKeepsSourcePositionWhenAnActorRowIsUnusable() {
        val missingUser = actor("", "缺少用户编号")
        val valid = actor("102", "于清")
        valid.getJSONObject("user_info").remove("user_id")

        val page = ActorRankCatalog.parse(
            response(missingUser, valid, more = false, nextOffset = 17),
            ActorRankCursor(15, "fixture-page")
        )

        assertEquals("102", page.entries.single().id)
        assertEquals(17, page.entries.single().rank)
        assertNull(page.next)
    }

    @Test fun staleNextOffsetClosesPagination() {
        val page = ActorRankCatalog.parse(
            response(actor("103", "陈晓"), more = true, nextOffset = 15),
            ActorRankCursor(15, "fixture-page")
        )
        assertEquals(16, page.entries.single().rank)
        assertNull(page.next)
    }

    @Test fun emptyBoardIsDistinguishedFromMalformedActorRows() {
        assertEquals(ActorRankPage(emptyList(), null),
            ActorRankCatalog.parse(response(more = false, nextOffset = 0)))
        assertThrows(IOException::class.java) {
            ActorRankCatalog.parse(response(actor("0", "无编号"), more = false, nextOffset = 1))
        }
    }

    @Test fun starlightKeepsItsOwnMetricAndRejectsDramaStarResults() {
        val person = actor("104", "李佳")
        person.getJSONObject("rec_text_item").put("RecommendText", "673万星光")
        val post = "5小时前发布：新剧开机"
        person.put("intro", post)
        val starlight = response(person, more = false, nextOffset = 1, board = "ranklist_actor_fans")
        val entry = ActorRankCatalog.parse(starlight, board = ActorRankBoard.STARLIGHT).entries.single()
        assertEquals("673万星光", entry.heat)
        assertEquals(post, entry.description)

        val dramaStars = response(person, more = false, nextOffset = 1)
        assertThrows(IOException::class.java) {
            ActorRankCatalog.parse(dramaStars, board = ActorRankBoard.STARLIGHT)
        }
    }
}
