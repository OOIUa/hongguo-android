// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicEpisodeSourceTest {
    private static final String SERIES = "7000000000000000001", FIRST = "7000000000000000002", SECOND = "7000000000000000003";
    private static final String CDN = "https://v3-hgweb.qznovelvod.com/fixture.mp4";
    private MockWebServer server;
    private Call.Factory transport;

    @Before public void start() throws IOException {
        server = new MockWebServer();
        server.start();
        transport = request -> {
            assertEquals("hongguoduanju.com", request.url().host());
            return PublicEpisodeSource.HTTP.newCall(request.newBuilder().url(server.url(request.url().encodedPath())).build());
        };
    }

    @After public void stop() throws IOException { server.shutdown(); }

    private static String page(String vid, int accessible, boolean seo) {
        String metadata = seo ? ",\"publicVideoSeo\":{\"available\":true,\"seriesId\":\"" + SERIES
                + "\",\"itemId\":\"" + vid + "\",\"durationSeconds\":125.14,\"width\":360,\"height\":640}" : "";
        return "<script>_ROUTER_DATA = {\"loaderData\":{\"player_(series_id)/(vid)/page\":{\"isSuccess\":true,"
                + "\"series_id\":\"" + SERIES + "\",\"vid\":\"" + vid + "\","
                + "\"req\":{\"pcLoginRequired\":false,\"pcAuthenticatedPlayback\":false},"
                + "\"seriesDetail\":{\"series_id\":" + SERIES + ",\"vid_list\":[" + FIRST + "," + SECOND
                + "],\"accessible_episode_cnt\":" + accessible + "}" + metadata + "}}};</script>";
    }

    private static String reply(String url, long expiry) {
        return "{\"base_resp\":{\"StatusCode\":0},\"main_url\":\"" + url + "\",\"url_expire\":" + expiry + "}";
    }

    private Stream.VideoTrack fetch(String vid) throws IOException {
        return PublicEpisodeSource.fetch(SERIES, vid, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), transport);
    }

    @Test public void requiresSeparatePermissionForSameEpisodeAndUsesAnonymousVisitorFlow() throws Exception {
        server.enqueue(new MockResponse().setBody(page(FIRST, 1, true)).setHeader("Set-Cookie", "fixture=private"));
        server.enqueue(new MockResponse().setBody(reply(CDN, Long.MAX_VALUE)));
        Stream.VideoTrack track = fetch(FIRST);
        assertEquals(CDN, track.url);
        assertEquals(125.14, track.publicDurationSeconds, 0);
        assertEquals("网页 360P", track.definition);
        assertFalse(track.encrypt);
        RecordedRequest pageRequest = server.takeRequest();
        RecordedRequest player = server.takeRequest();
        assertEquals("GET", pageRequest.getMethod());
        assertEquals("/player/" + SERIES + "/" + FIRST, pageRequest.getPath());
        assertEquals("POST", player.getMethod());
        assertEquals("/api/video/protected/fallback-play-info", player.getPath());
        assertEquals("{\"item_id\":\"" + FIRST + "\"}", player.getBody().readUtf8());
        assertNull(player.getHeader("Cookie"));
        assertNull(player.getHeader("Authorization"));

        server.enqueue(new MockResponse().setBody(page(SECOND, 2, false)));
        server.enqueue(new MockResponse().setBody(reply(CDN, Long.MAX_VALUE)));
        Stream.VideoTrack unlisted = fetch(SECOND);
        assertEquals(0.0, unlisted.publicDurationSeconds, 0);
        assertEquals("网页片源", unlisted.definition);
    }

    @Test public void restrictedMismatchedOrMissingPermissionNeverRequestsMedia() {
        String valid = page(SECOND, 2, true);
        for (String html : new String[]{page(SECOND, 1, false),
                valid.replace("\"pcLoginRequired\":false", "\"pcLoginRequired\":true"),
                valid.replace("\"pcAuthenticatedPlayback\":false", "\"pcAuthenticatedPlayback\":true"),
                valid.replace("pcLoginRequired", "noPermissionFlag")}) {
            server.enqueue(new MockResponse().setBody(html));
            assertThrows(PublicEpisodeSource.GuestUnavailableException.class, () -> fetch(SECOND));
        }
        for (String html : new String[]{page(FIRST, 2, false),
                valid.replace("\"seriesId\":\"" + SERIES, "\"seriesId\":\"" + FIRST),
                valid.replace("<script>", "<div>")}) {
            server.enqueue(new MockResponse().setBody(html));
            assertThrows(IOException.class, () -> fetch(SECOND));
        }
        assertEquals(7, server.getRequestCount());
    }

    @Test public void rejectsRedirectsDenialsAndBackoffWithoutReplay() {
        for (int status : new int[]{302, 401, 403, 404}) {
            server.enqueue(new MockResponse().setResponseCode(status).addHeader("Location", "/other").setBody("private"));
            assertThrows(PublicEpisodeSource.GuestUnavailableException.class, () -> fetch(FIRST));
        }
        for (int status : new int[]{429, 503, 200}) {
            server.enqueue(new MockResponse().setResponseCode(status).addHeader("Retry-After", "0"));
            assertThrows(PublicEpisodeSource.GuestRateLimitedException.class, () -> fetch(FIRST));
        }
        assertEquals(7, server.getRequestCount());
    }

    @Test public void rejectsNonCdnExpiredOrOversizedGuestResponses() {
        for (String model : new String[]{reply("http://v1.qznovelvod.com/a", Long.MAX_VALUE),
                reply("https://qznovelvod.com.invalid/a", Long.MAX_VALUE),
                reply("https://user@qznovelvod.com/a", Long.MAX_VALUE),
                reply("https://qznovelvod.com:444/a", Long.MAX_VALUE),
                reply("https://qznovelvod.com/a#segment", Long.MAX_VALUE), reply(CDN, 50),
                reply(CDN, Long.MAX_VALUE).replace("\"StatusCode\":0", "\"StatusCode\":1")}) {
            server.enqueue(new MockResponse().setBody(page(FIRST, 1, false)));
            server.enqueue(new MockResponse().setBody(model));
            IOException invalid = assertThrows(PublicEpisodeSource.GuestPlaybackException.class, () -> fetch(FIRST));
            assertEquals("官网未返回有效的分集播放信息", com.hongguo.android.DramaRepositoryKt.readableFailure(invalid, "准备播放"));
        }
        server.enqueue(new MockResponse().setBody(page(FIRST, 1, false)));
        server.enqueue(new MockResponse().setChunkedBody("x".repeat(65_537), 8_192));
        assertThrows(IOException.class, () -> fetch(FIRST));
        server.enqueue(new MockResponse().setHeader("Content-Length", 4 * 1024 * 1024 + 1));
        assertThrows(IOException.class, () -> fetch(FIRST));
    }

    @Test public void deadlineAndThreadCancellationStopTheCurrentCall() throws Exception {
        assertThrows(SocketTimeoutException.class, () -> PublicEpisodeSource.fetch(SERIES, FIRST, System.nanoTime() - 1, transport));
        assertEquals(0, server.getRequestCount());
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try { fetch(FIRST); }
            catch (Throwable t) { error.set(t); }
        });
        reader.start();
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        reader.interrupt();
        reader.join(1_000);
        assertFalse(reader.isAlive());
        assertTrue(error.get() instanceof InterruptedIOException);
        assertEquals(1, server.getRequestCount());
    }
}
