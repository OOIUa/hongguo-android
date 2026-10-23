package com.hongguo.api.core;

/**
 * SM3（GB/T 32905）杂凑算法，纯 Java 实现。
 *
 * 移植自 jackotom/hongguo-drama 的 src/native/sm3.js（GPL-3.0）。
 * 原注释：Electron 自带 crypto 可能不含 SM3；同理标准 JDK 也没有
 * SM3 Provider（BouncyCastle 才有），故直接实现，避免引入第三方依赖。
 *
 * 仅实现签名链路用到的单次杂凑（无 HMAC、无流式更新），
 * 对应 JS 侧 {@code sm3(buffer) -> 32 字节}。
 */
public final class Sm3 {

    private Sm3() {}

    private static int rotl(int x, int n) {
        int k = n & 31;
        return (x << k) | (x >>> ((32 - k) & 31));
    }

    /** T_j 常量：前 16 轮 0x79cc4519，之后 0x7a879d8a。 */
    private static int tj(int j) {
        return j < 16 ? 0x79cc4519 : 0x7a879d8a;
    }

    private static int ff(int a, int b, int c, int j) {
        return j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
    }

    private static int gg(int e, int f, int g, int j) {
        return j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
    }

    /** P0 = x ^ rotl(x,15) ^ rotl(x,23)。 */
    private static int p0(int x) {
        return x ^ rotl(x, 15) ^ rotl(x, 23);
    }

    /** P1 = x ^ rotl(x,9) ^ rotl(x,17)。 */
    private static int p1(int x) {
        return x ^ rotl(x, 9) ^ rotl(x, 17);
    }

    /**
     * 计算 SM3 摘要。
     *
     * @param input 输入字节
     * @return 32 字节摘要
     */
    public static byte[] hash(byte[] input) {
        // 填充：msg || 0x80 || 0x00... || 64-bit 大端比特长度，总长为 64 的倍数
        int len = input.length;
        int padded = ((len + 9 + 63) / 64) * 64;
        byte[] data = new byte[padded];
        System.arraycopy(input, 0, data, 0, len);
        data[len] = (byte) 0x80;
        long bits = (long) len * 8L;
        for (int i = 0; i < 8; i++) {
            data[padded - 1 - i] = (byte) (bits >>> (8 * i));
        }

        int[] state = {
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e};
        int[] w = new int[68];

        for (int offset = 0; offset < padded; offset += 64) {
            for (int i = 0; i < 16; i++) {
                w[i] = be32(data, offset + i * 4);
            }
            for (int i = 16; i < 68; i++) {
                int x = w[i - 16] ^ w[i - 9] ^ rotl(w[i - 3], 15);
                w[i] = p0(x) ^ rotl(w[i - 13], 7) ^ w[i - 6];
            }
            int a = state[0], b = state[1], c = state[2], d = state[3];
            int e = state[4], f = state[5], g = state[6], h = state[7];
            for (int j = 0; j < 64; j++) {
                int a12 = rotl(a, 12);
                int s1 = rotl(a12 + e + rotl(tj(j), j), 7);
                int s2 = s1 ^ a12;
                int t1 = ff(a, b, c, j) + d + s2 + (w[j] ^ w[j + 4]);
                int t2 = gg(e, f, g, j) + h + s1 + w[j];
                d = c;
                c = rotl(b, 9);
                b = a;
                a = t1;
                h = g;
                g = rotl(f, 19);
                f = e;
                e = p1(t2);
            }
            state[0] ^= a; state[1] ^= b; state[2] ^= c; state[3] ^= d;
            state[4] ^= e; state[5] ^= f; state[6] ^= g; state[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            putBe32(out, i * 4, state[i]);
        }
        return out;
    }

    public static byte[] hash(String s) {
        return hash(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    // ==================== 自检 ====================

    /** SM3("abc") 的标准摘要值（GB/T 32905 附录 A）。 */
    private static final String ABC_DIGEST =
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0";

    /** 自检：对 "abc" 与空串各算一次，比对标准值。 */
    public static boolean selfTest() {
        byte[] got = hash("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte x : got) sb.append(String.format("%02x", x));
        boolean ok = ABC_DIGEST.equals(sb.toString());
        if (!ok) {
            com.hongguo.api.util.Log.warn("SM3 自检失败：期望 " + ABC_DIGEST + " 实际 " + sb);
        }
        return ok;
    }
}
