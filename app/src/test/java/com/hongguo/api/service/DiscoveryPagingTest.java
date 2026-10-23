package com.hongguo.api.service;

import com.hongguo.api.util.Config;
import com.hongguo.api.util.Json;
import com.hongguo.api.core.Safeguards;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class DiscoveryPagingTest {
    @BeforeClass public static void initialize() {
        Config.initialize("{\"upstream\":{\"api_host\":\"example.invalid\"}}",
                new File(System.getProperty("java.io.tmpdir"), "hongguo-discovery-test").getAbsolutePath(), "1234567890123456");
    }

    private static Map<String, Object> data(boolean more, int next, String... ids) {
        List<Object> items = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> item = Json.obj();
            item.put("series_id", id);
            item.put("title", "示例内容");
            item.put("episode_cnt", 20);
            items.add(item);
        }
        Map<String, Object> data = Json.obj();
        data.put("video_data", items);
        data.put("has_more", more);
        data.put("next_offset", next);
        return data;
    }

    private static List<String> ids(Client.Page page) {
        List<String> result = new ArrayList<>();
        for (Object value : page.items) result.add(Json.optStr(Json.optObj(value).get("series_id"), ""));
        return result;
    }

    @Test public void oversizedProviderPageResumesAtFirstUnconsumedRecord() {
        Client.DiscoveryAccumulator first = new Client.DiscoveryAccumulator(0, 2, null, false);
        assertFalse(first.consume(data(false, 5, "1", "2", "3", "4", "5")));
        assertEquals(Arrays.asList("1", "2"), ids(first.result()));
        assertEquals(2, first.result().nextSkip);
        assertTrue(first.result().hasMore);
        Client.DiscoveryAccumulator second = new Client.DiscoveryAccumulator(2, 2, Arrays.asList("1", "2"), false);
        second.consume(data(false, 5, "3", "4", "5"));
        assertEquals(Arrays.asList("3", "4"), ids(second.result()));
        assertEquals(4, second.result().nextSkip);
        assertTrue(second.result().hasMore);
        Client.DiscoveryAccumulator last = new Client.DiscoveryAccumulator(4, 2, Arrays.asList("1", "2", "3", "4"), false);
        last.consume(data(false, 5, "5"));
        assertEquals(Collections.singletonList("5"), ids(last.result()));
        assertEquals(5, last.result().nextSkip);
        assertFalse(last.result().hasMore);
    }

    @Test public void cursorCountsRawRecordsAndKeepsProviderContinuationOnShortPages() {
        Client.DiscoveryAccumulator page = new Client.DiscoveryAccumulator(18, 18, Arrays.asList("1", "2"), false);
        assertTrue(page.consume(data(true, 36, "1", "2", "3")));
        assertEquals(Collections.singletonList("3"), ids(page.result()));
        assertEquals(36, page.result().nextSkip);
        assertTrue(page.result().hasMore);
    }

    @Test public void invalidRecordsAndDuplicateOnlyPagesCannotLoopForever() {
        Client.DiscoveryAccumulator invalid = new Client.DiscoveryAccumulator(0, 18, null, false);
        invalid.consume(data(false, 4, "", "null", "not-an-id", "18446744073709551615"));
        assertEquals(Collections.singletonList("18446744073709551615"), ids(invalid.result()));
        assertEquals(4, invalid.result().nextSkip);
        Client.DiscoveryAccumulator duplicate = new Client.DiscoveryAccumulator(0, 18, Arrays.asList("1", "2"), false);
        duplicate.consume(data(true, 2, "1", "2"));
        assertTrue(duplicate.result().items.isEmpty());
        assertFalse(duplicate.result().hasMore);
        assertEquals(2, duplicate.result().nextSkip);
    }

    @Test public void emptyTailPreservesCardsAndStopsContinuation() {
        Client.DiscoveryAccumulator page = new Client.DiscoveryAccumulator(0, 18, null, false);
        assertTrue(page.consume(data(true, 2, "1", "2")));
        assertFalse(page.consume(data(true, 2)));
        assertEquals(2, page.result().items.size());
        assertEquals(2, page.result().nextSkip);
        assertFalse(page.result().hasMore);
    }

    @Test public void seenFilterKeepsBoundedNewestUniqueIds() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 200; i++) ids.add(String.valueOf(i));
        ids.add("1");
        ids.add("invalid");
        Client.DiscoveryAccumulator page = new Client.DiscoveryAccumulator(0, 18, ids, false);
        assertEquals(180, page.shown.size());
        assertEquals("22", page.shown.get(0));
        assertEquals("1", page.shown.get(179));
    }

    @Test public void firstPageCacheKeepsItsRealContinuationAndTerminalFlag() throws Exception {
        List<Object> rows = Json.optArr(data(false, 77, "1", "2").get("video_data"));
        String key = Safeguards.cacheKey("latest-v2", "short_play", "false", "18");
        Safeguards.cacheSet(key, new Object[]{rows, false, 77}, 600);
        Client.Page cached = Client.latestPage("short_play", false, 18, false, null, 0);
        assertEquals(77, cached.nextSkip);
        assertFalse(cached.hasMore);
        assertEquals(2, cached.items.size());
    }

    @Test public void fallbackCursorNeverMovesBackwardOrOverflows() {
        assertEquals(23, Client.discoveryNextCursor(20, 3, 0));
        assertEquals(20, Client.discoveryNextCursor(20, 0, 100));
        assertEquals(100, Client.discoveryNextCursor(20, 3, "100"));
        assertEquals(Integer.MAX_VALUE, Client.discoveryNextCursor(Integer.MAX_VALUE - 1, 18, null));
    }

    @Test public void comicRankingNormalizesListPayloadsWithoutLosingRawCellPositions() {
        Map<String, Object> cells = Json.parseObject("{\"has_more\":false,\"next_offset\":3,\"cell_data\":[null,"
                + "{\"video_data\":[{\"series_id\":18446744073709551615,\"title\":\"漫剧\"}]},"
                + "{\"video_data\":{\"series_id\":\"2\"}}]}");
        Client.DiscoveryAccumulator page = new Client.DiscoveryAccumulator(0, 1, null, false);
        page.consume(Client.rankData(cells));
        assertEquals(Collections.singletonList("18446744073709551615"), ids(page.result()));
        assertEquals(2, page.result().nextSkip);
        assertTrue(page.result().hasMore);
    }

    @Test public void listingRequestsAreSignedByDefaultAndDisableRequiresExplicitDebugValue() {
        assertTrue(Client.signedListsEnabled(null));
        assertTrue(Client.signedListsEnabled(""));
        assertTrue(Client.signedListsEnabled("true"));
        assertTrue(Client.signedListsEnabled("invalid-value"));
        assertFalse(Client.signedListsEnabled("false"));
        assertFalse(Client.signedListsEnabled(" OFF "));
    }

    @Test public void emptyHttpSuccessMustNotMasqueradeAsEmptyCatalogue() {
        for (String body : Arrays.asList("", "  ", "{}", "null")) {
            try {
                Client.parseApiResponse(body, 200);
                fail("An empty upstream response must fail");
            } catch (Safeguards.UpstreamResponseError error) {
                assertEquals("上游未返回数据", error.getMessage());
            }
        }
        Map<String, Object> genuineEmpty = Client.parseApiResponse("{\"code\":0,\"data\":{\"video_data\":[],\"has_more\":false}}", 200);
        assertTrue(Json.optArr(Json.optObj(genuineEmpty.get("data")).get("video_data")).isEmpty());
    }
}
