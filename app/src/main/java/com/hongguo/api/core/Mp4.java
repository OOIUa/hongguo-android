package com.hongguo.api.core;

import com.hongguo.api.util.Crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯文件 / 纯算法 MP4 CENC 解密。
 *
 * 移植自 server/src/mp4.js。与原实现的关键差异：不使用正则扫描字节流，
 * 而是完整解析 ISO BMFF 盒结构，因此对多 trak、大小端、64 位偏移都更稳健。
 *
 * 解密方案：CENC 的 AES-128-CTR，计数器为 128 位大端，
 *   counter = (base_iv_high64 + sampleIndex) << 64，即低 64 位恒为 0。
 */
public final class Mp4 {

    /** 需要在 trak 内递归查找的容器盒。 */
    private static final Set<String> CONTAINERS = new HashSet<>(java.util.Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "mvex", "moof", "traf"));

    private Mp4() {}

    // ==================== 字节视图（支持非零基址） ====================

    /**
     * 一段字节 + 它在文件中的起始绝对偏移。
     *
     * 为什么需要它：Box.start 记录的是**文件绝对偏移**，而大小端读取助手
     * 直接用绝对下标索引字节数组。整文件读入内存时 base 恒为 0；
     * 但流式方案只把 moov 段（几MB）载入内存时，该段首字节对应的文件偏移
     * 远非 0，若不做base 换算，所有偏移都会整体错位。
     */
    public static final class ByteView {
        public final byte[] b;
        public final long base;

        public ByteView(byte[] b, long base) {
            this.b = b;
            this.base = base;
        }

        /** 整文件视图（base = 0），等价于历史行为。 */
        public static ByteView whole(byte[] buf) {
            return new ByteView(buf, 0);
        }

        long idx(long fileOffset) { return fileOffset - base; }

        /** 越界安全的取字节；越界返回 0（调用点均已有边界校验）。 */
        int at(long fileOffset) {
            long i = idx(fileOffset);
            return (i < 0 || i >= b.length) ? 0 : (b[(int) i] & 0xFF);
        }
    }

    // ==================== 样本存储抽象 ====================

    /**
     * 样本字节的随机读写存储。
     *
     * 引入它的目的：解密只需按样本表逐个读写 [offset, offset+size) 区间，
     * 没有任何跨样本依赖（AES-CTR 每样本独立计数器，长度守恒），
     * 因此完全不需要把整个 MP4 读进堆内存 —— 这是原实现内存暴涨的根因。
     */
    public interface SampleStore {
        /** 存储总长度（字节）。 */
        long length();

        /**
         * 读取 [off, off+len) 区间。
         *
         * ⚠️ 实现**可以复用同一缓冲**：返回的数组仅保证在下一次 read/write
         * 调用之前有效。两个调用点（decryptTrack / verifyVideoIv）拿到后都
         * 立刻交给 AES-CTR 并只使用返回值，不会跨调用持有它。
         */
        byte[] read(long off, int len);

        /** 覆写 [off, off+data.length) 区间。 */
        void write(long off, byte[] data);
    }

    /** 整文件已在内存时的存储实现（保持历史行为，供自检与小文件使用）。 */
    public static final class ByteArrayStore implements SampleStore {
        private final byte[] buf;

        public ByteArrayStore(byte[] buf) { this.buf = buf; }

        @Override public long length() { return buf.length; }

        @Override
        public byte[] read(long off, int len) {
            return java.util.Arrays.copyOfRange(buf, (int) off, (int) (off + len));
        }

        @Override
        public void write(long off, byte[] data) {
            System.arraycopy(data, 0, buf, (int) off, data.length);
        }
    }

    /** 基于 RandomAccessFile 的存储实现：堆内存占用与文件大小解耦。 */
    public static final class RafStore implements SampleStore {
        private final java.io.RandomAccessFile raf;
        /** 复用读缓冲，避免每个样本都new 一次数组。 */
        private final byte[] scratch;

        public RafStore(java.io.RandomAccessFile raf, int bufSize) {
            this.raf = raf;
            this.scratch = new byte[Math.max(4096, bufSize)];
        }

        @Override public long length() {
            try {
                return raf.length();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public byte[] read(long off, int len) {
            try {
                if (len <= scratch.length) {
                    raf.seek(off);
                    raf.readFully(scratch, 0, len);
                    return java.util.Arrays.copyOf(scratch, len);
                }
                // 大样本（少见）单独分配，绕开复用缓冲
                byte[] big = new byte[len];
                raf.seek(off);
                raf.readFully(big);
                return big;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public void write(long off, byte[] data) {
            try {
                raf.seek(off);
                raf.write(data);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    // ==================== 盒结构 ====================

    /** MP4 盒。 */
    public static final class Box {
        public final String type;
        public final long start;        // 盒起始绝对偏移
        public final int headerSize;    // 盒头长度
        public final long size;         // 盒总长度（含头）
        public final List<Box> children = new ArrayList<>();

        Box(String type, long start, int headerSize, long size) {
            this.type = type;
            this.start = start;
            this.headerSize = headerSize;
            this.size = size;
        }

        public long end() { return start + size; }
        public long payloadStart() { return start + headerSize; }

        public Box child(String t) {
            for (Box c : children) if (c.type.equals(t)) return c;
            return null;
        }

        public List<Box> childrenOf(String t) {
            List<Box> out = new ArrayList<>();
            for (Box c : children) if (c.type.equals(t)) out.add(c);
            return out;
        }
    }

    /** 轨道信息。 */
    public static final class Track {
        public long trackId;
        public String handler;          // 'vide' | 'soun' | ...
        public int width;
        public int height;
        public int sampleCount;
        public long[] sizes = new long[0];
        public long[] offsets = new long[0];
        public String[] ivs;            // hex，元素为 8 或 16 字节
        public int ivSize = 16;
        public String kid;
        public String constantIv;
    }

    /** 解析结果。 */
    public static final class Analysis {
        public final List<Track> tracks = new ArrayList<>();
        public long timescale = 90000;
    }

    // ==================== 大小端读取 ====================
    //
    // 全部以「文件绝对偏移」为入参，内部经 ByteView.idx换算成数组下标。
    // 这样同一套读取逻辑既能服务整文件视图（base=0），
    // 也能服务只载入 moov 段的窗口视图（base>0）。

    private static int u8(ByteView v, long p) { return v.at(p); }

    private static int u16(ByteView v, long p) {
        return ((v.at(p) & 0xFF) << 8) | (v.at(p + 1) & 0xFF);
    }

    private static long u32(ByteView v, long p) {
        return ((long) (v.at(p) & 0xFF) << 24)
             | ((long) (v.at(p + 1) & 0xFF) << 16)
             | ((long) (v.at(p + 2) & 0xFF) << 8)
             | (v.at(p + 3) & 0xFF);
    }

    private static long u64(ByteView v, long p) {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | (v.at(p + i) & 0xFF);
        return r;
    }

    /** latin1 字符串（等价 JS 的 toString('latin1')）。 */
    private static String latin1(ByteView v, long from, long to) {
        StringBuilder sb = new StringBuilder();
        long i0 = v.idx(from);
        long i1 = v.idx(to);
        if (i0 < 0) i0 = 0;
        if (i1 > v.b.length) i1 = v.b.length;
        for (long i = i0; i < i1; i++) {
            sb.append((char) (v.b[(int) i] & 0xFF));
        }
        return sb.toString();
    }

    private static String hex(ByteView v, long from, long to) {
        StringBuilder sb = new StringBuilder();
        for (long i = from; i < to; i++) {
            sb.append(String.format("%02x", v.at(i) & 0xFF));
        }
        return sb.toString();
    }

    // ==================== 盒解析 ====================

    /**
     * 解析 v 中 offset 起的盒树。
     *
     * @param offset 起始偏移（文件绝对偏移）
     * @param end    结束偏移（不含，文件绝对偏移）
     */
    public static List<Box> parseBoxes(ByteView v, long offset, long end) {
        List<Box> boxes = new ArrayList<>();
        long p = offset;
        while (p + 8 <= end) {
            long size = u32(v, p);
            String type = latin1(v, p + 4, p + 8);
            int headerSize = 8;

            if (size == 1) {
                if (p + 16 > end) break;
                size = u64(v, p + 8);
                headerSize = 16;
            } else if (size == 0) {
                size = end - p; // 延伸到文件末尾
            }

            if (size < headerSize || p + size > end || size < 0) break;
            Box box = new Box(type, p, headerSize, size);
            if (CONTAINERS.contains(type)) {
                box.children.addAll(parseBoxes(v, box.payloadStart(), box.end()));
            } else if (type.equals("stsd")) {
                // stsd: version/flags(4) + entry_count(4)，之后是 sample entry
                box.children.addAll(parseBoxes(v, box.payloadStart() + 8, box.end()));
            }
            boxes.add(box);
            p += size;
        }
        return boxes;
    }

    /** 整文件视图的parseBoxes（base = 0，等价于历史行为）。 */
    public static List<Box> parseBoxes(byte[] buf, long offset, long end) {
        return parseBoxes(ByteView.whole(buf), offset, end);
    }

    /** 递归收集指定类型的全部盒。 */
    public static List<Box> collect(Box box, String type) {
        List<Box> acc = new ArrayList<>();
        collectInto(box, type, acc);
        return acc;
    }

    private static void collectInto(Box box, String type, List<Box> acc) {
        for (Box c : box.children) {
            if (c.type.equals(type)) acc.add(c);
            collectInto(c, type, acc);
        }
    }

    /** 递归查找第一个指定类型的盒。 */
    public static Box findBox(Box box, String type) {
        for (Box c : box.children) {
            if (c.type.equals(type)) return c;
            Box hit = findBox(c, type);
            if (hit != null) return hit;
        }
        return null;
    }

    // ==================== 元信息 ====================

    /** 解析 mvhd，取 timescale。 */
    private static long readTimescale(ByteView v, Box moov) {
        Box mvhd = moov.child("mvhd");
        if (mvhd == null) return 90000;
        long p = mvhd.payloadStart();
        int version = u8(v, p);
        if (version == 1) return u32(v, p + 20);
        return u32(v, p + 12);
    }

    /** tenc 信息。 */
    private static final class Tenc {
        String kid;
        String constantIv;
        int ivSize;
        boolean isProtected;
    }

    /** 解析 trak 的 tkhd，取 track_ID 与宽高。 */
    private static Track readTrackHeader(ByteView v, Box trak) {
        Track t = new Track();
        Box tkhd = trak.child("tkhd");
        if (tkhd == null) return t;
        long p = tkhd.payloadStart();
        int version = u8(v, p);
        if (version == 1) {
            t.trackId = u32(v, p + 20);
            t.width = (int) (u32(v, p + 88) / 65536);
            t.height = (int) (u32(v, p + 92) / 65536);
        } else {
            t.trackId = u32(v, p + 12);
            t.width = (int) (u32(v, p + 76) / 65536);
            t.height = (int) (u32(v, p + 80) / 65536);
        }
        return t;
    }

    /** 解析 mdia/hdlr，取处理类型（vide / soun）。 */
    private static String readHandler(ByteView v, Box trak) {
        Box mdia = trak.child("mdia");
        Box hdlr = mdia == null ? null : mdia.child("hdlr");
        if (hdlr == null) return null;
        return latin1(v, hdlr.payloadStart() + 8, hdlr.payloadStart() + 12);
    }

    /**
     * 解析 stbl 的样本表，得到每个样本在文件中的绝对偏移与长度。
     */
    private static void readSampleTable(ByteView v, Box stbl, Track track) {
        List<Long> sizes = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();

        // stsz：样本大小（size==0 时逐样本读取）
        Box stsz = stbl.child("stsz");
        if (stsz != null) {
            long p = stsz.payloadStart() + 4; // 跳过 version/flags
            long uniform = u32(v, p);
            long count = u32(v, p + 4);
            if (uniform > 0) {
                for (long i = 0; i < count; i++) sizes.add(uniform);
            } else {
                for (long i = 0; i < count; i++) sizes.add(u32(v, p + 8 + i * 4));
            }
        }

        // stsc：样本到 chunk 的映射
        List<long[]> stscEntries = new ArrayList<>(); // {firstChunk, samplesPerChunk, descIndex}
        Box stsc = stbl.child("stsc");
        if (stsc != null) {
            long p = stsc.payloadStart() + 4;
            long n = u32(v, p);
            for (long i = 0; i < n; i++) {
                long q = p + 4 + i * 12;
                stscEntries.add(new long[]{u32(v, q), u32(v, q + 4), u32(v, q + 8)});
            }
        }

        // stco / co64：chunk 偏移
        List<Long> chunkOffsets = new ArrayList<>();
        Box stco = stbl.child("stco");
        if (stco != null) {
            long p = stco.payloadStart() + 4;
            long n = u32(v, p);
            for (long i = 0; i < n; i++) chunkOffsets.add(u32(v, p + 4 + i * 4));
        } else {
            Box co64 = stbl.child("co64");
            if (co64 != null) {
                long p = co64.payloadStart() + 4;
                long n = u32(v, p);
                for (long i = 0; i < n; i++) chunkOffsets.add(u64(v, p + 4 + i * 8));
            }
        }

        // 依据 stsc 把 chunk 展开为样本偏移
        for (int e = 0; e < stscEntries.size(); e++) {
            long[] cur = stscEntries.get(e);
            long[] next = (e + 1 < stscEntries.size()) ? stscEntries.get(e + 1) : null;
            long lastChunk = next != null ? next[0] - 1 : chunkOffsets.size();
            for (long ch = cur[0]; ch <= lastChunk; ch++) {
                int ci = (int) (ch - 1);
                if (ci < 0 || ci >= chunkOffsets.size()) break;
                long off = chunkOffsets.get(ci);
                for (long s = 0; s < cur[1]; s++) {
                    int idx = offsets.size();
                    if (idx >= sizes.size()) break;
                    offsets.add(off);
                    off += sizes.get(idx);
                }
            }
        }

        track.sizes = new long[sizes.size()];
        for (int i = 0; i < sizes.size(); i++) track.sizes[i] = sizes.get(i);
        track.offsets = new long[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) track.offsets[i] = offsets.get(i);
        track.sampleCount = sizes.size();
    }

    /**
     * 读取 tenc 盒的 default_KID、default_IV_size 与 constant IV（若有）。
     */
    private static Tenc readTenc(ByteView v, Box trak) {
        Box sinf = findBox(trak, "sinf");
        Box schm = sinf == null ? null : sinf.child("schm");
        if (schm != null) {
            long p = schm.payloadStart() + 4;
            String scheme = latin1(v, p, p + 4);
            if (!scheme.equals("cenc")) return null; // 仅处理 CENC
        }
        Box tenc = sinf == null ? null : sinf.child("tenc");
        if (tenc == null) return null;
        Tenc out = new Tenc();
        long p = tenc.payloadStart();
        int version = u8(v, p);
        int flags = ((v.at(p + 1) & 0xFF) << 16) | ((v.at(p + 2) & 0xFF) << 8)
                  | (v.at(p + 3) & 0xFF);
        long q;
        if (version == 0) {
            out.ivSize = u8(v, p + 4);
            q = p + 5;
        } else {
            out.ivSize = u8(v, p + 9);
            q = p + 10;
        }
        out.kid = hex(v, q, q + 16);
        if ((flags & 0x01) != 0) {
            out.constantIv = hex(v, q + 16, q + 16 + out.ivSize);
        }
        out.isProtected = (flags & 0x02) != 0;
        return out;
    }

    /**
     * 存在 subsample 信息时，无法直接用长度反推 IV 大小；
     * 改为假定 16 字节走一遍，若最终偏移不吻合再退回 8 字节。
     */
    private static long estimateWithoutSubsample(ByteView v, long start, long end, int count) {
        long q = start;
        int n = 0;
        while (q + 18 <= end && n < count) {
            int subsampleCount = u16(v, q + 16);
            q += 16 + 2 + subsampleCount * 6L;
            n++;
        }
        if (q > end) return (end - start);
        return q - start;
    }

    /**
     * 解析 senc 盒，取每个样本的 IV。
     *
     * 重要：IV 长度并非总是 16 字节。本项目实测两种都存在：
     *   - 16 字节：标准 CENC，per-sample IV 为完整 128 位；
     *   - 8 字节：PIFF/简化形态，盒长度恰好等于 8 + count*IV_size。
     * 因此用「盒剩余长度 / 样本数」反推实际 IV 长度，并以 tenc 声明交叉校验。
     */
    private static String[] readSencIVs(ByteView v, Box senc, int tencIvSize) {
        long p = senc.payloadStart();
        int flags = ((v.at(p + 1) & 0xFF) << 16) | ((v.at(p + 2) & 0xFF) << 8)
                  | (v.at(p + 3) & 0xFF);
        int count = (int) u32(v, p + 4);
        boolean hasSubsample = (flags & 0x02) != 0;

        // 反推 IV 长度：剩余字节里先扣掉 subsample 开销
        long payload = senc.end() - (p + 8);
        if (hasSubsample) payload = estimateWithoutSubsample(v, p + 8, senc.end(), count);
        int ivSize = count > 0 ? (int) (payload / count) : 16;
        if (ivSize != 8 && ivSize != 16) {
            ivSize = (tencIvSize == 8) ? 8 : 16;
        }
        // tenc 声明优先（它是权威来源）
        if (tencIvSize == 8 || tencIvSize == 16) ivSize = tencIvSize;

        List<String> ivs = new ArrayList<>();
        long q = p + 8;
        for (int i = 0; i < count; i++) {
            if (q + ivSize > senc.end()) break;
            ivs.add(hex(v, q, q + ivSize));
            q += ivSize;
            if (hasSubsample) {
                if (q + 2 > senc.end()) break;
                int subsampleCount = u16(v, q);
                q += 2 + subsampleCount * 6L;
            }
        }
        return ivs.toArray(new String[0]);
    }

    /**
     * 收集密文 mp4 中所有加密轨（视频 + 音频）及其样本表与 IV。
     *
     * @param v 字节视图。流式解密时只需传入「moov 段窗口」，
     *          Box.start 仍是文件绝对偏移，样本偏移才能对上 mdat 里的真实位置。
     */
    public static Analysis analyzeEncryptedTracks(ByteView v) {
        List<Box> top = parseBoxes(v, v.base, v.base + v.b.length);
        Box moov = null;
        for (Box b : top) if (b.type.equals("moov")) moov = b;
        if (moov == null) throw new IllegalArgumentException("不是有效的 MP4：缺少 moov 盒");

        Analysis out = new Analysis();
        out.timescale = readTimescale(v, moov);

        for (Box trak : moov.childrenOf("trak")) {
            Box mdia = trak.child("mdia");
            Box minf = mdia == null ? null : mdia.child("minf");
            Box stblBox = minf == null ? null : minf.child("stbl");
            if (stblBox == null) continue;

            Track t = new Track();
            t.handler = readHandler(v, trak);
            Track header = readTrackHeader(v, trak);
            t.trackId = header.trackId;
            t.width = header.width;
            t.height = header.height;
            readSampleTable(v, stblBox, t);
            if (t.sampleCount == 0) continue;

            // senc 可能在 stbl 内（明文 CENC），或经 saiz/saio 指向
            Box senc = stblBox.child("senc");
            if (senc == null) senc = findBox(trak, "senc");
            Tenc tenc = readTenc(v, trak);
            // tenc 的 default_IV_size 是 IV 长度的权威来源
            t.ivs = senc != null ? readSencIVs(v, senc, tenc != null ? tenc.ivSize : 0) : null;
            t.ivSize = tenc != null ? tenc.ivSize : 16;
            t.kid = tenc != null ? tenc.kid : null;
            t.constantIv = tenc != null ? tenc.constantIv : null;

            out.tracks.add(t);
        }
        return out;
    }

    /** 整文件已在内存时的分析入口（base = 0，等价于历史行为）。 */
    public static Analysis analyzeEncryptedTracks(byte[] buf) {
        return analyzeEncryptedTracks(ByteView.whole(buf));
    }

    // ==================== moov 定位（流式前置） ====================

    /** moov 盒在文件中的位置。 */
    public static final class MoovSpan {
        public final long start;
        public final long size;

        public MoovSpan(long start, long size) {
            this.start = start;
            this.size = size;
        }

        public long end() { return start + size; }
    }

    /**
     * 只读顶层盒头，定位 moov 的 [start, size)。
     *
     * 顶层遍历每次只碰 8/16 字节盒头并按 {@code p += size} 跳跃，
     * 因此**无需把文件读进内存**即可找到 moov —— 即使 moov 在文件末尾
     * （非faststart 的常见形态）也只需顺序扫到文件尾。
     *
     * 严格复刻 parseBoxes 的两条特殊规则：size==1 读 64 位 largesize、
     * size==0 延伸到 EOF，否则会算错窗口边界。
     *
     * @param headAt 已知「至少有多少字节可读」的区间提供器（避免越界读）
     */
    public interface HeadReader {
        /** 读取 [off, off+len) 的盒头字节；越界返回 null 表示读不到。 */
        byte[] head(long off, int len);
    }

    /** 基于文件的盒头读取器。 */
    public static final class FileHeadReader implements HeadReader {
        private final java.io.RandomAccessFile raf;

        public FileHeadReader(java.io.RandomAccessFile raf) { this.raf = raf; }

        @Override
        public byte[] head(long off, int len) {
            try {
                if (off < 0 || off + len > raf.length()) return null;
                byte[] b = new byte[len];
                raf.seek(off);
                raf.readFully(b);
                return b;
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }

    private static int hU32(byte[] h, int p) {
        return ((h[p] & 0xFF) << 24) | ((h[p + 1] & 0xFF) << 16)
             | ((h[p + 2] & 0xFF) << 8) | (h[p + 3] & 0xFF);
    }

    private static long hU64(byte[] h, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[p + i] & 0xFF);
        return v;
    }

    /** 扫描顶层盒，定位 moov。找不到返回 null。 */
    public static MoovSpan findMoov(HeadReader hr, long fileLen) {
        long p = 0;
        while (p + 8 <= fileLen) {
            byte[] h8 = hr.head(p, 8);
            if (h8 == null) return null;
            long size = hU32(h8, 0) & 0xFFFFFFFFL;
            String type = new String(h8, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int headerSize = 8;

            if (size == 1) {
                byte[] h16 = hr.head(p, 16);
                if (h16 == null) return null;
                size = hU64(h16, 8);
                headerSize = 16;
            } else if (size == 0) {
                size = fileLen - p;// 延伸到文件末尾
            }
            if (size < headerSize || p + size > fileLen || size < 0) return null;

            if (type.equals("moov")) return new MoovSpan(p, size);
            p += size;
        }
        return null;
    }

    // ==================== CTR计数器 ====================

    /**
     * 逐样本 IV（senc/PIFF 常为 8 字节）直接作为 CTR 计数器的高 64 位，低 64 位补 0。
     *
     * 本项目实测：senc 里的 IV 是「逐样本递增」的 8 字节序列，
     * 即 iv[i] = base_iv + i。因此「取 iv[i] 当高 64 位」与「base_iv 加 i 后左移」
     * 完全等价，但前者能天然兼容 16 字节 IV 的标准 CENC。
     */
    public static byte[] ivToCounter(String ivHex) {
        String h = ivHex == null ? "" : ivHex.trim();
        // 16 字节标准 IV：本身就是 128 位计数器
        if (h.length() >= 32) {
            return Crypto.unhex(h.substring(0, 32).length() % 2 == 0
                    ? h.substring(0, 32) : h.substring(0, 31) + "0");
        }
        // 8 字节 IV（PIFF 形态）：位于高 64 位，低 64 位补 0
        StringBuilder sb = new StringBuilder(h);
        while (sb.length() < 32) sb.append('0');
        return Crypto.unhex(sb.substring(0, 32));
    }

    /**
     * 构造 AES-128-CTR 的初始计数器：counter = (baseIvHi64 + index) << 64。
     *
     * 语义等价于原BigInteger 实现（(hi+index).shiftLeft(64).and(2^128-1)），
     * 但 long 的自然溢出恰好就是 mod 2^64 —— 正是掩码后保留的低 64 位，
     * 因此直接用 long 加法即可，无需大数分配。
     */
    public static byte[] ctrCounter(String baseIvHex, int index) {
        long hi = 0;
        String h = baseIvHex == null ? "" : baseIvHex.trim();
        // 只取前 16 个十六进制字符（高 64 位）；超出部分与原实现一样被掩码丢弃
        int take = Math.min(h.length(), 16);
        for (int i = 0; i < take; i++) {
            int d = Character.digit(h.charAt(i), 16);
            if (d < 0) break;
            hi = (hi << 4) | d;
        }
        long sum = hi + index;
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (sum >>> (56 - 8 * i));   // 高 64 位
            out[8 + i] = 0;                          // 低 64 位恒为 0
        }
        return out;
    }

    /** 取第 i 个样本的 CTR 计数器：优先用逐样本 IV，缺失时回退 base_iv + i。 */
    public static byte[] counterFor(Track track, String baseIvHex, int i) {
        if (track.ivs != null && i >= 0 && i < track.ivs.length && track.ivs[i] != null) {
            return ivToCounter(track.ivs[i]);
        }
        return ctrCounter(baseIvHex, i);
    }

    // ==================== 解密 ====================

    /** 解密结果。 */
    public static final class DecryptResult {
        public final int decrypted;
        public final int total;
        DecryptResult(int d, int t) { decrypted = d; total = t; }
    }

    /**
     * 就地解密单轨的全部样本（AES-128-CTR）。
     *
     * @param keyHex    content key（32 位十六进制）
     * @param baseIvHex 该轨 base_iv 高 8 字节（仅在无逐样本 IV 时使用）
     */
    public static DecryptResult decryptTrack(byte[] buf, Track track, String keyHex, String baseIvHex) {
        return decryptTrack(new ByteArrayStore(buf), track, keyHex, baseIvHex);
    }

    /**
     * 就地解密单轨的全部样本（AES-128-CTR），样本数据经 {@link SampleStore} 随机读写。
     *
     * 逐样本处理：每次只把「一个样本」放进内存，因此峰值堆内存与文件总大小无关。
     * 单样本一般几十 KB，即使1080p 整集也只在几十 KB 量级波动。
     */
    public static DecryptResult decryptTrack(SampleStore store, Track track, String keyHex,
                String baseIvHex) {
        byte[] key = Crypto.unhex(keyHex);
        if (key.length != 16) throw new IllegalArgumentException("content key 长度非法");
        boolean hasIvs = track.ivs != null && track.ivs.length > 0;
        if (!hasIvs && baseIvHex == null) {
            throw new IllegalArgumentException("该轨既无逐样本 IV 也无 base_iv，无法解密");
        }
        long storeLen = store.length();
        int done = 0;
        for (int i = 0; i < track.sampleCount; i++) {
            long off = track.offsets[i];
            long size = track.sizes[i];
            if (size <= 0) continue;
            if (off < 0 || off + size > storeLen) continue;
            byte[] counter = counterFor(track, baseIvHex, i);
            byte[] sample = store.read(off, (int) size);
            byte[] plain = Crypto.aes128Ctr(key, counter, sample);
            // AES-CTR 长度守恒：plain.length == size，原地覆写同一区间
            store.write(off, plain);
            done++;
        }
        return new DecryptResult(done, track.sampleCount);
    }

    /**
     * 取该轨的 base_iv 候选（16 位 hex =计数器高 64 位）。
     */
    public static List<String> trackBaseIvCandidates(Track track) {
        final List<String> out = new ArrayList<>();
        class Pusher {
            void push(String hx) {
                if (hx == null || hx.isEmpty()) return;
                String hi = hx.length() >= 16 ? hx.substring(0, 16) : hx;
                if (hi.length() == 16 && !out.contains(hi)) out.add(hi);
            }
        }
        Pusher p = new Pusher();
        p.push(track.constantIv);
        if (track.ivs != null) {
            for (String iv : track.ivs) {
                p.push(iv);
                if (out.size() >= 4) break;
            }
        }
        return out;
    }

    // ==================== IV 自证 ====================

    /**
     * AVCC 自证：4 字节大端长度前缀链必须自洽地走完整个样本，且每个 NAL 类型合法。
     * 也接受 Annex-B 起始码（00 00 00 01/ 00 00 01）。
     *
     * 关键点：一个视频样本通常含多个 NAL（SPS/PPS/SEI/IDR），
     * 所以必须遍历整条长度链，只看首个 NAL 会误判。
     */
    public static boolean avccSelfOk(byte[] plain, int size) {
        int n = Math.min(plain.length, size > 0 ? size : plain.length);
        if (n < 5) return false;
        // Annex-B 起始码
        if (plain[0] == 0 && plain[1] == 0
                && (plain[2] == 1 || (plain[2] == 0 && plain[3] == 1))) {
            return true;
        }
        int off = 0;
        int nals = 0;
        while (off + 5 <= n) {
            // plain 是单个样本的裸数组，base=0 视图即可
            long len = u32(ByteView.whole(plain), off);
            if (len <= 0 || off + 4 + len > n) return false;
            // NAL 头：1 bit forbidden_zero + 2 bit nal_ref_idc + 5 bit nal_unit_type
            if ((plain[off + 4] & 0x80) != 0) return false;
            off += 4 + len;
            if (++nals > 64) return false;
        }
        return nals > 0 && (off == n || n - off <= 3);
    }

    /**
     * 挑选探测样本：首个 + 中段若干 + 末尾，跳过 size 过小的样本。
     */
    public static int[] pickProbeIndices(Track track) {
        int n = track.sampleCount;
        if (n == 0) return new int[0];
        int[] cand = {0, 1, 2, n >> 2, n >> 1, n - 1};
        List<Integer> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i : cand) {
            if (i < 0 || i >= n) continue;
            if (!seen.add(i)) continue;
            if (track.sizes[i] >= 8) out.add(i);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        java.util.Arrays.sort(arr);
        return arr;
    }

    /**
     * 用候选 base_iv 试解一个视频轨，返回能通过 AVCC 自证的 base_iv；失败返回 null。
     */
    public static String verifyVideoIv(byte[] buf, Track track, String keyHex, List<String> candidates) {
        return verifyVideoIv(new ByteArrayStore(buf), track, keyHex, candidates);
    }

    /**
     * 用候选 base_iv 试解一个视频轨，返回能通过 AVCC 自证的 base_iv；失败返回 null。
     *
     * 只读不写：因此可在「moov 已解析、样本尚未解密」的任意阶段安全调用。
     */
    public static String verifyVideoIv(SampleStore store, Track track, String keyHex,
                                       List<String> candidates) {
        byte[] key;
        try {
            key = Crypto.unhex(keyHex);
        } catch (Exception e) {
            return null;
        }
        if (key.length != 16) return null;

        boolean hasIvs = track.ivs != null && track.ivs.length >= track.sampleCount;
        List<String> list;
        if (hasIvs) {
            list = new ArrayList<>();
            list.add(track.ivs[0]);
        } else {
            list = candidates != null ? candidates : new ArrayList<>();
        }
        long storeLen = store.length();
        for (String cand : list) {
            int ok = 0;
            int tested = 0;
            for (int i : pickProbeIndices(track)) {
                long off = track.offsets[i];
                long size = track.sizes[i];
                if (size == 0 || off + size > storeLen) continue;
                tested++;
                byte[] counter = counterFor(track, cand, i);
                byte[] plain = Crypto.aes128Ctr(key, counter, store.read(off, (int) size));
                if (avccSelfOk(plain, (int) size)) ok++;
            }
            // 全部探测样本都自洽才算通过：避免 60% 阈值被噪声蒙混
            if (tested > 0 && ok == tested) return cand;
        }
        return null;
    }

    // ==================== 剥离 CENC 信令（纯 Java 减壳） ====================

    /**
     * 视觉样本条目类型（VisualSampleEntry，固定头 78 字节）。
     * encv 为加密视频条目；其余为常见明文视频条目（万一信令已除也认得）。
     */
    private static final Set<String> VISUAL_ENTRY = new HashSet<>(java.util.Arrays.asList(
            "encv", "hvc1", "hev1", "avc1", "av01", "mp4v", "vp08", "vp09", "dvav", "dva1"));

    /**
     * 音频样本条目类型（AudioSampleEntry，QuickTime 布局固定头 28 字节）。
     */
    private static final Set<String> AUDIO_ENTRY = new HashSet<>(java.util.Arrays.asList(
            "enca", "mp4a", "ac-3", "ec-3", "Opus", "fLaC", "samr", "sawb"));

    /** 样本条目固定头长度；未知类型返回 -1（调用方应放弃剥离）。 */
    private static int entryFixedHeader(String type) {
        if (VISUAL_ENTRY.contains(type)) return 78;
        if (AUDIO_ENTRY.contains(type)) return 28;
        return -1;
    }

    /**
     * 剥离 CENC 加密信令，产出普通 mp4 —— 纯 Java 实现，不依赖 ffmpeg。
     *
     * <p>前提：样本数据（mdat）已是解密后的明文（由 offlineDecrypt 完成），
     * 加密信令全部位于 moov：
     * <ul>
     *   <li>stsd 样本条目（encv/enca）内的 {@code sinf}（frma/schm/schi>tenc）；</li>
     *   <li>stbl 内的 {@code senc}/{@code saio}/{@code saiz}（逐样本 IV 与索引）；</li>
     *   <li>moov 直属的 {@code pssh}（DRM 系统声明，若有）。</li>
     * </ul>
     * {@code sgpd}/{@code sbgp} 一律保留：实测本项目的是 {@code roll} 音频分组，
     * 与加密无关（ffmpeg remux 也同样保留）。
     *
     * <p>剥离后 moov 变小，其后所有绝对偏移（stco/co64 的 chunk 偏移）必须统一
     * 左移 delta，因此要求「全部 chunk 偏移都落在 moov 之后」。布局不符时
     * （moov 在尾部、偏移越界、64 位盒、未知条目类型、双 moov 等）返回 false，
     * 由调用方回落 ffmpeg —— 宁可多走一次外部进程，不可产出错位文件。
     *
     * @return true 表示 out 已写好且通过自校验（盒链完整、加密信令除净）
     */
    public static boolean stripEncryption(Path in, Path out) {
        try {
            return strip0(in, out);
        } catch (Exception ignore) {
            // 任何意外都视为「布局不符」，由调用方回落 ffmpeg
            return false;
        }
    }

    private static boolean strip0(Path in, Path out) throws Exception {
        long fileLen = Files.size(in);
        if (fileLen < 16) return false;

        // ---- 1. 只读盒头摸清顶层布局：ftyp / .. / moov / [free|skip]* / mdat ----
        byte[] hdr = new byte[16];
        long moovStart = -1, moovEnd = -1, mdatStart = -1, mdatHeaderSize = 8;
        boolean ftypFirst = false;
        long p = 0;
        try (RandomAccessFile raf = new RandomAccessFile(in.toFile(), "r")) {
            while (p + 8 <= fileLen) {
                raf.seek(p);
                raf.readFully(hdr, 0, 8);
                long size = u32b(hdr, 0);
                String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
                int hs = 8;
                if (size == 1) {
                    raf.readFully(hdr, 8, 8);
                    size = u64b(hdr, 8);
                    hs = 16;
                } else if (size == 0) {
                    size = fileLen - p;
                }
                if (size < hs || p + size > fileLen) return false;
                if (p == 0) {
                    if (!type.equals("ftyp")) return false;
                    ftypFirst = true;
                }
                if (type.equals("moov")) {
                    if (moovStart >= 0) return false; // 双 moov：布局意外，放弃
                    moovStart = p;
                    moovEnd = p + size;
                } else if (type.equals("mdat")) {
                    mdatStart = p;
                    mdatHeaderSize = hs;
                    break;
                }
                p += size;
            }
            if (!ftypFirst || moovStart < 0 || mdatStart < 0 || mdatStart < moovEnd) {
                return false; // 无 moov、moov 在 mdat 之后（尾部 moov 形态）等
            }
            // moov 与 mdat 之间只允许 free / skip（ffmpeg 的 faststart 对齐盒）
            long q = moovEnd;
            while (q < mdatStart) {
                raf.seek(q);
                raf.readFully(hdr, 0, 8);
                long size = u32b(hdr, 0);
                String type = new String(hdr, 4, 4, StandardCharsets.ISO_8859_1);
                int hs = 8;
                if (size == 1) {
                    raf.readFully(hdr, 8, 8);
                    size = u64b(hdr, 8);
                    hs = 16;
                } else if (size == 0) {
                    return false; // 中段盒不允许延伸到文件尾
                }
                if (size < hs || q + size > mdatStart) return false;
                if (!type.equals("free") && !type.equals("skip")) return false;
                q += size;
            }

            // ---- 2. 读入头部段（ftyp .. mdat 盒头，含盒头本身）在内存中修补 ----
            int headLen = (int) (mdatStart + mdatHeaderSize);
            if (headLen > 64 * 1024 * 1024) return false; // moov 异常巨大，放弃
            byte[] head = new byte[headLen];
            raf.seek(0);
            raf.readFully(head);

            // 解析终点取 mdatStart：head 里 mdat 只有盒头（负载不在段内），
            // 声明尺寸会越界，整段解析会在 mdat 处中断。
            int parseEnd = (int) mdatStart;
            List<Box> top = parseBoxes(ByteView.whole(head), 0, parseEnd);
            Box moov = null;
            for (Box b : top) {
                if (b.type.equals("moov")) { moov = b; break; }
            }
            if (moov == null) return false;
            if (top.get(top.size() - 1).end() != parseEnd) return false; // 盒链不收尾

            // ---- 3. 剥离加密信令，沿途修补各层盒尺寸 ----
            List<long[]> removals = new ArrayList<>();
            long delta = pruneEncryption(head, moov, removals);
            if (delta <= 0) return false; // 没有加密信令可剥，交回 ffmpeg 保持原行为

            // ---- 4. chunk 偏移统一左移 delta（全部条目必须落在 moov 之后）----
            for (Box trak : moov.children) {
                if (!trak.type.equals("trak")) continue;
                Box mdia = trak.child("mdia");
                Box stbl = mdia == null ? null : mdia.child("minf") == null
                        ? null : mdia.child("minf").child("stbl");
                if (stbl == null) continue;
                patchChunkOffsets(head, stbl, "stco", 4, delta, moovEnd);
                patchChunkOffsets(head, stbl, "co64", 8, delta, moovEnd);
            }

            // ---- 5. 写出：head 跳过移除段 + mdat 负载从源文件流式拷贝 ----
            removals.sort(Comparator.comparingLong(a -> a[0]));
            for (int i = 1; i < removals.size(); i++) {
                if (removals.get(i)[0] < removals.get(i - 1)[1]) return false; // 重叠即异常
            }
            long mdatPayload = mdatStart + mdatHeaderSize;
            try (OutputStream os = Files.newOutputStream(out)) {
                long copied = 0;
                for (long[] r : removals) {
                    os.write(head, (int) copied, (int) (r[0] - copied));
                    copied = r[1];
                }
                os.write(head, (int) copied, headLen - (int) copied);
                try (InputStream is = Files.newInputStream(in)) {
                    long toSkip = mdatPayload;
                    while (toSkip > 0) {
                        long n = is.skip(toSkip);
                        if (n <= 0) {
                            if (is.read() < 0) return false; // 源文件比预期短
                            toSkip--;
                        } else {
                            toSkip -= n;
                        }
                    }
                    copyStream(is, os, fileLen - mdatPayload);
                }
            }

            // ---- 6. 自校验：回读输出的头部段，验证真正交付的文件 ----
            // （不能校验内存里的 head：那是压缩前的布局，尺寸链不可能收尾）
            long outLen = Files.size(out);
            long outMdatStart = mdatStart - delta;
            if (outLen != fileLen - delta) return false;
            int verifyLen = (int) (outMdatStart + 8);
            byte[] vhead = new byte[verifyLen];
            try (RandomAccessFile raf2 = new RandomAccessFile(out.toFile(), "r")) {
                raf2.readFully(vhead);
            }
            List<Box> chk = parseBoxes(ByteView.whole(vhead), 0, (int) outMdatStart);
            if (chk.isEmpty()) return false;
            if (!chk.get(0).type.equals("ftyp")) return false;
            if (chk.get(chk.size() - 1).end() != outMdatStart) return false;
            if (u32b(vhead, (int) outMdatStart) != outLen - outMdatStart) return false;
            Box moov2 = null;
            for (Box b : chk) {
                if (b.type.equals("moov")) { moov2 = b; break; }
            }
            if (moov2 == null) return false;
            for (Box trak : moov2.children) {
                if (!trak.type.equals("trak")) continue;
                Box mdia = trak.child("mdia");
                Box stbl = mdia == null ? null : mdia.child("minf") == null
                        ? null : mdia.child("minf").child("stbl");
                if (stbl == null) continue;
                for (String t : new String[]{"senc", "saio", "saiz"}) {
                    if (stbl.child(t) != null) return false;
                }
                Box stsd = stbl.child("stsd");
                if (stsd != null) {
                    for (Box e : stsd.children) {
                        // 条目类型必须已改回普通类型（encv/enca 是加密专用）
                        if (!isPlainEntryType(e.type)) return false;
                        int fh = entryFixedHeader(e.type);
                        if (fh < 0) return false;
                        List<Box> kids =
                                parseBoxes(ByteView.whole(vhead), e.start + 8 + fh, e.end());
                        for (Box c : kids) {
                            if (c.type.equals("sinf")) return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * 递归剥离加密信令，返回从 box 子树中移除的字节总数；
     * 沿途就地修补各层盒的 32 位尺寸字段（head 下标即文件绝对偏移）。
     *
     * 只删三类盒：stbl 直属的 senc/saio/saiz、stsd 条目内的 sinf、
     * moov 直属的 pssh。遇到 64 位尺寸盒或未知样本条目类型即抛错
     * （上层捕获后放弃剥离）——修补逻辑只覆盖 32 位尺寸这一种形态。
     */
    private static long pruneEncryption(byte[] head, Box box, List<long[]> removals) {
        long removed = 0;
        if (box.type.equals("stbl")) {
            for (Box c : new ArrayList<>(box.children)) {
                if (c.type.equals("senc") || c.type.equals("saio") || c.type.equals("saiz")) {
                    removals.add(new long[]{c.start, c.end()});
                    removed += c.size;
                    box.children.remove(c);
                }
            }
        } else if (box.type.equals("moov")) {
            for (Box c : new ArrayList<>(box.children)) {
                if (c.type.equals("pssh")) {
                    removals.add(new long[]{c.start, c.end()});
                    removed += c.size;
                    box.children.remove(c);
                }
            }
        } else if (box.type.equals("stsd")) {
            // stsd 的子盒是样本条目（parseBoxes 按叶子处理），
            // 需按条目类型跳固定头后才能看到 sinf。
            for (Box entry : box.children) {
                int fh = entryFixedHeader(entry.type);
                if (fh < 0) {
                    throw new IllegalStateException("未知样本条目类型: " + entry.type);
                }
                List<Box> kids =
                        parseBoxes(ByteView.whole(head), entry.start + 8 + fh, entry.end());
                long er = 0;
                for (Box c : kids) {
                    if (c.type.equals("sinf")) {
                        removals.add(new long[]{c.start, c.end()});
                        er += c.size;
                        // 删信令的同时把条目类型从 encv/enca 改回原始类型
                        // （frma 权威记录，解码配置子盒兜底推断）——
                        // 否则条目仍是 CENC 加密类型，播放器不认
                        rewriteEntryType(head, entry, c);
                    } else {
                        er += pruneEncryption(head, c, removals);
                    }
                }
                if (er > 0) {
                    patchBoxSize(head, entry, entry.size - er);
                    removed += er;
                }
            }
            // stsd 自身也随条目一起缩小（调用方只按返回值修上层盒，
            // 不会穿透到 stsd——漏了这步盒链会在 stsd 处断裂）
            if (removed > 0) patchBoxSize(head, box, box.size - removed);
            return removed;
        }
        for (Box c : box.children) {
            removed += pruneEncryption(head, c, removals);
        }
        if (removed > 0) patchBoxSize(head, box, box.size - removed);
        return removed;
    }

    /**
     * 把样本条目的类型从加密类型（encv/enca）改写回原始类型。
     * 权威来源是 sinf 内 frma 盒的 data_format（加密前的条目类型）；
     * frma 缺失或不可识别时按条目内的解码配置子盒推断
     * （hvcC→hvc1、avcC→avc1、av1C→av01、esds→mp4a）；
     * 都判不出则抛错放弃剥离——类型写错比不剥更糟。
     */
    private static void rewriteEntryType(byte[] head, Box entry, Box sinf) {
        String nt = plainEntryType(head, entry, sinf);
        if (nt == null) {
            throw new IllegalStateException("无法确定样本条目原始类型，放弃剥离: " + entry.type);
        }
        byte[] tb = nt.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(tb, 0, head, (int) entry.start + 4, 4);
    }

    /** 推断样本条目的原始（非加密）类型；判不出返回 null。 */
    private static String plainEntryType(byte[] head, Box entry, Box sinf) {
        // 1. sinf 内 frma 的 data_format：加密前的原始条目类型（权威）
        List<Box> inner = parseBoxes(ByteView.whole(head), sinf.start + 8, sinf.end());
        for (Box b : inner) {
            if (b.type.equals("frma") && b.size >= 12 && b.headerSize == 8) {
                String f = new String(head, (int) b.start + 8, 4,
                        StandardCharsets.ISO_8859_1);
                if (isPlainEntryType(f)) return f;
            }
        }
        // 2. 按解码配置子盒推断
        List<Box> kids = parseBoxes(ByteView.whole(head),
                entry.start + 8 + entryFixedHeader(entry.type), entry.end());
        for (Box b : kids) {
            switch (b.type) {
                case "hvcC": return "hvc1";
                case "avcC": return "avc1";
                case "av1C": return "av01";
                case "esds": return "mp4a";
                default: break;
            }
        }
        return null;
    }

    /** 是否为普通（非加密）样本条目类型。 */
    private static boolean isPlainEntryType(String t) {
        return !t.equals("encv") && !t.equals("enca")
                && (VISUAL_ENTRY.contains(t) || AUDIO_ENTRY.contains(t));
    }

    /** 就地修补盒的 32 位尺寸字段；64 位盒直接抛错（放弃剥离）。 */
    private static void patchBoxSize(byte[] head, Box box, long newSize) {
        if (box.headerSize != 8) {
            throw new IllegalStateException("64 位盒尺寸，放弃剥离: " + box.type);
        }
        if (newSize < 8 || newSize > 0xFFFFFFFFL) {
            throw new IllegalStateException("盒尺寸越界，放弃剥离: " + box.type);
        }
        putU32(head, (int) box.start, newSize);
    }

    /**
     * 把 stco/co64 的全部 chunk 偏移左移 delta。
     * 任一条目落在 moovEnd 之前（不该发生）即抛错——布局不符时整体放弃剥离。
     */
    private static void patchChunkOffsets(byte[] head, Box stbl, String type, int width,
                                          long delta, long moovEnd) {
        Box b = stbl.child(type);
        if (b == null) return;
        if (b.headerSize != 8) {
            throw new IllegalStateException("64 位偏移盒，放弃剥离: " + type);
        }
        long p = b.start + 12; // 跳过盒头(8) + version/flags(4)
        int n = (int) u32b(head, (int) p);
        p += 4; // entry_count
        if (n < 0 || p + (long) n * width > b.end()) {
            throw new IllegalStateException("偏移表长度异常，放弃剥离: " + type);
        }
        for (int i = 0; i < n; i++) {
            long v = width == 4 ? u32b(head, (int) p) : u64b(head, (int) p);
            if (v < moovEnd) {
                throw new IllegalStateException("chunk 偏移落在 moov 之前，放弃剥离");
            }
            if (width == 4) putU32(head, (int) p, v - delta);
            else putU64(head, (int) p, v - delta);
            p += width;
        }
    }

    /** 从 is 拷贝恰好 len 字节到 os（短读/短写都补齐，源不足则抛错）。 */
    private static void copyStream(InputStream is, OutputStream os, long len)
            throws IOException {
        byte[] buf = new byte[65536];
        long left = len;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int n = is.read(buf, 0, want);
            if (n < 0) throw new IOException("源文件在读流中提前结束");
            os.write(buf, 0, n);
            left -= n;
        }
    }

    private static long u32b(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((long) (b[p + 1] & 0xFF) << 16)
             | ((long) (b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    private static long u64b(byte[] b, int p) {
        long r = 0;
        for (int i = 0; i < 8; i++) r = (r << 8) | (b[p + i] & 0xFF);
        return r;
    }

    private static void putU32(byte[] b, int p, long v) {
        b[p] = (byte) (v >>> 24);
        b[p + 1] = (byte) (v >>> 16);
        b[p + 2] = (byte) (v >>> 8);
        b[p + 3] = (byte) v;
    }

    private static void putU64(byte[] b, int p, long v) {
        for (int i = 0; i < 8; i++) b[p + i] = (byte) (v >>> (56 - 8 * i));
    }
}
