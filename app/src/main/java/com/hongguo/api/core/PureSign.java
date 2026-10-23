package com.hongguo.api.core;

import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果 App 接口签名，纯 Java 实现（不再依赖 unidbg + libmetasec_ml.so）。
 *
 * 移植自 jackotom/hongguo-drama 的 src/native/app-source.js（GPL-3.0，
 * 其签名与 spade 算法改编自 woshishiq1/drpys，经 N3urda/hongguoTV 传递）。
 * 原实现用 unidbg 在 x86 上模拟 ARM64 so 计算同样的六个签名头；
 * 此处按同一算法直接计算，字节级对齐：
 *
 *   x-gorgon  : RC4 变体 + 位旋转/位反转后处理
 *   x-khronos : 秒级时间戳
 *   x-ladon   : be32(khronos) 的 base64
 *   x-argus   : le32(khronos) 的 base64
 *   x-helios  : md5 -> 64 位字表 -> 类 Feistel 变换的 base64
 *   x-medusa  : sm3(query) + md5(body) 经 hashF13 分支 -> protobuf 消息
 *               -> xmxor 混淆 -> 逆序 -> 异或 -> AesV3 加密的 base64
 *
 * 用法：{@link #sign(String, byte[])} 传入完整 URL（含 query）与请求体，
 * 返回需要合并进请求头的签名字段。
 *
 * 注意：签名对 query 字节级敏感，URL 必须与最终发出的请求完全一致
 * （本类只读 url 的 '?' 之后部分做 sm3，因此调用方传入什么就签什么）。
 */
public final class PureSign {

    private PureSign() {}

    private static final SecureRandom RND = new SecureRandom();

    // ==================== 基础位运算（对齐 JS 的 u32 语义） ====================

    private static int rotl(int x, int n) {
        int k = n & 31;
        return (x << k) | (x >>> ((32 - k) & 31));
    }

    private static int ror(int x, int n) {
        int k = n & 31;
        return (x >>> k) | (x << ((32 - k) & 31));
    }

    /** 8 位循环左移。 */
    private static int rotl8(int x) {
        return ((x << 4) | (x >>> 4)) & 255;
    }

    /** 8 位循环左移 3 位（xmxor 专用，与 rotl8 的移位数不同）。 */
    private static int rotl8_3(int x) {
        return ((x << 3) | (x >>> 5)) & 255;
    }

    /** 反转一个字节的 8 个位。 */
    private static int reverseBits(int value) {
        int r = 0;
        for (int i = 0; i < 8; i++) r = (r << 1) | ((value >> i) & 1);
        return r;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    private static void putBe32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static byte[] le32(int v) {
        byte[] b = new byte[4];
        putLe32(b, 0, v);
        return b;
    }

    private static byte[] be32(int v) {
        byte[] b = new byte[4];
        putBe32(b, 0, v);
        return b;
    }

    private static long readU64Le(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    private static void putU64Be(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    private static void putU64Le(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static byte[] xorBytes(byte[] a, byte[] b) {
        byte[] out = a.clone();
        for (int i = 0; i < out.length; i++) out[i] ^= b[i % b.length];
        return out;
    }

    private static byte[] md5(byte[] data) {
        return Crypto.md5(data);
    }

    private static byte[] sm3(byte[] data) {
        return Sm3.hash(data);
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    // ==================== protobuf 手写编码 ====================

    private static byte[] varint(long value) {
        // 仅签路使用非负值（负值一律走 sint 的 zigzag）
        long n = value;
        List<Byte> out = new ArrayList<>();
        while (n > 127) {
            out.add((byte) ((n & 127) | 128));
            n >>= 7;
        }
        out.add((byte) n);
        byte[] r = new byte[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    private static long zigzag(long value) {
        return value < 0 ? ((-value) * 2 - 1) : value * 2;
    }

    /** 单个 protobuf 字段。type: string/bytes/message/float/sint/int。 */
    private static byte[] protoField(long tag, Object value, String type) {
        if (value == null) return new byte[0];
        if ("string".equals(type) && "".equals(value)) return new byte[0];
        byte[] head;
        byte[] body;
        if ("bytes".equals(type)) {
            body = (byte[]) value;
            head = concat(varint((tag << 3) | 2), varint(body.length));
        } else if ("message".equals(type)) {
            body = (byte[]) value;
            head = concat(varint((tag << 3) | 2), varint(body.length));
        } else if ("string".equals(type)) {
            body = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            head = concat(varint((tag << 3) | 2), varint(body.length));
        } else if ("float".equals(type)) {
            body = new byte[4];
            int bits = Float.floatToIntBits(((Number) value).floatValue());
            putLe32(body, 0, bits);
            head = varint((tag << 3) | 5);
        } else {
            long v = ((Number) value).longValue();
            head = varint(tag << 3);
            body = varint("sint".equals(type) ? zigzag(v) : v);
        }
        return concat(head, body);
    }

    /** proto([[tag, value, type], ...]) 的紧凑写法。 */
    private static byte[] proto(Object[][] fields) {
        List<byte[]> parts = new ArrayList<>();
        for (Object[] f : fields) {
            String type = f.length > 2 ? String.valueOf(f[2]) : "string";
            parts.add(protoField(((Number) f[0]).longValue(), f[1], type));
        }
        return concat(parts.toArray(new byte[0][]));
    }

    // ==================== hashF13 家族 ====================

    /** getIv：按字节奇偶交替混合的 32 位状态机。 */
    private static int getIv(int iv, byte[] data) {
        int value = iv;
        for (int i = 0; i < data.length; i++) {
            int d = data[i] & 0xFF;
            if ((i & 1) == 0) {
                value = ((value >>> 4) ^ value ^ (value << 6) ^ d);
            } else {
                value = ~((value >>> 7) ^ value ^ (d | (value << 12)));
            }
        }
        return value;
    }

    /** sumMd5：取前 12 字节的固定轮混淆，末位或 4 再异或 0x1000000。 */
    private static int sumMd5(byte[] data) {
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int temp = (i & 1) == 0 ? ((check >>> 3) ^ check) : ((check >>> 5) ^ check);
            check = (i & 1) == 0 ? ((data[i] & 0xFF) ^ (check << 7))
                                 : ((data[i] & 0xFF) | (check << 11));
            if ((i & 1) != 0) check = ~check;
            check = (check ^ temp);
        }
        return ((check | 4) ^ 0x1000000);
    }

    private static final int[] BRANCH2_SV = {
            0xa7aefe20, 0x7149f1d6, 0x47e4ca07, 0xe9b58f67, 0x93b924de, 0xc614d0f5, 0x38afe0ef, 0xb2bbad73,
            0xe24444c3, 0x9d3aec9b, 0xdf7b37e4, 0xd8b16d40, 0xf8ac31b8, 0x76b9a90b, 0x31d833ee, 0x953fce64,
            0x353595a4, 0x4609c13b, 0x36925008, 0x8c6d0925, 0x5df5c177, 0x1cfbf52b, 0x8a4fa7f0, 0x114ca35e,
            0x8193f984, 0x7a7a8733, 0x316ab4d5, 0x3c20cfc9, 0xa6d84453, 0x3a18500c, 0x798ec47a, 0x97a76b28,
            0x66c4ff96, 0x51716443, 0x0dd2fc3b, 0xb5696da7, 0xbbeb3ac5, 0x5c53d204, 0xd32608ce, 0x7279b9ec,
            0xf4188ecf, 0xf7d793db, 0x332cc491, 0xab76ae15, 0x9bebe727, 0x18a01384, 0x5be9f8a7, 0x5f90a754,
            0x39b663c0, 0x36673c83, 0x7c92f514, 0x9d7d94d7, 0xe2e8d9aa, 0x5f7e9ea9, 0x7abd4551, 0x569e05da,
            0x40a25632, 0x3df5a9a5, 0xbab37d80, 0x454286dc, 0x3f5d4e78, 0x03d7b75d, 0xb1fe4af7, 0xa5ab26a3};

    private static final byte[] BRANCH2_ORDERS = Crypto.unhex(
            "0f0704000908030a060b050d0e010c02"
            + "0f05080c0009020103070e060b0a0d04"
            + "060500070c000a04080f010b0d09020e"
            + "060b02050403080101070a000d0c090e"
            + "0d070e0f0b0208030c0509010004060a"
            + "0d0902060f0b0a040807000c0503010e"
            + "0c090f07060f030e020d0405010b0a00"
            + "0c050a090e0802040407030f01060b00"
            + "0b0f0408020a0700090d06010e03050c"
            + "0b060a0508020c03070f0e090d000104"
            + "0906080f050800040a0b030d01020c0e"
            + "090d0c0604070a03030f00080105020e"
            + "01000d0f090a0b0e0402080703060c05"
            + "01080a0c0f0905060b000304020e070d"
            + "04080f000c0f0e0d0a01060207090503"
            + "040205080d0b0a0606000e0f070c0903"
            + "0a08040f000b01060d0c0709030e0502"
            + "0a070b050f00020e0108030d0c060904"
            + "0d070f08050f06040b0a0e0c09000203"
            + "0d0c020704010b0e0e08060f09050003");

    private static int md5V3Step(int kind, int a, int b, int c, int d, int m, int shift, int constant) {
        int f;
        if (kind == 0) f = (b & c) | (~b & d);
        else if (kind == 1) f = (b & d) | (c & ~d);
        else if (kind == 2) f = b ^ c ^ d;
        else f = c ^ (b | ~d);
        return (b + rotl(a + f + m + constant, shift));
    }

    /** MD5 变体：固定轮常量表 + 消息编排表驱动的 64 轮压缩。 */
    private static byte[] md5SumV3(byte[] message, int countV2, byte[] orders, int countV1) {
        int[] sv = new int[BRANCH2_SV.length];
        for (int i = 0; i < sv.length; i++) sv[i] = ror(BRANCH2_SV[i], countV1);
        int[] start = {ror(0x79e0f2fb, countV2), ror(0xc8b52570, countV2),
                       ror(0xebc2f8cd, countV2), ror(0x7c104d93, countV2)};
        int endCount = (countV2 + 6) & 255;
        int[] end = {ror(0x19be4866, endCount), ror(0xe85986b4, endCount),
                     ror(0xe19b326e, endCount), ror(0x71d1d7d4, endCount)};
        int[] m = new int[16];
        for (int i = 0; i < 16; i++) m[i] = le32(message, i * 4);
        int a = start[0], b = start[1], c = start[2], d = start[3];

        int[] shifts0 = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22};
        int[] shifts1 = {5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20};
        int[] shifts2 = {4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23};
        int[] shifts3 = {6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

        for (int kind = 0; kind < 4; kind++) {
            int[] shifts = kind == 0 ? shifts0 : kind == 1 ? shifts1 : kind == 2 ? shifts2 : shifts3;
            int offset = kind * 16;
            for (int i = 0; i < 16; i++) {
                int phase = i & 3;
                int mm = m[orders[offset + i] & 0xFF];
                int cc = sv[offset + i];
                int next;
                if (phase == 0) next = md5V3Step(kind, a, b, c, d, mm, shifts[i], cc);
                else if (phase == 1) next = md5V3Step(kind, d, a, b, c, mm, shifts[i], cc);
                else if (phase == 2) next = md5V3Step(kind, c, d, a, b, mm, shifts[i], cc);
                else next = md5V3Step(kind, b, c, d, a, mm, shifts[i], cc);
                if (phase == 0) a = next;
                else if (phase == 1) d = next;
                else if (phase == 2) c = next;
                else b = next;
            }
        }
        byte[] ret = new byte[16];
        putLe32(ret, 0, (start[0] + a) ^ end[0]);
        putLe32(ret, 4, (start[1] + b) ^ end[1]);
        putLe32(ret, 8, (start[2] + c) ^ end[2]);
        putLe32(ret, 12, (start[3] + d) ^ end[3]);
        return concat(ret, le32(sumMd5(ret)));
    }

    private static byte[] branch2F13(int iv, byte[] querySm3, byte[] bodyMd5, byte[] tsBytes, long khronos) {
        int v = (iv & 13) * 86;
        int ivV0 = ((v >>> 15) & 255) + ((v >>> 8) & 255);
        int n1 = new int[]{0x8980f29b, 0xeb549c7f, 0xb08726db, 0xd40cb5e6, 0xe8f559e4}[ivV0];
        int countV1 = (int) ((n1 + khronos + 1) & 255);
        int countV2 = (int) (n1 + khronos);
        int shift = (countV2 + 5) & 7;
        int[] seed = {0x84, 0x96, 0x77, 0x9d, 0xd4, 0x15, 0x0b, 0xf8};
        byte[] pad = new byte[8];
        for (int i = 0; i < 8; i++) pad[i] = (byte) (((seed[i] | (seed[i] << 8)) >> shift) & 255);
        byte[] input = concat(querySm3, bodyMd5, tsBytes, pad,
                Crypto.unhex("a0010000"));
        int from = ivV0 << 6;
        int to = (ivV0 + 1) << 6;
        byte[] orders = new byte[to - from];
        System.arraycopy(BRANCH2_ORDERS, from, orders, 0, orders.length);
        return md5SumV3(input, countV2, orders, countV1);
    }

    /** hashF13：按 iv 低 4 位选分支（0 为主分支，1 不被服务端接受，2 为备用变体）。 */
    private static byte[] hashF13(byte[] querySm3, byte[] bodyMd5, byte[] tsBytes, long khronos) {
        int iv = getIv(getIv(getIv(0x20230928, querySm3), bodyMd5), tsBytes);
        int low = iv & 15;
        int ivV0 = (low * 171) >> 9;
        int branch = low - ivV0 * 3;
        if (branch == 2) return branch2F13(iv, querySm3, bodyMd5, tsBytes, khronos);
        if (branch != 0) {
            return concat(slice(querySm3, 0, 16), bodyMd5,
                    le32(sumMd5(concat(querySm3, bodyMd5))));
        }

        int[] ivV1List = {0xc4a78580, 0xb3c0fd39, 0xc58c5686, 0xc9aa3ba7, 0xf5a7adf2, 0x963c2ed1};
        int ivV1 = ivV1List[ivV0];
        int countV1 = (int) ((ivV1 + khronos + 1) & 255);
        int countV2 = (int) (ivV1 + khronos);
        int[] tt02 = {
                0xebb64faf, 0x07aadcc2, 0xcf3187bf, 0xe01138ff, 0x6d0bfcff, 0x5a30a3be, 0xb41ad638, 0x34180eb8,
                0xf233eb6f, 0xb1a584cc, 0xccc30dc7, 0x47d1db51, 0xd55653de, 0x70a84fa1, 0x57473c12, 0xf76f0288,
                0x2c077f0a, 0xda0dcad0, 0xfbb86f6c, 0xfdc4cf00, 0x688a020d, 0xe676c6a6, 0x8cd6338b, 0x1a3c8d0e,
                0xcce8b06b, 0x6ad0ed0b, 0xa0522717, 0xdc71ac83, 0x2285db71, 0xd5b4dda6, 0x736f8650, 0x6560306c,
                0x617ce2a6, 0xe423417e, 0x0a40e143, 0x544e4032, 0x88dffb2a, 0x716c1ae0, 0x4c467a88, 0x05b23bb3,
                0xe1d0b866, 0xbaa3dcb8, 0xae3374d3, 0xc3381a50, 0x1702f75b, 0xfe6da368, 0xf0b4cf48, 0x4e0ffbb8,
                0x72aad10d, 0x26c53a3d, 0xf2bce0f6, 0xb4557581, 0x4a257fdd, 0x8c3182a2, 0xab0b3b86, 0x3d5dfb14,
                0x4f103634, 0xd37b52d7, 0x444eff16, 0xeb0a33d1, 0x6ca86f6e, 0x0284ba7, 0x08387cfa, 0x5fb37586};
        int[] tt = new int[tt02.length];
        for (int i = 0; i < tt.length; i++) tt[i] = ror(tt02[i], countV1);
        int n0 = (countV2 + 2) & 7;
        int[] seed = {0xfa, 0x45, 0x61, 0xd7};
        // JS: Buffer.from(seed.map(...)) —— seed 仅 4 个元素，pad 为 4 字节
        byte[] pad = new byte[seed.length];
        for (int i = 0; i < seed.length; i++) pad[i] = (byte) (((seed[i] | (seed[i] << 8)) >> n0) & 255);
        byte[] data = concat(querySm3, bodyMd5, tsBytes, pad, Crypto.unhex("00000000000001a0"));

        int[] di = new int[(data.length / 4) + 112 + 16];
        int diLen = 0;
        for (int i = 0; i + 3 < data.length; i += 4) di[diLen++] = be32(data, i);
        int di0 = di[0];
        for (int i = 0; i < 112; i++) {
            int di1 = di[i + 1];
            int di14 = di[i + 14];
            int r1 = rotl(di1, 14) ^ rotl(di1, 25) ^ (di1 >>> 3);
            int r2 = rotl(di14, 13) ^ rotl(di14, 15) ^ (di14 >>> 10);
            di[diLen++] = (di0 + di[i + 9] + r1 + r2);
            di0 = di1;
        }

        int[] initRaw = {0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
                (int) 984304912L, (int) 3005047866L, (int) 2874125293L, (int) 2152413264L};
        int[] init = new int[8];
        for (int i = 0; i < 8; i++) init[i] = ror(initRaw[i], countV2 & 31);
        int[][] chosenTable = {
                {101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3},
                {96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4},
                {96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5},
                {99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6},
                {96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4},
                {100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5}};
        int[] chosen = chosenTable[ivV0];
        int[] d = init.clone();
        for (int i = 0; i < chosen[0]; i++) {
            int base = (int) (ivV1 + i);
            int n0v = di[base & 127];
            int n1 = ((d[chosen[3]] ^ d[chosen[4]]) & d[chosen[1]]) ^ d[chosen[3]];
            int n2 = rotl(d[chosen[1]], 26) ^ rotl(d[chosen[1]], 21) ^ rotl(d[chosen[1]], 7);
            int n4 = (n0v + n1 + n2 + tt[base & 63] + d[chosen[5]]);
            int n5 = rotl(d[chosen[2]], 30) ^ rotl(d[chosen[2]], 19) ^ rotl(d[chosen[2]], 10);
            int n6 = (d[chosen[2]] & d[chosen[6]]) | ((d[chosen[2]] | d[chosen[6]]) & d[chosen[7]]);
            int n7 = (n5 + n6);
            int old = d[chosen[9]];
            // d.unshift(d.pop())：末元素移到队首（循环右移一格）
            int last = d[d.length - 1];
            System.arraycopy(d, 0, d, 1, d.length - 1);
            d[0] = last;
            d[chosen[10]] = (n7 + n4);
            d[chosen[8]] = (old + n4);
        }
        byte[] ret = new byte[32];
        for (int i = 0; i < 8; i++) putBe32(ret, i * 4, (d[i] + init[i]));
        for (int i = 0; i < 16; i++) ret[i] = (byte) (ret[i] ^ ret[i + 16]);
        return concat(slice(ret, 0, 16), le32(sumMd5(slice(ret, 0, 16))));
    }

    private static byte[] slice(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(b, from, out, 0, out.length);
        return out;
    }

    // ==================== x-gorgon ====================

    /** RC4 变体（KSA 只赋值不交换，PRGA 异或 s[(y+y)&255]）。 */
    private static byte[] rc4Gorgon(byte[] data, int[] key) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key[i % key.length]) & 255;
            s[i] = s[j];
        }
        byte[] out = new byte[data.length];
        int i = 0;
        j = 0;
        for (int k = 0; k < data.length; k++) {
            i += 1;
            int x = s[i];
            j += x;
            int y = s[j & 255];
            s[i] = y;
            out[k] = (byte) (data[k] ^ s[(y + y) & 255]);
        }
        return out;
    }

    private static String xGorgon(String query, byte[] body, long timestamp, int random) {
        byte[] q4 = slice(md5(query.getBytes(StandardCharsets.UTF_8)), 0, 4);
        byte[] b4 = body != null && body.length > 0
                ? slice(md5(body), 0, 4) : new byte[4];
        byte[] sdk = le32(67503104);
        byte[] input = concat(q4, b4, new byte[4], sdk, be32((int) timestamp));
        int[] key = {0x4a, 0x40, 0x16, (random >> 8) & 255, 0x47, 0x6c, 0x01, random & 255};
        byte[] out = rc4Gorgon(input, key);
        for (int i = 0; i < out.length; i++) {
            int value = rotl8(out[i] & 0xFF);
            int next = i + 1 < out.length ? (out[i + 1] & 0xFF) : (out[0] & 0xFF);
            out[i] = (byte) (~(reverseBits((next ^ value) & 255) ^ 20) & 255);
        }
        return Crypto.hex(concat(Crypto.unhex("8404"),
                new byte[]{(byte) (random & 255), (byte) ((random >> 8) & 255), 0x40, 0x01}, out));
    }

    // ==================== AesV3（medusa 内置的 AES 变体） ====================

    private static final byte[] SBOX = Base64.getDecoder().decode(
            "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==");

    /** AES 变体：S 盒按 khronos 选段、行/列换用查表、密钥扩展 12 组。 */
    private static final class AesV3 {
        private final int wordSize;
        private final byte[] box;
        private final int[] con2;
        private final int[] order;
        private final int[][] keys;

        AesV3(byte[] key, long khronos) {
            this.wordSize = (int) (khronos & 3);
            this.box = slice(SBOX, this.wordSize * 256, (this.wordSize + 1) * 256);
            int[][] con2Table = {{1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}};
            int[][] orderTable = {
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
                    {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                    {0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15},
                    {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3}};
            this.con2 = con2Table[this.wordSize];
            this.order = orderTable[this.wordSize];
            int init = new int[]{0xca025ddc, 0x823dc546, 0xc9420583, 0xc298225f}[this.wordSize];
            byte[] initial = new byte[16];
            for (int i = 0; i < 4; i++) putLe32(initial, i * 4, init);
            byte[] mk = xorBytes(initial, key);
            byte[] expanded = concat(mk, new byte[32]);
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at] & 0xFF, b = expanded[at + 1] & 0xFF;
                int c = expanded[at + 2] & 0xFF, d = expanded[at + 3] & 0xFF;
                if ((i & 3) == 0) {
                    int t = ((init >>> (rounds & 24)) ^ (box[b] & 0xFF)) & 255;
                    b = box[c] & 0xFF;
                    c = box[d] & 0xFF;
                    d = box[a] & 0xFF;
                    a = t;
                }
                rounds += 2;
                expanded[at + 4] = (byte) (a ^ (expanded[at - 12] & 0xFF));
                expanded[at + 5] = (byte) (b ^ (expanded[at - 11] & 0xFF));
                expanded[at + 6] = (byte) (c ^ (expanded[at - 10] & 0xFF));
                expanded[at + 7] = (byte) (d ^ (expanded[at - 9] & 0xFF));
            }
            this.keys = new int[12][4];
            for (int i = 0; i < 12; i++)
                for (int j = 0; j < 4; j++) keys[i][j] = expanded[i * 4 + j] & 0xFF;
        }

        private void add(int[][] rows, int[][] key) {
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) rows[i][j] ^= key[i][j];
        }

        private void addCon(int[][] rows, int[][] ks) {
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) rows[i][j] ^= ks[i][con2[j]];
        }

        private void sub(int[][] rows) {
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) rows[i][j] = box[rows[i][j]] & 0xFF;
            int[][] old = new int[4][];
            for (int i = 0; i < 4; i++) old[i] = rows[i].clone();
            for (int i = 0; i < 4; i++) rows[i] = old[con2[i]];
        }

        private void shift(int[][] rows) {
            byte[] b = flat(rows);
            for (int i = 0; i < 16; i++) rows[i / 4][i % 4] = b[order[i]] & 0xFF;
        }

        private void shiftCon(int[][] rows) {
            int[][] old = new int[4][];
            for (int i = 0; i < 4; i++) old[i] = rows[i].clone();
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) rows[i][j] = old[i][con2[j]];
        }

        private byte[] block(byte[] value) {
            int[][] rows = matrix(value);
            int[][] k0 = subKeys(0);
            addCon(rows, k0);
            for (int i = 1; i < 3; i++) {
                sub(rows);
                shift(rows);
                if (i == 1) {
                    shiftCon(rows);
                    aesMix(rows);
                }
                addCon(rows, subKeys(i * 4));
            }
            add(rows, subKeys(4));
            return flat(rows);
        }

        private int[][] subKeys(int from) {
            int[][] out = new int[4][4];
            for (int i = 0; i < 4; i++)
                System.arraycopy(keys[from + i], 0, out[i], 0, 4);
            return out;
        }

        /** 加密：明文具化到 32 字节 -> CBC 两轮 -> 按 key 逐位回填源数据，末尾缀 key 末字节。 */
        byte[] encrypt(byte[] data, byte[] iv) {
            byte[] plaintext = new byte[32];
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int n0 = (src(data, at) >> 4) & 2;
                int n1 = n0 | (src(data, at + 1) & 64);
                int n2 = n1 | ((src(data, at + 2) >> 2) & 1);
                int n3 = n2 | ((src(data, at + 3) << 3) & 128);
                int n4 = n3 | ((src(data, at + 4) >> 1) & 4);
                int n5 = n4 | ((src(data, at + 5) << 3) & 16);
                int n6 = n5 | ((src(data, at + 6) << 5) & 32);
                plaintext[i] = (byte) (n6 | ((src(data, at + 7) >> 4) & 8));
            }
            plaintext[31] = 1;
            List<byte[]> blocks = new ArrayList<>();
            byte[] previous = iv.clone();
            for (int i = 0; i < plaintext.length; i += 16) {
                byte[] value = block(xorBytes(slice(plaintext, i, i + 16), previous));
                blocks.add(value);
                previous = value;
            }
            byte[] key = concat(blocks.toArray(new byte[0][]));
            byte[] out = data.clone();
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int k = key[i] & 0xFF;
                // JS 语义：越界读写均静默忽略，故读用 src()、写用 put()
                put(out, at, (src(out, at) & 0xdf) | ((k << 4) & 32));
                put(out, at + 1, (src(out, at + 1) & 0xbf) | (k & 64));
                put(out, at + 2, (src(out, at + 2) & 0xfb) | ((k << 2) & 4));
                put(out, at + 3, (src(out, at + 3) & 0xef) | ((k >> 3) & 16));
                put(out, at + 4, (src(out, at + 4) & 0xf7) | ((k + k) & 8));
                put(out, at + 5, (src(out, at + 5) & 0xfd) | ((k >> 3) & 2));
                put(out, at + 6, (src(out, at + 6) & 0xfe) | ((k >> 5) & 1));
                put(out, at + 7, (src(out, at + 7) & 0x7f) | ((k << 4) & 128));
            }
            return concat(slice(key, key.length - 1, key.length), out);
        }

        private static void put(byte[] out, int idx, int value) {
            if (idx >= 0 && idx < out.length) out[idx] = (byte) value;
        }

        /** JS 语义：越界读返回 0（undefined 在位运算中等价于 0）。 */
        private int src(byte[] data, int idx) {
            return idx < data.length ? (data[idx] & 0xFF) : 0;
        }

        private static int[][] matrix(byte[] bytes) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) rows[i][j] = bytes[i * 4 + j] & 0xFF;
            return rows;
        }

        private static byte[] flat(int[][] rows) {
            byte[] b = new byte[16];
            for (int i = 0; i < 4; i++)
                for (int j = 0; j < 4; j++) b[i * 4 + j] = (byte) rows[i][j];
            return b;
        }

        private static void aesMix(int[][] rows) {
            for (int i = 0; i < 4; i++) {
                int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                int u = rows[0][i];
                rows[0][i] ^= t ^ xt(rows[0][i] ^ rows[1][i]);
                rows[1][i] ^= t ^ xt(rows[1][i] ^ rows[2][i]);
                rows[2][i] ^= t ^ xt(rows[2][i] ^ rows[3][i]);
                rows[3][i] ^= t ^ xt(rows[3][i] ^ u);
            }
        }

        private static int xt(int x) {
            return ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255;
        }
    }

    // ==================== xmxor / keyHash / medusa / helios ====================

    /** xmxor：按 key 表逐字节混淆 + 三段尾部修正。 */
    private static byte[] xmxor(byte[] data, byte[] key) {
        byte[] encoded = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            int at = (i * 4) & 28;
            int d0 = key[at] & 0xFF;
            int d1 = key[at + 1] & 0xFF;
            int d2 = (rotl8(data[i] & 0xFF) + d0);
            d2 = (~d2) ^ d1;
            d2 = rotl8_3(d2 & 0xFF);
            d2 = (d2 + d1) & 255;
            d2 = (d2 ^ d0) & 255;
            encoded[data.length - i - 1] = (byte) ((~d2) & 255);
        }
        int last = (encoded[encoded.length - 1] ^ encoded[encoded.length - 2]) & 255;
        int first = encoded[0] & 0xFF;
        encoded[0] = (byte) (((~last) + first) & 255);
        encoded[1] = (byte) (((encoded[0] ^ encoded[encoded.length - 1] ^ 254) + encoded[1]) & 255);
        encoded[2] = (byte) ((encoded[2]
                + ((((last - first) ^ rotl8_3(encoded[1] & 0xFF)) ^ 2))) & 255);
        for (int i = 0; i < encoded.length - 4; i++) {
            int temp = ((rotl8_3(encoded[i + 2] & 0xFF) ^ encoded[i + 1] ^ (i + 3)));
            encoded[i + 3] = (byte) (((~temp) + encoded[i + 3]) & 255);
        }
        encoded[encoded.length - 1] = (byte) (encoded[encoded.length - 1] ^ encoded[encoded.length - 2]);
        int sum = 0;
        for (int i = 0; i < encoded.length - 1; i++) sum += encoded[i + 1] & 0xFF;
        encoded[0] = (byte) (((encoded[0] ^ encoded[1]) + sum) & 255);
        return encoded;
    }

    /** keyHash：signKey || le32(random) || signKey 的 SM3，附一个由 random 派生的 4 字节种子。 */
    private static byte[][] keyHash(byte[] signKey, int random) {
        byte[] hash = sm3(concat(signKey, le32(random), signKey));
        int d1 = (random >> 16) & 255;
        int d2 = (((d1 << 11) | (random >>> 24)) ^ (d1 >> 5) ^ d1);
        d2 = ~d2;
        return new byte[][]{hash, le32(d2)};
    }

    private static final byte[] MEDUSA_SIGN_KEY = Crypto.unhex(
            "8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
    private static final byte[] MEDUSA_AES_KEY = Crypto.unhex("f1593376766ea98d34f31b057a9d5be4");
    private static final byte[] MEDUSA_AES_IV = Crypto.unhex("1fe109a4125283f418de9e051a969e12");
    private static final byte[] MEDUSA_MAGIC16 = Crypto.unhex("f7e85ffad7d7dc3bd62ac87057cf6118");
    private static final byte[] MEDUSA_MAGIC8 = Crypto.unhex("4001000000000000");

    /** 进程级 nonce（18 随机字节的 base64url）。 */
    private static final String PROCESS_NONCE =
            Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(18));
    private static final long PROCESS_STARTED = System.currentTimeMillis();

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    /**
     * 设备信息 protobuf。与 jackotom 参考实现保持一致：字段 30-36 为固定设备值
     * （25053RT47C / Redmi / Xiaomi），不跟随 query 参数，仅 deviceId/version 外部传入。
     */
    private static byte[] deviceProto(String deviceId, String version) {
        return proto(new Object[][]{
                {1, 1, "sint"}, {2, 2, "sint"}, {3, "8662"}, {4, deviceId},
                {5, PROCESS_NONCE}, {6, "!noperm!"}, {7, -888888, "sint"}, {8, -888888, "sint"},
                {9, 3, "sint"}, {10, -888888, "sint"}, {11, "!notset!"},
                {12, "Asia/Shanghai,8"}, {13, "zh_CN"}, {14, 4, "sint"},
                {16, 0, "float"}, {17, 0, "float"}, {18, 0, "float"}, {19, 0, "float"},
                {20, 0, "float"}, {21, 0, "float"}, {22, "16"}, {23, 41, "sint"},
                {24, 36, "sint"}, {25, PROCESS_STARTED, "sint"}, {26, PROCESS_STARTED, "sint"},
                {27, PROCESS_STARTED, "sint"}, {28, PROCESS_STARTED, "sint"}, {29, -1, "sint"},
                {30, "25053RT47C"}, {31, "Redmi"}, {32, "25053RT47C"}, {33, "25053RT47C"},
                {34, "Xiaomi"}, {35, "Redmi"}, {36, "Redmi"}, {38, 31, "sint"}});
    }

    /** 构造 x-medusa。 */
    private static String buildMedusa(String url, byte[] body, long khronos,
                                      String deviceId, String version) {
        byte[] bodyMd5 = body != null && body.length > 0 ? md5(body) : new byte[16];
        String query = queryOf(url);
        byte[] querySm3 = sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] ts = le32((int) khronos);
        byte[] queryBodyTs = hashF13(querySm3, bodyMd5, ts, khronos);

        byte[] messageRand = new byte[4];
        RND.nextBytes(messageRand);
        long messageRandU = le32(messageRand, 0) & 0xFFFFFFFFL;
        int envLaunch = 100 + RND.nextInt(21);
        int envPid = 10001 + RND.nextInt(2000);
        byte[] env = proto(new Object[][]{
                {1, envLaunch, "sint"}, {2, 146331399, "sint"}, {3, 146331396, "sint"},
                {5, 7, "sint"}, {6, "v04.06.04.03-bugfix"}, {7, envPid, "sint"},
                {12, deviceProto(deviceId, version), "message"},
                {13, proto(new Object[][]{
                        {1, System.currentTimeMillis() / 1000, "sint"},
                        {2, -2, "sint"}, {4, 200, "sint"}}), "message"},
                {14, version}});
        byte[] queryHash = sm3(concat(query.getBytes(StandardCharsets.UTF_8), bodyMd5,
                "none".getBytes(StandardCharsets.UTF_8)));

        byte[] nested = proto(new Object[][]{
                {1, 111, "sint"}, {2, 10, "sint"}, {3, 694367, "sint"}, {5, 586952199, "sint"}});
        byte[] message = proto(new Object[][]{
                {1, MEDUSA_MAGIC16, "bytes"}, {2, 3, "sint"}, {3, messageRandU, "sint"},
                {4, "8662"}, {5, deviceId}, {6, "1588093228"}, {7, version},
                {8, "v04.06.04-ml-android"}, {9, 67503104, "sint"},
                {10, MEDUSA_MAGIC8, "bytes"}, {12, khronos, "sint"},
                {13, queryBodyTs, "bytes"}, {14, slice(querySm3, 0, 6), "bytes"},
                {15, nested, "message"}, {16, PROCESS_NONCE}, {17, khronos, "sint"},
                {19, queryHash, "bytes"}, {20, "none"}, {21, 312, "sint"},
                {23, env, "message"},
                {24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,"
                        + "\"kd\":0,\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\","
                        + "\"do\":0,\"tk\":true}"}});

        int random = RND.nextInt();
        byte[][] hs = keyHash(MEDUSA_SIGN_KEY, random);
        byte[] hash = hs[0];
        byte[] seed = hs[1];
        byte[] transformed = xmxor(message, hash);
        transformed = concat(MEDUSA_MAGIC8, transformed);
        // 整体逆序
        for (int i = 0, j = transformed.length - 1; i < j; i++, j--) {
            byte t = transformed[i];
            transformed[i] = transformed[j];
            transformed[j] = t;
        }
        for (int i = 0; i < transformed.length; i++) {
            transformed[i] ^= seed[(~i) & 3];
        }
        int check = (((querySm3[0] & 63) << 14) | 0x18000001 | ((queryBodyTs[0] & 63) << 8));
        int xmRand = RND.nextInt();
        byte[] packed = concat(new byte[]{0x35}, le32(xmRand), le32(check), transformed,
                new byte[]{(byte) ((random >> 16) & 255), (byte) ((random >> 24) & 255)});
        byte[] encrypted = new AesV3(MEDUSA_AES_KEY, khronos).encrypt(packed, MEDUSA_AES_IV);
        byte[] versionBytes = Crypto.unhex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        byte[] prefix = new byte[20];
        for (int i = 0; i < 20; i += 4) {
            // JS: u32(version.readUInt32LE(i) ^ khronos) 后 writeUInt32LE
            putLe32(prefix, i, (le32(versionBytes, i) ^ (int) khronos));
        }
        return b64(concat(prefix,
                new byte[]{(byte) (random & 255), (byte) ((random >> 8) & 255), 0, 1}, encrypted));
    }

    /** helios：md5  ASCII 化 -> 64 位字表 -> 34 轮扩展 -> 分组类 Feistel。 */
    private static String helios(long khronos) {
        return helios(khronos, RND.nextInt());
    }

    /** 测试钩子：可注入 random 的 helios（默认重载用 RND.nextInt()）。 */
    static String helios(long khronos, int random) {
        byte[] digest = md5(concat(le32(random), "8662".getBytes(StandardCharsets.UTF_8)));
        StringBuilder hex = new StringBuilder(32);
        for (byte x : digest) hex.append(String.format("%02x", x));
        byte[] ascii = hex.toString().getBytes(StandardCharsets.US_ASCII);
        long[] words = new long[4];
        for (int i = 0; i < 4; i++) words[i] = readU64Le(ascii, i * 8);

        // JS: words.splice(0,2) 后每轮 push 一个、shift 一个，队列恒长 2。
        // 用双端队列精确模拟：b8 始终取「最早入队未消费」的元素。
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        queue.addLast(words[2]);
        queue.addLast(words[3]);
        long b0 = words[0];
        long b8 = words[1];
        long[] table = new long[35];
        table[0] = words[0];
        for (int i = 0; i < 34; i++) {
            long x8 = Long.rotateRight(b8, 8) + b0;
            x8 = x8 ^ i;
            queue.addLast(x8);
            x8 = x8 ^ Long.rotateRight(b0, 61);
            table[i + 1] = x8;
            b0 = x8;
            b8 = queue.pollFirst();
        }
        String text = khronos + "-1588093228-8662";
        byte[] tb = text.getBytes(StandardCharsets.UTF_8);
        int padLen = ((tb.length + 1 + 15) / 16) * 16;
        byte[] pad = new byte[padLen];
        java.util.Arrays.fill(pad, (byte) (16 - (tb.length % 16)));
        System.arraycopy(tb, 0, pad, 0, tb.length);

        List<byte[]> output = new ArrayList<>();
        for (int at = 0; at < pad.length; at += 16) {
            long a = readU64Le(pad, at);
            long b = readU64Le(pad, at + 8);
            for (int i = 0; i < 34; i++) {
                b = table[i] ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateRight(a, 61);
            }
            byte[] block = new byte[16];
            // JS: block.writeBigUInt64LE —— 小端写入
            putU64Le(block, 0, a);
            putU64Le(block, 8, b);
            output.add(block);
        }
        List<byte[]> all = new ArrayList<>();
        all.add(le32(random));
        all.addAll(output);
        return b64(concat(all.toArray(new byte[0][])));
    }

    // ==================== 分支判定与入口 ====================

    /** 与 hashF13 相同的分支计算；branch==1 时服务端不认，调用方应抖动 _rticket 避开。 */
    public static int branchOf(String url, byte[] body, long khronos) {
        byte[] bodyMd5 = body != null && body.length > 0 ? md5(body) : new byte[16];
        byte[] q = sm3(queryOf(url).getBytes(StandardCharsets.UTF_8));
        int iv = getIv(getIv(getIv(0x20230928, q), bodyMd5), le32((int) khronos));
        int low = iv & 15;
        return low - (((low * 171) >> 9) * 3);
    }

    private static String queryOf(String url) {
        int i = url.indexOf('?');
        return i < 0 ? "" : url.substring(i + 1);
    }

    /**
     * 计算一组签名头。
     *
     * @param url  完整 URL（含 query），必须与最终请求一致
     * @param body 请求体字节；GET 传 null
     * @return 需要合并进请求头的字段（x-gorgon / x-khronos / x-ladon / x-argus / x-helios / x-medusa）
     */
    public static Map<String, String> sign(String url, byte[] body) {
        return sign(url, body, System.currentTimeMillis() / 1000L);
    }

    /**
     * 与 {@link #sign(String, byte[])} 相同，但 khronos 由调用方指定。
     *
     * 用于 branch==1 抖动场景：调用方先用 {@link #branchOf} 判定当前 URL 是否会被
     * 服务端拒绝，规避时必须用同一个 khronos 重签，否则判定与签名会错位。
     */
    public static Map<String, String> sign(String url, byte[] body, long khronos) {
        int gorgonRandom = RND.nextInt(65536);
        String query = queryOf(url);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("x-gorgon", xGorgon(query, body, khronos, gorgonRandom));
        out.put("x-khronos", String.valueOf(khronos));
        out.put("x-ladon", b64(be32((int) khronos)));
        out.put("x-argus", b64(le32((int) khronos)));
        out.put("x-helios", helios(khronos));
        out.put("x-medusa", buildMedusa(url, body, khronos,
                paramOf(query, "device_id", "0"),
                paramOf(query, "version_name", "7.1.3.32")));
        return out;
    }

    private static String paramOf(String query, String key, String fallback) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            if (kv.substring(0, eq).equals(key)) {
                String v = kv.substring(eq + 1);
                if (!v.isEmpty()) return v;
            }
        }
        return fallback;
    }

    // ==================== 自检 ====================

    /**
     * 自检：对固定输入跑一遍全链路，确保不抛异常且头字段齐全。
     * 真正的字节级校验靠线上请求（code=0 即签名被接受）。
     */
    public static boolean selfTest() {
        try {
            String url = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/"
                    + "?aid=8662&device_id=1234567890123456&version_code=71532"
                    + "&version_name=7.1.5.32&device_type=SM-N9860&_rticket=1700000000000";
            Map<String, String> h = sign(url,
                    "{\"biz_param\":{},\"mixed_video_id_map\":{\"1004\":[\"7686715217652223038\"]}}"
                            .getBytes(StandardCharsets.UTF_8));
            boolean ok = h.size() == 6
                    && h.get("x-gorgon") != null && h.get("x-medusa") != null
                    && h.get("x-helios") != null;
            Log.info("pure-sign 自检：" + (ok ? "通过（6 个签名头齐全）" : "失败 " + h.keySet()));
            return ok;
        } catch (Exception e) {
            Log.warn("pure-sign 自检异常：" + e);
            return false;
        }
    }
}
