package com.hongguo.api.core;

import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Log;

/**
 * spade_a -> content key 纯离线解包。
 *
 * 移植自 server/src/spade.js（逆向自 libttmplayer.so FUN_001c4550，ver1 路径）。
 * 算法为纯字节变换：XOR + POPCOUNT + 位置相关，不含 KEK、不含 AES。
 *
 * 链路：video_model.encryptInfo.spadeA(base64)
 *      -> base64 解码 -> unwrapV1 -> content key（16 字节，32 位十六进制）
 *      -> 配合密文 senc逐样本 IV -> AES-128-CTR 解密。
 *
 * 注：ver2（spade type 为 "app_v2" / "web_v2"）走 libttmplayer FUN_00271a50
 *     （base64 + MD5(KEK) + AES-GCM-256），当前红果视频未见使用，此处不实现。
 */
public final class Spade {

    private Spade() {}

    /** 等价于 C 的 strncmp，遇到任一 NUL 终止符即视为相等（对齐原实现 _strncmp0）。 */
    private static boolean strncmp0(byte[] a, byte[] b, int n) {
        for (int k = 0; k < n; k++) {
            int ca = k < a.length ? (a[k] & 0xFF) : 0;
            int cb = k < b.length ? (b[k] & 0xFF) : 0;
            if (ca != cb) return false;
            if (ca == 0) return true;
        }
        return true;
    }

    private static boolean strncmp0(byte[] a, String s, int n) {
        for (int k = 0; k < n; k++) {
            int ca = k < a.length ? (a[k] & 0xFF) : 0;
            int cb = k < s.length() ? (s.charAt(k) & 0xFF) : 0;
            if (ca != cb) return false;
            if (ca == 0) return true;
        }
        return true;
    }

