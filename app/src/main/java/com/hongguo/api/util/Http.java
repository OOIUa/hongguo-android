package com.hongguo.api.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.SocketTimeoutException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 HttpURLConnection 的 HTTP 客户端（对应 Node 侧的 fetch / http.request）。
 *
 * 为什么不用 java.net.http.HttpClient：
 *  JDK17+ 才有，而本项目要兼容 Windows 上随包的 JRE 17；
 *  且 HttpURLConnection 的重定向与超时行为更贴近原 Node 实现，迁移风险更低。
 *
 * 关键行为对齐：
 *  - 默认跟随重定向（同 fetch 的 redirect: 'follow'）
 *  - 默认不自动压缩（原 Node 侧显式 delete accept-encoding，交给对端决定）
 *  - 超时抛异常而非静默返回
 */
public final class Http {

    private Http() {}

    /** 响应结果。 */
    public static final class Resp {
        public final int status;
        public final byte[] body;
        public final Map<String, List<String>> headers;

        Resp(int status, byte[] body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public String text() { return new String(body, StandardCharsets.UTF_8); }

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().isEmpty() ? null : e.getValue().get(0);
                }
            }
            return null;
        }

        public boolean ok() { return status >= 200 && status < 300; }
    }

    /** 请求参数。 */
    public static final class Req {
        public String method = "GET";
        public String url;
        public Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body;
        public int timeoutMs = 15000;
        public boolean followRedirect = true;

        public Req(String url) { this.url = url; }

        public Req method(String m) { this.method = m; return this; }
        public Req header(String k, String v) { headers.put(k, v); return this; }
        public Req body(byte[] b) { this.body = b; return this; }
        public Req timeout(int ms) { this.timeoutMs = ms; return this; }
    }

    /** 发起请求。 */
    public static Resp send(Req req) throws IOException {
        return send(req, 0);
    }

    private static Resp send(Req req, int redirects) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("请求已取消");
        final int timeout = Math.max(1000, Math.min(15000, req.timeoutMs));
        final long deadline = System.nanoTime() + timeout * 1000000L;
        HttpURLConnection conn;
        try { conn = open(req.url, req.followRedirect); }
        catch (Exception ignored) { throw new IOException("网络请求地址无效"); }
        try {
            conn.setRequestMethod(req.method);
            conn.setConnectTimeout(Math.min(timeout, 8000));
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> e : req.headers.entrySet()) {
                if (e.getValue() != null) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (req.body != null) {
                conn.setDoOutput(true);
                if (conn.getRequestProperty("Content-Length") == null) {
                    conn.setFixedLengthStreamingMode(req.body.length);
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(req.body);
                }
            }
            int status = conn.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                if (!req.followRedirect) return new Resp(status, new byte[0], conn.getHeaderFields());
                if (redirects >= 3 || !(req.method.equals("GET") || req.method.equals("HEAD")))
                    throw new IOException("不支持的服务重定向");
                String location = conn.getHeaderField("Location");
                URI old = URI.create(req.url);
                URI next = location == null ? null : old.resolve(location);
                if (next == null || next.getUserInfo() != null || old.getHost() == null
                        || !old.getHost().equalsIgnoreCase(next.getHost())
                        || !old.getScheme().equalsIgnoreCase(next.getScheme())
                        || old.getPort() != next.getPort())
                    throw new IOException("不支持的服务重定向");
                Req redirected = new Req(next.toString()).method(req.method).timeout(timeout);
                redirected.headers.putAll(req.headers);
                conn.disconnect();
                return send(redirected, redirects + 1);
            }
            // 错误码也可能有响应体（上游风控时返回 JSON 错误）
            InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = readAll(is, deadline);
            return new Resp(status, data, conn.getHeaderFields());
        } catch (SocketTimeoutException ignored) {
            throw new SocketTimeoutException("网络请求超时");
        } catch (InterruptedIOException ignored) {
            throw new InterruptedIOException("请求已取消或超时");
        } catch (IOException ignored) {
            // Transport exception messages may include signed URLs or response fragments.
            throw new IOException("网络请求失败");
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url, boolean follow) throws IOException {
        // 统一走 HttpURLConnection：项目所有请求都是 http/https。
        // 协议校验必须在强转之前做——强转后 instanceof 恒真，等于没有校验，
        // 非 http/https 的地址会在这里抛 ClassCastException 而非可读的提示。
        URLConnection raw = URI.create(url).toURL().openConnection();
        if (!(raw instanceof HttpURLConnection)) {
            throw new IOException("仅支持 http/https，当前协议不支持：" + url);
        }
        HttpURLConnection c = (HttpURLConnection) raw;
        c.setInstanceFollowRedirects(false);
        return c;
    }

    private static byte[] readAll(InputStream is, long deadline) throws IOException {
        if (is == null) return new byte[0];
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("请求已取消");
                if (System.nanoTime() > deadline) throw new SocketTimeoutException("网络请求超时");
                if (bos.size() > 8 * 1024 * 1024 - n) throw new IOException("响应超过大小限制");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ==================== 工具 ====================

    public static String urlencode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String urldecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 解析查询串为Map（对齐 URLSearchParams：重复键取最后一个）。
     */
    public static Map<String, String> parseQuery(String qs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) return out;
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            if (i < 0) {
                out.put(urldecode(pair), "");
            } else {
                out.put(urldecode(pair.substring(0, i)), urldecode(pair.substring(i + 1)));
            }
        }
        return out;
    }

    /** 把多行文本按逗号切分成去空白列表；null/空返回 null。 */
    public static List<String> splitCsv(String v) {
        if (v == null || v.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
