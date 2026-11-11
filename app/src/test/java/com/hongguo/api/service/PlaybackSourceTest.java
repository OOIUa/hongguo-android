package com.hongguo.api.service;

import com.hongguo.api.core.PureSign;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Json;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/** 2026-10-27 播放取链恢复：匿名设备注册 + 无签名播放请求 + 内联片源模型。 */
public class PlaybackSourceTest {

    /** 19 位、超出 double 精度的设备号（合成值，非真实身份）。 */
    private static final String DEVICE_ID = "7432109876543210987";
    private static final String INSTALL_ID = "7432109876543210123";

    /** Spade 自检真值之一，解出合法 content key。 */
    private static final String VALID_SPADE = Base64.getEncoder().encodeToString(Crypto.unhex(
            "93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282"));

    @BeforeClass public static void initialize() {
        Config.initialize("{\"upstream\":{\"api_host\":\"example.invalid\"}}",
                new File(System.getProperty("java.io.tmpdir"), "hongguo-playback-test").getAbsolutePath(),
                "1234567890123456");
    }

    @Before public void reset() {
        PlaybackDevice.resetForTest(null);
        AppSource.setPlaybackPostForTest(null);
        // 默认让 video_tab 不可用，覆盖旧接口回退路径；需要新接口的用例自行注入。
        AppSource.setPlaybackTabForTest((vid, identity) -> null);
        // 默认让单集接口回空，覆盖旧路径；需要单集接口的用例自行注入。
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            throw new AppSource.EmptyPlaybackResponseException();
        });
    }

    @After public void restore() {
        AppSource.setPlaybackFallbackForTest(null);
        PlaybackDevice.resetForTest(null);
        AppSource.setPlaybackPostForTest(null);
        AppSource.setPlaybackTabForTest(null);
        AppSource.setSinglePlaybackForTest(null);
    }

    // ==================== ttEncrypt 注册请求体 ====================

    @Test public void registrationBodyDecryptsToGzipPayloadWithDigest() throws Exception {
        byte[] random = new byte[32];
        for (int i = 0; i < random.length; i++) random[i] = (byte) i;
        long now = 1_793_000_000_000L;
        byte[] body = PlaybackDevice.buildBody(now, "cdid-fixed", random);

        assertArrayEquals(Crypto.unhex("746305100000"), Arrays.copyOfRange(body, 0, 6));
        assertArrayEquals(random, Arrays.copyOfRange(body, 6, 38));

        byte[] derived = PlaybackDevice.deriveKeyIv(random);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derived, 0, 16, "AES"),
                new IvParameterSpec(derived, 16, 16));
        byte[] plain = c.doFinal(body, 38, body.length - 38);
        byte[] digest = Arrays.copyOfRange(plain, 0, 64);
        byte[] compressed = Arrays.copyOfRange(plain, 64, plain.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-512").digest(compressed), digest);

        Map<String, Object> payload = Json.parseObject(new String(gunzip(compressed), StandardCharsets.UTF_8));
        assertEquals("ss_app_log", payload.get("magic_tag"));
        assertEquals(now, Json.optLong(payload.get("_gen_time"), 0));
        Map<String, Object> header = Json.optObj(payload.get("header"));
        assertEquals("cdid-fixed", header.get("cdid"));
        assertEquals(8662L, Json.optLong(header.get("aid"), 0));
        assertEquals("com.phoenix.read", header.get("package"));
        assertFalse("注册请求不得携带账号或 Cookie 字段",
                header.containsKey("cookie") || header.containsKey("user_id") || header.containsKey("sessionid"));
    }

    @Test public void registrationKeyMatchesDoubleSha512OverSalt() throws Exception {
        byte[] random = new byte[32];
        Arrays.fill(random, (byte) 7);
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] inner = sha.digest(random);
        sha.update(inner);
        sha.update(Crypto.unhex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
                + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"));
        assertArrayEquals(Arrays.copyOf(sha.digest(), 32), PlaybackDevice.deriveKeyIv(random));
    }

    @Test public void registrationHeadersCarryStubAndNoSignature() throws Exception {
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = PlaybackDevice.headers(body, 123L);
        assertEquals(Crypto.md5HexUpper(body), h.get("x-ss-stub"));
        assertEquals("application/octet-stream;tt-data=a", h.get("Content-Type"));
        assertEquals("123", h.get("x-ss-req-ticket"));
        for (String k : h.keySet()) assertFalse(k, isSignatureHeader(k));
    }

    // ==================== 注册响应解析 ====================

    @Test public void responseIdsKeepNineteenDigitsExactly() throws Exception {
        Map<String, Object> j = Json.parseObject("{\"device_id\":" + DEVICE_ID
                + ",\"install_id\":" + INSTALL_ID + ",\"code\":0}");
        PlaybackDevice.Identity id = PlaybackDevice.parse(j, "c", "");
        assertEquals(DEVICE_ID, id.deviceId);
        assertEquals(INSTALL_ID, id.iid);
        assertEquals("c", id.cdid);
        assertFalse(id.toString().contains(DEVICE_ID));
    }

    @Test public void responseIdsPreferStringFields() {
        Map<String, Object> j = Json.obj();
        j.put("device_id", 1.0e18);           // 已丢精度的浮点，单独出现时必须拒绝
        j.put("device_id_str", DEVICE_ID);
        assertEquals(DEVICE_ID, PlaybackDevice.responseId(j, "device_id"));
    }

    @Test public void responseIdsRejectZeroFloatsAndJunk() {
        Object[] bad = {0L, "0", "000", 1.0e18, -5L, "-5", "12a", "", " ", "1".repeat(31), true};
        for (Object v : bad) {
            Map<String, Object> j = Json.obj();
            j.put("install_id", v);
            assertEquals(String.valueOf(v), "", PlaybackDevice.responseId(j, "install_id"));
        }
        Map<String, Object> big = Json.obj();
        big.put("install_id", new BigInteger("98765432109876543210"));
        assertEquals("98765432109876543210", PlaybackDevice.responseId(big, "install_id"));
    }

    @Test(expected = IOException.class)
    public void registrationRejectedByBusinessCode() throws Exception {
        PlaybackDevice.parse(Json.parseObject("{\"code\":1,\"device_id_str\":\"" + DEVICE_ID
                + "\",\"install_id_str\":\"" + INSTALL_ID + "\"}"), "c", "");
    }

    // ==================== 进程内共享与失败冷却 ====================

    @Test public void everyCallRegistersItsOwnIdentity() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            calls.incrementAndGet();
            assertEquals(PlaybackDevice.REGISTER_URL, url);
            return okReply();
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<PlaybackDevice.Identity>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) results.add(pool.submit(PlaybackDevice::fresh));
            for (Future<PlaybackDevice.Identity> f : results) assertEquals(DEVICE_ID, f.get(5, TimeUnit.SECONDS).deviceId);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(4, calls.get());
    }

    @Test public void consecutiveEpisodesDoNotReuseAnIdentity() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            registrations.incrementAndGet();
            return okReply();
        });
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            used.add(identity);
            throw new IllegalStateException("reached model request");
        });
        for (int i = 0; i < 2; i++) {
            try {
                AppSource.fetch("7000000000000000001", "auto");
                fail();
            } catch (IllegalStateException expected) {
                assertEquals("reached model request", expected.getMessage());
            }
        }
        assertEquals(2, registrations.get());
        assertNotSame(used.get(0), used.get(1));
    }

    @Test public void failureIsReusedOnlyDuringCooldown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        boolean[] healthy = {false};
        PlaybackDevice.resetForTest((url, headers, body) -> {
            calls.incrementAndGet();
            if (!healthy[0]) throw new IOException("offline");
            return okReply();
        });
        IOException first = expectRegistrationFailure();
        IOException second = expectRegistrationFailure();
        assertSame("冷却期内复用同一失败，不重复注册", first, second);
        assertEquals(1, calls.get());
        assertTrue(first.getMessage().contains("匿名播放设备注册失败"));
        assertFalse("失败消息不得带出底层细节", first.getMessage().contains("offline"));

        healthy[0] = true;
        PlaybackDevice.advanceClockForTest(PlaybackDevice.FAILURE_COOLDOWN_MS + 1);
        assertEquals(DEVICE_ID, PlaybackDevice.fresh().deviceId);
        assertEquals(2, calls.get());
    }

    @Test public void emptyOrNonJsonRegistrationIsFailure() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(200, ""));
        expectRegistrationFailure();
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(503, "{}"));
        expectRegistrationFailure();
    }

    // ==================== 签名播放请求 ====================

    @Test public void playbackRequestIsSignedWithTheRegisteredIdentityAndCookie() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> new PlaybackDevice.Reply(200, okReply().text,
                Arrays.asList("install_id=11; Path=/; Domain=fqnovel.com", "ttreq=1$abc; Path=/", "other=secret; Path=/")));
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        assertEquals("install_id=11; ttreq=1$abc", id.cookie);
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String base = "https://" + AppSource.RETRY_HOST + "/novel/player/multi_video_model/v1/";
        AppSource.SignedPlayback post = AppSource.signPlayback(base, id, new java.util.LinkedHashMap<>(), body, 1_793_000_000_123L);

        String query = post.url.substring(post.url.indexOf('?') + 1);
        assertTrue(query.startsWith("iid=" + INSTALL_ID + "&device_id=" + DEVICE_ID + "&"));
        assertTrue(query.contains("&cdid=" + id.cdid + "&_rticket="));
        assertTrue(query.contains("version_code=73932") && query.contains("version_name=7.3.9.32") && query.contains("pv_player=73932"));
        assertFalse(query.contains("71332"));
        assertTrue("resolution 的 * 不编码", query.contains("resolution=1280*2772"));
        assertFalse(query.contains("&ts="));
        assertEquals(1, count(query, "device_id="));
        assertEquals("只接受分支 0", 0, PureSign.branchOf(post.url, body, 1_793_000_000L));
        assertEquals(post.headers.get("x-ss-req-ticket"), query.substring(query.indexOf("_rticket=") + 9));
        for (String name : new String[]{"x-gorgon", "x-khronos", "x-ladon", "x-argus", "x-helios", "x-medusa"}) {
            assertFalse(name, post.headers.getOrDefault(name, "").isEmpty());
        }
        assertEquals(AppSource.PLAYBACK_UA, post.headers.get("User-Agent"));
        assertEquals("install_id=11; ttreq=1$abc", post.headers.get("Cookie"));
        assertEquals("application/json; charset=utf-8", post.headers.get("Content-Type"));
        assertEquals(Crypto.md5HexUpper(body), post.headers.get("x-ss-stub"));

        AppSource.SignedPlayback get = AppSource.signPlayback(base, id, AppSource.tabQuery("7600000000000000001"), null, 1_793_000_000_123L);
        assertFalse(get.headers.containsKey("x-ss-stub"));
        assertFalse(get.headers.containsKey("Content-Type"));
        assertEquals("install_id=11; ttreq=1$abc", get.headers.get("Cookie"));
        assertFalse(AppSource.deviceBase().containsKey("iid"));
        assertFalse(AppSource.deviceBase().containsKey("device_id"));
    }

    @Test public void registrationWithoutCookiesStillSignsWithoutCookieHeader() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        assertEquals("", id.cookie);
        assertFalse(AppSource.signPlayback("https://h/p", id, new java.util.LinkedHashMap<>(), null, 1L).headers.containsKey("Cookie"));
    }

    @Test public void httpRejectionMovesToTheNextRouteAndIsReportedLast() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        List<String> hosts = new ArrayList<>();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            hosts.add(host);
            throw new AppSource.PlaybackHttpException(403);
        });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (AppSource.PlaybackHttpException e) {
            assertEquals("App 接口 HTTP 403", e.getMessage());
        }
        assertEquals(Arrays.asList(AppSource.VIDEO_API_HOST, AppSource.RETRY_HOST), hosts);
    }

    @Test public void renewedIdentityRetriesTheOfficialRouteBeforeTheAlternateHost() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> registrations.incrementAndGet() == 1
                ? okReply() : reply("7432109876543219999", "7432109876543218888"));
        List<String> tabIdentities = new ArrayList<>();
        Map<String, Object> model = Json.obj();
        List<Object> list = Json.arr();
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        model.put("video_list", list);
        AppSource.setPlaybackTabForTest((vid, identity) -> {
            tabIdentities.add(identity.deviceId);
            return tabIdentities.size() == 1 ? null : response(vid, model);
        });
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        assertEquals("https://v1.qznovelvod.com/1080.mp4", AppSource.fetch("7600000000000000001", "auto").url);
        assertEquals(Arrays.asList(DEVICE_ID, "7432109876543219999"), tabIdentities);
    }

    /** 2026-11-09 issue #3：GET 只给缺密钥的内联片源时，POST 仍可能给出完整片源，不能就此报「片源不可用」。 */
    @Test public void unusableModelOnOneRouteMovesToTheNextRoute() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        Map<String, Object> broken = Json.obj();
        List<Object> brokenList = Json.arr();
        brokenList.add(keylessEncryptedItem());
        broken.put("video_list", brokenList);
        Map<String, Object> good = Json.obj();
        List<Object> goodList = Json.arr();
        goodList.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        good.put("video_list", goodList);
        AtomicInteger tabs = new AtomicInteger();
        AppSource.setPlaybackTabForTest((vid, identity) -> { tabs.incrementAndGet(); return response(vid, broken); });
        AppSource.setPlaybackPostForTest((body, identity, host) -> response("7600000000000000001", good));
        assertEquals("https://v1.qznovelvod.com/1080.mp4", AppSource.fetch("7600000000000000001", "auto").url);
        assertEquals(1, tabs.get());
    }

    // ==================== 单集接口（2026-11-11） ====================

    @Test public void singlePlaybackSucceedsWithoutCallingLegacyPaths() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AtomicInteger legacy = new AtomicInteger();
        Stream.VideoTrack track = new Stream.VideoTrack();
        track.url = "https://v1.qznovelvod.com/single.mp4";
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> track);
        AppSource.setPlaybackTabForTest((vid, identity) -> { legacy.incrementAndGet(); return null; });
        AppSource.setPlaybackPostForTest((body, identity, host) -> { legacy.incrementAndGet(); throw new IOException(); });
        assertSame(track, AppSource.fetch("7600000000000000001", "auto"));
        assertEquals(0, legacy.get());
    }

    @Test public void emptySingleReplyFallsBackToLegacyRoutes() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        Map<String, Object> good = Json.obj();
        List<Object> list = Json.arr();
        list.add(item("h264", 1080, 1920, "https://v1.qznovelvod.com/legacy.mp4", VALID_SPADE));
        good.put("video_list", list);
        AppSource.setPlaybackTabForTest((vid, identity) -> response(vid, good));
        assertEquals("https://v1.qznovelvod.com/legacy.mp4", AppSource.fetch("7600000000000000001", "auto").url);
    }

    @Test public void singleModelErrorIsReportedWhenLegacyRoutesAreEmpty() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            throw new IllegalStateException("本集仅提供 ByteVC2");
        });
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException e) {
            fail("单集接口的模型错误应优先于旧路径的空响应");
        } catch (IllegalStateException e) {
            assertEquals("本集仅提供 ByteVC2", e.getMessage());
        }
    }

    @Test public void inlineMapDoesNotRequireFallbackUrl() throws Exception {
        String vid = "7600000000000000001";
        Map<String, Object> list = Json.obj();
        list.put("video_1", item("h264", 720, 1280, "https://v1.qznovelvod.com/test.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        assertEquals("https://v1.qznovelvod.com/test.mp4", AppSource.parseModel(vid, response(vid, model), "auto").url);
    }

    @Test public void singleUrlIsSortedAndUsesSingleVersion() {
        PlaybackDevice.Identity id = new PlaybackDevice.Identity("1111", "2222", "cd", "");
        String url = AppSource.singleUrl(id, 1700000000123L);
        assertTrue(url.startsWith("https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/?_rticket=1700000000123&ac=wifi&"));
        assertTrue(url.contains("&device_id=1111&"));
        assertTrue(url.contains("&iid=2222&"));
        assertTrue(url.contains("&version_code=73532&version_name=7.3.5.32"));
        assertFalse(url.contains("pv_player"));
        assertFalse(url.contains("cdid"));
    }

    @Test public void unusableModelOnEveryRouteReportsTheModelError() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        Map<String, Object> broken = Json.obj();
        List<Object> brokenList = Json.arr();
        brokenList.add(keylessEncryptedItem());
        broken.put("video_list", brokenList);
        AtomicInteger requests = new AtomicInteger();
        AppSource.setPlaybackTabForTest((vid, identity) -> { requests.incrementAndGet(); return response(vid, broken); });
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            requests.incrementAndGet();
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException e) {
            fail("模型错误应优先于空响应");
        } catch (IllegalStateException e) {
            assertEquals("本集片源地址或密钥无法解析", e.getMessage());
        }
        assertEquals(AppSource.PLAYBACK_ROUTES.length, requests.get());
    }

    @Test public void emptyPlaybackResponseIsReportedAsIdentityRejection() {
        try {
            AppSource.parsePlaybackResponse("  ");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("空响应"));
        }
        try {
            AppSource.parsePlaybackResponse("{\"code\":0}");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("App 接口未返回播放数据", e.getMessage());
        }
    }

    @Test public void emptyPlaybackResponseRetriesOnceWithRenewedIdentity() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> registrations.incrementAndGet() == 1
                ? okReply() : reply("7432109876543219999", "7432109876543218888"));
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            used.add(identity);
            hosts.add(host);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            if (used.size() == 1) throw new AppSource.EmptyPlaybackResponseException();
            throw new IllegalStateException("reached retry");
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("reached retry", e.getMessage());
        }
        assertEquals(2, used.size());
        assertEquals(2, registrations.get());
        assertEquals(DEVICE_ID, used.get(0).deviceId);
        assertEquals("7432109876543219999", used.get(1).deviceId);
        assertTrue(bodies.get(0).contains("\"device_level\":1"));
        assertEquals(Arrays.asList(AppSource.VIDEO_API_HOST, AppSource.RETRY_HOST), hosts);
    }

    @Test public void secondEmptyPlaybackResponseIsNotRetriedAgain() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            registrations.incrementAndGet();
            return okReply();
        });
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException expected) {
            // 只换一次身份
        }
        assertEquals(2, posts.get());
        assertEquals(2, registrations.get());
    }

    @Test public void failedRenewalStillRetriesAlternateHost() throws Exception {
        AtomicInteger registrations = new AtomicInteger();
        PlaybackDevice.resetForTest((url, headers, body) -> {
            if (registrations.incrementAndGet() > 1) throw new IOException("offline");
            return okReply();
        });
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            AppSource.fetch("7000000000000000001", "auto");
            fail();
        } catch (AppSource.EmptyPlaybackResponseException e) {
            assertEquals(1, e.getSuppressed().length);
        }
        assertEquals("换身份失败仍以原身份试一次备用域名", 2, posts.get());
        assertEquals(2, registrations.get());
    }

    // ==================== video_tab 播放模型 ====================

    @Test public void tabRequestCarriesEveryVideoModelField() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        PlaybackDevice.Identity id = PlaybackDevice.fresh();
        String url = AppSource.signPlayback("https://" + AppSource.VIDEO_API_HOST + "/reading/bookapi/video_tab/multi_video_model/v1/",
                id, AppSource.tabQuery("7600000000000000001"), null, 1_793_000_000_123L).url;
        String query = url.substring(url.indexOf('?') + 1);
        assertTrue(query.contains("&cdid=" + id.cdid + "&video_id=7600000000000000001&video_id_type=0"
                + "&device_level=1&need_all_video_definition=1&need_mp4_align=0&use_os_player=0"
                + "&video_platform=1024&source=0&from_video_id=&_rticket="));
        assertEquals(1, count(query, "device_id="));
    }

    @Test public void tabResponseIsNormalizedOrRejected() throws Exception {
        String vid = "7600000000000000001";
        Map<String, Object> j = AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\""
                + vid + "\":{\"video_model\":\"{\\\"video_list\\\":[{\\\"codec_type\\\":\\\"bytevc1\\\"}]}\"}}}}");
        assertNotNull(j);
        assertEquals(1, Json.optArr(Json.optObj(Json.optObj(Json.optObj(j.get("data")).get(vid)).get("video_model"))
                .get("video_list")).size());
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":100103,\"message\":\"PARAM_INVALID\"}"));
        assertNull(AppSource.parseTabResponse(vid, ""));
        assertNull(AppSource.parseTabResponse(vid, "{\"data\":{}}"));
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\"7600000000000000999\":"
                + "{\"video_model\":{\"video_list\":[{}]}}}}}"));
        assertNull(AppSource.parseTabResponse(vid, "{\"code\":0,\"data\":{\"video_model_data\":{\"" + vid
                + "\":{\"video_model\":{\"video_list\":[]}}}}}"));
    }

    @Test public void tabModelIsPlayedWithoutOldEndpoint() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        List<Object> list = Json.arr();
        list.add(item("bytevc2", 720, 1280, "https://v1.qznovelvod.com/vvc.mp4", VALID_SPADE));
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        AppSource.setPlaybackTabForTest((vid, identity) -> response(vid, model));
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AssertionError("旧接口不应被请求"); });
        Stream.VideoTrack t = AppSource.fetch("7600000000000000001", "auto");
        assertEquals("bytevc2 被过滤", "https://v1.qznovelvod.com/1080.mp4", t.url);
    }

    @Test public void unavailableTabFallsBackToOldEndpoint() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> okReply());
        AtomicInteger tabs = new AtomicInteger();
        AtomicInteger posts = new AtomicInteger();
        AppSource.setPlaybackTabForTest((vid, identity) -> { tabs.incrementAndGet(); return null; });
        AppSource.setPlaybackPostForTest((body, identity, host) -> {
            posts.incrementAndGet();
            throw new IllegalStateException("reached old endpoint");
        });
        try {
            AppSource.fetch("7600000000000000001", "auto");
            fail();
        } catch (IllegalStateException e) {
            assertEquals("业务或格式错误不再继续试", "reached old endpoint", e.getMessage());
        }
        assertEquals(1, tabs.get());
        assertEquals(1, posts.get());
    }

    // ==================== 内联片源模型 ====================

    @Test public void inlineVideoListIsUsedWithoutFallbackRequest() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/hevc.mp4", VALID_SPADE));
        list.add(item("h264", 720, 1280, "https://v1.qznovelvod.com/avc.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        // 若误走 fallback，会请求这个不受信任地址并抛出异常
        model.put("fallback_api", "https://evil.invalid/fallback");

        Stream.VideoTrack t = AppSource.parseModel("7600000000000000001", response("7600000000000000001", model), "auto");
        assertEquals("自动模式 H.264 优先", "https://v1.qznovelvod.com/avc.mp4", t.url);
        assertTrue(t.encrypt);
        assertEquals(VALID_SPADE, t.spadeA);
        assertEquals("720p", t.definition);
    }

    @Test public void inlineModelMayBeJsonStringAndHonoursRequestedQuality() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 540, 960, "https://v1.qznovelvod.com/540.mp4", VALID_SPADE));
        list.add(item("bytevc1", 1080, 1920, "https://v1.qznovelvod.com/1080.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        Stream.VideoTrack t = AppSource.parseModel("7600000000000000002",
                responseRaw("7600000000000000002", Json.stringify(model)), "1080p");
        assertEquals("https://v1.qznovelvod.com/1080.mp4", t.url);
    }

    /** 内联 1080P 缺加密信息；备用接口返回带密钥的片源。 */
    private static Map<String, Object> incompleteInlineModel() {
        List<Object> list = Json.arr();
        list.add(item("h264", 1080, 1920, "https://v1.qznovelvod.com/nokey.mp4", null));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        model.put("video_id", "v0abc");
        model.put("video_duration", 60.0);
        model.put("fallback_api", "https://vod.snssdk.com/video/fallback");
        return model;
    }

    private static Map<String, Object> fallbackReply(String id, double duration, int shortSide) {
        Map<String, Object> row = item("h264", shortSide, shortSide * 16 / 9, "https://v1.qznovelvod.com/keyed.mp4", VALID_SPADE);
        Map<String, Object> rows = Json.obj();
        rows.put("video_1", row);
        Map<String, Object> data = Json.obj();
        data.put("video_id", id);
        data.put("video_duration", duration);
        data.put("video_list", rows);
        Map<String, Object> info = Json.obj();
        info.put("data", data);
        Map<String, Object> outer = Json.obj();
        outer.put("video_info", info);
        return outer;
    }

    @Test public void inlineWithoutEncryptionInfoUsesVerifiedFallback() throws Exception {
        String vid = "7600000000000000011";
        AppSource.setPlaybackFallbackForTest(url -> fallbackReply("v0abc", 60.2, 1080));
        Stream.VideoTrack t = AppSource.parseModel(vid, response(vid, incompleteInlineModel()), "auto");
        assertEquals("https://v1.qznovelvod.com/keyed.mp4", t.url);
        assertTrue(t.encrypt);
    }

    @Test public void fallbackMustMatchIdentityDurationAndQuality() {
        String vid = "7600000000000000012";
        Map<String, Map<String, Object>> replies = new java.util.LinkedHashMap<>();
        replies.put("其他视频", fallbackReply("v0other", 60.0, 1080));
        replies.put("时长不同", fallbackReply("v0abc", 30.0, 1080));
        replies.put("清晰度更低", fallbackReply("v0abc", 60.0, 720));
        replies.forEach((name, reply) -> {
            AppSource.setPlaybackFallbackForTest(url -> reply);
            try {
                // 对不上的备用片源不得使用；内联档位未声明加密，改按明文（由媒体索引再校验）。
                Stream.VideoTrack t = AppSource.parseModel(vid, response(vid, incompleteInlineModel()), "auto");
                assertEquals(name, "https://v1.qznovelvod.com/nokey.mp4", t.url);
                assertFalse(name, t.encrypt);
            } catch (Exception other) {
                throw new AssertionError(other);
            }
        });
    }

    @Test public void declaredEncryptedInlineItemDoesNotTryFallback() throws Exception {
        String vid = "7600000000000000013";
        Map<String, Object> model = incompleteInlineModel();
        Map<String, Object> info = Json.obj();
        info.put("encrypt", true);
        ((Map<String, Object>) ((List<Object>) model.get("video_list")).get(0)).put("encrypt_info", info);
        AppSource.setPlaybackFallbackForTest(url -> { throw new AssertionError("不应请求备用接口"); });
        try {
            AppSource.parseModel(vid, response(vid, model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("备用"));
        }
    }

    @Test public void byteVc2OnlyEpisodeSaysSoInsteadOfGenericFailure() throws Exception {
        String vid = "7600000000000000014";
        List<Object> list = Json.arr();
        list.add(item("bytevc2", 1080, 1920, "https://v1.qznovelvod.com/vc2.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel(vid, response(vid, model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ByteVC2"));
        }
    }

    /** 备用接口回复：单档指定编码、编号与时长（ByteVC2 兼容请求测试用）。 */
    private static Map<String, Object> codecReply(String codec, String id, double duration) {
        Map<String, Object> reply = fallbackReply(id, duration, 720);
        Map<String, Object> data = Json.optObj(Json.optObj(reply.get("video_info")).get("data"));
        Map<String, Object> row = Json.optObj(Json.optObj(data.get("video_list")).get("video_1"));
        row.put("codec_type", codec);
        row.put("main_url", "https://v1.qznovelvod.com/" + codec + ".mp4");
        return reply;
    }

    private static Map<String, Object> byteVc2InlineModel() {
        List<Object> list = Json.arr();
        list.add(item("bytevc2", 1080, 1920, "https://v1.qznovelvod.com/vc2.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        model.put("video_id", "v0vc2");
        model.put("video_duration", 60.0);
        model.put("fallback_api", "https://vod.snssdk.com/video/fallback?sig=a%2Bb&codec_type=3");
        return model;
    }

    @Test public void byteVc2OnlyEpisodeRetriesFallbackWithCompatibilityCodec() throws Exception {
        String vid = "7600000000000000015";
        List<String> urls = new ArrayList<>();
        // 2026-11-10 实测：codec_type=1 给出 h265（issue #3，第六季第 58 集）。
        AppSource.setPlaybackFallbackForTest(url -> {
            urls.add(url);
            return codecReply(url.endsWith("&codec_type=1") ? "h265" : "bvc2", "v0vc2", 60.0);
        });
        Stream.VideoTrack t = AppSource.parseModel(vid, response(vid, byteVc2InlineModel()), "auto");
        assertEquals("https://v1.qznovelvod.com/h265.mp4", t.url);
        assertEquals(2, urls.size());
        assertEquals("https://vod.snssdk.com/video/fallback?sig=a%2Bb&codec_type=3", urls.get(0));
        assertEquals("其余参数原样保留，编码参数被替换",
                "https://vod.snssdk.com/video/fallback?sig=a%2Bb&codec_type=1", urls.get(1));

        // codec_type=1 仍是 ByteVC2 时，再试旧的 force_codec=h264。
        urls.clear();
        AppSource.setPlaybackFallbackForTest(url -> {
            urls.add(url);
            return codecReply(url.contains("force_codec") ? "hevc" : "bytevc2", "v0vc2", 60.0);
        });
        assertEquals("https://v1.qznovelvod.com/hevc.mp4",
                AppSource.parseModel(vid, response(vid, byteVc2InlineModel()), "auto").url);
        assertEquals(3, urls.size());
        assertEquals("https://vod.snssdk.com/video/fallback?sig=a%2Bb&codec_type=0&force_codec=h264", urls.get(2));

        // 第一个兼容请求失败（网络或格式）也继续试下一个。
        AppSource.setPlaybackFallbackForTest(url -> {
            if (url.endsWith("&codec_type=1")) throw new java.io.IOException("reset");
            return codecReply(url.contains("force_codec") ? "h264" : "bytevc2", "v0vc2", 60.0);
        });
        assertEquals("https://v1.qznovelvod.com/h264.mp4",
                AppSource.parseModel(vid, response(vid, byteVc2InlineModel()), "auto").url);

        Map<String, Object> bare = Json.obj();
        bare.put("fallback_api", "https://vod.snssdk.com/video/fallback");
        AppSource.setPlaybackFallbackForTest(url ->
                codecReply(url.endsWith("?codec_type=1") ? "h265" : "bytevc2", "v0vc2", 60.0));
        assertEquals("https://v1.qznovelvod.com/h265.mp4",
                AppSource.parseModel(vid, response(vid, bare), "auto").url);
    }

    @Test public void byteVc2CompatibilityAnswerMustStillBeCompatibleAndSameVideo() throws Exception {
        String vid = "7600000000000000016";
        Map<String, Map<String, Object>> replies = new LinkedHashMap<>();
        replies.put("仍是 ByteVC2", codecReply("bytevc2", "v0vc2", 60.0));
        replies.put("其他视频", codecReply("hevc", "v0other", 60.0));
        replies.put("时长不同", codecReply("hevc", "v0vc2", 30.0));
        for (Map.Entry<String, Map<String, Object>> e : replies.entrySet()) {
            AppSource.setPlaybackFallbackForTest(url ->
                    url.endsWith("&codec_type=3") ? codecReply("bytevc2", "v0vc2", 60.0) : e.getValue());
            try {
                AppSource.parseModel(vid, response(vid, byteVc2InlineModel()), "auto");
                fail(e.getKey());
            } catch (IllegalStateException expected) {
                assertFalse(e.getKey(), expected.getMessage().contains("qznovelvod"));
            }
        }
        AppSource.setPlaybackFallbackForTest(url -> codecReply("bytevc2", "v0vc2", 60.0));
        try {
            AppSource.parseModel(vid, response(vid, byteVc2InlineModel()), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("ByteVC2"));
        }
    }

    /** 2026-11-09 issue #3：未加密的分集整个省略 encrypt_info，且没有可用的备用片源时按明文播放。 */
    @Test public void inlineItemWithoutEncryptionInfoIsPlayedAsPlainWhenNoFallbackConfirms() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("bytevc1", 720, 1280, "https://v1.qznovelvod.com/plain.mp4", null));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        Stream.VideoTrack t = AppSource.parseModel("7600000000000000003", response("7600000000000000003", model), "auto");
        assertEquals("https://v1.qznovelvod.com/plain.mp4", t.url);
        assertFalse(t.encrypt);
        assertEquals("", t.spadeA);
    }

    @Test public void plainDownloadRejectsProtectedIndex() {
        assertTrue(Stream.hasProtection("\0\0\0\0moovtrakstsdencvsinf".getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(Stream.hasProtection("\0\0\0\0moovtrakstsdenca".getBytes(StandardCharsets.ISO_8859_1)));
        assertFalse(Stream.hasProtection("\0\0\0\0moovtrakstsdhvc1mp4a".getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test public void inlineEncryptedItemWithoutSpadeIsNotPlayedAsPlaintext() throws Exception {
        List<Object> list = Json.arr();
        list.add(keylessEncryptedItem());
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000003", response("7600000000000000003", model), "auto");
            fail("缺少解密信息的内联片源不得当作明文播放");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("nokey"));
        }

        Map<String, Object> plain = item("h264", 720, 1280, "https://v1.qznovelvod.com/plain.mp4", null);
        Map<String, Object> info = Json.obj();
        info.put("encrypt", false);
        plain.put("encrypt_info", info);
        list.set(0, plain);
        Stream.VideoTrack t = AppSource.parseModel("7600000000000000003", response("7600000000000000003", model), "auto");
        assertEquals("https://v1.qznovelvod.com/plain.mp4", t.url);
        assertFalse(t.encrypt);
    }

    @Test public void inlineMediaOutsideCdnAllowListIsRejected() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 720, 1280, "https://evil.invalid/x.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000004", response("7600000000000000004", model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().contains("evil"));
        }
    }

    @Test public void onlyTheRequestedEpisodeModelIsUsed() throws Exception {
        List<Object> list = Json.arr();
        list.add(item("h264", 720, 1280, "https://v1.qznovelvod.com/other.mp4", VALID_SPADE));
        Map<String, Object> model = Json.obj();
        model.put("video_list", list);
        try {
            AppSource.parseModel("7600000000000000005", response("7600000000000000999", model), "auto");
            fail();
        } catch (IllegalStateException expected) {
            assertEquals("App 接口未返回所选分集", expected.getMessage());
        }
    }

    // ==================== 工具 ====================

    private static PlaybackDevice.Reply okReply() {
        return reply(DEVICE_ID, INSTALL_ID);
    }

    private static PlaybackDevice.Reply reply(String deviceId, String installId) {
        return new PlaybackDevice.Reply(200, "{\"server_time\":1,\"device_id\":" + deviceId + ","
                + "\"install_id\":" + installId + ",\"device_id_str\":\"" + deviceId
                + "\",\"install_id_str\":\"" + installId + "\",\"new_user\":1}");
    }

    private static IOException expectRegistrationFailure() {
        try {
            PlaybackDevice.fresh();
            fail("应注册失败");
            return null;
        } catch (IOException e) {
            assertTrue(e instanceof PlaybackDevice.RegistrationException);
            return e;
        }
    }

    private static Map<String, Object> item(String codec, int w, int h, String url, String spade) {
        Map<String, Object> m = Json.obj();
        m.put("codec_type", codec);
        m.put("definition", Math.min(w, h) + "p");
        m.put("vwidth", (long) w);
        m.put("vheight", (long) h);
        m.put("main_url", url);
        if (spade != null) {
            Map<String, Object> enc = Json.obj();
            enc.put("spade_a", spade);
            enc.put("encrypt", true);
            m.put("encrypt_info", enc);
        }
        return m;
    }

    /** 明确声明加密却没有 spade_a 的档位：任何时候都不能当明文播放。 */
    private static Map<String, Object> keylessEncryptedItem() {
        Map<String, Object> m = item("h264", 720, 1280, "https://v1.qznovelvod.com/nokey.mp4", null);
        Map<String, Object> enc = Json.obj();
        enc.put("encrypt", true);
        m.put("encrypt_info", enc);
        return m;
    }

    private static Map<String, Object> response(String vid, Map<String, Object> model) {
        return responseRaw(vid, model);
    }

    private static Map<String, Object> responseRaw(String vid, Object model) {
        Map<String, Object> entry = Json.obj();
        entry.put("video_model", model);
        Map<String, Object> data = Json.obj();
        data.put(vid, entry);
        Map<String, Object> j = Json.obj();
        j.put("code", 0L);
        j.put("data", data);
        return j;
    }

    private static boolean isSignatureHeader(String name) {
        String k = name.toLowerCase();
        return k.equals("x-gorgon") || k.equals("x-khronos") || k.equals("x-argus") || k.equals("x-ladon")
                || k.equals("x-helios") || k.equals("x-medusa") || k.equals("x-tt-dt");
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