    /**
     * unwrapV1：spade 字节 -> content key（32 位十六进制字符串）。
     *
     * @param spade 输入字节
     * @param flag  播放器 option 0x97 的值（实测为 0）；非 0 时变换方向相反
     * @return content key，失败返回 null
     */
    public static String unwrapV1(byte[] spade, int flag) {
        if (spade == null) return null;
        int L = spade.length;
        if (L < 3) return null;

        // bVar5 = spade[0] ^ spade[1] ^ spade[2]，其后推 type 字符串长度
        int bVar5 = (spade[0] ^ spade[1] ^ spade[2]) & 0xFF;
        int iVar9 = bVar5 - 0x30;
        if (iVar9 < 1) return null;

        // 工作缓冲长度，并做越界校验
        int uVar1 = L - bVar5 + 0x2f;
        if (uVar1 < 1 || 1 + uVar1 > L) return null;

        // System.arraycopy(dest, 0, spade, 1, uVar1)  即 memcpy(dest, spade + 1, uVar1)
        byte[] dest = new byte[uVar1];
        System.arraycopy(spade, 1, dest, 0, uVar1);

        // 解出 type 字符串以区分 v1 / v2
        byte[] s1 = new byte[iVar9];
        int b16 = spade[L - iVar9 - 2] & 0xFF;
        int b14 = spade[L - iVar9 - 1] & 0xFF;
        for (int i = 0; i < iVar9; i++) {
            s1[i] = (byte) (b14 ^ b16 ^ (spade[i + (L - iVar9)] & 0xFF));
        }
        if (strncmp0(s1, "app_v2", iVar9) || strncmp0(s1, "web_v2", iVar9)) {
            return null; // ver2 走 AES-GCM 路径，此处不实现
        }

        // ver1 字节变换
        int cur14 = 0x55;
        int cur16 = 0xfa;
        for (int i = 0; i < uVar1; i++) {
            int b6 = dest[i] & 0xFF;
            int u18 = Crypto.popcount(i);
            int b3 = b6;
            int b7 = cur14;
            if ((i & 1) != 0) {
                b3 = cur16;
                b7 = b6;
                cur16 = cur14;
            }
            // C 的有符号 char 溢出语义在此等价于对 0xff 取模
            int cVar4 = (flag != 0) ? (u18 + 0x15) : (-0x15 - u18);
            dest[i] = (byte) ((cVar4 + (cur16 ^ b6)) & 0xFF);
            cur14 = b7;
            cur16 = b3;
        }

        // 按 dest[0] 的十六进制字符值切出content key
        int b0 = dest[0] & 0xFF;
        int u11;
        if (b0 >= 0x30 && b0 <= 0x39) u11 = b0 - 0x30;
        else if (b0 >= 0x61 && b0 <= 0x7a) u11 = b0 - 0x57;
        else return null;

        int iv9 = uVar1 - (u11 & 0xFF);
        if (iv9 < 2) return null;
        // 对应JS 的 dest.subarray(1, iv9).toString('latin1')
        StringBuilder sb = new StringBuilder(iv9 - 1);
        for (int i = 1; i < iv9; i++) {
            sb.append((char) (dest[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 判断解出的 key 是否为合法的 32 位十六进制字符串。 */
    public static boolean isValidKey(String key) {
        if (key == null || key.length() != 32) return false;
        for (int i = 0; i < 32; i++) {
            char c = key.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    /** 解包结果。 */
    public static final class Result {
        public final String key;
        public final int flag;

        Result(String key, int flag) {
            this.key = key;
            this.flag = flag;
        }
    }

    /**
     * spadeToKey：video_model 的 spadeA（base64）-> content key（32 位十六进制）。
     * flag 不确定时自动两种都试。
     */
    public static Result spadeToKey(String spadeAB64, int flag) {
        byte[] raw;
        try {
            raw = Crypto.base64Decode(spadeAB64 == null ? "" : spadeAB64);
        } catch (Exception e) {
            return new Result(null, flag);
        }
        if (raw == null || raw.length == 0) return new Result(null, flag);

        String key = unwrapV1(raw, flag);
        if (isValidKey(key)) return new Result(key, flag);

        String alt = unwrapV1(raw, 1 - flag);
        if (isValidKey(alt)) return new Result(alt, 1 - flag);

        return new Result(null, flag);
    }

    // ==================== 自检 ====================

    /** 5 组运行时真值（libttmplayer FUN_001c4550 hook 抓取）。 */
    private static final String[][] TRUTH = {
        {"93bc1df253ba1bf7618b19c448b806f64d810afc45b715fe5eb925f26cbf12f541ba098282",
         "287216bfa89e662a0f748120c305e199"},
        {"a1bc2ff164b91df04dbf00f17dba00f47cbb35c27b922aec67a628eb52972fdc7ea036a7a7",
         "d742b28967e4e6c92b699f4375add27f"},
        {"9cbc12fb588721cd69b739f977b70be7429c08e074990ec847843dcc738638cf76b33ebcbc",
         "113fb30d9767d80e3edbb905b052204f"},
        {"a3bc2df760ba2bc27b8b34c04a8907f557be19f249be35c748ba03f64fbc1ff161ba29a2a2",
         "b5674a8384f25d757585dadf343274d5"},
        {"9cbc12f45aba11f76fb814f444bd15f459b225cb5e8c15dd459a0fd170ac0ad358aa108b8b",
         "121846f9d5829130ebc0397023feaf8c"},
    };

    /** 自检：用 5 组真值验证解包算法。 */
    public static boolean selfTest() {
        int ok = 0;
        for (String[] row : TRUTH) {
            String got = unwrapV1(Crypto.unhex(row[0]), 0);
            if (row[1].equals(got)) ok++;
            else Log.warn("spade 自检失败：期望 " + row[1] + " 实际 " + got);
        }
        boolean pass = ok == TRUTH.length;
        Log.info("spade 解包自检：" + ok + "/" + TRUTH.length + (pass ? " 全部通过" : "存在失败"));
        return pass;
    }
}
