package com.hongguo.api.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Conservative, bounded non-fragmented MP4 index validation and same-size signalling repair. */
public final class Mp4Progressive {
    private Mp4Progressive() {}
    public static final int MAX_SAMPLES = 250_000;
    public static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;
    private static final Set<String> CONTAINERS = new HashSet<>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "sinf", "schi"));
    private static final Set<String> VISUAL = new HashSet<>(Arrays.asList(
            "avc1", "avc3", "hvc1", "hev1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1"));
    private static final Set<String> AUDIO = new HashSet<>(Arrays.asList(
            "mp4a", "ac-3", "ec-3", "Opus", "fLaC", "samr", "sawb"));
    private static final Set<String> FREE = new HashSet<>(Arrays.asList("sinf", "senc", "saiz", "saio", "pssh"));

    public static final class Index {
        public final byte[] clearMoov;
        public final Mp4.Analysis analysis;
        public final Set<Long> encryptedTrackIds;
        private Index(byte[] clearMoov, Mp4.Analysis analysis, Set<Long> encrypted) {
            this.clearMoov = clearMoov;
            this.analysis = analysis;
            this.encryptedTrackIds = encrypted;
        }
    }

    /** Input contains exactly one moov. No byte is removed and no media offset is changed. */
    public static Index inspect(byte[] moovBytes, long absoluteStart, boolean encrypted) {
        if (moovBytes.length < 8 || moovBytes.length > 16 * 1024 * 1024) fail();
        Parser parser = new Parser(moovBytes);
        List<Node> roots = parser.boxes(0, moovBytes.length, 0);
        if (roots.size() != 1 || !"moov".equals(roots.get(0).type)) fail();
        Node moov = roots.get(0);
        Set<Long> encryptedIds = new HashSet<>();
        int trackCount = 0;
        long preflightSamples = 0;
        for (Node trak : moov.children) {
            if (!"trak".equals(trak.type)) continue;
            if (++trackCount > 8) fail();
            Node tkhd = required(trak, "tkhd");
            requirePayload(tkhd, moovBytes[tkhd.payload] == 1 ? 96 : 84);
            int version = moovBytes[tkhd.payload] & 255;
            if (version > 1) fail();
            long trackId = u32(moovBytes, tkhd.payload + (version == 1 ? 20 : 12));
            Node mdia = required(trak, "mdia"), hdlr = required(mdia, "hdlr");
            requirePayload(hdlr, 12);
            String handler = text(moovBytes, hdlr.payload + 8);
            if (!"vide".equals(handler) && !"soun".equals(handler)) fail();
            Node stbl = required(required(mdia, "minf"), "stbl");
            Node stsz = required(stbl, "stsz");
            requirePayload(stsz, 12);
            long uniform = u32(moovBytes, stsz.payload + 4), samples = u32(moovBytes, stsz.payload + 8);
            if (samples < 1 || samples > MAX_SAMPLES || uniform > MAX_SAMPLE_BYTES) fail();
            preflightSamples += samples;
            if (preflightSamples > MAX_SAMPLES) fail();
            if (stsz.end - stsz.payload != 12 + (uniform == 0 ? samples * 4 : 0)) fail();
            if (uniform == 0) for (int i = 0; i < samples; i++) {
                if (u32(moovBytes, stsz.payload + 12 + i * 4) > MAX_SAMPLE_BYTES) fail();
            }
            Node chunks = stbl.child("stco");
            int width = 4;
            if (chunks != null && stbl.child("co64") != null) fail();
            if (chunks == null) { chunks = required(stbl, "co64"); width = 8; }
            long chunkCount = table(moovBytes, chunks, width);
            if (chunkCount < 1 || chunkCount > samples) fail();
            Node stsc = required(stbl, "stsc");
            long mappings = table(moovBytes, stsc, 12), previous = 0;
            if (mappings < 1 || mappings > chunkCount) fail();
            for (int i = 0; i < mappings; i++) {
                int at = stsc.payload + 8 + i * 12;
                long first = u32(moovBytes, at), perChunk = u32(moovBytes, at + 4);
                if ((i == 0 && first != 1) || first <= previous || first > chunkCount
                        || perChunk < 1 || perChunk > samples || u32(moovBytes, at + 8) != 1) fail();
                previous = first;
            }
            Node stsd = required(stbl, "stsd");
            if (stsd.children.size() != 1 || u32(moovBytes, stsd.payload + 4) != 1) fail();
            Node entry = stsd.children.get(0);
            boolean protectedEntry = "encv".equals(entry.type) || "enca".equals(entry.type);
            if (protectedEntry) {
                if (!encrypted || !encryptedIds.add(trackId)) fail();
                Node sinf = required(entry, "sinf"), frma = required(sinf, "frma"), schm = required(sinf, "schm");
                requirePayload(frma, 4); requirePayload(schm, 8);
                String original = text(moovBytes, frma.payload);
                if (!("encv".equals(entry.type) ? VISUAL : AUDIO).contains(original)
                        || !"cenc".equals(text(moovBytes, schm.payload + 4))) fail();
                Node senc = required(stbl, "senc");
                requirePayload(senc, 8);
                // Existing cipher is full-sample AES-CTR. Subsample/pattern encryption cannot be inferred safely.
                if (u32(moovBytes, senc.payload) != 0 || u32(moovBytes, senc.payload + 4) != samples) fail();
                long ivBytes = senc.end - senc.payload - 8;
                if (ivBytes != samples * 8 && ivBytes != samples * 16) fail();
                entry.clearType = original;
            } else if (entry.child("sinf") != null || stbl.child("senc") != null
                    || stbl.child("saiz") != null || stbl.child("saio") != null) fail();
        }
        if (trackCount == 0 || (encrypted && encryptedIds.isEmpty())) fail();
        Node mvhd = required(moov, "mvhd");
        requirePayload(mvhd, (moovBytes[mvhd.payload] & 255) == 1 ? 24 : 16);
        Mp4.Analysis analysis = Mp4.analyzeEncryptedTracks(new Mp4.ByteView(moovBytes, absoluteStart));
        if (analysis.tracks.size() != trackCount) fail();
        Set<Long> trackIds = new HashSet<>();
        long totalSamples = 0;
        for (Mp4.Track track : analysis.tracks) {
            totalSamples += track.sampleCount;
            if (totalSamples > MAX_SAMPLES || !trackIds.add(track.trackId)
                    || track.offsets.length != track.sampleCount || track.sizes.length != track.sampleCount) fail();
            if (encryptedIds.contains(track.trackId)
                    && (track.ivs == null || track.ivs.length != track.sampleCount)) fail();
        }
        byte[] clear = moovBytes.clone();
        rewrite(clear, moov);
        return new Index(clear, analysis, encryptedIds);
    }

    private static void rewrite(byte[] clear, Node node) {
        if (node.clearType != null) putType(clear, node.start + 4, node.clearType);
        if (FREE.contains(node.type)) { putType(clear, node.start + 4, "free"); return; }
        for (Node child : node.children) rewrite(clear, child);
    }
    private static void putType(byte[] bytes, int at, String type) {
        System.arraycopy(type.getBytes(StandardCharsets.ISO_8859_1), 0, bytes, at, 4);
    }
    private static long table(byte[] bytes, Node n, int width) {
        requirePayload(n, 8);
        long count = u32(bytes, n.payload + 4);
        if (count > MAX_SAMPLES || n.end - n.payload != 8 + count * width) fail();
        return count;
    }
    private static Node required(Node n, String type) {
        Node child = n.child(type); if (child == null) fail(); return child;
    }
    private static void requirePayload(Node n, int length) { if (n.end - n.payload < length) fail(); }
    private static void fail() { throw new IllegalArgumentException("渐进媒体索引或加密布局暂不支持"); }
    private static void fail(String reason) { throw new IllegalArgumentException(reason); }
    private static String text(byte[] bytes, int at) { return new String(bytes, at, 4, StandardCharsets.ISO_8859_1); }
    private static long u32(byte[] bytes, int at) {
        return ((long) (bytes[at] & 255) << 24) | ((long) (bytes[at + 1] & 255) << 16)
                | ((long) (bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }
    private static final class Node {
        final int start, payload, end;
        final String type;
        String clearType;
        List<Node> children = new ArrayList<>();
        Node(int start, int payload, int end, String type) {
            this.start = start; this.payload = payload; this.end = end; this.type = type;
        }
        Node child(String wanted) {
            Node found = null;
            for (Node n : children) if (wanted.equals(n.type)) { if (found != null) fail(); found = n; }
            return found;
        }
    }
    private static final class Parser {
        final byte[] bytes;
        int boxes;
        Parser(byte[] bytes) { this.bytes = bytes; }
        List<Node> boxes(int from, int end, int depth) {
            if (depth > 16) fail();
            List<Node> out = new ArrayList<>();
            int at = from;
            while (at < end) {
                if (end - at < 8 || ++boxes > 100_000) fail();
                long size = u32(bytes, at); int header = 8;
                if (size == 1) {
                    if (end - at < 16 || u32(bytes, at + 8) != 0) fail();
                    size = u32(bytes, at + 12); header = 16;
                }
                if (size < header || size > end - at) fail();
                String type = text(bytes, at + 4);
                if ("mvex".equals(type) || "moof".equals(type) || "traf".equals(type)
                        || "stz2".equals(type) || "uuid".equals(type)) {
                    fail("MP4_UNSUPPORTED_" + type.toUpperCase(java.util.Locale.ROOT));
                }
                Node n = new Node(at, at + header, at + (int) size, type);
                if ("sgpd".equals(type) || "sbgp".equals(type)) {
                    requirePayload(n, 8);
                    String grouping = text(bytes, n.payload + 4);
                    // Recovery/preroll groups do not override sample encryption or media offsets.
                    // Keep them intact for the player's normal sample-table handling; seig stays unsupported.
                    if (!"roll".equals(grouping) && !"prol".equals(grouping)) {
                        fail("seig".equals(grouping) ? "MP4_ENCRYPTION_SAMPLE_GROUP" : "MP4_UNSUPPORTED_SAMPLE_GROUP");
                    }
                }
                if (CONTAINERS.contains(type)) n.children = boxes(n.payload, n.end, depth + 1);
                else if ("stsd".equals(type)) {
                    requirePayload(n, 8); n.children = boxes(n.payload + 8, n.end, depth + 1);
                } else if (VISUAL.contains(type) || AUDIO.contains(type) || "encv".equals(type) || "enca".equals(type)) {
                    boolean visual = VISUAL.contains(type) || "encv".equals(type);
                    int fixed = visual ? 78 : 28;
                    requirePayload(n, fixed);
                    if (!visual && (bytes[n.payload + 8] != 0 || bytes[n.payload + 9] != 0)) fail();
                    n.children = boxes(n.payload + fixed, n.end, depth + 1);
                }
                out.add(n); at = n.end;
            }
            return out;
        }
    }
}
