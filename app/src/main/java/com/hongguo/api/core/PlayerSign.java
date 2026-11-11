// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.core;

import com.hongguo.api.util.Crypto;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 单集播放接口 {@code /novel/player/video_model/v1/} 的完整签名：x-gorgon、x-argus、x-ladon。
 *
 * <p>2026-11-11 起播放模型接口对 {@link PureSign} 的占位 argus/ladon 一律回 200 + 空正文。
 * 同步自 LiuWQ0809/hongguo-android {@code PlayerSign.java}（commit {@code 3eaf813}，2026-11-11，GPL-3.0），
 * 其算法注明参考 Xanerix/guoapp {@code ad8dc125}（GPL-3.0）。
 */
public final class PlayerSign {
    private PlayerSign() {}
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public static java.util.Map<String, String> headers(String query, byte[] body, long now, String deviceId) {
        if (query == null || body == null || deviceId == null || !deviceId.matches("[0-9]{1,30}"))
            throw new IllegalArgumentException("播放器签名参数无效");
        long timestamp = now / 1000;
        byte[] nonce = new byte[11]; RANDOM.nextBytes(nonce);
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("x-khronos", String.valueOf(timestamp));
        headers.put("x-ss-req-ticket", String.valueOf(now));
        headers.put("x-ss-stub", Crypto.md5HexUpper(body));
        headers.put("x-gorgon", gorgon(query, body, timestamp));
        headers.put("x-argus", argus(query, body, timestamp, deviceId, nonce));
        headers.put("x-ladon", ladon(timestamp, Arrays.copyOfRange(nonce, 2, 6)));
        return headers;
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] join(byte[]... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] value : values) out.write(value, 0, value.length);
        return out.toByteArray();
    }
    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~127L) != 0) { out.write(((int) value & 127) | 128); value >>>= 7; }
        out.write((int) value);
        return out.toByteArray();
    }
    private static byte[] number(int field, long value) { return join(varint(field * 8L), varint(value)); }
    private static byte[] field(int field, byte[] value) {
        return join(varint(field * 8L + 2), varint(value.length), value);
    }
    private static byte[] text(int field, String value) { return field(field, utf8(value)); }
    private static byte[] pad(byte[] data) {
        int n = 16 - data.length % 16;
        byte[] result = Arrays.copyOf(data, data.length + n);
        Arrays.fill(result, data.length, result.length, (byte) n);
        return result;
    }
    private static ByteBuffer little(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }

    static String gorgon(String query, byte[] body, long timestamp) {
        byte[] data = new byte[20];
        System.arraycopy(Crypto.md5(utf8(query)), 0, data, 0, 4);
        if (body != null) System.arraycopy(Crypto.md5(body), 0, data, 4, 4);
        System.arraycopy(new byte[]{0, 6, 11, 28}, 0, data, 12, 4);
        ByteBuffer.wrap(data, 16, 4).putInt((int) timestamp);
        byte[] key = Crypto.unhex("44b9b9d9a4aef9fca493aa757ca3c2c4a496938f");
        for (int i = 0; i < data.length; i++) data[i] ^= key[i];
        for (int i = 0; i < data.length; i++) {
            int current = data[i] & 255;
            int mixed = ((current << 4) | (current >>> 4)) & 255;
            mixed ^= data[(i + 1) % data.length] & 255;
            data[i] = (byte) ((Integer.reverse(mixed) >>> 24) ^ 255 ^ data.length);
        }
        return Crypto.hex(join(Crypto.unhex("8404401c0000"), data));
    }

    static String ladon(long timestamp, byte[] prefix) {
        byte[] digest = utf8(Crypto.hex(Crypto.md5(join(prefix, utf8("8662")))));
        ByteBuffer bytes = little(digest);
        long left = bytes.getLong(0), right = bytes.getLong(8);
        long[] queue = new long[36], keys = new long[34];
        queue[0] = bytes.getLong(16); queue[1] = bytes.getLong(24);
        for (int i = 0; i < keys.length; i++) {
            keys[i] = left;
            long mixed = (Long.rotateRight(right, 8) + left) ^ i;
            queue[i + 2] = mixed;
            left = mixed ^ Long.rotateLeft(left, 3);
            right = queue[i];
        }
        byte[] data = pad(utf8(timestamp + "-1611921764-8662"));
        ByteBuffer buffer = little(data);
        for (int offset = 0; offset < data.length; offset += 16) {
            left = buffer.getLong(offset); right = buffer.getLong(offset + 8);
            for (long key : keys) {
                right = key ^ (left + Long.rotateRight(right, 8));
                left = right ^ Long.rotateLeft(left, 3);
            }
            buffer.putLong(offset, left); buffer.putLong(offset + 8, right);
        }
        return java.util.Base64.getEncoder().encodeToString(join(prefix, data));
    }

    private static byte[] argusMessage(String query, byte[] body, long timestamp, String deviceId, byte[] nonce) {
        byte[] bodyHash = Sm3.hash(body == null ? new byte[16] : Crypto.md5(body));
        byte[] queryHash = Sm3.hash(utf8(query));
        byte[] counters = join(number(1, 1), number(2, 1), number(3, 1), number(7, 3348294860L));
        byte[] environment = join(text(1, "25053RT47C"), text(2, "16"), text(3, "update_64"),
                number(4, 2147483648L));
        return join(number(1, 0x20200929L * 2), number(2, 2),
                number(3, little(nonce).getInt(6) & 0x7fffffffL), text(4, "8662"), text(5, deviceId),
                text(6, "1611921764"), text(7, "7.3.5.32"), text(8, "v04.07.01-ml-android"),
                number(9, 135135744), field(10, new byte[8]), number(11, 0), number(12, timestamp * 2),
                field(13, Arrays.copyOf(bodyHash, 6)), field(14, Arrays.copyOf(queryHash, 6)),
                field(15, counters), field(16, new byte[0]), text(20, "none"), number(21, 738),
                field(23, environment), number(25, 2));
    }

    private static byte[] argusTransform(byte[] message, byte[] key) {
        byte[] seed = join(key, Crypto.unhex("3ccc567b"), key);
        ByteBuffer derived = little(Sm3.hash(seed));
        long[] rounds = new long[72];
        for (int i = 0; i < 4; i++) rounds[i] = derived.getLong(i * 8);
        for (int i = 4; i < rounds.length; i++) {
            long mixed = Long.rotateRight(rounds[i - 1], 3) ^ rounds[i - 3];
            mixed ^= Long.rotateRight(mixed, 1);
            rounds[i] = ~rounds[i - 4] ^ mixed ^ ((0x3dc94c3a046d678bL >>> ((i - 4) % 62)) & 1) ^ 3;
        }
        byte[] data = pad(message);
        ByteBuffer buffer = little(data);
        for (int offset = 0; offset < data.length; offset += 16) {
            long left = buffer.getLong(offset), right = buffer.getLong(offset + 8);
            for (long round : rounds) {
                long next = left ^ (Long.rotateLeft(right, 1) & Long.rotateLeft(right, 8))
                        ^ Long.rotateLeft(right, 2) ^ round;
                left = right; right = next;
            }
            buffer.putLong(offset, left); buffer.putLong(offset + 8, right);
        }
        return data;
    }

    static String argus(String query, byte[] body, long timestamp, String deviceId, byte[] nonce) {
        try {
            byte[] key = Crypto.unhex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
            byte[] xor = Crypto.unhex("d04ffdffd04ffdff");
            byte[] data = argusTransform(argusMessage(query, body, timestamp, deviceId, nonce), key);
            for (int i = 0; i < data.length; i++) data[i] ^= xor[i % xor.length];
            data = join(xor, data);
            for (int left = 0, right = data.length - 1; left < right; left++, right--) {
                byte swap = data[left]; data[left] = data[right]; data[right] = swap;
            }
            byte[] container = pad(join(Crypto.unhex("a6e783ee7001100918"), data, Crypto.unhex("567b")));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(Crypto.md5(Arrays.copyOfRange(key, 0, 16)), "AES"),
                    new javax.crypto.spec.IvParameterSpec(Crypto.md5(Arrays.copyOfRange(key, 16, 32))));
            return java.util.Base64.getEncoder().encodeToString(join(Crypto.unhex("3ccc"), cipher.doFinal(container)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("无法生成播放器签名", error);
        }
    }
}
