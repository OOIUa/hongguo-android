package com.hongguo.api.service;

import com.hongguo.api.core.Mp4;
import com.hongguo.api.core.Spade;
import com.hongguo.api.util.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Android local media preparation. Call blocking methods from an IO worker. */
public final class Stream {
    private Stream() {}

    public interface Progress {
        /** Stages: waiting, resolving, downloading, processing, ready, cached. Unknown total is -1. */
        void update(String stage, long done, long total);
        boolean isCancelled();
    }

    private static final long CACHE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = CACHE_BYTES;
    private static final long MAX_MOOV_BYTES = 32L * 1024 * 1024;
    private static final Object CACHE_GUARD = new Object();
    private static final Map<String, Integer> PINS = new HashMap<>();
    private static final Set<String> INITIALIZED_DIRS = new HashSet<>();
    private static final Map<String, KeyLock> LOCKS = new HashMap<>();
    private static final Semaphore PREPARATIONS = new Semaphore(2, true);

    private static final class KeyLock {
        final ReentrantLock lock = new ReentrantLock(true);
        int users;
    }

    /** Contract consumed by the original AppSource. */
    public static final class VideoTrack {
        public String url;
        public String spadeA;
        public boolean encrypt;
        public String definition = "?";
        public long size;
    }

    public static VideoTrack videoTrack(String vid, String quality) throws Exception {
        return AppSource.fetch(vid, quality);
    }

    private static Path cacheDir() {
        String root = Config.str("runtime.data_dir", null);
        if (root == null || root.trim().isEmpty()) {
            throw new IllegalStateException("媒体存储尚未初始化");
        }
        return Paths.get(root).resolve("stream-cache").toAbsolutePath().normalize();
    }

