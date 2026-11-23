package com.hongguo.api.service;

import com.hongguo.api.util.Config;
import java.io.File;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import com.hongguo.api.util.Http;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackRecoveryTest {
    private final AtomicInteger registrations = new AtomicInteger();
    private final Stream.VideoTrack track = new Stream.VideoTrack();

    @Before public void setup() {
        Config.initialize("{}", new File(System.getProperty("java.io.tmpdir"), "recovery-test").getPath(), "1234567890123456");
        track.url = "https://v1.qznovelvod.com/recovery.mp4";
        PlaybackDevice.resetForTest((url, headers, body) -> {
            int n = registrations.incrementAndGet();
            return new PlaybackDevice.Reply(200, "{\"device_id\":\"71000000000000000" + n + "\",\"install_id\":\"72000000000000000" + n + "\"}");
        });
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> { throw new AssertionError("Unexpected legacy request"); });
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> { throw new AssertionError("Unexpected legacy request"); });
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> track);
    }

    @After public void cleanup() {
        AppSource.setSinglePlaybackForTest(null); AppSource.setPlaybackTabForTest(null);
        AppSource.setPlaybackPostForTest(null); AppSource.setPlaybackFallbackForTest(null);
        PlaybackDevice.resetForTest(null);
    }

    @Test public void successfulIdentityIsReusedForAnotherEpisode() throws Exception {
        assertSame(track, AppSource.fetch("1", "auto"));
        assertSame(track, AppSource.fetch("2", "auto"));
        assertEquals(1, registrations.get());
    }

    @Test public void rejectionAndEmptyResponseMessagesDoNotBlameTheNetwork() {
        String refused = com.hongguo.android.DramaRepositoryKt.readableFailure(
                new AppSource.PlaybackRejectedException("播放服务拒绝请求（HTTP 403）"), "准备播放");
        assertTrue(refused.contains("403")); assertFalse(refused.contains("检查网络"));
        String empty = com.hongguo.android.DramaRepositoryKt.readableFailure(
                new AppSource.EmptyPlaybackResponseException(), "准备播放");
        assertTrue(empty.contains("空响应")); assertFalse(empty.contains("检查网络"));
    }

    @Test public void firstPlayAndNextEpisodeEachRecoverAnEmptyReplyInTheSameSession() throws Exception {
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            used.add(identity);
            if (used.size() % 2 == 1) throw new AppSource.EmptyPlaybackResponseException();
            return track;
        });
        AppSource.fetch("1", "auto"); AppSource.fetch("2", "auto");
        assertEquals(1, registrations.get());
        assertEquals(4, used.size());
        for (PlaybackDevice.Identity id : used) assertSame(used.get(0), id);
    }

    @Test public void persistentEmptyYieldsToCompatibilityAfterTwoReads() throws Exception {
        AtomicInteger singles = new AtomicInteger();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            singles.incrementAndGet(); throw new AppSource.EmptyPlaybackResponseException();
        });
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> null);
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        assertThrows(AppSource.EmptyPlaybackResponseException.class, () -> AppSource.fetch("1", "auto"));
        assertEquals(2, singles.get());
        assertEquals(2, registrations.get()); // One initial session and the existing compatibility route.
    }


    private void emptyCompatibilityRoutes() {
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> { throw new AppSource.EmptyPlaybackResponseException(); });
    }

    private final AppSource.SinglePlayback emptyReader = (vid, quality, identity, deadline) -> {
        throw new AppSource.EmptyPlaybackResponseException();
    };
    private final AppSource.PublicPlayback unexpectedGuest = (series, vid, deadline) -> {
        throw new AssertionError("Unexpected website request");
    };

    @Test public void emptyBodyWithOnlyTransientFailuresUsesGuestOnceWithinOriginalDeadline() throws Exception {
        List<String> order = new ArrayList<>();
        long[] deadlines = new long[2];
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            order.add("mobile"); deadlines[1] = deadline; throw new SocketTimeoutException();
        });
        AppSource.setAdditionalPlaybackForTest((vid, quality, identity, deadline) -> {
            order.add("first"); deadlines[0] = deadline; throw new AppSource.EmptyPlaybackResponseException();
        }, (vid, quality, identity, deadline) -> {
            order.add("legacy"); assertEquals(deadlines[1], deadline); throw new java.net.ConnectException();
        }, (series, vid, deadline) -> {
            order.add("guest"); assertEquals("99", series); assertEquals("1", vid);
            assertEquals(deadlines[0], deadline); return track;
        });
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> {
            order.add("get"); assertEquals(deadlines[1], deadline); throw new java.net.SocketException("网络连接中断");
        });
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> {
            order.add("post " + host); assertEquals(deadlines[1], deadline); throw new AppSource.EmptyPlaybackResponseException();
        });
        assertSame(track, AppSource.fetch("1", "auto", "99"));
        assertEquals(java.util.Arrays.asList("first", "mobile", "legacy", "get", "post " + AppSource.VIDEO_API_HOST,
                "post " + AppSource.RETRY_HOST, "guest"), order);
        assertEquals(TimeUnit.SECONDS.toNanos(6), deadlines[0] - deadlines[1]);
        assertEquals(1, registrations.get());
    }

    @Test public void compatibilitySuccessTakesPriorityOverEligibleWebsite() throws Exception {
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw new SocketTimeoutException(); });
        AppSource.setAdditionalPlaybackForTest(emptyReader, (vid, quality, identity, deadline) -> track, unexpectedGuest);
        assertSame(track, AppSource.fetch("1", "auto", "99"));
        assertEquals(1, registrations.get());
    }

    @Test public void lastCompatibilityRouteCanSucceedWithoutRegistrationOrGuest() throws Exception {
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw new SocketTimeoutException(); });
        AppSource.setAdditionalPlaybackForTest(emptyReader, emptyReader, unexpectedGuest);
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> {
            used.add(identity);
            if (AppSource.VIDEO_API_HOST.equals(host)) throw new AppSource.EmptyPlaybackResponseException();
            assertEquals(AppSource.RETRY_HOST, host);
            return com.hongguo.api.util.Json.parseObject("{\"code\":0,\"data\":{\"1\":{\"video_model\":{\"video_list\":["
                    + "{\"codec_type\":\"h264\",\"encrypt_info\":{\"encrypt\":false},"
                    + "\"main_url\":\"https://v1.qznovelvod.com/fixture.mp4\"}]}}}}");
        });
        assertEquals("https://v1.qznovelvod.com/fixture.mp4", AppSource.fetch("1", "auto", "99").url);
        assertEquals(1, registrations.get());
        assertEquals(2, used.size());
        assertSame(used.get(0), used.get(1));
    }

    @Test public void nativeOnlyFirstTabTimeoutKeepsPreviousStopBehavior() throws Exception {
        SocketTimeoutException timeout = new SocketTimeoutException("first tab");
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            throw new AssertionError("Native-only first-tab timeout previously stopped the chain");
        });
        AppSource.setAdditionalPlaybackForTest((vid, quality, identity, deadline) -> { throw timeout; }, null, unexpectedGuest);
        assertSame(timeout, assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto")));
    }

    @Test public void timeoutWithoutEmptyCannotSelectWebsite() throws Exception {
        SocketTimeoutException timeout = new SocketTimeoutException();
        AppSource.SinglePlayback offline = (vid, quality, identity, deadline) -> { throw timeout; };
        AppSource.setSinglePlaybackForTest(offline);
        AppSource.setAdditionalPlaybackForTest(offline, offline, unexpectedGuest);
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> { throw timeout; });
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> { throw timeout; });
        assertSame(timeout, assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto", "99")));
    }

    @Test public void missingSeriesDoesNotLookUpWebsite() throws Exception {
        emptyCompatibilityRoutes();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw new SocketTimeoutException(); });
        AppSource.setAdditionalPlaybackForTest(emptyReader, emptyReader, unexpectedGuest);
        assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto"));
        assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto", "invalid"));
    }

    @Test public void nonemptyMalformedMissingAndUnusableModelsAreNotEmptyResponses() throws Exception {
        emptyCompatibilityRoutes();
        for (String text : new String[]{" ", "{}", "{", "{\"code\":0,\"data\":{}}"}) {
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
                AppSource.parseMobileResponse(vid, text); return null;
            });
            AppSource.setAdditionalPlaybackForTest(emptyReader, null, unexpectedGuest);
            Exception failure = assertThrows(IllegalStateException.class, () -> AppSource.fetch("1", "auto", "99"));
            assertFalse(failure instanceof AppSource.EmptyPlaybackResponseException);
        }
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> null);
        AppSource.setAdditionalPlaybackForTest(emptyReader, null, unexpectedGuest);
        assertTrue(assertThrows(IllegalStateException.class, () -> AppSource.fetch("1", "auto", "99"))
                .getMessage().contains("模型"));
    }

    @Test public void hardFailureIsNotOverwrittenByLaterTimeoutOrEmpty() throws Exception {
        emptyCompatibilityRoutes();
        for (Exception hard : new Exception[]{new java.io.IOException("invalid transport"),
                new java.net.SocketException("unknown socket problem"), new AppSource.PlaybackHttpException(503)}) {
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw hard; });
            AppSource.setAdditionalPlaybackForTest(emptyReader,
                    (vid, quality, identity, deadline) -> { throw new SocketTimeoutException(); }, unexpectedGuest);
            assertSame(hard, assertThrows(Exception.class, () -> AppSource.fetch("1", "auto", "99")));
        }
    }

    @Test public void refusalAndCancellationStopAtFirstTabAndAfterAnEmpty() throws Exception {
        for (Exception terminal : new Exception[]{new AppSource.PlaybackHttpException(403),
                new AppSource.PlaybackRejectedException("backoff"), new InterruptedIOException("cancelled")}) {
            AppSource.SinglePlayback stopped = (vid, quality, identity, deadline) -> { throw terminal; };
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw new AssertionError("refusal ignored"); });
            AppSource.setAdditionalPlaybackForTest(stopped, null, unexpectedGuest);
            Class<? extends Exception> expected = terminal instanceof InterruptedIOException
                    ? InterruptedIOException.class : AppSource.PlaybackRejectedException.class;
            assertThrows(expected, () -> AppSource.fetch("1", "auto", "99"));
            AppSource.setSinglePlaybackForTest(stopped);
            AppSource.setAdditionalPlaybackForTest(emptyReader, null, unexpectedGuest);
            assertThrows(expected, () -> AppSource.fetch("1", "auto", "99"));
        }
    }

    @Test public void failedRegistrationCannotSelectWebsite() throws Exception {
        PlaybackDevice.resetForTest((url, headers, body) -> { throw new java.io.IOException("offline"); });
        AppSource.setAdditionalPlaybackForTest(emptyReader, null, unexpectedGuest);
        assertThrows(java.io.IOException.class, () -> AppSource.fetch("1", "auto", "99"));
    }

    @Test public void secondaryReadTimeoutAfterReceivingModelDisqualifiesWebsite() throws Exception {
        emptyCompatibilityRoutes();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> { throw new SocketTimeoutException(); });
        AppSource.setAdditionalPlaybackForTest(emptyReader, null, unexpectedGuest);
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> com.hongguo.api.util.Json.parseObject(
                "{\"code\":0,\"data\":{\"1\":{\"video_model\":{\"fallback_api\":\"https://www.fqnovel.com/test\"}}}}"));
        SocketTimeoutException modelFailure = new SocketTimeoutException("fixture model");
        AppSource.setPlaybackFallbackForTest((url, deadline) -> { throw modelFailure; });
        assertSame(modelFailure, assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto", "99")));
    }

    @Test public void emptyResponsesLeadToOneWebsiteRefusalWithoutRetry() throws Exception {
        emptyCompatibilityRoutes();
        AtomicInteger calls = new AtomicInteger();
        PublicEpisodeSource.GuestUnavailableException denied = new PublicEpisodeSource.GuestUnavailableException();
        AppSource.setSinglePlaybackForTest(emptyReader);
        AppSource.setAdditionalPlaybackForTest(emptyReader, emptyReader, (series, vid, deadline) -> {
            calls.incrementAndGet(); throw denied;
        });
        assertSame(denied, assertThrows(PublicEpisodeSource.GuestUnavailableException.class,
                () -> AppSource.fetch("1", "auto", "99")));
        assertEquals(1, calls.get());
    }

    @Test public void lateNativeSuccessCannotEscapeEpisodeDeadlineOrTriggerGuest() throws Exception {
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            TimeUnit.NANOSECONDS.sleep(Math.max(1, deadline - System.nanoTime()));
            return track;
        });
        AppSource.setAdditionalPlaybackForTest(null, null, unexpectedGuest);
        assertThrows(SocketTimeoutException.class, () -> AppSource.fetch("1", "auto", "99", 100));
    }

    @Test public void optionalSlowReadLeavesTimeForGuestUnderShortBudget() throws Exception {
        long[] limits = new long[2];
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            limits[1] = deadline;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
            throw new SocketTimeoutException();
        });
        AppSource.setAdditionalPlaybackForTest((vid, quality, identity, deadline) -> {
            limits[0] = deadline; throw new AppSource.EmptyPlaybackResponseException();
        }, (vid, quality, identity, deadline) -> { throw new AssertionError("expired optional read"); },
        (series, vid, deadline) -> {
            assertEquals(limits[0], deadline);
            assertTrue(deadline > System.nanoTime()); return track;
        });
        assertSame(track, AppSource.fetch("1", "auto", "99", 900));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300), limits[0] - limits[1]);
    }

    @Test public void realEmptyGzipThenValidGzipRecoversBothEpisodes() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(compressed)) {
                out.write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            for (int episode = 1; episode <= 2; episode++) {
                server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip").setBody(""));
                server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip")
                        .setBody(new okio.Buffer().write(compressed.toByteArray())));
            }
            server.start();
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
                Http.Req req = new Http.Req(server.url("/" + vid).toString()).method("POST").body(new byte[0]);
                Http.Resp response = Http.sendPlaybackMetadata(req, deadline);
                AppSource.checkPlaybackResponse(response);
                if (response.body.length == 0) throw new AppSource.EmptyPlaybackResponseException();
                assertEquals("ok", response.text());
                return track;
            });
            AppSource.fetch("1", "auto");
            AppSource.fetch("2", "auto");
            assertEquals(1, registrations.get());
            for (String path : new String[]{"/1", "/1", "/2", "/2"}) {
                okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals(path, request.getPath());
                assertEquals("gzip", request.getHeader("Accept-Encoding"));
            }
        }
    }

    @Test public void serverBackoffStopsRecoveryAndKeepsProvenSession() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int status : new int[]{200, 503, 429}) {
                server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "30")
                        .setHeader("Content-Encoding", "gzip").setBody("malformed body must never be read"));
            }
            server.start();
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
                if ("1".equals(vid)) return track;
                Http.Resp response = Http.sendPlaybackMetadata(new Http.Req(server.url("/" + vid).toString()), deadline);
                AppSource.checkPlaybackResponse(response);
                throw new AssertionError("Backoff treated as an empty reply");
            });
            for (int status : new int[]{200, 503, 429}) {
                AppSource.fetch("1", "auto");
                Exception rejection = assertThrows(AppSource.PlaybackRejectedException.class,
                        () -> AppSource.fetch("2", "auto"));
                assertTrue(rejection.getMessage().contains("HTTP " + status));
            }
            AppSource.fetch("1", "auto");
            assertEquals(1, registrations.get());
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void metadataGetReplaysSameRequestOnceAfterConnectionReset() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 2, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<List<String>> received = worker.submit(() -> {
                List<String> requests = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    try (java.net.Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        java.io.BufferedReader headers = new java.io.BufferedReader(new java.io.InputStreamReader(
                                socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                        String line;
                        StringBuilder request = new StringBuilder();
                        while ((line = headers.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                        requests.add(request.toString());
                        if (i == 0) {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\nx"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            Thread.sleep(75);
                            socket.setSoLinger(true, 0);  // reset an established response, after headers
                        } else {
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        }
                    }
                }
                return requests;
            });
            try {
                Http.Req req = new Http.Req("http://127.0.0.1:" + server.getLocalPort() + "/2")
                        .header("X-Request-ID", "episode2").timeout(2500);
                assertEquals("ok", Http.sendPlaybackMetadata(req, Long.MAX_VALUE).text());
                List<String> requests = received.get(3, TimeUnit.SECONDS);
                for (String request : requests) {
                    assertTrue(request.startsWith("GET /2 "));
                    assertTrue(request.toLowerCase(java.util.Locale.ROOT).contains("x-request-id: episode2"));
                }
            } finally { worker.shutdownNow(); }
        }
    }

    @Test public void corruptNonemptyGzipAndExpiredBudgetNeverReplay() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip").setBody("not gzip"));
            server.start();
            Http.Req req = new Http.Req(server.url("/1").toString());
            assertThrows(java.io.IOException.class, () -> Http.sendPlaybackMetadata(req, Long.MAX_VALUE));
            assertThrows(java.net.SocketTimeoutException.class, () -> Http.sendPlaybackMetadata(req, System.nanoTime() - 1));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void timeoutsKeepTheOriginalDeadlineAndInterruptionStopsTheNextRequest() throws Exception {
        List<Long> deadlines = new ArrayList<>();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            deadlines.add(deadline);
            throw new SocketTimeoutException("read timed out");
        });
        AppSource.setPlaybackTabForTest((vid, identity, deadline) -> {
            deadlines.add(deadline);
            throw new SocketTimeoutException("connect timed out");
        });
        InterruptedIOException cancelled = new InterruptedIOException("cancelled");
        AppSource.setPlaybackPostForTest((body, identity, host, deadline) -> {
            deadlines.add(deadline);
            throw cancelled;
        });
        long latestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        assertSame(cancelled, assertThrows(InterruptedIOException.class, () -> AppSource.fetch("1", "auto", 1000)));
        assertEquals(3, deadlines.size());
        assertTrue(deadlines.get(0) <= latestDeadline);
        for (long deadline : deadlines) assertEquals(deadlines.get(0).longValue(), deadline);
        assertEquals(1, registrations.get());
    }

    @Test public void metadataReadHonorsRemainingEpisodeTime() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeadersDelay(800, TimeUnit.MILLISECONDS).setBody("ok"));
            server.start();
            Http.Req req = new Http.Req(server.url("/metadata").toString()).timeout(8000);
            assertThrows(SocketTimeoutException.class, () -> Http.sendPlaybackMetadataOnce(req,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150)));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void supplementalRedirectWithRetryAfterStopsBeforeFollowingLocation() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Retry-After", "30")
                    .setHeader("Location", "/later"));
            server.enqueue(new MockResponse().setBody("unexpected redirect"));
            server.start();
            Http.Resp response = Http.sendPlaybackMetadataOnce(new Http.Req(server.url("/metadata").toString()),
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3));
            assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.checkPlaybackResponse(response));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void explicitHttpAndBusinessRejectionsDoNotRetryOrFallBack() throws Exception {
        for (int status : new int[]{401, 403, 429}) {
            AtomicInteger singles = new AtomicInteger();
            AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
                singles.incrementAndGet(); throw new AppSource.PlaybackHttpException(status);
            });
            assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("1", "auto"));
            assertEquals(1, singles.get());
        }
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            AppSource.parseSingleResponse(vid, "{\"code\":101144,\"data\":null}"); return track;
        });
        assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("1", "auto"));
    }

    @Test public void expiredBudgetStartsNoNewPlaybackRequests() throws Exception {
        assertThrows(java.io.IOException.class, () -> AppSource.fetch("1", "auto", 0));
        assertEquals(0, registrations.get());
        AtomicInteger singles = new AtomicInteger();
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            singles.incrementAndGet(); Thread.sleep(250); throw new AppSource.EmptyPlaybackResponseException();
        });
        assertThrows(AppSource.EmptyPlaybackResponseException.class, () -> AppSource.fetch("1", "auto", 200));
        assertEquals(1, singles.get()); assertEquals(1, registrations.get());
    }

    @Test public void cancellationDuringRecoveryDoesNotRegisterAgain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch empty = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            empty.countDown(); throw new AppSource.EmptyPlaybackResponseException();
        });
        try {
            Future<?> task = worker.submit(() -> {
                try { AppSource.fetch("1", "auto"); fail(); }
                catch (InterruptedIOException expected) { }
                catch (Exception other) { throw new AssertionError(other); }
                finally { stopped.countDown(); }
            });
            assertTrue(empty.await(3, TimeUnit.SECONDS)); task.cancel(true);
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            assertEquals(1, registrations.get());
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test public void staleFailureCannotEvictAnotherRequestsSuccessfulIdentity() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        List<PlaybackDevice.Identity> used = new ArrayList<>();

        AppSource.setSinglePlaybackForTest((vid, quality, identity, deadline) -> {
            if (vid.equals("2")) {
                waiting.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for new identity");
                throw new AppSource.PlaybackRejectedException("old refusal");
            }
            if (vid.equals("3")) throw new AppSource.PlaybackRejectedException("current refusal");
            used.add(identity); return track;
        });
        try {
            AppSource.fetch("1", "auto");
            Future<?> stale = worker.submit(() -> assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("2", "auto")));
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("3", "auto"));
            AppSource.fetch("4", "auto");
            release.countDown(); stale.get(3, TimeUnit.SECONDS);
            AppSource.fetch("5", "auto");
            assertEquals(2, registrations.get());
            assertNotSame(used.get(0), used.get(1));
            assertSame(used.get(1), used.get(2));
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
