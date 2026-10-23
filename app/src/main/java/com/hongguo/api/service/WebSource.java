package com.hongguo.api.service;

import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官网网页版数据源（免签）。
 *
 * 红果短剧官网 hongguoduanju.com 的页面是服务端渲染的：
 * - /search/{keyword}      内嵌 _ROUTER_DATA.searchList（搜索结果）
 * - /player/{series_id}    内嵌 vid_list（完整分集 vid 列表）与 series_name
 *
 * 播放直链历史上也在 /player/{sid}/{vid} 页内（main_url，明文 MP4），
 * 但官网改版后 SSR 已不再输出该字段，仅剩 360p 预览 contentUrl
 * （付费集下载即 404），故播放取址不再走官网，统一由 AppSource 负责
 * （见 Stream.videoTrack）。官网数据现仅用于搜索与分集列表。
 *
 * 移植参考：jackotom/hongguo-drama src/native/hongguo.js（GPL-3.0）。
 */
public final class WebSource {

    private WebSource() {}

    private static final String SITE_ORIGIN = "https://hongguoduanju.com";
    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    // ==================== 公开接口 ====================

    /**
     * 官网搜索。
     *
     * @param keyword 关键词
     * @return 结果列表，每项含 series_id / series_title / series_intro / cover / episode_cnt；
     *         搜索失败或空结果返回空列表
     */
    public static List<Map<String, Object>> search(String keyword) {
        List<Map<String, Object>> out = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return out;
        try {
            String html = fetchHtml(SITE_ORIGIN + "/search/" + Http.urlencode(kw));
            int ki = html.indexOf("\"searchList\"");
            if (ki < 0) return out;
            int bi = html.indexOf('[', ki);
            if (bi < 0) return out;
            int end = matchBracket(html, bi, '[', ']');
            if (end < 0) return out;
            List<Object> list = Json.optArr(Json.parse(html.substring(bi, end + 1)));
            for (Object item : list) {
                Map<String, Object> cell = Json.optObj(item);
                if (cell == null) continue;
                Map<String, Object> vd = Json.optObj(cell.get("video_data"));
                if (vd == null) vd = cell;
                String sid = Json.optStr(vd.get("series_id"), "");
                if (sid.isEmpty()) continue;
                Map<String, Object> row = Json.obj();
                row.put("series_id", sid);
                row.put("series_title", Json.optStr(vd.get("series_title"),
                        Json.optStr(cell.get("name"), "")));
                row.put("series_intro", Json.optStr(vd.get("series_intro"), ""));
                row.put("cover", normalizeVideoUrl(Json.optStr(vd.get("series_cover"), "")));
                row.put("episode_cnt", Json.optInt(vd.get("episode_cnt"), 0));
                out.add(row);
            }
        } catch (Exception e) {
            Log.warn("官网搜索失败");
        }
        return out;
    }

    /**
     * 官网分集列表。
     *
     * @param seriesId 剧 id
     * @return {series_id, series_title, cover, total, web_accessible_episodes,
     *          episodes:[{vid, vid_index, title, web_available}]}；失败返回 null
     */
    public static Map<String, Object> episodes(String seriesId) {
        String sid = seriesId == null ? "" : seriesId.trim();
        if (sid.isEmpty()) return null;
        try {
            String html = fetchHtml(SITE_ORIGIN + "/player/" + Http.urlencode(sid));
            Matcher m = Pattern.compile("\"vid_list\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(html);
            if (!m.find()) return null;
            List<String> vids = new ArrayList<>();
            for (String s : m.group(1).split(",")) {
                String v = s.trim().replaceAll("^\"|\"$", "");
                if (v.matches("\\d+")) vids.add(v);
            }
            if (vids.isEmpty()) return null;

            String title = pickJsonString(html, "series_name");
            if (title.isEmpty()) title = "未命名短剧";
            String cover = normalizeVideoUrl(pickJsonString(html, "series_cover"));
            Matcher am = Pattern.compile("\"accessible_episode_cnt\"\\s*:\\s*(\\d+)").matcher(html);
            Integer accessible = am.find() ? Math.min(Integer.parseInt(am.group(1)), vids.size()) : null;

            List<Object> eps = new ArrayList<>();
            for (int i = 0; i < vids.size(); i++) {
                Map<String, Object> e = Json.obj();
                e.put("vid", vids.get(i));
                e.put("vid_index", i + 1);
                e.put("title", "");
                e.put("series_id", sid);
                e.put("series_title", title);
                e.put("cover", cover);
                e.put("web_available", accessible == null ? Boolean.TRUE : i < accessible);
                eps.add(e);
            }
            Map<String, Object> out = Json.obj();
            out.put("series_id", sid);
            out.put("series_title", title);
            out.put("cover", cover);
            out.put("total", eps.size());
            out.put("web_accessible_episodes", accessible == null ? -1 : accessible);
            out.put("episodes", eps);
            out.put("source", "web");
            return out;
        } catch (Exception e) {
            Log.warn("官网分集失败");
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /** 拉取官网页面 HTML（桌面 UA + 站内 Referer）。 */
    static String fetchHtml(String url) throws IOException {
        Http.Req req = new Http.Req(url)
                .method("GET")
                .timeout(12000)
                .header("User-Agent", WEB_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", SITE_ORIGIN + "/");
        Http.Resp res = Http.send(req);
        if (!res.ok()) throw new IOException("HTTP " + res.status);
        return res.text();
    }

    /** 从 HTML 内嵌 JSON 里取一个字符串字段（处理 \u002F / \u0026 转义）。 */
    static String pickJsonString(String text, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        return m.find() ? normalizeVideoUrl(m.group(1)) : "";
    }

    /** 去掉 HTML/JSON 转义与 Range 参数，还原可直接下载的地址。 */
    static String normalizeVideoUrl(String raw) {
        if (raw == null) return "";
        return raw.replace("\\u002F", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replaceAll("([?&])Range=[^&]*", "$1")
                .replaceAll("[?&]$", "");
    }

    /** 从 openIdx 处的开括号做起平衡扫描，返回匹配闭括号下标（尊重字符串与转义）。 */
    static int matchBracket(String s, int openIdx, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
