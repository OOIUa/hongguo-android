package com.hongguo.api.service;

import com.hongguo.api.core.Device;
import com.hongguo.api.core.PureSign;
import com.hongguo.api.core.Safeguards;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红果短剧 API 客户端。
 *
 * 移植自 server/src/client.js。保留原有语义：设备身份池、_rticket 时间戳、
 * x-ss-stub MD5、列表类接口免签、剧集详情缓存、风控/登录态退避重试。
 *
 * 与 Node 版的两处架构变化：
 *  - 签名不再走外部 unidbg 服务，而是本进程内 PureSign 纯代码计算
 *    （branch==1 时抖动 _rticket 规避，实测服务端返回 code=0）；
 *  - 搜索与分集详情在 App 接口之外增加官网数据源（WebSource）：
 *    搜索与分集详情均为 App 优先，失败或空结果时、
 *    失败回落官网。
 */
public final class Client {

    private Client() {}

    // ==================== 配置装载 ====================
    //
    // 上游参数（api_host / base_query / session_headers）住在 config.json 的
    // upstream 段（原 content-config.json，1.0.7 起合并，固定值不常改）。
    // 环境变量 HONGGUO_HOST / HONGGUO_UA 仍可临时覆盖。

    public static final String HOST = Log.env("HONGGUO_HOST",
            Config.str("upstream.api_host", ""));

    static {
        if (HOST.isEmpty()) {
            throw new RuntimeException(
                    "config.json 缺少 upstream.api_host（上游接口参数段，见 upstream._说明）");
        }
    }

    /**
     * 基础 query 参数。{@code _} 开头的键是给用户看的注释（config.json 的
     * 文档风格），整体透传进上游 query 前必须剥掉。
     */
    /** base_query 的缓存实例：device_id 就注入在它身上，必须保持同一实例。 */
    private static Map<String, Object> BASE_QUERY_CACHE;

