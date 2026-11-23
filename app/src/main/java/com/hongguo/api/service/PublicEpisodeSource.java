// SPDX-License-Identifier: GPL-3.0-only
// Guest player flow ported from OOIUa/hongguo-ios, adapted there from jackotom/hongguo-drama v8.0.
package com.hongguo.api.service;

import com.hongguo.api.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Resolves only the episode offered by the official website to an anonymous visitor. */
public final class PublicEpisodeSource {
    private static final int PAGE_BYTES = 4 * 1024 * 1024;
    private static final int MODEL_BYTES = 64 * 1024;
    private static final Pattern ID = Pattern.compile("[0-9]{1,30}");
    private static final Pattern ROUTER = Pattern.compile("<(?i:script)\\b[^>]*>\\s*(?:window\\.)?_ROUTER_DATA\\s*=\\s*\\{");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            // Handle server backoff before OkHttp can replay a 503 with Retry-After: 0.
            .addNetworkInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                try {
                    checkStatus(response);
                    return response;
                } catch (IOException error) {
                    response.close();
                    throw error;
                }
            }).build();

    private PublicEpisodeSource() {}

    public abstract static class GuestPlaybackException extends IOException {
        private GuestPlaybackException(String message) { super(message); }
    }

    public static final class GuestUnavailableException extends GuestPlaybackException {
        public GuestUnavailableException() { super("官网暂未向访客提供本集完整片源"); }
    }

    public static final class GuestRateLimitedException extends GuestPlaybackException {
        public GuestRateLimitedException() { super("官网请求过于频繁，请稍后再试"); }
    }

    private static final class InvalidResponseException extends GuestPlaybackException {
        InvalidResponseException(String message) { super(message); }
    }

    static Stream.VideoTrack fetch(String seriesId, String vid, long deadlineNanos) throws IOException {
        return fetch(seriesId, vid, deadlineNanos, HTTP);
    }

    static Stream.VideoTrack fetch(String seriesId, String vid, long deadlineNanos, Call.Factory transport) throws IOException {
        if (seriesId == null || vid == null || !ID.matcher(seriesId).matches() || !ID.matcher(vid).matches()) {
            throw invalid();
        }
        Request page = new Request.Builder().url(WebSource.SITE_ORIGIN + "/player/" + seriesId + "/" + vid)
                .header("Accept", "text/html").build();
        String html = load(page, PAGE_BYTES, deadlineNanos, transport);
        Stream.VideoTrack track = pageTrack(html, seriesId, vid);
        Request model = new Request.Builder().url(WebSource.SITE_ORIGIN + "/api/video/protected/fallback-play-info")
                .header("Accept", "application/json")
                .post(RequestBody.create(Json.stringify(Collections.singletonMap("item_id", vid)), JSON)).build();
        track.url = mediaUrl(load(model, MODEL_BYTES, deadlineNanos, transport), System.currentTimeMillis() / 1000.0);
        remaining(deadlineNanos);
        return track;
    }

    private static Stream.VideoTrack pageTrack(String html, String seriesId, String vid) throws IOException {
        Matcher marker = ROUTER.matcher(html);
        Map<String, Object> page = null;
        while (marker.find() && page == null) {
            int start = marker.end() - 1;
            int end = WebSource.matchBracket(html, start, '{', '}');
            if (end < 0) throw invalid();
            Map<String, Object> root = object(html.substring(start, end + 1));
            Map<String, Object> loaders = Json.optObj(root.get("loaderData"));
            if (loaders != null) page = Json.optObj(loaders.get("player_(series_id)/(vid)/page"));
        }
        if (page == null || !Boolean.TRUE.equals(page.get("isSuccess"))
                || !seriesId.equals(Json.optStr(page.get("series_id"))) || !vid.equals(Json.optStr(page.get("vid")))) {
            throw invalid();
        }
        Map<String, Object> series = Json.optObj(page.get("seriesDetail"));
        if (series == null || !seriesId.equals(Json.optStr(series.get("series_id")))) throw invalid();
        Map<String, Object> access = Json.optObj(page.get("req"));
        if (access == null || !Boolean.FALSE.equals(access.get("pcLoginRequired"))
                || !Boolean.FALSE.equals(access.get("pcAuthenticatedPlayback"))) throw new GuestUnavailableException();
        List<Object> episodes = Json.optArr(series.get("vid_list"));
        int accessible;
        try { accessible = Integer.parseInt(Json.optStr(series.get("accessible_episode_cnt"))); }
        catch (NumberFormatException ignored) { throw new GuestUnavailableException(); }
        boolean available = false;
        for (int i = 0; i < Math.min(episodes.size(), accessible); i++) {
            if (vid.equals(Json.optStr(episodes.get(i)))) { available = true; break; }
        }
        if (!available) throw new GuestUnavailableException();

        Stream.VideoTrack track = new Stream.VideoTrack();
        track.publicDurationSeconds = 0.0;
        track.definition = "网页片源";
        track.spadeA = "";
        Map<String, Object> seo = Json.optObj(page.get("publicVideoSeo"));
        if (seo != null && Boolean.TRUE.equals(seo.get("available"))) {
            if (!seriesId.equals(Json.optStr(seo.get("seriesId"))) || !vid.equals(Json.optStr(seo.get("itemId")))) throw invalid();
            double duration = number(seo.get("durationSeconds"));
            if (duration <= 0) throw invalid();
            track.publicDurationSeconds = duration;
            int pixels = Math.min(Json.optInt(seo.get("width"), 0), Json.optInt(seo.get("height"), 0));
            if (pixels > 0) track.definition = "网页 " + pixels + "P";
        }
        return track;
    }

    private static String mediaUrl(String text, double nowSeconds) throws IOException {
        Map<String, Object> response = object(text);
        Map<String, Object> base = Json.optObj(response.get("base_resp"));
        Object status = base == null ? null : base.get("StatusCode");
        if (!"0".equals(Json.optStr(status != null ? status : response.get("code")))) throw invalid();
        if (response.containsKey("url_expire") && number(response.get("url_expire")) <= nowSeconds + 5) throw invalid();
        Object raw = response.get("main_url");
        if (!(raw instanceof String) || ((String) raw).contains("#")) throw invalid();
        try { return AppSource.checkedUrl((String) raw, AppSource.VOD_DOMAINS); }
        catch (Exception ignored) { throw invalid(); }
    }

    private static Map<String, Object> object(String text) throws IOException {
        try { return Json.parseObject(text); }
        catch (RuntimeException ignored) { throw invalid(); }
    }

    private static double number(Object value) throws IOException {
        try {
            double result = Double.parseDouble(Json.optStr(value));
            if (Double.isFinite(result)) return result;
        } catch (NumberFormatException ignored) {}
        throw invalid();
    }

    private static IOException invalid() { return new InvalidResponseException("官网未返回有效的分集播放信息"); }

    private static long remaining(long deadlineNanos) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放请求已取消");
        long nanos = deadlineNanos - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("官网取源超时");
        return nanos;
    }

    private static void checkStatus(Response response) throws IOException {
        String backoff = response.header("Retry-After");
        int status = response.code();
        if (status == 429 || (backoff != null && !backoff.trim().isEmpty())) throw new GuestRateLimitedException();
        if ((status >= 300 && status < 400) || status == 401 || status == 403 || status == 404) throw new GuestUnavailableException();
        if (status != 200) throw new InvalidResponseException("官网片源请求失败（HTTP " + status + "）");
    }

    private static String load(Request request, int limit, long deadlineNanos, Call.Factory transport) throws IOException {
        long budget = Math.min(remaining(deadlineNanos), TimeUnit.SECONDS.toNanos(6));
        Call call = transport.newCall(request);
        call.timeout().timeout(budget, TimeUnit.NANOSECONDS);
        Future<String> read = HTTP.dispatcher().executorService().submit(() -> {
            try (Response response = call.execute()) {
                ResponseBody body = response.body();
                if (body == null || body.contentLength() > limit) throw invalid();
                InputStream input = body.byteStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()))) != -1) {
                    if (count > limit - output.size()) throw invalid();
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        });
        try {
            return read.get(budget, TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("播放请求已取消");
        } catch (TimeoutException ignored) {
            throw new SocketTimeoutException("官网取源超时");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof GuestPlaybackException) throw (GuestPlaybackException) cause;
            if (cause instanceof InterruptedIOException) throw new SocketTimeoutException("官网取源超时");
            throw new IOException("官网分集请求失败");
        } finally {
            if (!read.isDone()) { call.cancel(); read.cancel(true); }
        }
    }
}
