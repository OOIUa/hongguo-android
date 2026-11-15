package com.hongguo.api.service;

import com.hongguo.api.util.Config;
import java.io.File;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
        AppSource.setPlaybackTabForTest((vid, identity) -> { throw new AssertionError("Unexpected legacy request"); });
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AssertionError("Unexpected legacy request"); });
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> track);
    }

    @After public void cleanup() {
        AppSource.setSinglePlaybackForTest(null); AppSource.setPlaybackTabForTest(null);
        AppSource.setPlaybackPostForTest(null); PlaybackDevice.resetForTest(null);
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

    @Test public void emptyReplyRenewsIdentityBeforeLegacyAndCachesTheSuccessfulReplacement() throws Exception {
        List<PlaybackDevice.Identity> used = new ArrayList<>();
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            used.add(identity);
            if (used.size() == 1) throw new AppSource.EmptyPlaybackResponseException();
            return track;
        });
        AppSource.fetch("1", "auto"); AppSource.fetch("2", "auto");
        assertEquals(2, registrations.get());
        assertNotSame(used.get(0), used.get(1));
        assertSame(used.get(1), used.get(2));
    }

    @Test public void persistentEmptyStopsAfterFiveAttempts() throws Exception {
        AtomicInteger singles = new AtomicInteger();
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            singles.incrementAndGet(); throw new AppSource.EmptyPlaybackResponseException();
        });
        AppSource.setPlaybackTabForTest((vid, identity) -> null);
        AppSource.setPlaybackPostForTest((body, identity, host) -> { throw new AppSource.EmptyPlaybackResponseException(); });
        assertThrows(AppSource.EmptyPlaybackResponseException.class, () -> AppSource.fetch("1", "auto"));
        assertEquals(5, singles.get());
        assertEquals(6, registrations.get()); // Five single attempts, then the existing one-time legacy renewal.
    }

    @Test public void explicitHttpAndBusinessRejectionsDoNotRetryOrFallBack() throws Exception {
        for (int status : new int[]{403, 429}) {
            AtomicInteger singles = new AtomicInteger();
            AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
                singles.incrementAndGet(); throw new AppSource.PlaybackHttpException(status);
            });
            assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("1", "auto"));
            assertEquals(1, singles.get());
        }
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            AppSource.parseSingleResponse(vid, "{\"code\":101144,\"data\":null}"); return track;
        });
        assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("1", "auto"));
    }

    @Test public void expiredBudgetStartsNoNewPlaybackRequests() throws Exception {
        assertThrows(java.io.IOException.class, () -> AppSource.fetch("1", "auto", 0));
        assertEquals(0, registrations.get());
        AtomicInteger singles = new AtomicInteger();
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            singles.incrementAndGet(); Thread.sleep(250); throw new AppSource.EmptyPlaybackResponseException();
        });
        assertThrows(AppSource.EmptyPlaybackResponseException.class, () -> AppSource.fetch("1", "auto", 200));
        assertEquals(1, singles.get()); assertEquals(1, registrations.get());
    }

    @Test public void cancellationDuringRecoveryDoesNotRegisterAgain() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch empty = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
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
        AtomicInteger renew = new AtomicInteger();
        AppSource.setSinglePlaybackForTest((vid, quality, identity) -> {
            if (vid.equals("2")) {
                waiting.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for new identity");
                throw new AppSource.PlaybackRejectedException("old refusal");
            }
            if (vid.equals("3") && renew.getAndIncrement() == 0) throw new AppSource.EmptyPlaybackResponseException();
            used.add(identity); return track;
        });
        try {
            AppSource.fetch("1", "auto");
            Future<?> stale = worker.submit(() -> assertThrows(AppSource.PlaybackRejectedException.class, () -> AppSource.fetch("2", "auto")));
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            AppSource.fetch("3", "auto");
            release.countDown(); stale.get(3, TimeUnit.SECONDS);
            AppSource.fetch("4", "auto");
            assertEquals(2, registrations.get());
            assertNotSame(used.get(0), used.get(1));
            assertSame(used.get(1), used.get(2));
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }
}
