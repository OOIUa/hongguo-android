package com.hongguo.api.service;

import com.hongguo.api.core.Spade;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 取直链，移植自 jackotom/hongguo-drama 的 src/native/app-source.js。
 *
 * <p><b>为什么需要这个类</b>：老的 multi_video_model/preload/v1 链路对部分分集
 * （实测 vid=7693917197759958040）已不再返回 video_model 数据 —— data 为 null，
 * 表现为「预热失败：无直链 / video_model」。他的修复（提交
 * "Fix later-episode playback with signed App source"）是换一条路：
 * <ol>
 *   <li>改打 b 域名（api5-normal-sinfonlineb）的 multi_video_model/v1；
 *       设备身份见下方「2026-10-27 上游变化」；</li>
 *   <li>响应里的 video_model 可能直接内联 {@code video_list}（数组），也可能只给一个
 *       {@code fallback_api} 地址，二次 GET 才拿到 {@code video_info.data.video_list}；</li>
 *   <li>媒体 URL 可能被 {@code key_seed} 加密（AES-128-CBC），需先解密；</li>
 *   <li>spade_a -&gt; content key 的推导与既有 {@link Spade#unwrapV1} 等价
 *       （他的 deriveContentKey 是同一算法），直接复用。</li>
 * </ol>
 *
 * <p>与 {@link Client} 的 preload/v1 老路并存：老路先不动，取址时本类优先、
 * 失败再回落，保证其他分集不受影响。
 *
 * <p><b>2026-10-27 上游变化</b>（同步自 sunnysky123/hongguo-web 1.1.5 {@code 79babdf}、
 * jackotom/hongguo-drama 3.9 {@code 5febca2}、tsukiyo-zero/hongguoTV {@code 3889318}）：
 * 播放接口开始校验设备身份，每次随机合成、互不关联的 iid/device_id 会被软拦截
 * （HTTP 200 + 0 字节）；该接口也不再接受签名请求。现改为：
 * <ol>
 *   <li>{@link PlaybackDevice} 向 device_register 注册匿名设备，进程内复用；</li>
 *   <li>播放请求 query 使用注册身份的 device_id / iid / cdid，<b>不带任何签名头</b>；</li>
 *   <li>UA 换为 {@link #PLAYBACK_UA}；</li>
 *   <li>片源内联在 video_model.video_list 时直接使用，不再请求 fallback_api；
 *       内联片源若声明加密却缺少 spade_a，按无效处理，不当作明文播放；</li>
 *   <li>空响应不再笼统报「未返回数据」，单独提示疑似设备身份被拒。</li>
 * </ol>
 * 目录、搜索等其他接口不受影响，仍走 {@link Client} 原有签名链路。
 */
public final class AppSource {

    private AppSource() {}

    /** 接口地址（尾斜杠照抄原实现，签名与路由都以它为准）。 */
    private static final String VIDEO_API =
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/";

    /** 接口主机（自检与排障用）。 */
    public static final String VIDEO_API_HOST = "api5-normal-sinfonlineb.fqnovel.com";

    private static final String VIDEO_UA =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; "
            + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 "
            + "QuicVersion:c67e9834 2025-09-08)";

    /** 播放请求 UA（与「注册身份 + 无签名」路径配套）。 */
    static final String PLAYBACK_UA =
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; "
            + "Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)";

    private static final String VERSION_NAME = "7.1.3.32";

    /** 请求域名白名单（他的 checkedUrl 域名表，防签名 body 被带往第三方）。 */
    private static final String[] API_DOMAINS = {"fqnovel.com", "snssdk.com"};

    /** 媒体 CDN 域名白名单。 */
    public static final String[] VOD_DOMAINS =
            {"qznovelvod.com", "fqnovelvod.com", "douyinvod.com"};

    /** decryptSpadeUrl 的常量段（app-source.js constants，64 字节）。 */
    private static final byte[] URL_CONSTANTS = Crypto.unhex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
            + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    // ==================== 入口 ====================

    /**
     * 取某集的 App 直链。quality 为空 / best / auto 时按「H.264 优先、清晰度优先」
     * 自动选流；指定数值（如 1080p）时按短边尺寸升序偏好。
     *
     * @return 可播放轨道；任何一步失败都抛异常，由调用方决定是否回落老路
     */
    public static Stream.VideoTrack fetch(String vid, String quality) throws Exception {
        if (vid == null || !vid.matches("\\d{1,30}")) {
            throw new IllegalArgumentException("分集编号无效");
        }

        // ---- 1. 无签名 POST multi_video_model/v1（使用注册的匿名身份）----
        byte[] body = Json.stringify(payload(vid)).getBytes(StandardCharsets.UTF_8);
        PlaybackDevice.Identity identity = PlaybackDevice.get();
        Map<String, Object> j = postPlayback(body, identity);
        return parseModel(vid, j, quality);
    }

    /**
     * 从播放接口响应解析出可播放轨道（包内可见，便于用离线响应做 JVM 测试）。
     * 只取所请求的分集，不借用响应里其他分集的模型。
     */
    static Stream.VideoTrack parseModel(String vid, Map<String, Object> j, String quality)
            throws Exception {
        if (Json.optInt(j.get("Code"), 0) != 0 || Json.optInt(j.get("code"), 0) != 0) {
            throw new IllegalStateException("App 接口暂未提供本集播放信息");
        }
        Map<String, Object> data = Json.optObj(j.get("data"));
        Map<String, Object> entry = data == null ? null : Json.optObj(data.get(vid));
        if (entry == null || entry.isEmpty()) {
            throw new IllegalStateException("App 接口未返回所选分集");
        }
        Map<String, Object> model = asObject(entry.get("video_model"));
        if (model.isEmpty()) {
            throw new IllegalStateException("App 接口未返回 video_model");
        }

        // ---- 2. 片源模型：新版直接内联 video_list，旧版跟随 fallback_api 二次请求 ----
        List<Object> inlineList = Json.optArr(model.get("video_list"));
        boolean inline = !inlineList.isEmpty();
        Map<String, Object> info;
        if (inline) {
            info = model;
        } else {
            String fallback = parseFallback(model.get("fallback_api"));
            if (fallback == null || fallback.isEmpty()) {
                throw new IllegalStateException("App 接口未返回本集片源地址");
            }
            Map<String, Object> outer = getPlain(checkedUrl(fallback, API_DOMAINS));
            Map<String, Object> outerInfo = Json.optObj(outer.get("video_info"));
            if (Json.optInt(outer.get("Code"), 0) != 0
                    || Json.optInt(outer.get("code"), 0) != 0
                    || Json.optInt(outerInfo == null ? null : outerInfo.get("Code"), 0) != 0) {
                throw new IllegalStateException("App 接口暂未提供本集播放信息");
            }
            info = outerInfo == null ? null : Json.optObj(outerInfo.get("data"));
        }
        if (info == null || info.isEmpty()) {
            throw new IllegalStateException("App 接口未返回本集片源模型");
        }
        // video_list 可能是对象 map（fallback 模型），也可能是数组（内联模型）
        List<Object> videoItems = new ArrayList<>();
        Object rawList = info.get("video_list");
        if (rawList instanceof Map) {
            videoItems.addAll(((Map<?, ?>) rawList).values());
        } else if (rawList instanceof List) {
            videoItems.addAll((List<?>) rawList);
        }
        // key_seed 存在说明媒体 URL 是密文，需 AES 解密
        byte[] seed = null;
        String seedStr = Json.optStr(info.get("key_seed"), "");
        if (!seedStr.isEmpty()) {
            seed = Crypto.base64Decode(seedStr);
        }

        // ---- 3. 选流（H.264 优先，其次清晰度）----
        List<Row> rows = new ArrayList<>();
        if (videoItems.isEmpty()) throw new IllegalStateException("本集未返回可用片源列表");
        for (Object item : videoItems) {
            Row r = toRow(Json.optObj(item));
            if (r != null) rows.add(r);
        }
        boolean auto = quality == null || quality.isEmpty()
                || quality.equals("best") || quality.equals("auto");
        int wantPx = auto ? 0 : pixelsOf(quality);
        List<Row> ok = new ArrayList<>();
        for (Row r : rows) {
            if (r.codecRank >= 2) continue;               // 只要 H.264 / HEVC
            if (!auto && wantPx > 0 && r.shortSide < wantPx) continue;
            ok.add(r);
        }
        if (auto) {
            ok.sort((a, b) -> a.codecRank != b.codecRank
                    ? a.codecRank - b.codecRank : b.pixels - a.pixels);
        } else {
            ok.sort((a, b) -> a.shortSide != b.shortSide
                    ? a.shortSide - b.shortSide : a.codecRank - b.codecRank);
        }
        if (ok.isEmpty()) {
            throw new IllegalStateException("本集暂未提供兼容的 H.264/HEVC 片源");
        }

        // ---- 4. 逐候选解 URL 与密钥，取第一个可用的 ----
        // 注意 VideoTrack.spadeA 的契约是「原始 base64 spade_a」——解包由
        // Stream.offlineDecrypt 统一完成（老路同此约定）。这里只预解一次
        // 用于判断该候选是否可用，不回存解出的 key。
        Exception last = null;
        for (Row r : ok) {
            String spade = firstNonEmpty(r.item.get("spade_a"),
                    Json.optStr(child(r.item, "encrypt_info").get("spade_a"), ""));
            boolean keyOk = spade.isEmpty();
            if (spade.isEmpty() && inline && !declaredPlain(r.item)) {
                // 内联模型声明加密（或未声明）却缺少 spade_a：不能当作明文播放
                keyOk = false;
            }
            if (!spade.isEmpty()) {
                keyOk = Spade.isValidKey(Spade.unwrapV1(Crypto.base64Decode(spade), 0));
            }
            if (!keyOk) continue; // spade_a 解不出合法 key 的候选不可用
            for (String candidate : urlCandidates(r.item)) {
                try {
                    String media = seed != null
                            ? decryptSpadeUrl(candidate, seed) : cleanUrl(candidate);
                    String url = checkedUrl(media, VOD_DOMAINS);
                    Stream.VideoTrack t = new Stream.VideoTrack();
                    t.url = url;
                    t.spadeA = spade;
                    t.encrypt = !spade.isEmpty();
                    t.definition = r.quality.isEmpty() ? "?" : r.quality;
                    t.size = r.size;
                    return t;
                } catch (Exception ex) {
                    last = ex;
                }
            }
        }
        throw new IllegalStateException("本集片源地址或密钥无法解析");
    }

    // ==================== 请求构造 ====================

    /** 请求体（biz_param 与老路一致；注意不带 dr_scene）。 */
    private static Map<String, Object> payload(String vid) {
        Map<String, Object> biz = Json.obj();
        biz.put("detail_page_version", 0);
        biz.put("device_level", 3);
        biz.put("disable_digg_stat", false);
        biz.put("need_all_video_definition", true);
        biz.put("need_mp4_align", false);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);
        biz.put("video_platform", 1024);
        Map<String, Object> mixed = Json.obj();
        List<Object> ids = Json.arr();
        ids.add(vid);
        mixed.put("1004", ids);
        Map<String, Object> out = Json.obj();
        out.put("biz_param", biz);
        out.put("mixed_video_id_map", mixed);
        return out;
    }

    /**
     * 播放请求的基础设备参数（字段顺序固定 —— query 按插入序编码）。
     * 不含 iid/device_id：播放身份由 {@link PlaybackDevice} 注册提供
     * （2026-10-27 起随机合成的身份会被上游软拦截）。
     */
    public static Map<String, Object> deviceBase() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ac", "wifi");
        d.put("channel", "update_64");
        d.put("aid", "8662");
        d.put("app_name", "novelread");
        d.put("version_code", "71332");
        d.put("version_name", VERSION_NAME);
        d.put("device_platform", "android");
        d.put("os", "android");
        d.put("ssmix", "a");
        d.put("device_type", "25053RT47C");
        d.put("device_brand", "Redmi");
        d.put("language", "zh");
        d.put("os_api", "36");
        d.put("os_version", "16");
        d.put("manifest_version_code", "71332");
        d.put("resolution", "1280*2772");
        d.put("dpi", "520");
        d.put("update_version_code", "71332");
        d.put("host_abi", "arm64-v8a");
        d.put("dragon_device_type", "phone");
        d.put("pv_player", "71332");
        d.put("compliance_status", "0");
        d.put("need_personal_recommend", "1");
        d.put("player_so_load", "1");
        d.put("is_android_pad_screen", "0");
        return d;
    }

    /**
     * 播放请求 URL：基础参数后依次追加注册身份的 device_id / iid / cdid 与 _rticket。
     *
     * <p>query 编码沿用 python urllib.parse.quote 的兜底行为：
     * {@code !'()* } 也编成 %XX（resolution=1280*2772 里的 * 就在此列）。
     */
    static String playbackUrl(PlaybackDevice.Identity identity, long rticket) {
        Map<String, Object> q = deviceBase();
        q.put("device_id", identity.deviceId);
        q.put("iid", identity.iid);
        q.put("cdid", identity.cdid);
        q.put("_rticket", String.valueOf(rticket));
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, Object> e : q.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(pyEncode(e.getKey())).append('=')
              .append(pyEncode(String.valueOf(e.getValue())));
        }
        return VIDEO_API + "?" + qs;
    }

    /** 播放请求头：无任何签名头（x-gorgon / x-argus / x-medusa 等）。 */
    static Map<String, String> playbackHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", PLAYBACK_UA);
        headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
        headers.put("Content-Type", "application/json; charset=UTF-8");
        return headers;
    }

    /** 无签名 POST（2026-10-27 起该接口不再接受签名请求）。 */
    private static Map<String, Object> postPlayback(byte[] body, PlaybackDevice.Identity identity)
            throws Exception {
        String url = playbackUrl(identity, System.currentTimeMillis());
        Http.Req req = new Http.Req(url).method("POST").timeout(15000);
        req.followRedirect = false;
        for (Map.Entry<String, String> e : playbackHeaders().entrySet()) {
            req.header(e.getKey(), e.getValue());
        }
        req.body(body);
        Http.Resp res = Http.send(req);
        if (res.status != 200) {
            throw new IllegalStateException("App 接口 HTTP " + res.status);
        }
        return parsePlaybackResponse(res.text());
    }

    /** 区分空响应（疑似设备身份被拒）与其他无效响应。空响应不值得重试同一请求。 */
    static Map<String, Object> parsePlaybackResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalStateException("App 接口返回空响应，疑似播放设备身份被拒，请稍后重试");
        }
        Map<String, Object> j;
        try { j = Json.parseObject(text); }
        catch (Exception ignored) { throw new IllegalStateException("播放信息格式无效"); }
        if (j == null || Json.optObj(j.get("data")) == null) {
            // data 缺失：接口变更或 IP 风控。
            throw new IllegalStateException("App 接口未返回播放数据");
        }
        return j;
    }

    /** fallback_api 的普通 GET：不带签名，只带 UA（与原实现一致）。 */
    private static Map<String, Object> getPlain(String url) throws Exception {
        Http.Req req = new Http.Req(url).method("GET").timeout(15000);
        req.header("User-Agent", VIDEO_UA);
        Http.Resp res = Http.send(req);
        if (res.status != 200) {
            throw new IllegalStateException("fallback HTTP " + res.status);
        }
        try { return Json.parseObject(res.text()); }
        catch (Exception ignored) { throw new IllegalStateException("播放信息格式无效"); }
    }

    /**
     * query 键值编码：未保留字符编 %XX（大写）。
     * 保留集 = JS encodeURIComponent 的不编码集减去被他显式转码的 !'()*。
     */
    public static String pyEncode(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    // ==================== 响应解析 ====================

    /** video_model 可能是 JSON 字符串或对象。 */
    private static Map<String, Object> asObject(Object v) {
        if (v instanceof String) {
            try {
                return Json.parseObject((String) v);
            } catch (Exception e) {
                return Json.obj();
            }
        }
        Map<String, Object> m = Json.optObj(v);
        return m == null ? Json.obj() : m;
    }

    /** 取子对象；缺失返回空 map（Json.optObj 对 null 返回 null，链式调用会踩 NPE）。 */
    private static Map<String, Object> child(Map<String, Object> m, String key) {
        if (m == null) return Json.obj();
        Map<String, Object> c = Json.optObj(m.get(key));
        return c == null ? Json.obj() : c;
    }

    /**
     * fallback_api 可能是 URL 字符串、[url]、{"fallback_api": url}
     * 或内嵌 JSON 的字符串。
     */
    private static String parseFallback(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            Map<String, Object> parsed;
            try {
                parsed = Json.parseObject(s);
            } catch (Exception e) {
                return s;
            }
            Object fb = parsed.get("fallback_api");
            return fb == null ? s : String.valueOf(fb);
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            return l.isEmpty() ? "" : String.valueOf(l.get(0));
        }
        Map<String, Object> map = Json.optObj(v);
        Object fb = map == null ? null : map.get("fallback_api");
        return fb == null ? "" : String.valueOf(fb);
    }

    /** 一条候选流的评分信息。 */
    private static final class Row {
        final Map<String, Object> item;
        final String codec;
        final int codecRank;
        final String quality;
        final int pixels;
        final int shortSide;
        final long size;

        Row(Map<String, Object> item, String codec, int codecRank, String quality,
                int pixels, int shortSide, long size) {
            this.item = item;
            this.codec = codec;
            this.codecRank = codecRank;
            this.quality = quality;
            this.pixels = pixels;
            this.shortSide = shortSide;
            this.size = size;
        }
    }

    private static Row toRow(Map<String, Object> item) {
        if (item == null || item.isEmpty()) return null;
        Map<String, Object> meta = child(item, "video_meta");
        String codec = Json.optStr(item.get("codec_type"),
                Json.optStr(meta.get("codec_type"), "")).toLowerCase();
        int rank = rank(codec);
        String quality = firstNonEmpty(
                Json.optStr(item.get("quality_desc"), ""),
                Json.optStr(item.get("definition"), ""),
                Json.optStr(meta.get("definition"), ""),
                Json.optStr(item.get("height"), ""),
                Json.optStr(item.get("vheight"), ""));
        int px = pixelsOf(quality);
        int w = 0, h = 0;
        int[][] pairs = {
                {Json.optInt(item.get("vwidth"), 0), Json.optInt(item.get("vheight"), 0)},
                {Json.optInt(item.get("width"), 0), Json.optInt(item.get("height"), 0)},
                {Json.optInt(meta.get("vwidth"), 0), Json.optInt(meta.get("vheight"), 0)},
                {Json.optInt(meta.get("width"), 0), Json.optInt(meta.get("height"), 0)},
        };
        for (int[] p : pairs) {
            if (p[0] > 0 && p[1] > 0) { w = p[0]; h = p[1]; break; }
        }
        long size = Json.optLong(meta.get("size"), 0);
        if (size == 0) size = Json.optLong(item.get("size"), 0);
        return new Row(item, codec, rank, quality, px, Math.min(w, h), size);
    }

    private static int rank(String codec) {
        if (codec.equals("h264") || codec.equals("avc") || codec.equals("avc1")) return 0;
        if (codec.equals("hevc") || codec.equals("h265") || codec.equals("hvc1")
                || codec.equals("hev1") || codec.equals("bytevc1")) return 1;
        return 2;
    }

    /** 取字符串里第一组数字；无则 0。 */
    public static int pixelsOf(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** main_url / backup_url / backup_url_list 的候选序列。 */
    private static List<String> urlCandidates(Map<String, Object> item) {
        List<String> out = new ArrayList<>();
        addStr(out, item.get("main_url"));
        addStr(out, item.get("backup_url"));
        Object list = item.get("backup_url_list");
        if (list instanceof List) {
            for (Object o : (List<?>) list) addStr(out, o);
        }
        return out;
    }

    private static void addStr(List<String> out, Object v) {
        if (v instanceof String && !((String) v).isEmpty()) out.add((String) v);
    }

    /** encrypt_info.encrypt 明确为 false / 0 时才视为明文片源。 */
    private static boolean declaredPlain(Map<String, Object> item) {
        Object flag = child(item, "encrypt_info").get("encrypt");
        if (flag instanceof Boolean) return !((Boolean) flag);
        if (flag instanceof Number) return ((Number) flag).longValue() == 0;
        if (flag instanceof String) {
            String f = ((String) flag).trim();
            return f.equals("0") || f.equalsIgnoreCase("false");
        }
        return false;
    }

    private static String firstNonEmpty(Object... vals) {
        for (Object v : vals) {
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return "";
    }

    /** 清掉上游常见的转义形式（他的 cleanUrl）。 */
    public static String cleanUrl(String value) {
        return value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
    }

    // ==================== URL 解密与校验 ====================

    /**
     * 解密 spade URL：seed 双 sha512 派生 AES-128-CBC 密钥/IV。
     * 布局：[0]=0xa8, [1]=?, [2]=0x01, [3]=0x00, [4:]=密文（16 字节整块）。
     */
    public static String decryptSpadeUrl(String value, byte[] seed) throws Exception {
        byte[] raw = Crypto.base64Decode(value);
        if (raw.length < 5) throw new IllegalStateException("spade URL 密文过短");
        if ((raw[0] & 0xFF) != 0xa8 || (raw[2] & 0xFF) != 0x01 || (raw[3] & 0xFF) != 0x00) {
            throw new IllegalStateException("spade URL 头格式错误");
        }
        int cipherLength = ((raw.length - 4) / 16) * 16;
        if (cipherLength == 0) throw new IllegalStateException("spade URL 无完整块");

        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] h1 = md.digest(seed);
        md.update(h1);
        byte[] h2 = md.digest(URL_CONSTANTS);

        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(h2, 0, 16, "AES"),
                new IvParameterSpec(h2, 16, 16));
        byte[] pt = c.doFinal(raw, 4, cipherLength);

        int end = pt.length;
        if (end > 0) {
            int pad = pt[end - 1] & 0xFF;
            if (pad >= 1 && pad <= 16 && pad <= end) end -= pad;
        }
        while (end > 0 && pt[end - 1] == 0) end--;
        return new String(pt, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * 媒体/接口 URL 白名单校验：必须 https、无用户信息、端口为 443，
     * 且 host 等于白名单域名或其子域。防签名 body 被重定向带往第三方。
     */
    public static String checkedUrl(String value, String[] domains) throws Exception {
        URI u;
        try {
            u = URI.create(value);
        } catch (Exception e) {
            throw new IllegalStateException("URL 格式无效");
        }
        String scheme = u.getScheme();
        String host = u.getHost();
        boolean portOk = u.getPort() == -1 || u.getPort() == 443;
        boolean hostOk = false;
        if (host != null) {
            for (String d : domains) {
                if (host.equals(d) || host.endsWith("." + d)) { hostOk = true; break; }
            }
        }
        if (!"https".equals(scheme) || u.getUserInfo() != null || !portOk || !hostOk) {
            throw new IllegalStateException("不受支持的服务地址");
        }
        return u.toString();
    }
}
