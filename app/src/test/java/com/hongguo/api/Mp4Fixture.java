package com.hongguo.api;

import java.io.ByteArrayOutputStream;

/**
 * 自检用 MP4 夹具：构造一个最小但结构完整的 CENC 加密 mp4。
 *
 * 目的：让 --selftest 能在无网络、无外部样本的情况下验证
 * Mp4.analyzeEncryptedTracks 的盒解析、样本表展开与 IV 提取是否正确。
 *
 * 结构：
 *   ftyp
 *   moov
 *     mvhd
 *     trak
 *       tkhd
 *       mdia
 *         hdlr (vide)
 *         minf
 *           stbl
 *             stsd (avc1 / encv 占位)
 *             stts / stsc / stsz / stco
 *             senc  (2 个样本，各 8 字节 IV)
 *             sinf
 *               schm (cenc)
 *               schi
 *                 tenc (default_KID 16B)
 *   mdat（真实密文：由固定 content key 加密合法 AVCC 明文而来）
 *
 * 自检价值：解密后能拿明文比对，且明文是合法 AVCC 长度前缀链，
 * 可通过 Mp4.avccSelfOk 自证 —— 这正是真实链路的判定方式。
 */
final class Mp4Fixture {

    private Mp4Fixture() {}

    private static final byte[] KID = {
        (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0x44,
        (byte) 0x55, (byte) 0x66, (byte) 0x77, (byte) 0x88,
        (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
        (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00,
    };

    /**
     * 夹具用的 content key（固定值，便于自检做「解密还原」断言）。
     * 与真实链路无关，仅供自检闭环。
     */
    private static final byte[] CONTENT_KEY = {
        (byte) 0xA1, (byte) 0xB2, (byte) 0xC3, (byte) 0xD4,
        (byte) 0xE5, (byte) 0xF6, (byte) 0x07, (byte) 0x18,
        (byte) 0x29, (byte) 0x3A, (byte) 0x4B, (byte) 0x5C,
        (byte) 0x6D, (byte) 0x7E, (byte) 0x8F, (byte) 0x90,
    };

    /** 每个样本的逐样本 IV（16 字节标准 CENC 形态）。 */
    private static final byte[][] IVS = {
        {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
         0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
        {0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02,
         0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00},
    };

    /** 构造明文样本：合法 AVCC 长度前缀链，解密后应通过 avccSelfOk 自证。 */
    private static byte[][] buildPlainSamples() {
        byte[][] plains = new byte[SAMPLE_COUNT][];
        // 样本 0：SPS + IDR 两个 NAL
        plains[0] = new byte[]{
                0, 0, 0, 2, 0x67, 0x42,          // NAL type 7 (SPS)
                0, 0, 0, 3, 0x65, (byte) 0x88, (byte) 0x84}; // NAL type 5 (IDR)
        // 样本 1：PPS + SEI + 尾部分片
        plains[1] = new byte[]{
                0, 0, 0, 2, 0x68, (byte) 0xCE,    // NAL type 8 (PPS)
                0, 0, 0, 2, 0x06, 0x05,          // NAL type 6 (SEI)
                0, 0, 0, 1, 0x41};               // NAL type 1 (非IDR 分片)
        return plains;
    }

    /** 用content key +逐样本 IV 加密明文样本，得到 mdat 负载。 */
    private static byte[] buildCipherPayload() {
        byte[][] plains = buildPlainSamples();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < SAMPLE_COUNT; i++) {
            out.write(com.hongguo.api.util.Crypto.aes128Ctr(
                    CONTENT_KEY, IVS[i], plains[i]), 0, plains[i].length);
        }
        return out.toByteArray();
    }

    /** 自检用的 content key（hex）。 */
    static String fixtureKeyHex() {
        return com.hongguo.api.util.Crypto.hex(CONTENT_KEY);
    }

    /** 自检用的明文样本，供解密还原比对。 */
    static byte[][] plainSamples() {
        return buildPlainSamples();
    }

    /** 构造样本数据：2 个样本。 */
    private static final int SAMPLE_COUNT = 2;

    /**
     * 构造 moov 位于**文件末尾**的加密 mp4（非 faststart 形态）。
     *
     * 为什么需要：真实短剧源流并非都是 faststart，moov 常在尾部。
     * 流式解密方案必须能在不读全文的前提下定位尾部 moov
     * （Mp4.findMoov 只读盒头并按 p += size 跳跃），
     * 而早期夹具只有 moov 在头部的形态，无法覆盖该分支。
     *
     * 结构：ftyp / mdat（密文）/ moov（索引，含回填的 stco 绝对偏移）
     */
    static byte[] buildTailMoovEncryptedMp4() {
        try {
            byte[] plain = buildMinimalEncryptedMp4();
        // 从头部形态里取出 moov 段（找到 'moov' 后回退 4 字节即为盒头）
        int moovPos = indexOf(plain, "moov".getBytes("ISO-8859-1"));
        if (moovPos < 4) throw new IllegalStateException("夹具异常：未找到 moov");
        int moovStart = moovPos - 4;
        int moovSize = (int) u32At(plain, moovStart);
        byte[] moov = java.util.Arrays.copyOfRange(plain, moovStart, moovStart + moovSize);
        byte[] ftyp = java.util.Arrays.copyOfRange(plain, 0, moovStart);
        // mdat：从头部形态里切出来（含盒头）
        byte[] mdat = sliceMdat(plain);

        // 组装：ftyp + mdat + moov，并在moov 内部回填 chunk 偏移
        int mdatPayloadStart = ftyp.length + 8;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(ftyp, 0, ftyp.length);
        bos.write(mdat, 0, mdat.length);
        byte[] moovFixed = patchStco(moov, mdatPayloadStart);
        bos.write(moovFixed, 0, moovFixed.length);
        return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("构造尾部 moov 夹具失败", e);
        }
    }

    /** 取出头部形态夹具里的 mdat 盒。 */
    private static byte[] sliceMdat(byte[] all) throws Exception {
        int p = indexOf(all, "mdat".getBytes("ISO-8859-1"));
        if (p < 4) throw new IllegalStateException("夹具异常：未找到 mdat");
        int start = p - 4;
        int size = (int) u32At(all, start);
        return java.util.Arrays.copyOfRange(all, start, start + size);
    }

    /** 把 moov 内 stco 的 chunk 偏移改为 chunkOffset，保持 stco 盒长度不变。 */
    private static byte[] patchStco(byte[] moov, int chunkOffset) throws Exception {
        int p = indexOf(moov, "stco".getBytes("ISO-8859-1"));
        if (p < 0) throw new IllegalStateException("夹具异常：moov 内未找到 stco");
        int boxStart = p - 4;
        int boxSize = (int) u32At(moov, boxStart);
        byte[] out = java.util.Arrays.copyOf(moov, moov.length);
        // payload: version/flags(4) + entry_count(4) + 偏移(4)
        int offPos = boxStart + 8 + 8;
        out[offPos] = (byte) (chunkOffset >>> 24);
        out[offPos + 1] = (byte) (chunkOffset >>> 16);
        out[offPos + 2] = (byte) (chunkOffset >>> 8);
        out[offPos + 3] = (byte) chunkOffset;
        // 确认盒长度未变（原地补丁的前提）
        if ((int) u32At(out, boxStart) != boxSize) {
            throw new IllegalStateException("夹具异常：stco 长度变化");
        }
        return out;
    }

    private static long u32At(byte[] b, int p) {
        return ((long) (b[p] & 0xFF) << 24) | ((long) (b[p + 1] & 0xFF) << 16)
             | ((long) (b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    static byte[] buildMinimalEncryptedMp4() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // ---- ftyp ----
            byte[] ftyp = box("ftyp", concat(
                    "isom".getBytes("ISO-8859-1"),
                    u32(512),
                    "isom".getBytes("ISO-8859-1"),
                    "iso2".getBytes("ISO-8859-1")));
            out.write(ftyp);

            // ---- moov ----
            byte[] mvhd = box("mvhd", concat(
                    new byte[]{0, 0, 0, 0},       // version/flags
                    u32(0), u32(0),                // creation/modification
                    u32(90000),                    // timescale
                    u32(0),                        // duration
                    u32(0x00010000), u32(0),       // rate/volume
                    new byte[10],                  // reserved
                    unityMatrix(),
                    new byte[24],                  // predefined
                    u32(2)));                      // next track id
            byte[] tkhd = box("tkhd", concat(
                    new byte[]{0, 0, 0, 3},       // version 0, flags=enabled
                    u32(0), u32(0),
                    u32(1),                       // track id
                    u32(0),                       // reserved
                    u32(180000),                  // duration
                    new byte[8],                  // reserved
                    u16(0), u16(0),               // layer/altgroup
                    u16(0), u16(0),               // volume/reserved
                    unityMatrix(),
                    u32(1920 << 16), u32(1080 << 16)));
            byte[] hdlr = box("hdlr", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(0),
                    "vide".getBytes("ISO-8859-1"),
                    new byte[12],
                    "VideoHandler".getBytes("ISO-8859-1"),
                    new byte[1]));
            byte[] schm = box("schm", concat(
                    new byte[]{0, 0, 0, 0},
                    "cenc".getBytes("ISO-8859-1"),
                    u32(0)));
            // tenc（version 0）：1 字节 version + 3 字节 flags
            //                 + 1 字节 default_Per_Sample_IV_Size + 16 字节 default_KID
            // 注意：version 0 没有 version 1 才有的 16 字节 reserved 字段，
            // 多写会让解析器整体偏移，KID 读出来全零。
            byte[] tenc = box("tenc", concat(
                    new byte[]{0, 0, 0, 2},          // version 0 + flags 0x02 (isProtected)
                    new byte[]{16},                  // default_Per_Sample_IV_Size = 16
                    KID));
            byte[] schi = box("schi", tenc);
            // frma：记录加密前的原始条目类型。真实 CENC 文件必有此盒，
            // 减壳时靠它把 encv/enca 改回 hvc1/mp4a——只删 sinf 不改类型，
            // 播放器会把条目当加密流而拒绝解码（ffprobe codec_name 为 null）。
            byte[] frma = box("frma", "hvc1".getBytes("ISO-8859-1"));
            byte[] sinf = box("sinf", concat(frma, schm, schi));

            // sinf 必须挂在 sample entry（encv）内，而不是 stbl 直接子级 ——
            // 这才是真实 CENC 文件的层级。stbl 只认CONTAINERS 白名单里的盒型，
            // 写成 stbl 的直接子级会导致 sinf.children 为空、tenc 读不到。
            // encv 布局：6 字节 reserved + 2 字节 data_ref_index
            //            + VisualSampleEntry 字段(70) + 子盒
            // 子盒除 sinf 外还带 hvcC（HEVC 解码配置）——真实加密条目即如此，
            // 减壳在 frma 缺失时靠它兜底推断原始类型。
            byte[] hvcC = box("hvcC", new byte[32]);
            byte[] encv = box("encv", concat(
                    new byte[6], u16(1), new byte[70], hvcC, sinf));

            byte[] stsd = box("stsd", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(1),
                    encv));
            // ---- stsz：逐样本记录真实尺寸 ----
            byte[][] plains = buildPlainSamples();
            ByteArrayOutputStream sz = new ByteArrayOutputStream();
            for (byte[] p : plains) sz.write(u32(p.length));
            byte[] stszFull = box("stsz", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(0),
                    u32(SAMPLE_COUNT),
                    sz.toByteArray()));
            byte[] stsc = box("stsc", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(1),                        // entry count
                    u32(1), u32(SAMPLE_COUNT), u32(1))); // 1 chunk含 2 样本
            // chunk 偏移先占位，稍后回填
            byte[] stcoPlaceholder = box("stco", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(1),
                    u32(0)));

            // ---- senc：version0 + flags0 + sample_count + 每样本 16 字节 IV ----
            //  刻意用标准 16 字节 IV 形态：项目实测的PIFF 8 字节 IV 由
            //  Mp4.ivToCounter 的独立用例覆盖，两种形态都要测到。
            ByteArrayOutputStream sencBody = new ByteArrayOutputStream();
            sencBody.write(new byte[]{0, 0, 0, 0});
            sencBody.write(u32(SAMPLE_COUNT));
            for (byte[] iv : IVS) sencBody.write(iv);
            byte[] senc = box("senc", sencBody.toByteArray());

            byte[] stbl = box("stbl", concat(
                    stsd, stsc, stszFull, stcoPlaceholder, senc));
            byte[] minf = box("minf", stbl);
            byte[] mdia = box("mdia", concat(hdlr, minf));
            byte[] trak = box("trak", concat(tkhd, mdia));
            byte[] moov = box("moov", concat(mvhd, trak));
            out.write(moov);

            // ---- mdat：写入真实密文（由固定 content key 加密明文 AVCC 而来）----
            int mdatStart = out.size();
            out.write(box("mdat", buildCipherPayload()));

            // 回填 chunk 偏移：指向 mdat payload 起点
            int chunkOffset = mdatStart + 8;
            byte[] stco = box("stco", concat(
                    new byte[]{0, 0, 0, 0},
                    u32(1),
                    u32(chunkOffset)));
            int stcoPos = indexOf(out.toByteArray(), "stco".getBytes("ISO-8859-1"));
            byte[] all = out.toByteArray();
            int newStcoLen = stco.length;
            int delta = newStcoLen - stcoPlaceholder.length;
            // 若长度变化则整体重建（保持简单：长度相同则原地替换）
            if (delta == 0) {
                System.arraycopy(stco, 0, all, stcoPos - 4, stco.length);
                return all;
            }
            // 长度不同：递归重建 moov
            return rebuild(out.toByteArray(), mdatStart);
        } catch (Exception e) {
            throw new RuntimeException("构造测试 mp4 失败", e);
        }
    }

    /** 递归替换 stco 内容并重建（处理长度变化）。 */
    private static byte[] rebuild(byte[] all, int mdatStart) throws Exception {
        int stcoPos = indexOf(all, "stco".getBytes("ISO-8859-1"));
        int boxStart = stcoPos - 4;
        int oldSize = ((all[boxStart] & 0xFF) << 24) | ((all[boxStart + 1] & 0xFF) << 16)
                     | ((all[boxStart + 2] & 0xFF) << 8) | (all[boxStart + 3] & 0xFF);
        int chunkOffset = mdatStart + 8;
        byte[] stco = box("stco", concat(
                new byte[]{0, 0, 0, 0}, u32(1), u32(chunkOffset)));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(all, 0, boxStart);
        bos.write(stco);
        bos.write(all, boxStart + oldSize, all.length - (boxStart + oldSize));
        return bos.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // ==================== 基础工具 ====================

    /** 构造标准盒（32 位长度 + type + payload）。 */
    private static byte[] box(String type, byte[] payload) throws Exception {
        byte[] t = type.getBytes("ISO-8859-1");
        int size = 8 + payload.length;
        return concat(u32(size), t, payload);
    }

    private static byte[] u32(int v) {
        return new byte[]{
            (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    private static byte[] unityMatrix() {
        return concat(
                u32(0x00010000), u32(0), u32(0),
                u32(0), u32(0x00010000), u32(0),
                u32(0), u32(0), u32(0x40000000));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) bos.write(p, 0, p.length);
        return bos.toByteArray();
    }
}