    @SuppressWarnings("unchecked")
    public static synchronized Map<String, Object> baseQuery() {
        if (BASE_QUERY_CACHE == null) {
            Map<String, Object> q = Json.optObj(Config.get("upstream.base_query"));
            Map<String, Object> out = new LinkedHashMap<>();
            if (q != null) {
                for (Map.Entry<String, Object> e : q.entrySet()) {
                    if (!e.getKey().startsWith("_")) out.put(e.getKey(), e.getValue());
                }
            }
            BASE_QUERY_CACHE = out;
        }
        return BASE_QUERY_CACHE;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> sessionHeaders() {
        Map<String, Object> h = Json.optObj(Config.get("upstream.session_headers"));
        Map<String, String> out = new LinkedHashMap<>();
        if (h != null) {
            for (Map.Entry<String, Object> e : h.entrySet()) {
                // 同 baseQuery：注释键不进请求头
                if (e.getKey().startsWith("_")) continue;
                if (e.getValue() != null) out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }


    private static final Map<String, Object> BASE_QUERY = baseQuery();
    private static final Device.Identity IDENTITY = Device.ensureDeviceId(BASE_QUERY);

    /** 桌面托管模式标志：存在则禁止刷新登录态、禁止降级容错。 */
    public static final boolean MANAGED =
            Log.env("HONGGUO_SESSION_API_KEY", null) != null;

    /** Current landpage/cell endpoints require signed requests; explicit false is a debug override only. */
    public static final boolean SIGN_LIST = signedListsEnabled(Log.env("HG_SIGN_LIST", null));

    static boolean signedListsEnabled(String value) {
        if (value == null) return true;
        return !Arrays.asList("0", "false", "no", "off")
                .contains(value.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static final String USER_AGENT = Log.env("HONGGUO_UA",
            sessionHeaders().getOrDefault("user-agent", "hongguo-web/1.0"));

    /** 设备身份池（DEVICE_POOL_SIZE > 0 时启用；托管模式固定单设备）。 */
    private static final Safeguards.DevicePool POOL = null;

    private static Safeguards.PoolDevice currentDevice() {
        return POOL == null ? null : POOL.current();
    }

    public static Safeguards.PoolDevice rotateDevice() {
        return POOL == null ? null : POOL.rotate();
    }

    public static Device.Identity identity() { return IDENTITY; }

    // ==================== 榜单 / 体裁常量 ====================

    public static final Map<String, String> RANK_BOARDS = new LinkedHashMap<>();
    public static final Map<String, String> RANK_NAMES = new LinkedHashMap<>();
    static {
        RANK_BOARDS.put("recommend", "comic_series_hot_rank");
        RANK_BOARDS.put("hot", "comic_series_hot_play");
        RANK_BOARDS.put("new", "comic_series_new_rank");
        RANK_NAMES.put("recommend", "漫剧推荐榜");
        RANK_NAMES.put("hot", "漫剧热播榜");
        RANK_NAMES.put("new", "漫剧新剧榜");
    }

    public static final String COMIC_RANK_CELL = "7470092475068071998";

    /** genre -> [req_scene, genre] */
    public static final Map<String, String[]> GENRES = new LinkedHashMap<>();
    public static final Map<String, String> GENRE_NAMES = new LinkedHashMap<>();
    static {
        GENRES.put("short_play", new String[]{"default", "short_play"});
        GENRES.put("comic_series", new String[]{"comic_series", "comic_series"});
        GENRES.put("ai_series", new String[]{"ai_series", "ai_series"});
        GENRE_NAMES.put("short_play", "短剧");
        GENRE_NAMES.put("comic_series", "漫剧");
        GENRE_NAMES.put("ai_series", "AI短剧");
    }

    // 主题 / 设定 / 背景 共用 cate_ 命名空间
    private static final Map<String, String> FILTER_CATE = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_SORT = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_GENDER = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_DAYS = new LinkedHashMap<>();
    private static final Map<String, String> FILTER_STATUS = new LinkedHashMap<>();
    static {
        String[][] cate = {
            {"脑洞", "cate_755"}, {"奇幻", "cate_6"}, {"剧情", "cate_316"}, {"玄幻", "cate_7"},
            {"末世", "cate_68"}, {"豪门", "cate_936"}, {"科幻", "cate_1092"}, {"冒险", "cate_1182"},
            {"重生", "cate_36"}, {"穿越", "cate_37"}, {"逆袭", "cate_739"}, {"异能", "cate_598"},
            {"系统", "cate_19"}, {"反转", "cate_756"}, {"娱乐圈", "cate_43"}, {"总裁", "cate_29"},
            {"架空", "cate_452"}, {"都市", "cate_1"}, {"古代", "cate_758"}, {"异界", "cate_599"},
            {"校园", "cate_4"}, {"职场", "cate_127"}, {"年代", "cate_79"}, {"乡村", "cate_11"},
            {"民国", "cate_390"},
        };
        for (String[] c : cate) FILTER_CATE.put(c[0], c[1]);

        FILTER_SORT.put("最新上架", "online_time");
        FILTER_SORT.put("最新", "online_time");
        FILTER_SORT.put("最高热度", "hot_score");
        FILTER_SORT.put("热度", "hot_score");
        FILTER_SORT.put("hot", "hot_score");
        FILTER_SORT.put("最高收藏", "hot_collect");
        FILTER_SORT.put("收藏", "hot_collect");

        FILTER_GENDER.put("男频", "1");
        FILTER_GENDER.put("男", "1");
        FILTER_GENDER.put("女频", "0");
        FILTER_GENDER.put("女", "0");

        FILTER_DAYS.put("7", "days_7");
        FILTER_DAYS.put("14", "days_14");
        FILTER_DAYS.put("30", "days_30");
        FILTER_DAYS.put("90", "days_90");
        FILTER_DAYS.put("7天内上新", "days_7");
        FILTER_DAYS.put("14天内上新", "days_14");
        FILTER_DAYS.put("30天内上新", "days_30");
        FILTER_DAYS.put("90天内上新", "days_90");

        FILTER_STATUS.put("已完结", "creation_status_0");
        FILTER_STATUS.put("完结", "creation_status_0");
        FILTER_STATUS.put("连载中", "creation_status_1");
        FILTER_STATUS.put("连载", "creation_status_1");
    }

    // ==================== 请求组装 ====================

    /** 组装完整 URL（含 base_query、设备身份、_rticket）。 */
    public static String buildUrl(String pathname, Map<String, Object> extra) {
        return buildUrl(pathname, extra, System.currentTimeMillis());
    }

    /**
     * 组装完整 URL，_rticket 由调用方指定。
     *
     * 签名分支 branch==1 时服务端不认，需要抖动 _rticket 后重签重发——
     * 此时 URL 必须用同一个 pathname/extra 重新组装，只有 _rticket 不同。
     */
    public static String buildUrl(String pathname, Map<String, Object> extra, long rticket) {
        Map<String, Object> q = new LinkedHashMap<>(BASE_QUERY);
        Safeguards.PoolDevice dev = currentDevice();
        if (dev != null) q.putAll(dev.query);
        if (extra != null) q.putAll(extra);
        q.put("_rticket", String.valueOf(rticket));

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, Object> e : q.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(e.getKey()).append('=')
              .append(Http.urlencode(String.valueOf(e.getValue())));
        }
        return "https://" + HOST + pathname + "?" + qs;
    }

    /** 构造基础请求头与请求体。 */
    private static Object[] buildHeaders(Object body) {
        Map<String, String> headers = new LinkedHashMap<>(sessionHeaders());
        Safeguards.PoolDevice dev = currentDevice();
        if (dev != null) {
            if (dev.userAgent != null) headers.put("user-agent", dev.userAgent);
            headers.remove("x-tt-token");
            headers.remove("cookie");
        }
        if (!headers.containsKey("user-agent")) headers.put("user-agent", USER_AGENT);
        headers.put("content-type", "application/json; charset=utf-8");

        byte[] data = null;
        if (body != null) {
            data = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
            headers.put("x-ss-stub", Crypto.md5HexUpper(data));
            headers.put("Content-Length", String.valueOf(data.length));
        } else {
            headers.remove("Content-Length");
        }
        return new Object[]{headers, data};
    }

    /** 单次 API 调用（不含重试）。 */
    private static Map<String, Object> apiOnce(String method, String pathname, Object body,
                                               Map<String, Object> extraQuery, boolean signed)
            throws Exception {
        String url = buildUrl(pathname, extraQuery);
        Object[] built = buildHeaders(body);
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) built[0];
        byte[] data = (byte[]) built[1];

        if (signed) {
            // 本地纯代码签名（PureSign，移植自 jackotom/hongguo-drama）。
            // 注意 branch==1 的签名变体服务端不认（实测 110001），
            // 需抖动 _rticket 换一个 query 重签，最多 32 次。
            long khronos = System.currentTimeMillis() / 1000L;
            long rticket = System.currentTimeMillis();
            int guard = 0;
            while (PureSign.branchOf(url, data, khronos) == 1 && guard++ < 32) {
                rticket += 1;
                url = buildUrl(pathname, extraQuery, rticket);
            }
            if (guard >= 32) {
                Log.warn("签名分支抖动 32 次未避开 branch==1，按当前 URL 签名（可能被服务端拒绝）");
            }
            headers.putAll(PureSign.sign(url, data, khronos));
        }
        headers.remove("accept-encoding");
        Safeguards.throttle().wait_();

        Http.Req req = new Http.Req(url).method(method).timeout(12000);
        req.followRedirect = false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            req.header(e.getKey(), e.getValue());
        }
        if (data != null) req.body(data);

        Http.Resp res = Http.send(req);
        return parseApiResponse(res.text(), res.status);
    }

    /** An empty HTTP 200 is a transport/protocol failure, not a successful empty catalogue. */
    static Map<String, Object> parseApiResponse(String text, int status) {
        if (status < 200 || status >= 300)
            throw new Safeguards.UpstreamResponseError("上游 HTTP " + status);
        if (text == null || text.trim().isEmpty())
            throw new Safeguards.UpstreamResponseError("上游未返回数据");
        Map<String, Object> response;
        try {
            response = Json.parseObject(text);
        } catch (Exception ignored) {
            Safeguards.UpstreamResponseError error = new Safeguards.UpstreamResponseError("上游未返回 JSON");
            Map<String, Object> diagnostic = Json.obj();
            diagnostic.put("http_status", status);
            diagnostic.put("body_bytes", text.getBytes(StandardCharsets.UTF_8).length);
            error.diagnostic = diagnostic;
            throw error;
        }
        if (response.isEmpty()) throw new Safeguards.UpstreamResponseError("上游未返回数据");
        Safeguards.checkResponse(response);
        return response;
    }

    /** Bounded metadata retries; authentication/risk responses are returned without replay. */
    public static Map<String, Object> api(String method, String pathname, Object body,
                                          Map<String, Object> extraQuery, int maxRetries,
                                          boolean signed) throws Exception {
        int attempts = Math.max(1, Math.min(maxRetries, 2));
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("请求已取消");
            try {
                return apiOnce(method, pathname, body, extraQuery, signed);
            } catch (Safeguards.AuthExpiredError | Safeguards.RiskControlError error) {
                throw error;
            } catch (Safeguards.UpstreamResponseError | IOException error) {
                if (Thread.currentThread().isInterrupted() || attempt + 1 >= attempts) throw error;
                Safeguards.sleep(300L * (attempt + 1));
            }
        }
    }

    private static long lastRefresh = 0;

    /**
     * 刷新设备身份与登录态。
     *
     * 本地纯代码签名模式下本方法为空操作：设备身份由
     * {@link Device#ensureDeviceId} 本机生成并持久化，token 来自
     * config.json 的 upstream.session_headers，不存在需要从外部
     * 签名服务抓取的状态。保留方法签名是为了让 api() 的重试流程
     * 无需为此分支。
     */
    public static synchronized void refreshSession() {
        if (System.currentTimeMillis() - lastRefresh < 20000) return; // 防抖
        lastRefresh = System.currentTimeMillis();
    }

    // ==================== 数据解析 ====================

    /** 从 category_schema 字符串中解析分类名（源数据为 JSON 字符串）。 */
    public static List<String> parseCategories(Object schema) {
        List<String> out = new ArrayList<>();
        if (schema == null) return out;
        String s = String.valueOf(schema);
        java.util.regex.Matcher rx = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(s);
        while (rx.find()) {
            String m = rx.group(1);
            if (!m.isEmpty() && !out.contains(m)) out.add(m);
        }
        return out;
    }

    /** 提取封面角标文本（爆剧/新剧/独播…）。 */
    public static List<String> coverTags(Object d) {
        List<String> out = new ArrayList<>();
        Map<String, Object> m = Json.optObj(d);
        if (m == null) return out;
        for (Object t : Json.optArr(m.get("cover_tag_info_list"))) {
            Map<String, Object> tm = Json.optObj(t);
            if (tm != null) {
                Object v = firstNonNull(tm, "text", "content", "name", "tag_text", "title");
                if (v != null) out.add(String.valueOf(v).replaceAll("<[^>]+>", ""));
            } else if (t instanceof String && !((String) t).isEmpty()) {
                out.add((String) t);
            }
        }
        return out;
    }

    private static Object firstNonNull(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isEmpty()) return v;
        }
        return null;
    }

    private static String str(Map<String, Object> m, String k) {
        return m == null ? "" : Json.optStr(m.get(k), "");
    }

    private static long num(Map<String, Object> m, String k) {
        return m == null ? 0 : Json.optLong(m.get(k), 0);
    }

    /** 剥 HTML 标签并截断。 */
    private static String strip(String s, int max) {
        String t = s == null ? "" : s.replaceAll("<[^>]+>", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 从搜索 cell 解析短剧条目；非短剧（无集数）或无 id 返回 null。 */
    public static Map<String, Object> parseSearchCell(Object cellObj) {
        Map<String, Object> cell = Json.optObj(cellObj);
        if (cell == null) return null;
        Object sid = firstNonNull(cell, "book_id", "search_result_id", "series_id");
        if (sid == null || !String.valueOf(sid).matches("[0-9]{1,30}")) return null;
        Map<String, Object> vd = Json.optObj(cell.get("video_detail"));
        if (vd == null) vd = Json.obj();

        Object vdataObj = cell.get("video_data");
        if (vdataObj instanceof List && !((List<?>) vdataObj).isEmpty()) {
            vdataObj = ((List<?>) vdataObj).get(0);
        }
        Map<String, Object> vdata = Json.optObj(vdataObj);
        if (vdata == null) vdata = Json.obj();
        Map<String, Object> inner = Json.optObj(vdata.get("video_detail"));
        if (inner == null) inner = Json.obj();

        long ep = num(vd, "episode_cnt");
        if (ep == 0) ep = num(vdata, "episode_cnt");
        if (ep == 0) ep = num(inner, "episode_cnt");
        if (ep == 0) return null; // 只要短剧（有集数的）

        Map<String, Object> hl = Json.optObj(cell.get("search_high_light"));
        String hlText = "";
        if (hl != null) {
            Map<String, Object> t = Json.optObj(hl.get("title"));
            if (t != null) hlText = str(t, "text");
        }
        String title = hlText;
        if (title.isEmpty()) title = str(vd, "series_title");
        if (title.isEmpty()) title = str(vdata, "title");
        if (title.isEmpty()) title = str(inner, "series_title");

        Map<String, Object> out = Json.obj();
        out.put("series_id", String.valueOf(sid));
        out.put("title", strip(title, 9999));
        out.put("episode_cnt", ep);
        out.put("score", vdata.containsKey("score") ? vdata.get("score")
                : (inner.containsKey("score") ? inner.get("score") : ""));
        out.put("play_cnt", vdata.containsKey("play_cnt") ? vdata.get("play_cnt")
                : Json.optLong(inner.get("series_play_cnt"), 0));
        out.put("hot", str(vdata, "rec_text"));
        out.put("copyright", str(vdata, "copyright"));
        String cover = str(vdata, "cover");
        if (cover.isEmpty()) cover = str(inner, "series_cover");
        out.put("cover", cover);
        out.put("tags", coverTags(vdata));
        String intro = !str(inner, "series_intro").isEmpty() ? str(inner, "series_intro")
                : str(vd, "series_intro");
        out.put("abstract", strip(intro, 60));
        out.put("vid", str(vdata, "vid"));
        out.put("duration", num(vdata, "duration"));
        out.put("horiz_cover", str(vdata, "horiz_cover"));
        return out;
    }

    /** 官网搜索结果转为与 {@link #parseSearchCell} 相同的条目结构（未知字段留空）。 */
    private static List<Object> webSearchItems(List<Map<String, Object>> web) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> w : web) {
            Map<String, Object> row = Json.obj();
            row.put("series_id", Json.optStr(w.get("series_id"), ""));
            row.put("title", Json.optStr(w.get("series_title"), ""));
            row.put("episode_cnt", Json.optLong(w.get("episode_cnt"), 0));
            row.put("score", "");
            row.put("play_cnt", 0);
            row.put("hot", "");
            row.put("copyright", "");
            row.put("cover", Json.optStr(w.get("cover"), ""));
            row.put("tags", new ArrayList<>());
            row.put("abstract", strip(Json.optStr(w.get("series_intro"), ""), 60));
            row.put("vid", "");
            row.put("duration", 0);
            row.put("horiz_cover", "");
            out.add(row);
        }
        return out;
    }

    /** 官网分集结果转为与 {@link #parseEpisodeDetail} 相同的结构（官网无简介/演员等字段）。 */
    private static Map<String, Object> webEpisodesToDetail(Map<String, Object> web) {
        String sid = Json.optStr(web.get("series_id"), "");
        List<Object> eps = new ArrayList<>();
        for (Object eObj : Json.optArr(web.get("episodes"))) {
            Map<String, Object> e = Json.optObj(eObj);
            if (e == null) continue;
            String vid = Json.optStr(e.get("vid"), "");
            Map<String, Object> row = Json.obj();
            row.put("index", Json.optLong(e.get("vid_index"), 0));
            row.put("vid", vid);
            row.put("title", Json.optStr(e.get("title"), ""));
            row.put("duration", 0);
            row.put("cover", Json.optStr(e.get("cover"), ""));
            row.put("comment_count", 0);
            row.put("digged_count", 0);
            // 官网侧标注：该集是否落在网页版限免集数内（仅作展示，
            // 播放取址已统一走 AppSource，不再据此探测官网直链）
            row.put("web_available", e.get("web_available"));
            eps.add(row);
        }
        Map<String, Object> meta = Json.obj();
        meta.put("series_id", sid);
        String title = Json.optStr(web.get("series_title"), "");
        meta.put("title", title.isEmpty() ? sid : title);
        meta.put("abstract", "");
        meta.put("episode_cnt", Json.optLong(web.get("total"), eps.size()));
        meta.put("status", "");
        meta.put("play_cnt", 0);
        meta.put("followed_cnt", 0);
        meta.put("create_time", 0);
        meta.put("cover", Json.optStr(web.get("cover"), ""));
        meta.put("category", new ArrayList<>());
        meta.put("tags", new ArrayList<>());
        meta.put("celebrities", new ArrayList<>());
        Map<String, Object> out = Json.obj();
        out.put("meta", meta);
        out.put("episodes", eps);
        return out;
    }

    /** 剧集详情请求体（image_shrink 为抓包中的固定值）。 */
    private static final String IMAGE_SHRINK = "W3siaW1hZ2VfdHlwZSI6MywiaW1hZ2Vfd2lkdGgiOjkwMCwic2hyaW5rX3R5cGUiOjN9LHsiaW1hZ2VfdHlwZSI6NCwiaW1hZ2Vfd2lkdGgiOjU0LCJzaHJpbmtfdHlwZSI6NH1d";

    private static Map<String, Object> episodesBody(String seriesId) {
        Map<String, Object> biz = Json.obj();
        biz.put("detail_page_version", 0);
        biz.put("disable_digg_stat", false);
        biz.put("image_shrink_datas_str", IMAGE_SHRINK);
        biz.put("need_all_video_definition", false);
        biz.put("need_mp4_align", false);
        biz.put("screen_width_px", "900");
        biz.put("source", 7);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);

        Map<String, Object> out = Json.obj();
        out.put("biz_param", biz);
        out.put("dr_scene", "preload");
        out.put("series_id", String.valueOf(seriesId));
        return out;
    }

    /**
     * 解析剧集详情。
     *
     * 注意 title 字段：上游 video_list[].title 里装的其实是「本剧简介」，
     * 每一集都重复同一段文字。这里与 series_intro 比对，命中则视为无效标题。
     */
    public static Map<String, Object> parseEpisodeDetail(String sid, Map<String, Object> vd) {
        if (vd == null) vd = Json.obj();
        String intro = str(vd, "series_intro").trim();
        List<Object> eps = new ArrayList<>();

        for (Object eObj : Json.optArr(vd.get("video_list"))) {
            Map<String, Object> e = Json.optObj(eObj);
            if (e == null) continue;
            String title = str(e, "title").trim();
            if (!title.isEmpty() && !intro.isEmpty()
                    && (title.equals(intro) || intro.contains(title))) {
                title = "";
            }
            Map<String, Object> row = Json.obj();
            row.put("index", num(e, "vid_index"));
            Object v = e.get("vid");
            String vid = v == null ? "" : String.valueOf(v);
            if (!vid.matches("[0-9]{1,30}")) continue;
            row.put("vid", vid);
            row.put("title", title.length() > 30 ? title.substring(0, 30) : title);
            row.put("duration", num(e, "duration"));
            String cover = str(e, "episode_cover");
            if (cover.isEmpty()) cover = str(e, "cover");
            row.put("cover", cover);
            row.put("comment_count", num(e, "comment_count"));
            row.put("digged_count", num(e, "digged_count"));
            eps.add(row);
        }

        eps.sort((a, b) -> Long.compare(
                Json.optLong(Json.optObj(a).get("index"), 0),
                Json.optLong(Json.optObj(b).get("index"), 0)));

        String firstEpCover = "";
        for (Object o : eps) {
            Map<String, Object> m = Json.optObj(o);
            if (m != null && !str(m, "cover").isEmpty()) {
                firstEpCover = str(m, "cover");
                break;
            }
        }

        List<Object> celebs = new ArrayList<>();
        for (Object cObj : Json.optArr(vd.get("celebrities"))) {
            Map<String, Object> c = Json.optObj(cObj);
            if (c == null) continue;
            Map<String, Object> cm = Json.obj();
            cm.put("演员", str(c, "nickname"));
            cm.put("角色", str(c, "role_name"));
            cm.put("头像", str(c, "avatar"));
            cm.put("简介", strip(str(c, "intro"), 80));
            celebs.add(cm);
        }

        Map<String, Object> meta = Json.obj();
        meta.put("series_id", String.valueOf(sid));
        String title = str(vd, "series_title");
        meta.put("title", title.isEmpty() ? String.valueOf(sid) : title);
        meta.put("abstract", str(vd, "series_intro"));
        long epCnt = num(vd, "episode_cnt");
        meta.put("episode_cnt", epCnt != 0 ? epCnt : eps.size());
        meta.put("status", Json.optLong(vd.get("series_status"), 0) == 1 ? "完结" : "连载中");
        meta.put("play_cnt", num(vd, "series_play_cnt"));
        meta.put("followed_cnt", num(vd, "followed_cnt"));
        meta.put("create_time", num(vd, "create_time"));
        String cover = str(vd, "series_cover");
        meta.put("cover", cover.isEmpty() ? firstEpCover : cover);
        meta.put("category", parseCategories(vd.get("category_schema")));
        meta.put("tags", coverTags(vd));
        meta.put("celebrities", celebs);

        Map<String, Object> out = Json.obj();
        out.put("meta", meta);
        out.put("episodes", eps);
        return out;
    }

    // ==================== 业务接口 ====================

    /**
     * 搜索短剧。按综合 tab 分页循环翻页，
     * 直到 has_more=false 或达到 max_items / 12 页安全上限。
     */
    public static List<Object> search(String query, int maxItemsArg) throws Exception {
        return searchPage(query, maxItemsArg, 0).items;
    }

    /** App signed search first; official HTML is a metadata fallback. */
    public static Page searchPage(String query, int maxItemsArg, int skip) throws Exception {
        String keyword = query == null ? "" : query.trim();
        int limit = Math.max(1, Math.min(maxItemsArg > 0 ? maxItemsArg : 20, 40));
        int skipN = Math.max(0, Math.min(skip, 10000));
        if (keyword.isEmpty()) return Page.slice(new ArrayList<>(), skipN, limit, false);
        if (keyword.length() > 200) throw new IllegalArgumentException("搜索词过长");
        Exception appError = null;
        try {
            Page page = appSearchPage(keyword, limit, skipN);
            if (!page.items.isEmpty()) return page;
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            appError = error;
        }
        String cacheKey = Safeguards.cacheKey("websearch", keyword);
        Object cached = Safeguards.cacheGet(cacheKey);
        List<Object> webItems = cached instanceof List ? castList(cached) : null;
        if (webItems == null) {
            webItems = webSearchItems(WebSource.search(keyword));
            if (!webItems.isEmpty()) Safeguards.cacheSet(cacheKey, webItems, 600);
        }
        if (!webItems.isEmpty()) return Page.slice(webItems, skipN, limit, false);
        if (appError != null) throw appError;
        return Page.slice(new ArrayList<>(), skipN, limit, false);
    }

    private static Page appSearchPage(String query, int limit, int skipN) throws Exception {
        // App cursor accumulator; every page preserves upstream passback/search_id.
        // 游标状态可复用：同一关键词的续页从头重抓上游，靠这里的缓存兜住重复请求
        String ck = Safeguards.cacheKey("searchp", query, limit, skipN);
        Object cached = Safeguards.cacheGet(ck);
        if (cached instanceof Object[]) {
            Object[] arr = (Object[]) cached;
            if (arr.length == 3 && arr[0] instanceof List) {
                return new Page(castList(arr[0]), Boolean.TRUE.equals(arr[1]),
                        Json.optInt(arr[2], skipN));
            }
        }

        List<Object> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int offset = 0;
        String passback = "";
        String searchId = "";
        boolean hasMore = false;

        // 续页要跳过前 skipN 条，只能从头多抓。为免每点一次「加载更多」就
        // 把上游从头翻一遍，这里把已累积的结果与上游游标按关键词缓存起来，
        // 后续续页从上次位置接着抓。缓存仅 10 分钟，避免结果集明显变化后仍给旧数据。
        String ckAll = Safeguards.cacheKey("searchacc", query);
        Object cachedAcc = Safeguards.cacheGet(ckAll);
        if (cachedAcc instanceof Object[]) {
            Object[] acc = (Object[]) cachedAcc;
            if (acc.length == 6) {
                results.addAll(castList(acc[0]));
                for (Object o : castList(acc[1])) seen.add(String.valueOf(o));
                offset = Json.optInt(acc[2], 0);
                passback = Json.optStr(acc[3], "");
                searchId = Json.optStr(acc[4], "");
                hasMore = Boolean.TRUE.equals(acc[5]);
                if (!hasMore) return Page.slice(results, skipN, limit, false);
            }
        }

        // 连续翻上游直到攒够要交付的那一页（skip 之后的 limit 条）
        int want = skipN + limit;
        // 上游每页 count=10 且要过滤掉无集数的条目，多留些轮次以免深翻页时凑不满
        for (int page = 0; page < 12; page++) {
            // 注意：hasMore 初值为 false，不能在首轮就用它判断退出，
            // 否则第一页都抓不到（搜索恒返回 0 条）。首次无条件抓一轮。
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("请求已取消");
            if (results.size() >= want) break;
            if (page > 0 && !hasMore) break;
            Map<String, Object> q = Json.obj();
            q.put("query", query);
            q.put("tab_name", "feed");
            q.put("search_source", "1");
            q.put("offset", String.valueOf(offset));
            q.put("count", "10");
            q.put("use_correct", "true");
            if (!passback.isEmpty()) q.put("passback", passback);
            if (!searchId.isEmpty()) q.put("search_id", searchId);

            Map<String, Object> j;
            try {
                j = api("GET", "/reading/bookapi/search/tab/v", null, q, 1, true);
            } catch (Exception e) {
                // 翻页中途失败：保留已累积的部分，交由调用方判断 has_more，
                // 不能把整个请求判死，否则续页永远无结果
                if (results.isEmpty()) throw e;
                break;
            }
            List<Object> tabs = Json.optArr(j.get("search_tabs"));
            if (tabs.isEmpty()) { hasMore = false; break; }
            Map<String, Object> tab = Json.optObj(tabs.get(0));
            if (tab == null) { hasMore = false; break; }
            List<Object> data = Json.optArr(tab.get("data"));
            for (Object cell : data) {
                Map<String, Object> item = parseSearchCell(cell);
                if (item == null) continue;
                String isid = str(item, "series_id");
                if (seen.contains(isid)) continue;
                seen.add(isid);
                results.add(item);
            }
            // 游标先落定，供下一页使用
            int adv = tab.get("next_offset") != null
                    ? Json.optInt(tab.get("next_offset"), offset + data.size())
                    : offset + data.size();
            String pb = str(tab, "passback");
            if (!pb.isEmpty()) passback = pb;
            String sid = str(tab, "search_id");
            if (!sid.isEmpty()) searchId = sid;
            hasMore = Json.optBool(tab.get("has_more"), false) && data.size() > 0;
            if (adv <= offset) hasMore = false;   // 游标原地不动 => 无后续
            offset = adv;
        }

        Safeguards.cacheSet(ckAll, new Object[]{
                new ArrayList<>(results), new ArrayList<>(seen), offset, passback, searchId, hasMore}, 600);

        // 丢掉前 skipN 条，只交付之后的那一页（切片逻辑见 Page.slice）
        Page page = Page.slice(results, skipN, limit, hasMore);
        Safeguards.cacheSet(ck, new Object[]{page.items, page.hasMore, page.nextSkip}, 600);
        return page;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return (List<Object>) o;
    }

    /** Only complete nonempty details enter the cache; empty App data triggers HTML fallback. */
    public static Map<String, Object> getEpisodes(String seriesId) throws Exception {
        String sid = seriesId == null ? "" : seriesId.trim();
        if (!sid.matches("[0-9]{1,30}")) throw new IllegalArgumentException("剧集编号无效");
        String cacheKey = Safeguards.cacheKey("episodes", sid);
        Map<String, Object> cached = Json.optObj(Safeguards.cacheGet(cacheKey));
        if (cached != null && !Json.optArr(cached.get("episodes")).isEmpty()) return cached;
        try {
            Map<String, Object> response = api("POST", "/novel/player/multi_video_detail/preload/v1",
                    episodesBody(sid), null, 2, true);
            Map<String, Object> result = parseEpisodeResponse(sid, response);
            if (Json.optArr(result.get("episodes")).isEmpty())
                throw new Safeguards.UpstreamResponseError("App 未返回剧集列表");
            Safeguards.cacheSet(cacheKey, result, 21600);
            return result;
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) throw error;
            Map<String, Object> web = WebSource.episodes(sid);
            if (web != null) {
                Map<String, Object> result = webEpisodesToDetail(web);
                if (!Json.optArr(result.get("episodes")).isEmpty()) {
                    Safeguards.cacheSet(cacheKey, result, 21600);
                    return result;
                }
            }
            throw error;
        }
    }

    /** Null-safe parsing shared with offline protocol fixtures. IDs stay decimal strings. */
    public static Map<String, Object> parseEpisodeResponse(String sid, Map<String, Object> response) {
        Map<String, Object> data = response == null ? null : Json.optObj(response.get("data"));
        Map<String, Object> entry = data == null ? null : Json.optObj(data.get(sid));
        Map<String, Object> video = entry == null ? null : Json.optObj(entry.get("video_data"));
        return parseEpisodeDetail(sid, video);
    }

    /** 批量取剧集元数据。 */
    public static Object[] getEpisodesBatch(List<String> seriesIds, int batchSizeArg) throws Exception {
        List<String> ids = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String s : seriesIds) {
            String sid = s == null ? "" : s.trim();
            if (!sid.isEmpty() && seen.add(sid)) ids.add(sid);
        }
        int bs = Math.max(1, Math.min(batchSizeArg > 0 ? batchSizeArg : 20, 20));
        Map<String, Object> out = Json.obj();
        List<Object> failed = new ArrayList<>();
        List<String> todo = new ArrayList<>();

        for (String sid : ids) {
            Object c = Safeguards.cacheGet(Safeguards.cacheKey("episodes", sid));
            if (c != null) {
                out.put(sid, Json.optObj(c).get("meta"));
            } else {
                todo.add(sid);
            }
        }

        for (int i = 0; i < todo.size(); i += bs) {
            List<String> batch = todo.subList(i, Math.min(i + bs, todo.size()));
            try {
                Map<String, Object> j = api("POST", "/novel/player/multi_video_detail/preload/v1",
                        episodesBody(String.join(",", batch)), null, 3, true);
                Map<String, Object> data = Json.optObj(j.get("data"));
                for (String sid : batch) {
                    Map<String, Object> entry = data == null ? null : Json.optObj(data.get(sid));
                    Map<String, Object> vd = entry == null ? null : Json.optObj(entry.get("video_data"));
                    if (vd == null || vd.isEmpty()) {
                        Map<String, Object> f = Json.obj();
                        f.put("series_id", sid);
                        f.put("error", "empty detail");
                        failed.add(f);
                        continue;
                    }
                    Map<String, Object> parsed = parseEpisodeDetail(sid, vd);
                    if (Json.optArr(parsed.get("episodes")).isEmpty()) {
                        Map<String, Object> f = Json.obj();
                        f.put("series_id", sid);
                        f.put("error", "empty episodes");
                        failed.add(f);
                        continue;
                    }
                    Safeguards.cacheSet(Safeguards.cacheKey("episodes", sid), parsed, 21600);
                    out.put(sid, parsed.get("meta"));
                }
            } catch (Exception e) {
                for (String sid : batch) {
                    Map<String, Object> f = Json.obj();
                    f.put("series_id", sid);
                    f.put("error", String.valueOf(e.getMessage()));
                    failed.add(f);
                }
            }
        }
        return new Object[]{out, failed};
    }

    /** 取漫剧榜单。board: recommend / hot / new。 */
    public static List<Object> rank(String board, int limit) throws Exception {
        RankPage p = rankPage(board, limit, 0);
        return p.items;
    }

    /**
     * 榜单分页结果（供前端无限滚动）。
     *
     * @param items 本页条目
     * @param hasMore 上游是否还有后续页
     * @param nextOffset 下一页应使用的偏移量
     */
    public static final class RankPage {
        public final List<Object> items;
        public final boolean hasMore;
        public final int nextOffset;

        RankPage(List<Object> items, boolean hasMore, int nextOffset) {
            this.items = items;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
        }
    }

    /**
     * 取漫剧榜单的一页。
     *
     * offset 为 0 时行为与从前一致（从头累积抓够 limit 条）；offset>0 时只抓
     * 本页，不会从头重复遍历 —— 无限滚动必须走这条路径，否则每次触底都会把
     * 前面的页重跑一遍。
     */
    public static RankPage rankPage(String board, int limit, int offsetArg) throws Exception {
        int size = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        int start = Math.max(0, offsetArg);
        String cacheKey = Safeguards.cacheKey("rankp-v2", board, size, start);
        Object cached = Safeguards.cacheGet(cacheKey);
        if (cached instanceof Object[]) {
            Object[] fields = (Object[]) cached;
            if (fields.length == 3 && fields[0] instanceof List) {
                List<Object> rows = new ArrayList<>(castList(fields[0]));
                int next = Math.max(start, Json.optInt(fields[2], start));
                return new RankPage(rows, Boolean.TRUE.equals(fields[1]) && !rows.isEmpty() && next > start, next);
            }
        }
        DiscoveryAccumulator page = new DiscoveryAccumulator(start, size, null, false);
        String session = UUID.randomUUID().toString();
        for (int requests = 0; requests < 4; requests++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("请求已取消");
            Map<String, Object> query = Json.obj();
            query.put("cell_id", COMIC_RANK_CELL);
            query.put("tab_type", "26");
            query.put("client_req_type", "2");
            query.put("client_template", "2");
            query.put("screen_width_px", "1350");
            query.put("selected_items", "comic_series_rank");
            query.put("sub_selected_items", RANK_BOARDS.getOrDefault(board, board));
            query.put("session_uuid", session);
            if (page.cursor != 0) query.put("offset", String.valueOf(page.cursor));
            Map<String, Object> response = api("GET", "/reading/bookapi/bookmall/cell/change/v",
                    null, query, 2, SIGN_LIST);
            Map<String, Object> data = Json.optObj(response.get("data"));
            Map<String, Object> cellView = data == null ? null : Json.optObj(data.get("cell_view"));
            if (!page.consume(rankData(cellView))) break;
        }
        Page result = page.result();
        for (int i = 0; i < result.items.size(); i++) Json.optObj(result.items.get(i)).put("rank", start + i + 1);
        Safeguards.cacheSet(cacheKey, new Object[]{new ArrayList<>(result.items), result.hasMore, result.nextSkip}, 1800);
        return new RankPage(result.items, result.hasMore, result.nextSkip);
    }

    /** Retain every raw ranking cell position even if its video payload is missing. */
    static Map<String, Object> rankData(Map<String, Object> cellView) {
        Map<String, Object> normalized = Json.obj();
        List<Object> rows = new ArrayList<>();
        for (Object raw : Json.optArr(cellView == null ? null : cellView.get("cell_data"))) {
            Map<String, Object> cell = Json.optObj(raw);
            Object value = cell == null ? null : cell.get("video_data");
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                value = list.isEmpty() ? null : list.get(0);
            }
            rows.add(value);
        }
        normalized.put("video_data", rows);
        normalized.put("has_more", cellView != null && Json.optBool(cellView.get("has_more"), false));
        normalized.put("next_offset", cellView == null ? null : cellView.get("next_offset"));
        return normalized;
    }

    /**
     * 最新上架。
     * - 短剧：官方有"今日上新"标签，only_today=true 精确返回今日上新；
     * - 漫剧/AI：官方最细 7 天粒度，统一返回"7天内上新·最新上架"。
     */
    public static List<Object> latest(String genre, boolean onlyToday, int maxItems, boolean refresh)
            throws Exception {
        return latestPage(genre, onlyToday, maxItems, refresh, null, 0).items;
    }

    /** 分页结果（通用）。 */
    public static final class Page {
        public final List<Object> items;
        public final boolean hasMore;
        public final int nextSkip;

        Page(List<Object> items, boolean hasMore, int nextSkip) {
            this.items = items;
            this.hasMore = hasMore;
            this.nextSkip = nextSkip;
        }

        /**
         * 从「已抓到的累积结果」里切出 [skip, skip+limit) 这一页。
         *
         * 上游不支持直接跳到第 N 条，续页只能从头多抓再丢掉前面的，
         * 所以切片集中放在这里，便于自检直接验证（search 需真实签名服务）。
         *
         * has_more 的判据是"这一页确实凑满了 limit 条、且上游还有后续"。
         * 攒不满即已到末尾，必须报 false —— 否则前端会一直显示「加载更多」，
         * 点了却永远拿不到内容。
         */
        public static Page slice(List<Object> all, int skip, int limit, boolean upstreamHasMore) {
            int skipN = Math.max(0, skip);
            int lim = Math.max(1, limit);
            int size = all == null ? 0 : all.size();
            int from = Math.min(skipN, size);
            int to = Math.min(skipN + lim, size);
            List<Object> out = new ArrayList<>(all == null
                    ? java.util.Collections.emptyList() : all.subList(from, to));
            boolean more = to < size || (out.size() >= lim && upstreamHasMore);
            return new Page(out, more, skipN + out.size());
        }
    }

    /**
     * 最新上架的一页（供无限滚动）。
     *
     * @param seenIds 已经展示过的 series_id。landpage 接口靠 filter_ids 去重，
     *                把已看过的 id 原样回传即可拿到后续内容且不与前面重复。
     */
    public static Page latestPage(String genre, boolean onlyToday, int maxItems, boolean refresh,
                                  List<String> seenIds, int skip) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) throw new IllegalArgumentException("无效的内容分类");
        int limit = Math.max(1, Math.min(maxItems, 120));
        int start = Math.max(0, skip);
        boolean firstPage = start == 0 && (seenIds == null || seenIds.isEmpty());
        String cacheKey = Safeguards.cacheKey("latest-v2", genre, String.valueOf(onlyToday), String.valueOf(limit));
        if (!refresh && firstPage) {
            Object cached = Safeguards.cacheGet(cacheKey);
            if (cached instanceof Object[]) {
                Object[] page = (Object[]) cached;
                if (page.length == 3 && page[0] instanceof List) {
                    int next = Math.max(start, Json.optInt(page[2], start));
                    List<Object> rows = new ArrayList<>(castList(page[0]));
                    return new Page(rows, Boolean.TRUE.equals(page[1]) && !rows.isEmpty() && next > start, next);
                }
            }
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("sort", "online_time");
        if (!genre.equals("short_play")) options.put("days", "days_7");
        Page result = discoveryPage(genre, options, seenIds, start, limit, onlyToday && genre.equals("short_play"));
        if (firstPage && !result.items.isEmpty()) {
            Safeguards.cacheSet(cacheKey, new Object[]{new ArrayList<>(result.items), result.hasMore, result.nextSkip}, 600);
        }
        return result;
    }

    /** landpage 接口请求体。 */
    private static Map<String, Object> landpageBody(List<String> shown, String scene, int offset,
                                                    int limit, List<Object> onlineTime, String g) {
        Map<String, Object> select = Json.obj();
        select.put("category_dim_epoch", new ArrayList<>());
        select.put("online_time", onlineTime);
        select.put("gender", new ArrayList<>());
        select.put("category_dim_role", new ArrayList<>());
        select.put("genre", new ArrayList<>(Arrays.asList(g)));
        select.put("sort", new ArrayList<>(Arrays.asList("online_time")));
        select.put("category_dim_theme", new ArrayList<>());

        Map<String, Object> body = Json.obj();
        body.put("filter_ids", String.join(",", shown));
        body.put("req_scene", scene);
        body.put("offset", offset);
        body.put("need_selector_panel", false);
        body.put("limit", limit);
        body.put("select_items", select);
        body.put("session_id", "");
        body.put("req_type", "only_content");
        body.put("client_req_type", 3);
        return body;
    }

    /** 取某体裁的实时筛选面板。 */
    public static List<Object> filters(String genre) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) gv = GENRES.get("short_play");

        Map<String, Object> select = Json.obj();
        select.put("category_dim_epoch", new ArrayList<>());
        select.put("online_time", new ArrayList<>());
        select.put("gender", new ArrayList<>());
        select.put("category_dim_role", new ArrayList<>());
        select.put("genre", new ArrayList<>(Arrays.asList(gv[1])));
        select.put("sort", new ArrayList<>());
        select.put("category_dim_theme", new ArrayList<>());

        Map<String, Object> body = Json.obj();
        body.put("filter_ids", "");
        body.put("req_scene", gv[0]);
        body.put("offset", 0);
        body.put("limit", 1);
        body.put("need_selector_panel", true);
        body.put("req_type", "default");
        body.put("client_req_type", 3);
        body.put("select_items", select);
        body.put("session_id", "");

        Map<String, Object> j = api("POST", "/reading/distribution/category/landpage/v",
                body, null, 3, SIGN_LIST);
        Map<String, Object> data = Json.optObj(j.get("data"));
        List<Object> rows = data == null ? new ArrayList<>() : Json.optArr(data.get("selector_rows"));
        List<Object> out = new ArrayList<>();
        for (Object rObj : rows) {
            Map<String, Object> r = Json.optObj(rObj);
            if (r == null) continue;
            Map<String, Object> row = Json.obj();
            row.put("type", str(r, "type"));
            row.put("row_name", str(r, "row_name"));
            row.put("selection_type", str(r, "selection_type"));
            List<Object> items = new ArrayList<>();
            for (Object itObj : Json.optArr(r.get("items"))) {
                Map<String, Object> it = Json.optObj(itObj);
                if (it == null) continue;
                Map<String, Object> im = Json.obj();
                im.put("id", it.get("selector_item_id"));
                im.put("name", it.get("show_name"));
                items.add(im);
            }
            row.put("items", items);
            out.add(row);
        }
        return out;
    }

    /** 单值或数组 -> id 数组；名称按 mapping 映射，已是 id/未知则原样透传。 */
    private static List<Object> toIds(String csv, Map<String, String> mapping) {
        List<Object> out = new ArrayList<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String v : Http.splitCsv(csv)) {
            String mapped = mapping.get(v);
            out.add(mapped != null ? mapped : v);
        }
        return out;
    }

    /** 按筛选条件浏览。 */
    public static List<Object> browse(String genre, Map<String, String> opts) throws Exception {
        return browsePage(genre, opts, null, 0).items;
    }

    /**
     * 筛选浏览的一页（供无限滚动）。
     *
     * @param seenIds 已展示过的 series_id，经 filter_ids 回传给上游做去重。
     */
    public static Page browsePage(String genre, Map<String, String> opts,
                                  List<String> seenIds, int skip) throws Exception {
        Map<String, String> options = opts == null ? java.util.Collections.emptyMap() : opts;
        int limit = Math.max(1, Math.min(Json.optInt(options.get("limit"), 60), 120));
        return discoveryPage(genre, options, seenIds, Math.max(0, skip), limit, false);
    }

    /** A single bounded native-page request. Offset counts consumed upstream records, not visible cards. */
    private static Page discoveryPage(String genre, Map<String, String> opts, List<String> seenIds,
                                      int skip, int limit, boolean onlyToday) throws Exception {
        String[] gv = GENRES.get(genre);
        if (gv == null) throw new IllegalArgumentException("无效的内容分类");
        Map<String, Object> select = Json.obj();
        select.put("genre", new ArrayList<>(Arrays.asList(gv[1])));
        select.put("category_dim_theme", toIds(opts.get("theme"), FILTER_CATE));
        select.put("category_dim_role", toIds(opts.get("setting"), FILTER_CATE));
        select.put("category_dim_epoch", toIds(opts.get("background"), FILTER_CATE));
        List<Object> sortIds = toIds(opts.get("sort"), FILTER_SORT);
        select.put("sort", sortIds.isEmpty() ? new ArrayList<>(Arrays.asList("online_time")) : sortIds);
        select.put("gender", toIds(opts.get("gender"), FILTER_GENDER));
        select.put("creation_status", toIds(opts.get("status"), FILTER_STATUS));
        select.put("online_time", toIds(opts.get("days"), FILTER_DAYS));
        DiscoveryAccumulator page = new DiscoveryAccumulator(skip, limit, seenIds, onlyToday);
        for (int requests = 0; requests < 4; requests++) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("请求已取消");
            Map<String, Object> body = Json.obj();
            body.put("filter_ids", String.join(",", page.shown));
            body.put("req_scene", gv[0]);
            body.put("offset", page.cursor);
            body.put("need_selector_panel", false);
            // Request exactly the remaining capacity, so a 60-card request never discards cards 61-72.
            body.put("limit", Math.min(18, limit - page.items.size()));
            body.put("select_items", select);
            body.put("session_id", "");
            body.put("req_type", "only_content");
            body.put("client_req_type", 3);
            Map<String, Object> response = api("POST", "/reading/distribution/category/landpage/v",
                    body, null, 2, SIGN_LIST);
            if (!page.consume(Json.optObj(response.get("data")))) break;
        }
        return page.result();
    }

    /** Pure cursor/page state used by offline protocol regression tests. */
    static final class DiscoveryAccumulator {
        final List<Object> items = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        final int start;
        final int limit;
        final boolean onlyToday;
        int cursor;
        boolean hasMore;

        DiscoveryAccumulator(int offset, int limit, List<String> seenIds, boolean onlyToday) {
            this.start = this.cursor = Math.max(0, offset);
            this.limit = Math.max(1, Math.min(limit, 120));
            this.onlyToday = onlyToday;
            if (seenIds != null) {
                // Retain the newest occurrence and bound the upstream filter_ids payload.
                LinkedHashSet<String> tail = new LinkedHashSet<>();
                for (int i = seenIds.size() - 1; i >= 0 && tail.size() < 180; i--) {
                    String id = seenIds.get(i);
                    if (validSeriesId(id)) tail.add(id);
                }
                shown.addAll(tail);
                java.util.Collections.reverse(shown);
                seen.addAll(shown);
            }
        }

        boolean consume(Map<String, Object> data) {
            List<Object> raw = data == null ? java.util.Collections.emptyList() : Json.optArr(data.get("video_data"));
            if (raw.isEmpty()) { hasMore = false; return false; }
            int consumed = 0;
            int todayCount = 0;
            for (Object value : raw) {
                if (items.size() >= limit) break;
                consumed++;
                Map<String, Object> item = Json.optObj(value);
                if (item == null) continue;
                String id = Json.optStr(firstNonNull(item, "series_id", "book_id"), "");
                if (!validSeriesId(id) || !seen.add(id)) continue;
                shown.add(id);
                if (shown.size() > 180) shown.remove(0);
                List<String> subtitles = new ArrayList<>();
                for (Object subtitle : Json.optArr(item.get("sub_title_list"))) {
                    Map<String, Object> row = Json.optObj(subtitle);
                    if (row != null) subtitles.add(str(row, "content"));
                }
                boolean today = subtitles.contains("今日上新");
                if (today) todayCount++;
                if (onlyToday && !today) continue;
                List<String> categories = parseCategories(item.get("category_schema"));
                if (categories.isEmpty()) {
                    for (String subtitle : subtitles) {
                        if (subtitle.isEmpty() || subtitle.equals("今日上新") || subtitle.contains("播放")
                                || subtitle.matches("^\\d+(\\.\\d+)?万.*") || subtitle.matches("^\\d+集$")) continue;
                        if (!categories.contains(subtitle)) categories.add(subtitle);
                    }
                }
                Map<String, Object> row = Json.obj();
                row.put("series_id", id);
                row.put("title", str(item, "title"));
                row.put("episode_cnt", num(item, "episode_cnt"));
                row.put("score", item.getOrDefault("score", ""));
                row.put("play_cnt", num(item, "play_cnt"));
                row.put("hot", str(item, "rec_text"));
                row.put("cover", str(item, "cover"));
                row.put("copyright", str(item, "copyright"));
                row.put("category", String.join(" / ", categories));
                row.put("today", today);
                List<String> tags = coverTags(item);
                String tag = str(Json.optObj(item.get("tag_info")), "text");
                if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag);
                row.put("tags", tags);
                row.put("abstract", strip(str(item, "video_desc"), 50));
                row.put("vid", str(item, "vid"));
                row.put("comment_count", num(item, "comment_count"));
                row.put("duration", num(item, "duration"));
                row.put("horiz_cover", str(item, "horiz_cover"));
                items.add(row);
            }
            boolean partial = consumed < raw.size();
            int next = discoveryNextCursor(cursor, consumed, partial ? null : data.get("next_offset"));
            hasMore = (partial || Json.optBool(data.get("has_more"), true)) && next > cursor;
            cursor = next;
            if (onlyToday && todayCount == 0) hasMore = false;
            return hasMore && items.size() < limit;
        }

        Page result() {
            return new Page(new ArrayList<>(items), hasMore && !items.isEmpty() && cursor > start, cursor);
        }
    }

    private static boolean validSeriesId(String id) {
        return id != null && id.matches("[0-9]{1,30}");
    }

    static int discoveryNextCursor(int current, int consumed, Object declaredNext) {
        if (consumed <= 0) return current;
        long fallback = Math.min(Integer.MAX_VALUE, (long) current + consumed);
        long declared = Json.optLong(declaredNext, fallback);
        return (int) Math.min(Integer.MAX_VALUE, declared > current ? declared : fallback);
    }

    // ==================== 工具 ====================

    /** 文件名安全化（保留 60 字上限）。 */
    public static String sanitize(String name) {
        String t = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
