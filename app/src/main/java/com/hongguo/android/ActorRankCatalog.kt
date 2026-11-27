// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.android

import com.hongguo.account.protocol.ProfileRules
import com.hongguo.account.protocol.cleanUrl
import com.hongguo.account.protocol.objects
import com.hongguo.account.protocol.str
import com.hongguo.account.protocol.validIdentifier
import com.hongguo.api.service.Client
import java.io.IOException
import org.json.JSONObject

data class ActorRankEntry(
    val id: String,
    val name: String,
    val avatar: String,
    val rank: Int,
    val heat: String,
    val description: String,
)

data class ActorRankCursor(val offset: Int, val sessionId: String)
data class ActorRankPage(val entries: List<ActorRankEntry>, val next: ActorRankCursor?)

enum class ActorRankBoard(val label: String, val selector: String) {
    DRAMA_STARS("剧星榜", "ranklist_series_actor"),
    STARLIGHT("星光榜", "ranklist_actor_fans"),
}

internal object ActorRankCatalog {
    private const val ACTORS = "ranklist_celebrity"

    fun page(board: ActorRankBoard, cursor: ActorRankCursor?): ActorRankPage {
        // Catalog selectors documented by hongguo-open-player v1.9.1 and openhongguo ShortRank.
        val query = linkedMapOf<String, Any>(
            "cell_id" to Client.COMIC_RANK_CELL, "cell_sub_id" to "0",
            "selected_items" to ACTORS, "sub_selected_items" to board.selector,
            "tab_type" to if (cursor == null) "26" else "18",
            "client_req_type" to "2", "client_template" to "2",
            "category_id" to "0", "gender" to "2", "limit" to "20",
            "offset" to (cursor?.offset ?: 0), "session_id" to cursor?.sessionId.orEmpty(),
            "background_selected_items" to "", "panel_selected_items" to "", "filter_ids" to "",
            "unlimited_selector_change_type" to "0",
        )
        val response = Client.api("GET", "/reading/bookapi/bookmall/cell/change/v", null, query, 1, Client.SIGN_LIST)
        return parse(JSONObject(response), cursor, board)
    }

    internal fun parse(root: JSONObject, cursor: ActorRankCursor? = null, board: ActorRankBoard = ActorRankBoard.DRAMA_STARS): ActorRankPage {
        if (root.optInt("code", -1) != 0) throw IOException("演员榜暂不可用")
        val data = root.optJSONObject("data") ?: throw IOException("演员榜数据缺失")
        val view = data.optJSONObject("cell_view") ?: throw IOException("演员榜数据缺失")
        val selected = selectedItem(view.optJSONObject("cell_selector"))
        val selectedBoard = selectedItem(selected?.optJSONObject("sub_cell_selector"))
        if ((selected != null && selected.str("selector_item_id") != ACTORS) ||
            (selectedBoard != null && selectedBoard.str("selector_item_id") != board.selector)) {
            throw IOException("平台返回的榜单与${board.label}不符")
        }
        val cells = view.optJSONArray("cell_data") ?: throw IOException("演员榜列表缺失")
        val offset = cursor?.offset ?: 0
        val users = cells.objects().flatMap { it.optJSONArray("ugc_user_data")?.objects().orEmpty() }
        val entries = users.mapIndexedNotNull { index, actor ->
            val info = actor.optJSONObject("user_info") ?: return@mapIndexedNotNull null
            val base = info.optJSONObject("base_info") ?: return@mapIndexedNotNull null
            val id = info.str("user_id").ifEmpty { base.str("user_id") }
            val name = base.str("user_name")
            if (!validIdentifier(id) || name.isEmpty()) return@mapIndexedNotNull null
            ActorRankEntry(
                id, name, ProfileRules.safeImageUrl(cleanUrl(base.str("user_avatar"))).orEmpty(), offset + index + 1,
                actor.optJSONObject("rec_text_item")?.str("RecommendText").orEmpty(), actor.str("intro"),
            )
        }.distinctBy { it.id }
        if (entries.isEmpty()) {
            if (cells.length() == 0 && !data.optBoolean("has_more")) return ActorRankPage(emptyList(), null)
            throw IOException("演员榜没有可用的演员数据")
        }
        val nextOffset = data.optInt("next_offset", 0)
        val next = if (data.optBoolean("has_more") && nextOffset > offset) {
            ActorRankCursor(nextOffset, data.str("session_id").ifEmpty { cursor?.sessionId.orEmpty() })
        } else null
        return ActorRankPage(entries, next)
    }

    private fun selectedItem(selector: JSONObject?): JSONObject? =
        selector?.optJSONObject("outer_row")?.optJSONArray("items")?.objects()?.firstOrNull { it.optBoolean("is_selected") }
}
