package com.hongguo.api.media;

import com.hongguo.api.core.Mp4;
import com.hongguo.api.service.Stream;
import com.hongguo.api.util.Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/** Local synthetic media only: no upstream requests, identities, or content are used. */
public class StreamMediaTest {
    private Path root;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("hongguo-media-test-");
        Config.initialize("{}", root.toString(), "1000000000000000");
    }

    @After public void teardown() throws Exception {
        Thread.interrupted();
        if (root != null) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(p);
        }
    }

    @Test public void publishesCompletePlayableAndReusesOnlyFinalName() throws Exception {
        byte[] media = plainFixture();
        Path out = output();
        List<String> stages = new ArrayList<>();
        try (OneResponse origin = new OneResponse(media, media.length)) {
            String result = prepare(origin.url(), out, false, progress(stages, false));
            assertEquals(out.toString(), result);
        }
        assertArrayEquals(media, Files.readAllBytes(out));
        assertEquals(out.toString(), Stream.cachedPath("123", "best"));
        assertTrue(stages.contains("downloading"));
        assertTrue(stages.contains("processing"));
        assertEquals("ready", stages.get(stages.size() - 1));
        assertNoTemporaryFiles();
    }

    @Test public void truncatedDownloadNeverBecomesCacheHit() throws Exception {
        byte[] media = plainFixture();
        List<String> stages = new ArrayList<>();
        try (OneResponse origin = new OneResponse(media, media.length + 25)) {
            try {
                prepare(origin.url(), output(), false, progress(stages, false));
                fail("truncated media must fail");
            } catch (IOException expected) { }
        }
        assertFalse(Files.exists(output()));
        assertNull(Stream.cachedPath("123", "best"));
        assertFalse(stages.contains("ready"));
        assertNoTemporaryFiles();
    }

    @Test public void cancellationCleansIntermediatesAndDoesNotPublish() throws Exception {
        byte[] media = plainFixture();
        List<String> stages = new ArrayList<>();
        try (OneResponse origin = new OneResponse(media, media.length)) {
            try {
                prepare(origin.url(), output(), false, progress(stages, true));
                fail("cancelled media must fail");
            } catch (IOException expected) { }
        }
        assertFalse(Files.exists(output()));
        assertFalse(stages.contains("ready"));
        assertNoTemporaryFiles();
    }

    @Test public void invalidEncryptedTrackCannotLeaveCiphertextInPlayableCache() throws Exception {
        byte[] media = plainFixture();
        try (OneResponse origin = new OneResponse(media, media.length)) {
            try {
                prepare(origin.url(), output(), true, null);
                fail("an invalid synthetic key must fail");
            } catch (IOException expected) { }
        }
        assertFalse(Files.exists(output()));
        assertNull(Stream.cachedPath("123", "best"));
        assertNoTemporaryFiles();
    }

    @Test public void rawAndPartialFilesAreNeverRecognizedAsComplete() throws Exception {
        Path dir = output().getParent();
        Files.createDirectories(dir);
        for (String suffix : new String[]{".mp4", ".raw.mp4", ".play.tmp", ".enc.tmp"}) {
            Files.write(dir.resolve("123_best" + suffix), new byte[]{1, 2, 3});
        }
        assertNull(Stream.cachedPath("123", "best"));
    }

    @Test public void interruptedCallerStopsBeforeResolvingUpstream() throws Exception {
        Thread.currentThread().interrupt();
        try {
            Stream.prepare("123", "best", null);
            fail("interrupted caller must stop");
        } catch (java.io.InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertNull(Stream.cachedPath("123", "best"));
    }

    private Path output() { return root.resolve("stream-cache").resolve("123_best.play.mp4"); }

    private void assertNoTemporaryFiles() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(output().getParent())) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static Stream.Progress progress(List<String> stages, boolean cancelDuringDownload) {
        return new Stream.Progress() {
            boolean cancelled;
            public void update(String stage, long done, long total) {
                stages.add(stage);
                if (cancelDuringDownload && "downloading".equals(stage) && done > 0) cancelled = true;
            }
            public boolean isCancelled() { return cancelled; }
        };
    }

    private static String prepare(String url, Path out, boolean encrypted, Stream.Progress progress) throws Exception {
        Stream.VideoTrack track = new Stream.VideoTrack();
        track.url = url;
        track.encrypt = encrypted;
        track.spadeA = "AA=="; // deliberately invalid synthetic data
        Method method = Stream.class.getDeclaredMethod("prepareTrack", Stream.VideoTrack.class, Path.class, Stream.Progress.class);
        method.setAccessible(true);
        try { return (String) method.invoke(null, track, out, progress); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            throw e;
        }
    }

    private byte[] plainFixture() throws Exception {
        Class<?> fixture = Class.forName("com.hongguo.api.Mp4Fixture");
        Method build = fixture.getDeclaredMethod("buildMinimalEncryptedMp4");
        Method key = fixture.getDeclaredMethod("fixtureKeyHex");
        build.setAccessible(true);
        key.setAccessible(true);
        byte[] bytes = (byte[]) build.invoke(null);
        String fixtureKey = (String) key.invoke(null);
        Mp4.Analysis analysis = Mp4.analyzeEncryptedTracks(bytes);
        for (Mp4.Track track : analysis.tracks) {
            Mp4.decryptTrack(bytes, track, fixtureKey, Mp4.trackBaseIvCandidates(track).get(0));
        }
        Path raw = root.resolve("fixture.raw");
        Path plain = root.resolve("fixture.mp4");
        Files.write(raw, bytes);
        assertTrue(Mp4.stripEncryption(raw, plain));
        return Files.readAllBytes(plain);
    }

    /** One response from a loopback socket, so HTTP tests also run without desktop HTTP-server modules. */
    private static final class OneResponse implements AutoCloseable {
        final ServerSocket server;
        final Thread thread;
        volatile Throwable failure;
        OneResponse(byte[] bytes, int declaredLength) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + declaredLength + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(bytes);
                    out.flush();
                } catch (Throwable e) { if (!server.isClosed()) failure = e; }
            }, "synthetic-media-origin");
            thread.setDaemon(true);
            thread.start();
        }
        String url() { return "http://127.0.0.1:" + server.getLocalPort() + "/fixture.mp4"; }
        public void close() throws Exception {
            server.close();
            thread.join(4000);
            assertFalse("local HTTP fixture thread did not stop", thread.isAlive());
            if (failure != null) throw new AssertionError("local HTTP fixture failed", failure);
        }
    }
}
