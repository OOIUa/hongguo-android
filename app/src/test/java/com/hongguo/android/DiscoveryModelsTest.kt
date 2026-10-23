package com.hongguo.android

import com.hongguo.api.util.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiscoveryModelsTest {
    @Test fun selectorTypesUseRealIdsAndFemaleZeroIsNotTheAllOption() {
        val rows = listOf(
            mapOf("type" to "category_dim_theme", "row_name" to "题材", "items" to listOf(
                mapOf("id" to "provider-theme-123", "name" to "真实题材"),
                mapOf("id" to "all", "name" to "全部"),
            )),
            mapOf("type" to "gender", "items" to listOf(mapOf("id" to "0", "name" to "女频"))),
            mapOf("type" to "unknown_kind", "items" to listOf(mapOf("id" to "x", "name" to "未知"))),
        )
        val groups = DiscoveryMapper.filterGroups(rows)
        assertEquals("题材", groups.first { it.key == "theme" }.label)
        assertEquals(listOf("", "provider-theme-123"), groups.first { it.key == "theme" }.options.map { it.id })
        assertEquals(listOf("", "0"), groups.first { it.key == "gender" }.options.map { it.id })
        assertFalse(groups.any { it.key == "unknown_kind" })
    }

    @Test fun missingSelectorsOnlyProvideDocumentedSortValues() {
        val groups = DiscoveryMapper.filterGroups(emptyList<Any>())
        assertEquals(listOf("sort"), groups.map { it.key })
        assertEquals(listOf("", "hot_score", "online_time", "hot_collect"), groups.single().options.map { it.id })
    }

    @Test fun duplicatedRowsMergeAndEmptyUnknownItemsAreIgnored() {
        val rows = listOf(
            mapOf("type" to "category_dim_role", "items" to listOf(mapOf("id" to "role-a", "name" to "设定甲"))),
            mapOf("type" to "category_dim_role", "items" to listOf(
                mapOf("id" to "role-a", "name" to "重复设定"), mapOf("id" to "role-b", "name" to "设定乙"),
                mapOf("id" to "", "name" to "无ID项目"), mapOf("id" to "bad", "name" to ""),
            )),
            mapOf("type" to "category_dim_epoch", "items" to emptyList<Any>()),
        )
        val groups = DiscoveryMapper.filterGroups(rows)
        assertEquals(listOf("", "role-a", "role-b"), groups.first { it.key == "setting" }.options.map { it.id })
        assertFalse(groups.any { it.key == "background" })
    }

    @Test fun selectionClearsAllWithoutChangingProviderIdsOrPageSize() {
        val options = DiscoveryMapper.options(BrowseSelection(options = mapOf(
            "theme" to "provider-theme-123", "gender" to "0", "days" to "all", "status" to "",
            "unknown" to "discard", "limit" to "999", "sort" to "hot_score",
        )))
        assertEquals("provider-theme-123", options["theme"])
        assertEquals("0", options["gender"])
        assertEquals("18", options["limit"])
        assertFalse(options.containsKey("days"))
        assertFalse(options.containsKey("unknown"))
    }

    @Test fun seenTailKeepsNewestUniqueIdsAndBoundsPayload() {
        val ids = (1..200).map(Int::toString) + listOf("1", "not-an-id")
        val tail = DiscoveryMapper.seenTail(ids)
        assertEquals(180, tail.size)
        assertEquals("22", tail.first())
        assertEquals("1", tail.last())
        assertEquals(tail.size, tail.distinct().size)
    }

    @Test fun shortPagesFollowRawCursorAndStationaryOrEmptyPagesStop() {
        val raw = Json.optArr(Json.parse("[{\"series_id\":18446744073709551615,\"title\":\"示例\",\"episode_cnt\":4}]"))
        val page = DiscoveryMapper.page(raw, true, 36, 18, emptyList())
        assertEquals("18446744073709551615", page.items.single().id)
        assertTrue(page.hasMore)
        assertEquals(36, page.nextOffset)
        assertFalse(DiscoveryMapper.page(raw, true, 18, 18, emptyList()).hasMore)
        assertFalse(DiscoveryMapper.page(raw, true, 36, 18, listOf("18446744073709551615")).hasMore)
        assertEquals(18, DiscoveryMapper.page(raw, true, 0, 18, emptyList()).nextOffset)
    }

    @Test fun emptyUpstreamResponseHasContentServiceMessageOnHomepage() {
        assertEquals("内容服务暂未返回数据，请稍后重试。", readableFailure(IOException("上游未返回数据"), "首页加载"))
    }
}
