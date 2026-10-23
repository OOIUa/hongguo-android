package com.hongguo.api.util;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 摘要与对称加密工具（对应 Node 的 crypto 模块用法）。 */
public final class Crypto {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Crypto() {}

    /** 小写十六进制。 */
    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 十六进制字符串解码，非法字符直接抛错。 */
    public static byte[] unhex(String s) {
        String t = s.trim();
        if (t.length() % 2 != 0) throw new IllegalArgumentException("十六进制长度必须为偶数");
        byte[] out = new byte[t.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(t.charAt(i * 2), 16);
            int lo = Character.digit(t.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("非法十六进制字符");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 大写十六进制（x-ss-stub 需要）。 */
    public static String hexUpper(byte[] data) {
        return hex(data).toUpperCase();
    }

    public static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new RuntimeException("MD5 不可用", e);
        }
    }

    /** MD5 大写十六进制，直接对应 Node 的 createHash('md5').update(d).digest('hex').toUpperCase()。 */
    public static String md5HexUpper(byte[] data) { return hexUpper(md5(data)); }

    public static String md5HexUpper(String s) {
        return md5HexUpper(s.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] base64Decode(String s) {
        return Base64.getMimeDecoder().decode(s.trim());
    }

    /**
     * AES-128-CTR 加解密（同一函数，加解密对称）。
     *
     * @param key    16 字节密钥
     * @param counter 16 字节初始计数器
     * @param data   待处理数据
     */
    public static byte[] aes128Ctr(byte[] key, byte[] counter, byte[] data) {
        if (key.length != 16) throw new IllegalArgumentException("content key 长度非法");
        if (counter.length != 16) throw new IllegalArgumentException("CTR 计数器必须 16 字节");
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.IvParameterSpec(counter));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES-128-CTR 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 32 位无符号整数的二进制 1 的个数（对应 spade 解包的 popcount）。
     */
    public static int popcount(int x) {
        int v = x;
        v = v - ((v >>> 1) & 0x55555555);
        v = (v & 0x33333333) + ((v >>> 2) & 0x33333333);
        v = (v + (v >>> 4)) & 0x0f0f0f0f;
        return (v * 0x01010101) >>> 24;
    }
}