    private static String qualityOf(String quality) {
        String q = quality == null ? "best" : quality.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || "auto".equals(q)) return "best";
        if ("best".equals(q)) return q;
        if (!q.matches("[1-9][0-9]{1,4}p?")) {
            throw new IllegalArgumentException("清晰度无效");
        }
        return q.endsWith("p") ? q : q + "p";
    }

    private static Path outputPath(String vid, String quality) {
        if (vid == null || !vid.matches("[0-9]{1,30}")) {
            throw new IllegalArgumentException("分集编号无效");
        }
        return cacheDir().resolve(vid + "_" + qualityOf(quality) + ".play.mp4");
    }

    private static void initializeCache(Path dir) throws IOException {
        synchronized (CACHE_GUARD) {
            Files.createDirectories(dir);
            if (INITIALIZED_DIRS.contains(dir.toString())) return;
            // A killed process can leave intermediates. Never recognize them as playable files.
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path p : files.toArray(Path[]::new)) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".tmp")) {
                        deleteQuietly(p);
                    }
                }
            }
            INITIALIZED_DIRS.add(dir.toString());
        }
    }

    /** Only atomically published .play.mp4 files are cache hits. */
    public static String cachedPath(String vid, String quality) {
        Path out = outputPath(vid, quality);
        synchronized (CACHE_GUARD) {
            try {
                if (Files.isRegularFile(out) && Files.size(out) > 0) return out.toString();
            } catch (IOException ignored) { }
        }
        return null;
    }

    public static String ensureDecrypted(String vid, String quality) throws Exception {
        return prepare(vid, quality, null);
    }

    /**
     * Resolve, download and prepare one complete MP4. Nothing is published on failure/cancellation.
     * Calls for the same vid/quality serialize and reuse the first successful result.
     */
    public static String prepare(String vid, String quality, Progress progress) throws Exception {
        Path out = outputPath(vid, quality);
        String q = qualityOf(quality);
        initializeCache(out.getParent());
        checkCancelled(progress);
        String key = out.toString();
        KeyLock keyed;
        synchronized (LOCKS) {
            keyed = LOCKS.get(key);
            if (keyed == null) {
                keyed = new KeyLock();
                LOCKS.put(key, keyed);
            }
            keyed.users++;
        }
        boolean locked = false;
        boolean slot = false;
        try {
            update(progress, "waiting", 0, -1);
            while (!(locked = keyed.lock.tryLock(200, TimeUnit.MILLISECONDS))) checkCancelled(progress);
            checkCancelled(progress);
            String hit = cachedPath(vid, q);
            if (hit != null) {
                touch(out);
                update(progress, "cached", Files.size(out), Files.size(out));
                return hit;
            }
            while (!(slot = PREPARATIONS.tryAcquire(200, TimeUnit.MILLISECONDS))) checkCancelled(progress);
            checkCancelled(progress);
            pruneCache(null);
            update(progress, "resolving", 0, -1);
            VideoTrack track = videoTrack(vid, q);
            checkCancelled(progress);
            if (track == null || track.url == null || track.url.isEmpty()) {
                throw new IOException("暂时无法获取本集播放信息");
            }
            return prepareTrack(track, out, progress);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw cancelled();
        } finally {
            if (slot) PREPARATIONS.release();
            if (locked) keyed.lock.unlock();
            synchronized (LOCKS) {
                keyed.users--;
                if (keyed.users == 0) LOCKS.remove(key);
            }
        }
    }

    // Package-private to allow synthetic media smoke tests without an upstream request.
    static String prepareTrack(VideoTrack track, Path out, Progress progress) throws Exception {
        Path dir = out.toAbsolutePath().normalize().getParent();
        initializeCache(dir);
        Path encrypted = null;
        Path raw = null;
        Path playable = null;
        try {
            encrypted = Files.createTempFile(dir, "media-", ".enc.tmp");
            playable = Files.createTempFile(dir, "media-", ".play.tmp");
            download(track.url, encrypted, progress);
            checkCancelled(progress);
            update(progress, "processing", 0, -1);
            if (track.encrypt) {
                raw = Files.createTempFile(dir, "media-", ".raw.tmp");
                decryptSamples(track.spadeA, encrypted, raw, progress);
                checkCancelled(progress);
                if (!Mp4.stripEncryption(raw, playable)) {
                    throw new IOException("本集媒体封装暂不支持，请尝试其他清晰度");
                }
            } else {
                // Unencrypted tracks still must be a complete MP4 before they become cache hits.
                validateMp4(encrypted);
                moveAtomically(encrypted, playable);
            }
            checkCancelled(progress);
            validateMp4(playable);
            if (Files.size(playable) > CACHE_BYTES) throw new IOException("本集文件超出缓存容量限制");
            synchronized (CACHE_GUARD) {
                checkCancelled(progress);
                moveAtomically(playable, out);
                touch(out);
                pruneCache(out);
            }
            long size = Files.size(out);
            update(progress, "ready", size, size);
            return out.toAbsolutePath().normalize().toString();
        } finally {
            deleteQuietly(encrypted);
            deleteQuietly(raw);
            deleteQuietly(playable);
        }
    }

    /** True streaming download, 64KB buffer. Interrupted/cancelled reads leave no cache entry. */
    public static long download(String url, Path dest) throws IOException {
        return download(url, dest, null);
    }

    private static long download(String url, Path dest, Progress progress) throws IOException {
        checkCancelled(progress);
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) {
            throw new IOException("媒体地址无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("媒体地址协议无效");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Accept-Encoding", "identity");
        boolean complete = false;
        try {
            int status = connection.getResponseCode();
            checkCancelled(progress);
            if (status != 200) throw new IOException("媒体下载失败（HTTP " + status + "）");
            long total = connection.getContentLengthLong();
            if (total > MAX_MEDIA_BYTES) throw new IOException("本集文件超出缓存容量限制");
            update(progress, "downloading", 0, total > 0 ? total : -1);
            long got = 0;
            long lastProgress = 0;
            try (InputStream in = connection.getInputStream(); OutputStream target = Files.newOutputStream(dest)) {
                byte[] buffer = new byte[65536];
                for (;;) {
                    checkCancelled(progress);
                    int n = in.read(buffer);
                    if (n < 0) break;
                    if (n == 0) continue;
                    checkCancelled(progress);
                    got += n;
                    if (got > MAX_MEDIA_BYTES) throw new IOException("本集文件超出缓存容量限制");
                    target.write(buffer, 0, n);
                    long now = System.nanoTime();
                    if (now - lastProgress > 100_000_000L) {
                        update(progress, "downloading", got, total > 0 ? total : -1);
                        lastProgress = now;
                    }
                }
            }
            checkCancelled(progress);
            if (got == 0 || (total >= 0 && got != total)) throw new IOException("媒体下载不完整，请重试");
            update(progress, "downloading", got, total > 0 ? total : got);
            complete = true;
            return got;
        } finally {
            connection.disconnect();
            if (!complete) deleteQuietly(dest);
        }
    }

    /** Existing parser/cipher operations are reused unchanged; no additional formats are inferred. */
    private static int decryptSamples(String spadeAB64, Path source, Path raw, Progress progress) throws Exception {
        checkCancelled(progress);
        Spade.Result result = Spade.spadeToKey(spadeAB64, 0);
        if (!Spade.isValidKey(result.key)) throw new IOException("本集媒体密钥格式暂不支持");
        Mp4.Analysis analysis;
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "r")) {
            Mp4.MoovSpan moov = Mp4.findMoov(new Mp4.FileHeadReader(file), file.length());
            if (moov == null || moov.size < 8 || moov.size > MAX_MOOV_BYTES) {
                throw new IOException("本集媒体索引无效或过大");
            }
            byte[] bytes = new byte[(int) moov.size];
            file.seek(moov.start);
            file.readFully(bytes);
            analysis = Mp4.analyzeEncryptedTracks(new Mp4.ByteView(bytes, moov.start));
        }
        if (analysis.tracks.isEmpty()) throw new IOException("未找到可处理的媒体轨道");
        Mp4.Track video = analysis.tracks.get(0);
        long sampleTotal = 0;
        long fileSize = Files.size(source);
        for (Mp4.Track track : analysis.tracks) {
            if ("vide".equals(track.handler)) video = track;
            if (track.sizes.length != track.sampleCount || track.offsets.length != track.sampleCount) {
                throw new IOException("本集媒体索引不完整");
            }
            for (int i = 0; i < track.sampleCount; i++) {
                long offset = track.offsets[i], size = track.sizes[i];
                if (offset < 0 || size < 0 || size > MAX_MOOV_BYTES || offset > fileSize - size) {
                    throw new IOException("本集媒体样本越界");
                }
                if (size > 0) sampleTotal++;
            }
        }
        copy(source, raw, progress);
        checkCancelled(progress);
        int decrypted = 0;
        try (RandomAccessFile file = new RandomAccessFile(raw.toFile(), "rw")) {
            final Mp4.SampleStore delegate = new Mp4.RafStore(file, 256 * 1024);
            final long total = sampleTotal;
            Mp4.SampleStore checked = new Mp4.SampleStore() {
                long done;
                long lastUpdate;
                private void check() {
                    if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) {
                        throw new CancellationException("准备已取消");
                    }
                }
                public long length() { check(); return delegate.length(); }
                public byte[] read(long offset, int length) { check(); return delegate.read(offset, length); }
                public void write(long offset, byte[] bytes) {
                    check();
                    delegate.write(offset, bytes);
                    done++;
                    long now = System.nanoTime();
                    if (now - lastUpdate > 100_000_000L || done == total) {
                        update(progress, "processing", done, total);
                        lastUpdate = now;
                    }
                }
            };
            String videoIv = Mp4.verifyVideoIv(checked, video, result.key, Mp4.trackBaseIvCandidates(video));
            if (videoIv == null) throw new IOException("本集媒体校验失败，请重新选择清晰度");
            for (Mp4.Track track : analysis.tracks) {
                List<String> candidates = Mp4.trackBaseIvCandidates(track);
                String iv = track == video ? videoIv : candidates.isEmpty() ? videoIv : candidates.get(0);
                decrypted += Mp4.decryptTrack(checked, track, result.key, iv).decrypted;
            }
        }
        checkCancelled(progress);
        if (decrypted != sampleTotal) throw new IOException("本集媒体处理不完整");
        return decrypted;
    }

    public static final class DecryptOut {
        public String path;
        public String key;
        public int tracks;
        public long size;
    }

    /** Compatibility entry point; output is committed only after existing parser checks pass. */
    public static DecryptOut offlineDecrypt(String spadeAB64, Path source, Path out) throws Exception {
        if (source.toAbsolutePath().normalize().equals(out.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("密文和成品必须使用不同文件");
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Path raw = Files.createTempFile(out.toAbsolutePath().getParent(), "media-", ".raw.tmp");
        Path playable = Files.createTempFile(out.toAbsolutePath().getParent(), "media-", ".play.tmp");
        try {
            DecryptOut result = new DecryptOut();
            result.tracks = decryptSamples(spadeAB64, source, raw, null);
            if (!Mp4.stripEncryption(raw, playable)) throw new IOException("本集媒体封装暂不支持");
            validateMp4(playable);
            moveAtomically(playable, out);
            result.path = out.toAbsolutePath().normalize().toString();
            result.size = Files.size(out);
            return result;
        } finally {
            deleteQuietly(raw);
            deleteQuietly(playable);
        }
    }

    private static void validateMp4(Path path) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            if (length < 24) throw new IOException("媒体文件无效");
            file.seek(4);
            if (file.readInt() != 0x66747970) throw new IOException("媒体文件格式不支持");
            boolean hasPayload = false;
            long offset = 0;
            while (offset < length) {
                if (length - offset < 8) throw new IOException("媒体文件不完整");
                file.seek(offset);
                long size = Integer.toUnsignedLong(file.readInt());
                int type = file.readInt();
                int header = 8;
                if (size == 1) { size = file.readLong(); header = 16; }
                else if (size == 0) size = length - offset;
                if (size < header || size > length - offset) throw new IOException("媒体文件不完整");
                if (type == 0x6d646174 && size > header) hasPayload = true;
                offset += size;
            }
            Mp4.MoovSpan moov = Mp4.findMoov(new Mp4.FileHeadReader(file), length);
            if (!hasPayload || moov == null || moov.size < 8 || moov.size > MAX_MOOV_BYTES) {
                throw new IOException("媒体文件缺少播放索引");
            }
            byte[] bytes = new byte[(int) moov.size];
            file.seek(moov.start);
            file.readFully(bytes);
            Mp4.Analysis analysis = Mp4.analyzeEncryptedTracks(new Mp4.ByteView(bytes, moov.start));
            if (analysis.tracks.isEmpty()) throw new IOException("媒体文件没有可播放轨道");
            for (Mp4.Track track : analysis.tracks) {
                if (track.offsets.length != track.sampleCount || track.sizes.length != track.sampleCount) {
                    throw new IOException("媒体文件索引不完整");
                }
                for (int i = 0; i < track.sampleCount; i++) {
                    long sampleOffset = track.offsets[i], size = track.sizes[i];
                    if (sampleOffset < 0 || size < 0 || sampleOffset > length - size) {
                        throw new IOException("媒体文件样本不完整");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("媒体文件索引无效");
        }
    }

    private static void copy(Path source, Path target, Progress progress) throws IOException {
        try (InputStream in = Files.newInputStream(source); OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[65536];
            for (;;) {
                checkCancelled(progress);
                int n = in.read(buffer);
                if (n < 0) break;
                if (n > 0) out.write(buffer, 0, n);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        // All pipeline intermediates live beside the result in app-private storage.
        // If the provider cannot atomically rename, fail instead of exposing a partial result.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Pair pin/unpin with the lifetime of each player holding this local path. */
    public static void pin(String path) {
        if (path == null) return;
        String key = Paths.get(path).toAbsolutePath().normalize().toString();
        synchronized (CACHE_GUARD) { PINS.put(key, PINS.getOrDefault(key, 0) + 1); }
    }

    public static void unpin(String path) {
        if (path == null) return;
        String key = Paths.get(path).toAbsolutePath().normalize().toString();
        synchronized (CACHE_GUARD) {
            int count = PINS.getOrDefault(key, 0);
            if (count <= 1) PINS.remove(key); else PINS.put(key, count - 1);
            pruneCache(null);
        }
    }

    /** 512MB completed-file quota. Pinned and newest result are retained until a later cleanup. */
    public static int pruneCache() { return pruneCache(null); }

    private static int pruneCache(Path protectedPath) {
        synchronized (CACHE_GUARD) {
            Path dir = cacheDir();
            if (!Files.isDirectory(dir)) return 0;
            try {
                List<Path> files = new ArrayList<>();
                long bytes = 0;
                try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                    for (Path p : children.toArray(Path[]::new)) {
                        if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".play.mp4")) {
                            files.add(p);
                            bytes += Files.size(p);
                        }
                    }
                }
                files.sort(Comparator.comparingLong(Stream::modified));
                Path newest = files.isEmpty() ? null : files.get(files.size() - 1);
                int removed = 0;
                for (Path candidate : files) {
                    if (bytes <= CACHE_BYTES) break;
                    if (candidate.equals(newest) || candidate.equals(protectedPath) || PINS.containsKey(candidate.toString())) continue;
                    long size = Files.size(candidate);
                    if (Files.deleteIfExists(candidate)) { bytes -= size; removed++; }
                }
                return removed;
            } catch (IOException ignored) { return 0; }
        }
    }

    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; }
    }

    private static void touch(Path path) {
        try { Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis())); } catch (IOException ignored) { }
    }

    private static void update(Progress progress, String stage, long done, long total) {
        if (progress != null) progress.update(stage, done, total);
    }

    private static void checkCancelled(Progress progress) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) throw cancelled();
    }

    private static InterruptedIOException cancelled() { return new InterruptedIOException("准备已取消"); }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
