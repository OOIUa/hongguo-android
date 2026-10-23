package com.hongguo.api.service;

import com.hongguo.api.core.Mp4;
import com.hongguo.api.core.Mp4Progressive;
import com.hongguo.api.core.Spade;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Crypto;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-demand Range transport exposing a clear, same-offset virtual MP4 to Media3. No local server. */
public final class ProgressiveMedia {
    private ProgressiveMedia() {}
    private static final int BLOCK_BYTES = 256 * 1024;
    private static final long MAX_SOURCE_BYTES = 512L * 1024 * 1024;
    private static final long CACHE_LIMIT = MAX_SOURCE_BYTES;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    private static final Object CACHE_GUARD = new Object();
    private static final LinkedHashMap<Path, CacheBlock> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static final Set<Path> LIVE_DIRS = new HashSet<>();
    private static final Set<Path> INITIALIZED_ROOTS = new HashSet<>();
    private static long cacheBytes;

    /** A conservative unsupported layout/transport may be retried with Stream.prepare. */
    public static final class UnsupportedException extends IOException {
        public UnsupportedException(String message) { super(message); }
    }
    public interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }
    interface ContentKeyResolver { String resolve(String spade); }

    public static Session open(String vid, String quality, Stream.Progress progress) throws Exception {
        if (vid == null || !vid.matches("[0-9]{1,30}")) throw new IllegalArgumentException("分集编号无效");
        checkOpening(progress);
        emit(progress, "resolving", 0, -1);
        Stream.VideoTrack track = Stream.videoTrack(vid, quality);
        checkOpening(progress);
        String root = Config.str("runtime.data_dir", null);
        if (root == null || root.trim().isEmpty()) throw new IOException("媒体存储尚未初始化");
        return openTrack(track, Paths.get(root).resolve("progressive-cache"), progress,
                uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    // Injectable transport is deliberately package-private; tests never need upstream content or keys.
    static Session openTrack(Stream.VideoTrack track, Path cacheRoot, Stream.Progress progress,
                             ConnectionFactory factory) throws Exception {
        return openTrack(track, cacheRoot, progress, factory, spade -> Spade.spadeToKey(spade, 0).key);
    }
    static Session openTrack(Stream.VideoTrack track, Path cacheRoot, Stream.Progress progress,
                             ConnectionFactory factory, ContentKeyResolver keyResolver) throws Exception {
        if (track == null || track.url == null) throw new IOException("暂时无法获取本集播放信息");
        checkOpening(progress);
        URI source = checkedUri(track.url);
        Path dir = createSessionDirectory(cacheRoot);
        Session session = new Session(source, dir, progress, factory);
        try {
            session.initialize(track, progress, keyResolver);
            checkOpening(progress);
            session.opening = false;
            emit(progress, "ready", session.downloadedBytes(), session.length());
            return session;
        } catch (Exception | Error failure) {
            session.close();
            throw failure;
        }
    }

    public static final class Session implements Closeable {
        private final URI source;
        private final Path dir;
        private final Stream.Progress progress;
        private final ConnectionFactory factory;
        private final ReentrantLock fetchLock = new ReentrantLock(true);
        private final Set<Reader> readers = new HashSet<>();
        private final Map<Long, CacheBlock> blocks = new LinkedHashMap<>();
        private final BitSet downloaded = new BitSet();
        private final List<Sample> samples = new ArrayList<>();
        private final List<Span> media = new ArrayList<>();
        private final List<Span> freeTop = new ArrayList<>();
        private final Reader defaultReader;
        private volatile boolean closed;
        private volatile boolean opening = true;
        private long length = -1, downloadedBytes;
        private String validatorHeader, validator;
        private long moovStart;
        private byte[] clearMoov;
        private byte[] key;

        private Session(URI source, Path dir, Stream.Progress progress, ConnectionFactory factory) {
            this.source = source; this.dir = dir; this.progress = progress; this.factory = factory;
            defaultReader = new Reader(this);
            readers.add(defaultReader);
        }
        public long length() { return length; }
        /** Unique source bytes fetched this session; repeat seeks do not inflate this value. */
        public long downloadedBytes() { synchronized (CACHE_GUARD) { return downloadedBytes; } }
        /** True only while every source block is present; sparse caches are never complete files. */
        public boolean isComplete() {
            synchronized (CACHE_GUARD) {
                if (closed || length < 0) return false;
                return blocks.size() == (length + BLOCK_BYTES - 1) / BLOCK_BYTES;
            }
        }
        public Reader newReader() throws IOException {
            synchronized (readers) {
                if (closed) throw new IOException("播放会话已关闭");
                Reader reader = new Reader(this); readers.add(reader); return reader;
            }
        }
        public int read(long position, byte[] buffer, int offset, int count) throws IOException {
            return defaultReader.read(position, buffer, offset, count);
        }

        private void initialize(Stream.VideoTrack track, Stream.Progress openingProgress, ContentKeyResolver keyResolver) throws Exception {
            emit(progress, "index", 0, -1);
            // The first request is tiny: require true 206 support before allocating media buffers.
            fetch(defaultReader, 0, 16, true);
            checkOpening(openingProgress);
            long at = 0, moovSize = 0;
            int boxCount = 0;
            boolean ftyp = false;
            while (at < length) {
                if (++boxCount > 4096 || length - at < 8) throw unsupported();
                byte[] header = raw(defaultReader, at, (int) Math.min(16, length - at));
                long size = u32(header, 0); int hs = 8;
                String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
                if (size == 1) {
                    if (header.length < 16 || u32(header, 8) > Integer.MAX_VALUE) throw unsupported();
                    size = (u32(header, 8) << 32) | u32(header, 12); hs = 16;
                } else if (size == 0) size = length - at;
                if (size < hs || size > length - at) throw unsupported();
                if (at == 0) { if (!"ftyp".equals(type)) throw unsupported(); ftyp = true; }
                if ("moof".equals(type) || "mfra".equals(type) || "sidx".equals(type)) throw unsupported();
                if ("moov".equals(type)) {
                    if (moovSize != 0 || size > 16 * 1024 * 1024) throw unsupported();
                    moovStart = at; moovSize = size;
                } else if ("mdat".equals(type)) media.add(new Span(at + hs, at + size));
                else if ("pssh".equals(type)) freeTop.add(new Span(at + 4, at + 8));
                at += size;
                checkOpening(openingProgress);
            }
            if (!ftyp || moovSize == 0 || media.isEmpty()) throw unsupported();
            Mp4Progressive.Index index;
            try {
                index = Mp4Progressive.inspect(raw(defaultReader, moovStart, (int) moovSize), moovStart, track.encrypt);
            } catch (IllegalArgumentException | IndexOutOfBoundsException ex) {
                UnsupportedException failure = unsupported();
                // Parser errors contain only fixed diagnostics. Retain the safe stack for compatibility probes.
                failure.initCause(ex);
                throw failure;
            }
            clearMoov = index.clearMoov;
            if (track.encrypt) {
                String resolvedKey = keyResolver.resolve(track.spadeA);
                if (!Spade.isValidKey(resolvedKey)) throw unsupported();
                key = Crypto.unhex(resolvedKey);
                Mp4.Track video = null;
                for (Mp4.Track t : index.analysis.tracks) {
                    if (index.encryptedTrackIds.contains(t.trackId) && "vide".equals(t.handler)) video = t;
                }
                if (video == null) throw unsupported();
                final Mp4.Track probeVideo = video;
                final String keyHex = resolvedKey;
                // Existing sparse probe/counter logic is reused. These few ranges are not whole-episode preparation.
                Mp4.SampleStore store = new Mp4.SampleStore() {
                    public long length() { return length; }
                    public byte[] read(long off, int len) {
                        try { return raw(defaultReader, off, len); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    }
                    public void write(long off, byte[] data) { throw new UnsupportedOperationException(); }
                };
                validateSampleBounds(index);
                String videoIv;
                try { videoIv = Mp4.verifyVideoIv(store, probeVideo, keyHex, Mp4.trackBaseIvCandidates(probeVideo)); }
                catch (UncheckedIOException e) { throw e.getCause(); }
                if (videoIv == null) throw unsupported();
                for (Mp4.Track t : index.analysis.tracks) {
                    if (!index.encryptedTrackIds.contains(t.trackId)) continue;
                    List<String> candidates = Mp4.trackBaseIvCandidates(t);
                    String baseIv = t == video ? videoIv : candidates.isEmpty() ? videoIv : candidates.get(0);
                    for (int i = 0; i < t.sampleCount; i++) if (t.sizes[i] > 0) {
                        samples.add(new Sample(t.offsets[i], (int) t.sizes[i], Mp4.counterFor(t, baseIv, i)));
                    }
                }
                samples.sort(Comparator.comparingLong(s -> s.start));
            } else validateSampleBounds(index);
            checkOpening(openingProgress);
        }

        private void validateSampleBounds(Mp4Progressive.Index index) throws UnsupportedException {
            List<Span> all = new ArrayList<>();
            for (Mp4.Track t : index.analysis.tracks) for (int i = 0; i < t.sampleCount; i++) {
                long off = t.offsets[i], size = t.sizes[i];
                if (size < 0 || size > Mp4Progressive.MAX_SAMPLE_BYTES || off < 0 || off > length - size) throw unsupported();
                if (size == 0) continue;
                boolean contained = false;
                for (Span span : media) if (off >= span.start && off + size <= span.end) { contained = true; break; }
                if (!contained) throw unsupported();
                all.add(new Span(off, off + size));
            }
            all.sort(Comparator.comparingLong(s -> s.start));
            long previousEnd = -1;
            for (Span span : all) { if (span.start < previousEnd) throw unsupported(); previousEnd = span.end; }
        }

        private int read(Reader reader, long position, byte[] buffer, int offset, int count) throws IOException {
            reader.check();
            if (buffer == null) throw new NullPointerException("buffer");
            if (position < 0 || offset < 0 || count < 0 || offset > buffer.length - count) throw new IndexOutOfBoundsException();
            if (count == 0) return 0;
            if (position >= length) return -1;
            int remaining = (int) Math.min(count, length - position), done = 0;
            // Return bounded pieces, as a DataSource is permitted to short-read.
            remaining = Math.min(remaining, BLOCK_BYTES);
            while (done < remaining) {
                reader.check();
                long p = position + done;
                if (p >= moovStart && p < moovStart + clearMoov.length) {
                    int n = (int) Math.min(remaining - done, moovStart + clearMoov.length - p);
                    System.arraycopy(clearMoov, (int) (p - moovStart), buffer, offset + done, n); done += n; continue;
                }
                int index = sampleAtOrAfter(p);
                Sample sample = index < samples.size() ? samples.get(index) : null;
                if (sample != null && sample.start <= p) {
                    if (reader.lastSample != sample) {
                        byte[] ciphertext = raw(reader, sample.start, sample.size);
                        reader.check();
                        reader.lastPlain = Crypto.aes128Ctr(key, sample.counter, ciphertext);
                        reader.lastSample = sample;
                    }
                    int n = (int) Math.min(remaining - done, sample.end - p);
                    System.arraycopy(reader.lastPlain, (int) (p - sample.start), buffer, offset + done, n); done += n;
                } else {
                    long end = Math.min(length, p + remaining - done);
                    if (sample != null) end = Math.min(end, sample.start);
                    if (p < moovStart) end = Math.min(end, moovStart);
                    byte[] bytes = raw(reader, p, (int) (end - p));
                    System.arraycopy(bytes, 0, buffer, offset + done, bytes.length);
                    // Top-level PSSH also becomes a same-size free box; only its four type bytes change.
                    for (Span span : freeTop) for (long x = Math.max(p, span.start); x < Math.min(end, span.end); x++) {
                        buffer[offset + done + (int) (x - p)] = (byte) "free".charAt((int) (x - span.start));
                    }
                    done += bytes.length;
                }
            }
            reader.check();
            return done;
        }
        private int sampleAtOrAfter(long position) {
            int lo = 0, hi = samples.size();
            while (lo < hi) { int mid = (lo + hi) >>> 1; if (samples.get(mid).end <= position) lo = mid + 1; else hi = mid; }
            return lo;
        }

        private byte[] raw(Reader reader, long position, int count) throws IOException {
            reader.check();
            if (position < 0 || count < 0 || count > 16 * 1024 * 1024 || (length >= 0 && position > length - count)) throw unsupported();
            byte[] result = new byte[count];
            int done = 0;
            while (done < count) {
                reader.check();
                long block = (position + done) / BLOCK_BYTES;
                byte[] bytes = block(reader, block);
                int start = (int) ((position + done) % BLOCK_BYTES), n = Math.min(count - done, bytes.length - start);
                if (n <= 0) throw new IOException("媒体分段长度异常");
                System.arraycopy(bytes, start, result, done, n); done += n;
            }
            return result;
        }
        private byte[] block(Reader reader, long number) throws IOException {
            boolean locked = false;
            try {
                while (!(locked = fetchLock.tryLock(100, TimeUnit.MILLISECONDS))) reader.check();
                reader.check();
                synchronized (CACHE_GUARD) {
                    CacheBlock cached = blocks.get(number);
                    if (cached != null) {
                        try {
                            byte[] bytes = Files.readAllBytes(cached.path);
                            if (bytes.length == cached.size && Arrays.equals(digest(bytes), cached.hash)) {
                                CACHE.get(cached.path); return bytes;
                            }
                        } catch (IOException ignored) { }
                        removeCache(cached);
                    }
                }
                long from = number * BLOCK_BYTES;
                int count = (int) Math.min(BLOCK_BYTES, length - from);
                byte[] bytes = fetch(reader, from, count, false);
                reader.check();
                Path destination = dir.resolve(number + ".range");
                Path temporary = Files.createTempFile(dir, "range-", ".part");
                try {
                    Files.write(temporary, bytes);
                    synchronized (CACHE_GUARD) {
                        reader.check();
                        while (cacheBytes + bytes.length > CACHE_LIMIT && !CACHE.isEmpty()) removeCache(CACHE.values().iterator().next());
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        CacheBlock entry = new CacheBlock(this, number, destination, bytes.length, digest(bytes));
                        blocks.put(number, entry); CACHE.put(destination, entry); cacheBytes += bytes.length;
                        if (!downloaded.get((int) number)) {
                            downloaded.set((int) number); downloadedBytes += bytes.length;
                        }
                    }
                } finally { Files.deleteIfExists(temporary); }
                emit(progress, "buffering", downloadedBytes(), length);
                return bytes;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw cancelled();
            } finally { if (locked) fetchLock.unlock(); }
        }

        private byte[] fetch(Reader reader, long from, int count, boolean first) throws IOException {
            if (count <= 0) throw unsupported();
            URI next = source;
            for (int redirect = 0; redirect <= 5; redirect++) {
                reader.check();
                HttpURLConnection connection;
                try { connection = factory.open(next); }
                catch (IOException failure) { reader.check(); throw new IOException("媒体连接暂时失败，请重试"); }
                try {
                    reader.attach(connection);
                    connection.setConnectTimeout(8000); connection.setReadTimeout(5000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    connection.setRequestProperty("Range", "bytes=" + from + "-" + (from + count - 1));
                    if (validator != null) connection.setRequestProperty("If-Range", validator);
                    int code = connection.getResponseCode();
                    reader.check();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || redirect == 5) throw new IOException("媒体重定向异常");
                        try { next = checkedUri(next.resolve(location).toString()); }
                        catch (IllegalArgumentException failure) { throw new IOException("媒体重定向异常"); }
                        continue;
                    }
                    if (code != 206) throw unsupported();
                    String contentRange = connection.getHeaderField("Content-Range");
                    Matcher range = CONTENT_RANGE.matcher(contentRange == null ? "" : contentRange);
                    if (!range.matches()) throw unsupported();
                    long start, end, total;
                    try { start = Long.parseLong(range.group(1)); end = Long.parseLong(range.group(2)); total = Long.parseLong(range.group(3)); }
                    catch (NumberFormatException ex) { throw unsupported(); }
                    if (start != from || end != from + count - 1 || total < 16 || total > MAX_SOURCE_BYTES
                            || end >= total || (length >= 0 && total != length)) throw unsupported();
                    if (first) {
                        length = total;
                        String etag = connection.getHeaderField("ETag"), modified = connection.getHeaderField("Last-Modified");
                        if ((etag != null && etag.length() > 512) || (modified != null && modified.length() > 128)) throw unsupported();
                        if (etag != null && !etag.startsWith("W/")) { validatorHeader = "ETag"; validator = etag; }
                        else if (modified != null) { validatorHeader = "Last-Modified"; validator = modified; }
                    } else if (validator != null && !validator.equals(connection.getHeaderField(validatorHeader))) {
                        throw new IOException("媒体来源已更新，请重新打开本集");
                    }
                    long declared = connection.getContentLengthLong();
                    if (declared >= 0 && declared != count) throw new IOException("媒体分段长度异常");
                    String encoding = connection.getHeaderField("Content-Encoding");
                    if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw unsupported();
                    byte[] bytes = new byte[count]; int done = 0;
                    try (InputStream input = connection.getInputStream()) {
                        while (done < count) {
                            reader.check(); int n = input.read(bytes, done, count - done);
                            if (n < 0) throw new IOException("媒体分段下载不完整");
                            if (n > 0) done += n;
                        }
                        reader.check();
                    }
                    return bytes;
                } catch (IOException failure) {
                    reader.check();
                    // No URL/query/cipher key is included in outward errors or causes.
                    if (failure instanceof UnsupportedException || failure instanceof InterruptedIOException) throw failure;
                    throw new IOException("媒体分段暂时读取失败，请重试");
                } finally { reader.detach(connection); connection.disconnect(); }
            }
            throw new IOException("媒体重定向异常");
        }

        @Override public void close() {
            List<Reader> copy;
            synchronized (readers) {
                if (closed) return;
                closed = true; copy = new ArrayList<>(readers); readers.clear();
            }
            for (Reader reader : copy) reader.close();
            synchronized (CACHE_GUARD) {
                for (CacheBlock block : new ArrayList<>(blocks.values())) removeCache(block);
                LIVE_DIRS.remove(dir);
                deleteTree(dir);
            }
        }
    }

    /** Each Media3 DataSource owns one Reader, so closing an old load cannot cancel a new one. */
    public static final class Reader implements Closeable {
        private final Session session;
        private final Object connectionGuard = new Object();
        private volatile boolean closed;
        private HttpURLConnection connection;
        private Sample lastSample;
        private byte[] lastPlain;
        private Reader(Session session) { this.session = session; }
        public synchronized int read(long position, byte[] buffer, int offset, int length) throws IOException {
            return session.read(this, position, buffer, offset, length);
        }
        private void check() throws IOException {
            if (closed || session.closed || Thread.currentThread().isInterrupted()) throw cancelled();
            if (session.opening) checkOpening(session.progress);
        }
        private void attach(HttpURLConnection value) throws IOException {
            synchronized (connectionGuard) { check(); connection = value; }
        }
        private void detach(HttpURLConnection value) {
            synchronized (connectionGuard) { if (connection == value) connection = null; }
        }
        @Override public void close() {
            HttpURLConnection old;
            synchronized (connectionGuard) { closed = true; old = connection; connection = null; }
            if (old != null) old.disconnect();
            synchronized (session.readers) { session.readers.remove(this); }
            // The read lock is intentionally not acquired: close must unblock network IO immediately.
        }
    }

    private static final class Sample {
        final long start, end; final int size; final byte[] counter;
        Sample(long start, int size, byte[] counter) { this.start = start; this.size = size; this.end = start + size; this.counter = counter; }
    }
    private static final class Span {
        final long start, end;
        Span(long start, long end) { this.start = start; this.end = end; }
    }
    private static final class CacheBlock {
        final Session owner; final long number; final Path path; final int size; final byte[] hash;
        CacheBlock(Session owner, long number, Path path, int size, byte[] hash) {
            this.owner = owner; this.number = number; this.path = path; this.size = size; this.hash = hash;
        }
    }
    private static void removeCache(CacheBlock block) {
        CACHE.remove(block.path); block.owner.blocks.remove(block.number); cacheBytes -= block.size;
        try { Files.deleteIfExists(block.path); } catch (IOException ignored) { }
    }
    private static Path createSessionDirectory(Path root) throws IOException {
        Path canonical = root.toAbsolutePath().normalize();
        synchronized (CACHE_GUARD) {
            Files.createDirectories(canonical);
            if (INITIALIZED_ROOTS.add(canonical)) {
                try (java.util.stream.Stream<Path> files = Files.list(canonical)) {
                    // Never load an abandoned/incomplete session as a playable or reusable file.
                    for (Path path : files.toArray(Path[]::new)) if (path.getFileName().toString().startsWith("p1-")
                            && !LIVE_DIRS.contains(path)) deleteTree(path);
                }
            }
            Path dir = Files.createTempDirectory(canonical, "p1-"); LIVE_DIRS.add(dir); return dir;
        }
    }
    private static void deleteTree(Path path) {
        // Only ever called for an explicitly created child of progressive-cache/test cache root.
        try (java.util.stream.Stream<Path> files = Files.walk(path)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static URI checkedUri(String raw) throws IOException {
        try {
            URI uri = URI.create(raw);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException ex) { throw new IOException("媒体地址无效"); }
    }
    private static long u32(byte[] bytes, int at) {
        return ((long) (bytes[at] & 255) << 24) | ((long) (bytes[at + 1] & 255) << 16)
                | ((long) (bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }
    private static void checkOpening(Stream.Progress progress) throws IOException {
        if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled())) throw cancelled();
    }
    private static InterruptedIOException cancelled() { return new InterruptedIOException("播放读取已取消"); }
    private static UnsupportedException unsupported() { return new UnsupportedException("本集暂不支持边播边下，将使用完整准备模式"); }
    private static void emit(Stream.Progress progress, String stage, long done, long total) {
        if (progress != null) progress.update(stage, done, total);
    }
}
