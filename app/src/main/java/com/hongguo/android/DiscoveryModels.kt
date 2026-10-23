package com.hongguo.android

import com.hongguo.api.util.Json

enum class HomeFeed { RECOMMEND, LATEST, COMIC }

data class BrowseSelection(
    val genre: String = "short_play",
    val options: Map<String, String> = emptyMap(),
)

data class FilterOption(val id: String, val label: String)
data class FilterGroup(val key: String, val label: String, val options: List<FilterOption>)

/** Converts upstream selector IDs without inventing theme/category values. */
internal object DiscoveryMapper {
    const val PAGE_SIZE = 18
    const val SEEN_LIMIT = 180
    private val keys = linkedMapOf(
        "category_dim_theme" to "theme", "category_dim_role" to "setting",
        "category_dim_epoch" to "background", "sort" to "sort", "gender" to "gender",
        "creation_status" to "status", "online_time" to "days",
    )
    private val labels = mapOf(
        "theme" to "主题", "setting" to "设定", "background" to "背景", "sort" to "排序",
        "gender" to "受众", "status" to "状态", "days" to "上架时间",
    )
    private val fixedSort = listOf(
        FilterOption("hot_score", "最高热度"), FilterOption("online_time", "最新上架"),
        FilterOption("hot_collect", "最高收藏"),
    )

    fun seenTail(ids: List<String>): List<String> = ids.asReversed().asSequence()
        .filter { it.matches(Regex("[0-9]{1,30}")) }.distinct().take(SEEN_LIMIT).toList().asReversed()

    fun options(selection: BrowseSelection): Map<String, String> = selection.options
        .filterKeys { it in labels }
        .mapValues { it.value.trim() }
        .filterValues { it.isNotEmpty() && !it.equals("all", ignoreCase = true) }
        .toMutableMap().apply { put("limit", PAGE_SIZE.toString()) }

    fun filterGroups(rows: List<*>): List<FilterGroup> {
        val groups = linkedMapOf<String, Pair<String, LinkedHashMap<String, FilterOption>>>()
        rows.forEach { raw ->
            val row = Json.optObj(raw) ?: return@forEach
            val type = Json.optStr(row["type"], "").trim()
            val key = keys[type] ?: type.takeIf { it in labels } ?: return@forEach
            val label = Json.optStr(row["row_name"], "").trim().ifEmpty { labels.getValue(key) }
            val options = groups.getOrPut(key) { label to linkedMapOf("" to FilterOption("", "全部")) }.second
            Json.optArr(row["items"]).forEach items@{ value ->
                val item = Json.optObj(value) ?: return@items
                val name = Json.optStr(item["name"] ?: item["show_name"], "").trim()
                var id = Json.optStr(item["id"] ?: item["selector_item_id"], "").trim()
                if (name.isEmpty()) return@items
                if (name == "不限" || name.startsWith("全部") || id.equals("all", ignoreCase = true)) id = ""
                else if (id.isEmpty()) return@items
                options.putIfAbsent(id, FilterOption(id, name))
            }
        }
        val sort = groups.getOrPut("sort") {
            labels.getValue("sort") to linkedMapOf("" to FilterOption("", "全部"))
        }.second
        fixedSort.forEach { sort.putIfAbsent(it.id, it) }
        return groups.mapNotNull { (key, row) ->
            if (row.second.size <= 1) null else FilterGroup(key, row.first, row.second.values.toList())
        }
    }

    fun cards(rows: List<*>): List<DramaCard> = rows.mapNotNull { raw ->
        val row = Json.optObj(raw) ?: return@mapNotNull null
        val id = Json.optStr(row["series_id"], "")
        if (!id.matches(Regex("[0-9]{1,30}"))) return@mapNotNull null
        DramaCard(
            id, Json.optStr(row["title"], "").ifBlank { "未命名短剧" },
            Json.optStr(row["cover"], ""), Json.optInt(row["episode_cnt"], 0),
            Json.optStr(row["abstract"], ""),
        )
    }.distinctBy { it.id }

    fun page(rows: List<*>, hasMore: Boolean, next: Int, offset: Int, seenIds: List<String>): DramaPage {
        val seen = seenTail(seenIds).toHashSet()
        val cards = cards(rows).filterNot { it.id in seen }
        val cursor = next.coerceAtLeast(offset.coerceAtLeast(0))
        // An empty or stationary page cannot drive an endless load-more loop.
        return DramaPage(cards, hasMore && cards.isNotEmpty() && cursor > offset, cursor)
    }
}
