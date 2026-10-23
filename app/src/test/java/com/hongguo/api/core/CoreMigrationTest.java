package com.hongguo.api.core;

import com.hongguo.api.service.AppSource;
import com.hongguo.api.service.Client;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Offline regression coverage for the independent Android core. No upstream requests. */
public class CoreMigrationTest {
    private static final String ID = "1234567890123456";
    private static final String LONG_ID = "18446744073709551615";

    @BeforeClass public static void initialize() {
        Config.initialize("{\"upstream\":{\"api_host\":\"example.invalid\"},\"runtime\":{\"cache\":{\"mem_max\":64,\"mem_max_mb\":2}}}",
                new File(System.getProperty("java.io.tmpdir"), "hongguo-core-test").getAbsolutePath(), ID);
    }

    @Test public void sm3MatchesStandardVectors() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", Crypto.hex(Sm3.hash("abc")));
        assertEquals("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b", Crypto.hex(Sm3.hash("")));
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < 16; i++) blocks.append("abcd");
        assertEquals("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732", Crypto.hex(Sm3.hash(blocks.toString())));
    }

    @Test public void aesCtrMatchesNistVector() {
        byte[] actual = Crypto.aes128Ctr(Crypto.unhex("2b7e151628aed2a6abf7158809cf4f3c"),
                Crypto.unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"), Crypto.unhex("6bc1bee22e409f96e93d7e117393172a"));
        assertEquals("874d6191b620e3261bef6864990db6ce", Crypto.hex(actual));
    }

    @Test public void decimalIdsNeverPassThroughFloatingPoint() {
        Map<String, Object> parsed = Json.parseObject("{\"series_id\":" + LONG_ID + ",\"vid\":7693917197759958040}");
        assertEquals(LONG_ID, Json.optStr(parsed.get("series_id")));
        assertEquals("7693917197759958040", Json.optStr(parsed.get("vid")));
        assertTrue(Json.stringify(parsed).contains(LONG_ID));
        assertEquals(parsed, Json.parse(Json.stringifyPretty(parsed)));
    }

    @Test public void searchKeepsLongIdsAndRichMetadata() {
        Map<String, Object> input = Json.parseObject("{\"book_id\":" + LONG_ID
                + ",\"video_data\":{\"episode_cnt\":6,\"title\":\"<em>测试</em>短剧\",\"cover\":\"https://example.invalid/cover\",\"score\":9.3}}");
        Map<String, Object> item = Client.parseSearchCell(input);
        assertNotNull(item);
        assertEquals(LONG_ID, item.get("series_id"));
        assertEquals("测试短剧", item.get("title"));
        assertEquals(6L, item.get("episode_cnt"));
        assertEquals("https://example.invalid/cover", item.get("cover"));
    }

    @Test public void detailsSortEpisodesAndDoNotUseSynopsisAsEpisodeTitle() {
        Map<String, Object> input = Json.parseObject("{\"series_title\":\"示例短剧\",\"series_intro\":\"同一段简介\",\"series_status\":1,"
                + "\"video_list\":[{\"vid\":" + LONG_ID + ",\"vid_index\":2,\"title\":\"同一段简介\",\"episode_cover\":\"cover2\"},"
                + "{\"vid\":\"7693917197759958040\",\"vid_index\":1,\"title\":\"开篇\",\"cover\":\"cover1\"}]}");
        Map<String, Object> result = Client.parseEpisodeDetail(LONG_ID, input);
        Map<String, Object> meta = Json.optObj(result.get("meta"));
        List<Object> episodes = Json.optArr(result.get("episodes"));
        assertEquals(LONG_ID, meta.get("series_id"));
        assertEquals("示例短剧", meta.get("title"));
        assertEquals("同一段简介", meta.get("abstract"));
        assertEquals("cover1", meta.get("cover"));
        assertEquals("完结", meta.get("status"));
        assertEquals("开篇", Json.optObj(episodes.get(0)).get("title"));
        assertEquals("", Json.optObj(episodes.get(1)).get("title"));
        assertEquals(LONG_ID, Json.optObj(episodes.get(1)).get("vid"));
    }

    @Test public void missingEntryAndEmptyDataAreSafeEmptyDetails() {
        assertTrue(Json.optArr(Client.parseEpisodeResponse(LONG_ID, Json.parseObject("{\"code\":0,\"data\":[]}")).get("episodes")).isEmpty());
        assertTrue(Json.optArr(Client.parseEpisodeResponse(LONG_ID, Json.parseObject("{\"code\":0,\"data\":{}}")).get("episodes")).isEmpty());
        Map<String, Object> invalid = Json.parseObject("{\"video_list\":[{\"vid\":\"\"},{\"vid\":null},{\"vid\":\"not-a-video-id\"}]}");
        assertTrue(Json.optArr(Client.parseEpisodeDetail(LONG_ID, invalid).get("episodes")).isEmpty());
    }

    @Test public void loadedLocalResultsHaveAnotherPageWithoutUpstreamCursor() {
        Client.Page first = Client.Page.slice(Arrays.asList("a", "b", "c"), 0, 2, false);
        assertTrue(first.hasMore);
        assertEquals(2, first.nextSkip);
        assertEquals(Arrays.asList("a", "b"), first.items);
        Client.Page last = Client.Page.slice(Arrays.asList("a", "b", "c"), 2, 2, false);
        assertFalse(last.hasMore);
        assertEquals(Arrays.asList("c"), last.items);
    }

    @Test public void identityComesOnlyFromApplication() {
        Map<String, Object> query = Json.obj();
        query.put("device_id", "9999999999999999");
        assertEquals(ID, Device.ensureDeviceId(query).deviceId);
        assertEquals(ID, query.get("device_id"));
        assertEquals("0", query.get("iid"));
        assertEquals(ID, KeyStore.store().deviceId());
        assertTrue(new File(Config.str("runtime.data_dir", "")).isAbsolute());
    }

    @Test public void logDoesNotExposeUserInputOrExceptionDetails() throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(captured, true, "UTF-8"));
            Log.warn("官网搜索失败 private-query-123 https://example.invalid/?token=secret-value");
            Log.error("raw-body-private", new IOException("private-exception-token"));
        } finally { System.setErr(original); }
        String output = captured.toString("UTF-8");
        assertTrue(output.contains("web_source_event"));
        assertFalse(output.contains("private"));
        assertFalse(output.contains("secret-value"));
        assertFalse(output.contains("example.invalid"));
    }

    @Test public void invalidTransportAndUntrustedMediaUrlDoNotEchoInput() throws Exception {
        try {
            Http.send(new Http.Req("unsupported://secret.invalid/?token=private-value"));
            fail("Unsupported protocol must fail");
        } catch (IOException error) {
            assertFalse(error.getMessage().contains("private-value"));
            assertFalse(error.getMessage().contains("secret.invalid"));
        }
        try {
            AppSource.checkedUrl("https://evil.invalid/?secret=private-value", AppSource.VOD_DOMAINS);
            fail("Untrusted host must fail");
        } catch (IllegalStateException error) { assertFalse(error.getMessage().contains("private-value")); }
    }

    @Test public void stringBusinessCodesKeepAuthenticationAndRiskClassification() {
        try {
            Safeguards.checkResponse(Json.parseObject("{\"code\":\"401\",\"message\":\"private-session-value\"}"));
            fail("Authentication error expected");
        } catch (Safeguards.AuthExpiredError error) {
            assertFalse(error.getMessage().contains("private-session-value"));
        }
        try {
            Safeguards.checkResponse(Json.parseObject("{\"code\":\"110001\",\"message\":\"private-risk-value\"}"));
            fail("Risk error expected");
        } catch (Safeguards.RiskControlError error) {
            assertFalse(error.getMessage().contains("private-risk-value"));
        }
        try {
            Safeguards.checkResponse(Json.parseObject("{\"code\":5678,\"message\":\"private-business-value\"}"));
            fail("Unknown nonzero business code must fail");
        } catch (Safeguards.UpstreamResponseError error) {
            assertFalse(error.getMessage().contains("private-business-value"));
        }
    }

    @Test public void boundedCacheEvictsOldPagesAndAccountsForNestedPageArrays() {
        for (int i = 0; i < 2000; i++) Safeguards.cacheSet("migration-cache-" + i, "page-" + i, 60);
        assertNull(Safeguards.cacheGet("migration-cache-0"));
        assertEquals("page-1999", Safeguards.cacheGet("migration-cache-1999"));
        char[] text = new char[2048];
        Arrays.fill(text, 'x');
        assertTrue(Safeguards.weigh(new Object[]{Arrays.asList(new String(text))}) > 4000);
    }
}
