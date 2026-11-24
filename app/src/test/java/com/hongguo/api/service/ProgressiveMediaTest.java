package com.hongguo.api.service;

import com.hongguo.api.core.Mp4;
import com.hongguo.api.core.Mp4Progressive;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Deterministic fake HTTP Range only: no upstream media, identities or network. */
public class ProgressiveMediaTest {
    private Path root;
    private ProgressiveMedia.Session session;
    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("progressive-test-");
        ProgressiveMedia.retryBaseMillis = 1;
    }
    @After public void cleanup() throws Exception {
        if (session != null) session.close();
        Thread.interrupted();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
        }
    }

    @Test public void opensWithoutWholeEpisodeAndSeeksOnlyRequestedBlocks() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 3 * 1024 * 1024));
        Origin origin = new Origin(media);
        session = open(origin, false, null);
        assertEquals(media.length, session.length());
        assertTrue("must be playable before whole episode is downloaded", session.downloadedBytes() < media.length / 2);
        assertFalse(session.isComplete());
        int initialRequests = origin.requests.get();
        ProgressiveMedia.Reader reader = session.newReader();
        long seek = 2 * 1024 * 1024L + 123;
        assertArrayEquals(Arrays.copyOfRange(media, (int) seek, (int) seek + 500), read(reader, seek, 500));
        assertEquals(initialRequests + 1, origin.requests.get());
        read(reader, seek, 200);
        assertEquals("repeated seek reuses the committed block", initialRequests + 1, origin.requests.get());
        reader.close();
        assertNoPlayableOrPartFiles();
    }

    @Test public void checksGuestDurationBeforeOpeningAndBeforeReusingGuestCache() throws Exception {
        byte[] media = clear(fixture(false));
        put32(media, typeAt(media, "mvhd") + 20, 30 * 90000);
        Stream.VideoTrack track = new Stream.VideoTrack();
        track.url = "https://main.qznovelvod.com/fixture";
        track.publicDurationSeconds = 31.0;
        session = ProgressiveMedia.openTrack(track, root, null, new Origin(media));
        session.close();
        track.publicDurationSeconds = 32.0;
        IOException mismatch = assertThrows(IOException.class,
                () -> ProgressiveMedia.openTrack(track, root, null, new Origin(media)));
        assertFalse(mismatch instanceof ProgressiveMedia.UnsupportedException);

        Config.initialize("{}", root.toString(), "1000000000000000");
        Path directory = Files.createDirectories(root.resolve("stream-cache"));
        Path guest = Files.write(directory.resolve("123_best.guest.play.mp4"), media);
        assertNull(Stream.cachedPath("123", "best"));
        assertEquals(guest.toString(), Stream.cachedPath("123", "best", 30.0));
        assertNull(Stream.cachedPath("123", "best", 32.0));
        Stream.checkPublicDuration(0.0, 11.01);
        assertThrows(IOException.class, () -> Stream.checkPublicDuration(0.0, 11));
    }

    @Test public void readsMovieDurationFromBothHeaderVersionsAndRejectsUnknownDuration() {
        for (int version : new int[]{0, 1}) {
            int length = version == 0 ? 36 : 48;
            ByteBuffer moov = ByteBuffer.allocate(length);
            moov.putInt(length).putInt(0x6d6f6f76).putInt(length - 8).putInt(0x6d766864);
            moov.put(16, (byte)version);
            moov.position(version == 0 ? 28 : 36);
            moov.putInt(1000);
            if (version == 0) moov.putInt(40500); else moov.putLong(40500);
            assertEquals(40.5, Mp4.analyzeEncryptedTracks(moov.array()).movieDurationSeconds, 0);
            if (version == 0) moov.putInt(32, -1); else moov.putLong(40, -1);
            assertTrue(Double.isNaN(Mp4.analyzeEncryptedTracks(moov.array()).movieDurationSeconds));
        }
    }

    @Test public void decryptsOnlyWholeSamplesAndSlicesArbitrarySeeksWithUnchangedOffsets() throws Exception {
        byte[] encrypted = expandMdat(fixture(false), 2 * 1024 * 1024);
        byte[] expected = clear(encrypted);
        Origin origin = new Origin(encrypted);
        session = open(origin, true, null);
        assertTrue(session.downloadedBytes() < encrypted.length);
        Mp4.Analysis analysis = Mp4.analyzeEncryptedTracks(encrypted);
        Mp4.Track track = analysis.tracks.get(0);
        try (ProgressiveMedia.Reader reader = session.newReader()) {
            int sample = (int) track.offsets[0];
            assertArrayEquals(Arrays.copyOfRange(expected, sample + 3, sample + 20), read(reader, sample + 3, 17));
            assertArrayEquals(Arrays.copyOfRange(expected, sample - 4, sample + 10), read(reader, sample - 4, 14));
            assertArrayEquals(Arrays.copyOfRange(expected, 0, sample + 30), read(reader, 0, sample + 30));
        }
        assertEquals(encrypted.length, expected.length);
    }

    @Test public void tailMoovUsesSparseIndexRangesAndWorksBeforeFullDownload() throws Exception {
        byte[] encrypted = expandMdat(fixture(true), 3 * 1024 * 1024);
        byte[] expected = clear(encrypted);
        Origin origin = new Origin(encrypted);
        session = open(origin, true, null);
        assertTrue(session.downloadedBytes() < encrypted.length / 2);
        Mp4.Box moov = moov(encrypted);
        assertArrayEquals(Arrays.copyOfRange(expected, (int) moov.start, (int) moov.end()),
                read(session.newReader(), moov.start, (int) moov.size));
        assertFalse(session.isComplete());
    }

    @Test public void closingOneBlockedReaderImmediatelyCancelsItAndLeavesSessionUsable() throws Exception {
        Origin origin = new Origin(clear(expandMdat(fixture(false), 2 * 1024 * 1024)));
        session = open(origin, false, null);
        origin.gatedBlock = 1024 * 1024;
        ProgressiveMedia.Reader old = session.newReader();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> cancelled = worker.submit(() -> {
                try { old.read(1024 * 1024, new byte[32], 0, 32); return false; }
                catch (InterruptedIOException expected) { return true; }
            });
            assertTrue(origin.entered.await(2, TimeUnit.SECONDS));
            old.close();
            assertTrue(cancelled.get(2, TimeUnit.SECONDS));
            origin.gatedBlock = -1;
            try (ProgressiveMedia.Reader replacement = session.newReader()) {
                assertEquals(32, replacement.read(1024 * 1024, new byte[32], 0, 32));
            }
        } finally { old.close(); worker.shutdownNow(); }
    }

    @Test public void openingCancellationDoesNotRemainBoundToFinishedJob() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Stream.Progress progress = new Stream.Progress() {
            public void update(String stage, long done, long total) {}
            public boolean isCancelled() { return cancelled.get(); }
        };
        Origin origin = new Origin(clear(expandMdat(fixture(false), 2 * 1024 * 1024)));
        session = open(origin, false, progress);
        cancelled.set(true);
        assertEquals(16, session.read(1024 * 1024, new byte[16], 0, 16));
        session.close();
        try { session.newReader(); fail(); } catch (IOException expected) { }
        session = null;
        try { open(origin, false, progress); fail(); } catch (InterruptedIOException expected) { }
    }

    @Test public void rejects200WrongRangeTruncationAndChangedRemoteVersion() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin origin = new Origin(media); origin.status = 200;
        try { open(origin, false, null); fail(); } catch (ProgressiveMedia.UnsupportedException expected) { }
        origin.status = 206; origin.wrongRange = true;
        try { open(origin, false, null); fail(); } catch (ProgressiveMedia.UnsupportedException expected) { }
        origin.wrongRange = false; origin.truncated = true;
        try { open(origin, false, null); fail(); } catch (IOException expected) { }
        origin.truncated = false;
        session = open(origin, false, null);
        origin.total = media.length + 1;
        try { session.read(1024 * 1024, new byte[16], 0, 16); fail(); } catch (IOException expected) { }
        assertFalse(session.isComplete());
        assertNoPlayableOrPartFiles();
    }

    @Test public void cdnNodeSwitchWithDifferentOrMissingETagKeepsPlaying() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin origin = new Origin(media);
        session = open(origin, false, null);
        origin.etag = null;
        byte[] got = new byte[16];
        assertEquals(16, session.read(1024 * 1024, got, 0, 16));
        assertArrayEquals(Arrays.copyOfRange(media, 1024 * 1024, 1024 * 1024 + 16), got);
        origin.etag = "\"other-node\"";
        assertEquals(16, session.read(1536 * 1024, got, 0, 16));
        assertNull("If-Range makes a node without the tag answer 200", origin.lastIfRange);
    }

    @Test public void transientRangeFailuresAreRetriedButPersistentOnesStillFail() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin origin = new Origin(media);
        session = open(origin, false, null);
        origin.failNext.set(2);
        byte[] got = new byte[16];
        assertEquals(16, session.read(1024 * 1024, got, 0, 16));
        assertArrayEquals(Arrays.copyOfRange(media, 1024 * 1024, 1024 * 1024 + 16), got);
        origin.failNext.set(100);
        try { session.read(1792 * 1024, got, 0, 16); fail(); } catch (IOException expected) { }
        assertTrue(origin.failNext.get() <= 100 - ProgressiveMedia.FETCH_ATTEMPTS);
    }

    @Test public void sameRepresentationMirrorSuppliesOnlyTheMissingRangeAndStaysActive() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.reprDigest = backup.reprDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        primary.status = 503;
        try (ProgressiveMedia.Reader reader = session.newReader()) {
            int seek = 1024 * 1024 + 41;
            assertArrayEquals(Arrays.copyOfRange(media, seek, seek + 32), read(reader, seek, 32));
            assertEquals(1, backup.requests.get());
            int afterSwitch = primary.requests.get();
            assertArrayEquals(Arrays.copyOfRange(media, 0, 8), read(reader, 0, 8));
            assertEquals(1, backup.requests.get());
            read(reader, 1536 * 1024, 16);
            assertEquals(afterSwitch, primary.requests.get());
            assertEquals(2, backup.requests.get());
        }
    }

    @Test public void openingBeforeAnyBytesAllowsAnIndependentMirrorWithoutDigest() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.status = 502;
        session = openMirrors(primary, backup);
        assertEquals(1, primary.requests.get());
        assertEquals(media.length, session.length());
        assertTrue(backup.requests.get() > 1);
    }

    @Test public void rejectsDifferentOrUnverifiedRepresentationsWithoutCommittingTheirBytes() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.reprDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        long original = session.downloadedBytes();
        primary.status = 503;
        for (String wrong : Arrays.asList(null, reprDigest(new byte[1]), "sha-256=:AAAA:")) {
            backup.reprDigest = wrong;
            try { session.read(1024 * 1024, new byte[16], 0, 16); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("校验")); }
            assertEquals(original, session.downloadedBytes());
        }
        backup.reprDigest = primary.reprDigest;
        backup.total = media.length + 1L;
        try { session.read(1024 * 1024, new byte[16], 0, 16); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("长度")); }
        assertEquals(original, session.downloadedBytes());
    }

    @Test public void denialsAndDeferredMaintenanceNeverContactTheMirror() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.contentDigest = backup.contentDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        primary.status = 503;
        try { session.read(1024 * 1024, new byte[16], 0, 16); fail(); } catch (IOException expected) { }
        assertEquals(0, backup.requests.get());
        session.close();
        primary.status = 206;
        primary.reprDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        for (int status : new int[] {401, 403, 429, 503}) {
            primary.status = status;
            if (status == 503) primary.retryAfter = "30";
            int before = primary.requests.get();
            try { session.read(1024 * 1024, new byte[16], 0, 16); fail(); } catch (IOException expected) { }
            assertEquals(before + 1, primary.requests.get());
            assertEquals(0, backup.requests.get());
        }
    }

    /**
     * 落地域不在白名单、但地址形状正常（https + 443）时，属于 CDN 按省份/边缘调度的常见情况：
     * 应该换备用源继续读，而不是整集终态失败；同时绝不对不受信主机发请求。
     * 见 v0.7.34 的 trustedMediaUri 白名单（真机 1080P 主路曾因此直接弹失败）。
     */
    @Test public void untrustedButWellFormedRedirectFallsBackToTheMirrorWithoutContactingIt() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.contentDigest = backup.contentDigest = reprDigest(media);
        primary.reprDigest = backup.reprDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        session.read(1024 * 1024, new byte[16], 0, 16);
        int before = primary.requests.get();
        primary.status = 302;
        primary.location = "https://unexpected.invalid/file.mp4";
        session.read(1024 * 1024 + 256 * 1024, new byte[16], 0, 16);
        assertEquals(before + 1, primary.requests.get());
        assertTrue("mirror serves the block after the landing host is rejected", backup.requests.get() >= 1);
    }

    /** 形状不正常的跳转（明文降级）仍然是终态失败，且不触碰备用镜像。 */
    @Test public void malformedRedirectStaysTerminalAndNeverContactsTheMirror() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.contentDigest = backup.contentDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        session.read(1024 * 1024, new byte[16], 0, 16);
        int before = primary.requests.get();
        primary.status = 302;
        primary.location = "http://unexpected.invalid/file.mp4";
        try { session.read(1024 * 1024 + 256 * 1024, new byte[16], 0, 16); fail(); }
        catch (IOException expected) { }
        assertEquals(before + 1, primary.requests.get());
        assertEquals(0, backup.requests.get());
    }

    @Test public void cancellingAStalledPrimaryReadDoesNotActivateTheMirror() throws Exception {
        byte[] media = clear(expandMdat(fixture(false), 2 * 1024 * 1024));
        Origin primary = new Origin(media), backup = new Origin(media);
        primary.reprDigest = backup.reprDigest = reprDigest(media);
        session = openMirrors(primary, backup);
        primary.gatedBlock = 1024 * 1024;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ProgressiveMedia.Reader reader = session.newReader()) {
            Future<Boolean> result = worker.submit(() -> {
                try { reader.read(primary.gatedBlock, new byte[16], 0, 16); return false; }
                catch (InterruptedIOException expected) { return true; }
            });
            assertTrue(primary.entered.await(2, TimeUnit.SECONDS));
            reader.close();
            assertTrue(result.get(2, TimeUnit.SECONDS));
            assertEquals(0, backup.requests.get());
        } finally { worker.shutdownNow(); }
    }

    private static String reprDigest(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        return "sha-256=:" + Base64.getEncoder().encodeToString(hash) + ":";
    }

    private ProgressiveMedia.Session openMirrors(Origin primary, Origin backup) throws Exception {
        Stream.VideoTrack track = new Stream.VideoTrack();
        track.url = "https://main.qznovelvod.com/fixture";
        track.backupUrls.add("https://backup.fqnovelvod.com/fixture");
        return ProgressiveMedia.openTrack(track, root, null, uri -> {
            if (uri.getHost().equals("main.qznovelvod.com")) return primary.open(uri);
            if (uri.getHost().equals("backup.fqnovelvod.com")) return backup.open(uri);
            throw new AssertionError("unexpected origin");
        });
    }

    @Test public void corruptBlockIsRefetchedAndAbandonedCacheIsNeverReused() throws Exception {
        Path abandoned = Files.createDirectories(root.resolve("p1-abandoned"));
        Files.write(abandoned.resolve("0.range"), new byte[300]);
        Origin origin = new Origin(clear(expandMdat(fixture(false), 1024 * 1024)));
        session = open(origin, false, null);
        assertFalse(Files.exists(abandoned));
        Path block;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            block = paths.filter(p -> p.getFileName().toString().equals("0.range")).findFirst().get();
        }
        byte[] damaged = Files.readAllBytes(block); damaged[0] ^= 1; Files.write(block, damaged);
        int requests = origin.requests.get();
        assertArrayEquals(Arrays.copyOfRange(origin.bytes, 0, 8), read(session.newReader(), 0, 8));
        assertEquals(requests + 1, origin.requests.get());
        session.close(); session = null;
        try (java.util.stream.Stream<Path> paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test public void completionRequiresEverySourceBlockAndCloseDeletesAllRanges() throws Exception {
        byte[] expected = clear(expandMdat(fixture(false), 1024 * 1024));
        Origin origin = new Origin(expected);
        session = open(origin, false, null);
        try (ProgressiveMedia.Reader reader = session.newReader()) {
            assertArrayEquals(expected, read(reader, 0, expected.length));
            assertEquals(-1, reader.read(expected.length, new byte[1], 0, 1));
        }
        assertEquals(expected.length, session.downloadedBytes());
        assertTrue(session.isComplete());
        session.close(); assertFalse(session.isComplete()); session = null;
        try (java.util.stream.Stream<Path> paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test public void rejectsSubsampleAndOversizeTableBeforeAllocatingSampleIndex() throws Exception {
        byte[] encrypted = fixture(false);
        byte[] subsample = encrypted.clone();
        int senc = typeAt(subsample, "senc"); subsample[senc + 7] = 2;
        try { open(new Origin(subsample), true, null); fail(); } catch (ProgressiveMedia.UnsupportedException expected) { }
        byte[] hugeCount = encrypted.clone(); int stsz = typeAt(hugeCount, "stsz");
        put32(hugeCount, stsz + 12, 0x7fffffff);
        try { open(new Origin(hugeCount), true, null); fail(); } catch (ProgressiveMedia.UnsupportedException expected) { }
        assertNoPlayableOrPartFiles();
    }

    @Test public void preservesRecoveryAndPrerollGroupsWhileDecryptingAtOriginalOffsets() throws Exception {
        for (String grouping : new String[]{"roll", "prol"}) {
            byte[] encrypted = withSampleGroups(fixture(false), grouping);
            byte[] expected = clear(encrypted);
            Origin origin = new Origin(encrypted);
            session = open(origin, true, null);
            try (ProgressiveMedia.Reader reader = session.newReader()) {
                assertArrayEquals(expected, read(reader, 0, expected.length));
            }
            int sgpd = typeAt(expected, "sgpd"), sbgp = typeAt(expected, "sbgp");
            assertEquals(grouping, new String(expected, sgpd + 8, 4, StandardCharsets.ISO_8859_1));
            assertEquals(grouping, new String(expected, sbgp + 8, 4, StandardCharsets.ISO_8859_1));
            assertEquals(encrypted.length, session.length());
            session.close(); session = null;
        }
    }

    @Test public void rejectsEncryptionOverrideGroupsAndRetainsSafeParserReason() throws Exception {
        try {
            open(new Origin(withSampleGroups(fixture(false), "seig")), true, null);
            fail("Encryption override groups require a separate supported cipher layout");
        } catch (ProgressiveMedia.UnsupportedException expected) {
            assertNotNull(expected.getCause());
            assertEquals("MP4_ENCRYPTION_SAMPLE_GROUP", expected.getCause().getMessage());
        }
        assertNoPlayableOrPartFiles();
    }

    private ProgressiveMedia.Session open(Origin origin, boolean encrypted, Stream.Progress progress) throws Exception {
        Stream.VideoTrack track = new Stream.VideoTrack();
        track.url = "https://media.invalid/test.mp4?signature=private-fixture"; track.encrypt = encrypted;
        String key = fixtureKey();
        return ProgressiveMedia.openTrack(track, root, progress, origin, ignored -> key);
    }
    private static byte[] read(ProgressiveMedia.Reader reader, long position, int count) throws IOException {
        byte[] bytes = new byte[count]; int done = 0;
        while (done < count) { int n = reader.read(position + done, bytes, done, count - done); if (n <= 0) throw new IOException("short fixture"); done += n; }
        return bytes;
    }
    private void assertNoPlayableOrPartFiles() throws Exception {
        try (java.util.stream.Stream<Path> files = Files.walk(root)) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".mp4") || p.toString().endsWith(".part")));
        }
    }
    private static byte[] fixture(boolean tail) throws Exception {
        Class<?> type = Class.forName("com.hongguo.api.Mp4Fixture");
        Method method = type.getDeclaredMethod(tail ? "buildTailMoovEncryptedMp4" : "buildMinimalEncryptedMp4");
        method.setAccessible(true); return (byte[]) method.invoke(null);
    }
    private static String fixtureKey() throws Exception {
        Method method = Class.forName("com.hongguo.api.Mp4Fixture").getDeclaredMethod("fixtureKeyHex");
        method.setAccessible(true); return (String) method.invoke(null);
    }
    private static Mp4.Box moov(byte[] media) {
        return Mp4.parseBoxes(media, 0, media.length).stream().filter(b -> "moov".equals(b.type)).findFirst().get();
    }
    private static byte[] clear(byte[] encrypted) throws Exception {
        Mp4.Box moov = moov(encrypted);
        Mp4Progressive.Index index = Mp4Progressive.inspect(Arrays.copyOfRange(encrypted, (int) moov.start, (int) moov.end()), moov.start, true);
        byte[] expected = encrypted.clone();
        System.arraycopy(index.clearMoov, 0, expected, (int) moov.start, index.clearMoov.length);
        for (Mp4.Track track : index.analysis.tracks) for (int i = 0; i < track.sampleCount; i++) {
            int at = (int) track.offsets[i], size = (int) track.sizes[i];
            byte[] plain = Crypto.aes128Ctr(Crypto.unhex(fixtureKey()), Mp4.counterFor(track, null, i), Arrays.copyOfRange(encrypted, at, at + size));
            System.arraycopy(plain, 0, expected, at, size);
        }
        return expected;
    }
    private static byte[] expandMdat(byte[] original, int padding) {
        Mp4.Box mdat = Mp4.parseBoxes(original, 0, original.length).stream().filter(b -> "mdat".equals(b.type)).findFirst().get();
        byte[] result = new byte[original.length + padding]; int end = (int) mdat.end();
        System.arraycopy(original, 0, result, 0, end);
        System.arraycopy(original, end, result, end + padding, original.length - end);
        put32(result, (int) mdat.start, (int) mdat.size + padding);
        return result;
    }
    private static byte[] withSampleGroups(byte[] original, String grouping) {
        // Add standard recovery groups to stbl, grow ancestor boxes and shift downstream chunk offsets.
        Mp4.Box moov = moov(original), trak = moov.child("trak"), mdia = trak.child("mdia"), minf = mdia.child("minf"), stbl = minf.child("stbl");
        byte[] groups = new byte[54];
        put32(groups, 0, 26); System.arraycopy("sgpd".getBytes(StandardCharsets.ISO_8859_1), 0, groups, 4, 4);
        groups[8] = 1; System.arraycopy(grouping.getBytes(StandardCharsets.ISO_8859_1), 0, groups, 12, 4);
        put32(groups, 16, 2); put32(groups, 20, 1); groups[24] = (byte) 255; groups[25] = (byte) 255;
        put32(groups, 26, 28); System.arraycopy("sbgp".getBytes(StandardCharsets.ISO_8859_1), 0, groups, 30, 4);
        System.arraycopy(grouping.getBytes(StandardCharsets.ISO_8859_1), 0, groups, 38, 4);
        put32(groups, 42, 1); put32(groups, 46, 2); put32(groups, 50, 1);
        int at = (int) stbl.end(); byte[] result = new byte[original.length + groups.length];
        System.arraycopy(original, 0, result, 0, at); System.arraycopy(groups, 0, result, at, groups.length);
        System.arraycopy(original, at, result, at + groups.length, original.length - at);
        for (Mp4.Box ancestor : new Mp4.Box[]{moov, trak, mdia, minf, stbl}) put32(result, (int) ancestor.start, (int) ancestor.size + groups.length);
        Mp4.Box stco = stbl.child("stco");
        int countAt = (int) stco.payloadStart() + 4;
        int count = ((original[countAt] & 255) << 24) | ((original[countAt + 1] & 255) << 16)
                | ((original[countAt + 2] & 255) << 8) | (original[countAt + 3] & 255);
        for (int i = 0; i < count; i++) {
            int p = countAt + 4 + i * 4;
            int old = ((original[p] & 255) << 24) | ((original[p + 1] & 255) << 16)
                    | ((original[p + 2] & 255) << 8) | (original[p + 3] & 255);
            put32(result, p, old >= at ? old + groups.length : old);
        }
        return result;
    }
    private static int typeAt(byte[] bytes, String type) {
        byte[] needle = type.getBytes(StandardCharsets.ISO_8859_1);
        outer: for (int i = 0; i < bytes.length - 4; i++) {
            for (int j = 0; j < 4; j++) if (bytes[i + j] != needle[j]) continue outer;
            return i;
        }
        throw new AssertionError("fixture missing box");
    }
    private static void put32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24); bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8); bytes[at + 3] = (byte) value;
    }

    private static final class Origin implements ProgressiveMedia.ConnectionFactory {
        final byte[] bytes;
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        volatile int status = 206, gatedBlock = -1;
        volatile boolean wrongRange, truncated;
        volatile String etag = "\"fixture-v1\"";
        volatile String lastIfRange;
        volatile String reprDigest, contentDigest, location, retryAfter;
        volatile long total = -1;
        final AtomicInteger failNext = new AtomicInteger();
        Origin(byte[] bytes) { this.bytes = bytes; }
        public HttpURLConnection open(URI uri) throws IOException {
            requests.incrementAndGet();
            return new HttpURLConnection(uri.toURL()) {
                volatile boolean disconnected;
                long start, end;
                public void connect() {}
                public boolean usingProxy() { return false; }
                public void disconnect() { disconnected = true; synchronized (this) { notifyAll(); } }
                public int getResponseCode() throws IOException {
                    lastIfRange = getRequestProperty("If-Range");
                    if (failNext.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new IOException("fixture reset");
                    String[] limits = getRequestProperty("Range").substring(6).split("-");
                    start = Long.parseLong(limits[0]); end = Long.parseLong(limits[1]); return status;
                }
                public String getHeaderField(String name) {
                    if ("Content-Range".equals(name)) return "bytes " + (wrongRange ? start + 1 : start) + "-" + end + "/" + (total > 0 ? total : bytes.length);
                    if ("ETag".equals(name)) return etag;
                    if ("Repr-Digest".equals(name)) return reprDigest;
                    if ("Content-Digest".equals(name)) return contentDigest;
                    if ("Retry-After".equals(name)) return retryAfter;
                    if ("Location".equals(name)) return location;
                    return null;
                }
                public long getContentLengthLong() { return end - start + 1; }
                public InputStream getInputStream() {
                    byte[] payload = Arrays.copyOfRange(bytes, (int) start, (int) end + (truncated ? 0 : 1));
                    return new InputStream() {
                        final ByteArrayInputStream data = new ByteArrayInputStream(payload);
                        private void gate() throws IOException {
                            if (gatedBlock >= start && gatedBlock <= end) {
                                entered.countDown();
                                synchronized (Origin.this) {
                                    while (!disconnected) {
                                        try { Origin.this.wait(20); }
                                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                                    }
                                }
                                throw new IOException("fixture disconnected");
                            }
                        }
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            gate(); return data.read(b, off, len);
                        }
                        @Override public int read() throws IOException { gate(); return data.read(); }
                    };
                }
            };
        }
    }
}
