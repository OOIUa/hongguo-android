// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.service;

import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * 匿名播放设备注册：播放接口 multi_video_model/v1 自 2026-10-27 起校验设备身份。
 *
 * <p>此前每次请求随机生成一对互不关联的 iid/device_id，上游返回 HTTP 200 + 0 字节
 * （软拦截）；同一请求改用平台 device_register 注册得到的匿名身份、且不带签名头后，
 * 正常返回 code=0 与完整片源。
 *
 * <p>同步来源（均为 2026-10-27）：
 * <ul>
 *   <li>tsukiyo-zero/hongguoTV {@code 3889318}（DeviceRegister.kt / PlaybackDeviceSession.kt，GPL-3.0-only）</li>
 *   <li>jackotom/hongguo-drama 3.9 {@code 5febca2}（playback-device.js，GPL-3.0-only）</li>
 *   <li>sunnysky123/hongguo-web 1.1.5 {@code 79babdf}（playback-device.js）</li>
 * </ul>
 *
 * <p>ttEncrypt 请求体：{@code 746305100000(6B) || random(32B) ||
 * AES-128-CBC(SHA512(gzip) || gzip)}，key/iv = SHA512(SHA512(random) || KDF_SALT) 前 32 字节。
 *
 * <p>本地取舍：
 * <ul>
 *   <li>只保留 device_id / iid / cdid，仅存进程内存；不保存设备令牌、Cookie 或完整响应，
 *       与账号模块的注册设备完全隔离（不读取、不复用账号 Cookie）。</li>
 *   <li>同一进程内播放、预加载与重试共享一次成功的注册。</li>
 *   <li>上游三个实现对失败的处理不同（两个 JS 实现缓存失败直到重启，Kotlin 实现每次重试）。
 *       Android 进程可能在离线时首次播放、随后长期驻留，因此这里只在 {@link #FAILURE_COOLDOWN_MS}
 *       内复用失败结果：既避免预加载与重试造成注册风暴，又不需要用户杀进程才能恢复。</li>
 * </ul>
 */
public final class PlaybackDevice {

    private PlaybackDevice() {}

    static final String REGISTER_URL =
            "https://log5-applog-lf.fqnovel.com/service/2/device_register/"
            + "?tt_data=a&cronet_version=8d40f833_2026-03-03"
            + "&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";

    /** 注册请求固定主机（自检与排障用）。 */
    public static final String REGISTER_HOST = "log5-applog-lf.fqnovel.com";

    private static final String REGISTER_UA =
            "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; "
            + "Build/BP2A.250605.031.A3)";

    /** ttEncrypt 固定盐；与 AppSource 解密媒体 URL 的常量段相同。 */
    private static final byte[] KDF_SALT = Crypto.unhex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
            + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    private static final byte[] MAGIC = Crypto.unhex("746305100000");

    /** 注册失败后在此时长内直接复用失败结果。 */
    static final long FAILURE_COOLDOWN_MS = 30_000L;

    private static final int TIMEOUT_MS = 8_000;

    private static final Pattern VALID_ID = Pattern.compile("[0-9]{1,30}");

    private static final SecureRandom RND = new SecureRandom();

    /** 注册得到的匿名播放身份。toString 不输出真实值。 */
    public static final class Identity {
        public final String deviceId;
        public final String iid;
        public final String cdid;

        Identity(String deviceId, String iid, String cdid) {
            this.deviceId = deviceId;
            this.iid = iid;
            this.cdid = cdid;
        }

        @Override
        public String toString() {
            return "PlaybackDevice.Identity([redacted])";
        }
    }

    /** 注册失败；消息可直接展示给用户。 */
    public static final class RegistrationException extends IOException {
        RegistrationException(Throwable cause) {
            super("匿名播放设备注册失败，请检查网络后重试", cause);
        }
    }

    /** 注册响应（状态码 + 正文）。 */
    static final class Reply {
        final int status;
        final String text;

        Reply(int status, String text) {
            this.status = status;
            this.text = text;
        }
    }

    /** 可替换的注册传输（JVM 测试注入）；生产实现见 {@link #httpRegister}. */
    interface Transport {
        Reply post(String url, Map<String, String> headers, byte[] body) throws IOException;
    }

    private static Transport transport = PlaybackDevice::httpRegister;
    private static Identity ready;
    private static RegistrationException lastFailure;
    private static long lastFailureAt;
    private static long clockOffsetForTest;

    // ==================== 入口 ====================

    /**
     * 取进程内共享的匿名播放身份；首次调用时注册。
     * synchronized 使并发的播放 / 预加载等待同一次注册，而不是各自注册一个设备。
     */
    public static synchronized Identity get() throws IOException {
        if (ready != null) return ready;
        long now = now();
        if (lastFailure != null && now - lastFailureAt < FAILURE_COOLDOWN_MS) {
            throw lastFailure;
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("播放请求已取消");
        }
        try {
            ready = register(now);
            lastFailure = null;
            return ready;
        } catch (InterruptedIOException e) {
            // 线程被取消（离开播放页、切集）不是注册失败：不进入冷却，下次播放立即重试。
            // 超时也是 InterruptedIOException 的子类，但线程未被中断，按失败处理。
            if (Thread.currentThread().isInterrupted()) throw e;
            lastFailure = new RegistrationException(e);
            lastFailureAt = now;
            throw lastFailure;
        } catch (Exception e) {
            lastFailure = e instanceof RegistrationException
                    ? (RegistrationException) e : new RegistrationException(e);
            lastFailureAt = now;
            throw lastFailure;
        }
    }

    /**
     * 播放接口拒绝了 rejected 身份时换一个新身份。另一线程已换过则直接复用它的结果，
     * 不会为同一次拒绝注册多个设备；注册失败仍受冷却约束。
     */
    public static synchronized Identity renew(Identity rejected) throws IOException {
        if (ready != null && ready != rejected) return ready;
        ready = null;
        return get();
    }

    private static Identity register(long now) throws Exception {
        String cdid = UUID.randomUUID().toString();
        byte[] random = new byte[32];
        RND.nextBytes(random);
        byte[] body = buildBody(now, cdid, random);
        Reply res = transport.post(REGISTER_URL, headers(body, now), body);
        if (res.status != 200) throw new IOException("register HTTP " + res.status);
        Map<String, Object> j;
        try {
            j = Json.parseObject(res.text);
        } catch (Exception e) {
            throw new IOException("register response is not JSON");
        }
        return parse(j, cdid);
    }

    // ==================== 请求构造 ====================

    static Map<String, String> headers(byte[] body, long now) throws Exception {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("User-Agent", REGISTER_UA);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/octet-stream;tt-data=a");
        h.put("log-encode-type", "gzip");
        h.put("x-ss-dp", "8662");
        h.put("x-ss-req-ticket", String.valueOf(now));
        h.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
        h.put("lc", "101");
        h.put("sdk-version", "2");
        h.put("passport-sdk-version", "5051452");
        h.put("x-neptune", "-8|50:51:59:30:40:47:49:39");
        h.put("x-ss-stub", Crypto.md5HexUpper(body));
        return h;
    }

    /** 注册 JSON（固定的匿名安卓机型参数，不含任何账号或真实设备信息）。 */
    static Map<String, Object> payload(long now, String cdid) {
        Map<String, Object> header = Json.obj();
        header.put("display_name", "红果免费短剧");
        header.put("update_version_code", 73932);
        header.put("manifest_version_code", 73932);
        header.put("app_version_minor", "7.3.9.32");
        header.put("aid", 8662);
        header.put("channel", "update_64");
        header.put("package", "com.phoenix.read");
        header.put("app_version", "7.3.9.32");
        header.put("version_code", 73932);
        header.put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo");
        header.put("sdk_target_version", 29);
        header.put("git_hash", "3a88f4e");
        header.put("os", "Android");
        header.put("os_version", "16");
        header.put("os_api", 36);
        header.put("device_model", "25053RT47C");
        header.put("device_brand", "Redmi");
        header.put("device_manufacturer", "Redmi");
        header.put("cpu_abi", "arm64-v8a");
        header.put("release_build", "b5e187f");
        header.put("density_dpi", 520);
        header.put("display_density", "mdpi");
        header.put("resolution", "1280x2772");
        header.put("language", "zh");
        header.put("timezone", 8);
        header.put("access", "wifi");
        header.put("not_request_sender", 0);
        header.put("carrier", "CHINA MOBILE");
        header.put("mcc_mnc", "46000");
        header.put("rom", "eng.root.20231116.160340");
        header.put("rom_version", "W528JS release-keys");
        header.put("cdid", cdid);
        header.put("sig_hash", "56a962410c494bbaf0b58dba20cae56f");
        byte[] openudid = new byte[8];
        RND.nextBytes(openudid);
        header.put("openudid", Crypto.hex(openudid));
        header.put("clientudid", UUID.randomUUID().toString());
        header.put("ipv6_list", Json.arr());
        header.put("region", "CN");
        header.put("tz_name", "Asia/Shanghai");
        header.put("tz_offset", 28800);
        header.put("sim_region", "cn");
        header.put("sim_serial_number", Json.arr());
        header.put("oaid_may_support", false);
        header.put("req_id", UUID.randomUUID().toString());
        header.put("device_platform", "android");
        Map<String, Object> custom = Json.obj();
        custom.put("account_region_src", "fallback");
        custom.put("account_region", "cn");
        custom.put("host_bit", 64);
        custom.put("dragon_device_type", "phone");
        header.put("custom", custom);
        header.put("pre_installed_channel", "");
        header.put("apk_first_install_time", now);
        header.put("is_system_app", 0);
        header.put("sdk_flavor", "china");
        header.put("guest_mode", 0);

        Map<String, Object> out = Json.obj();
        out.put("magic_tag", "ss_app_log");
        out.put("header", header);
        out.put("_gen_time", now);
        return out;
    }

    /** ttEncrypt(gzip(payload))。random 由调用方提供，便于测试解密往返。 */
    static byte[] buildBody(long now, String cdid, byte[] random) throws Exception {
        if (random.length != 32) throw new IllegalArgumentException("random must be 32 bytes");
        byte[] json = Json.stringify(payload(now, cdid)).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = gzip(json);
        byte[] derived = deriveKeyIv(random);
        byte[] plaintext = concat(MessageDigest.getInstance("SHA-512").digest(compressed), compressed);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(derived, 0, 16, "AES"),
                new IvParameterSpec(derived, 16, 16));
        return concat(MAGIC, random, c.doFinal(plaintext));
    }

    static byte[] deriveKeyIv(byte[] random) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        byte[] inner = sha.digest(random);
        sha.update(inner);
        sha.update(KDF_SALT);
        return Arrays.copyOf(sha.digest(), 32);
    }

    // ==================== 响应解析 ====================

    static Identity parse(Map<String, Object> j, String cdid) throws IOException {
        if (j == null) throw new IOException("register response is empty");
        Object code = j.containsKey("code") ? j.get("code") : j.get("Code");
        if (Json.optLong(code, 0) != 0) throw new IOException("register rejected");
        String deviceId = responseId(j, "device_id");
        String iid = responseId(j, "install_id");
        if (deviceId.isEmpty() || iid.isEmpty()) throw new IOException("register response has no ids");
        return new Identity(deviceId, iid, cdid);
    }

    /**
     * 优先取 {@code <name>_str}，否则取数值字段。19 位 ID 必须保持精确：
     * 只接受十进制字符串、Long 或 BigInteger 形式的正整数，拒绝浮点（可能已丢精度）和零。
     */
    static String responseId(Map<String, Object> j, String name) {
        Object preferred = j.get(name + "_str");
        Object v = (preferred == null || "".equals(preferred)) ? j.get(name) : preferred;
        String s;
        if (v instanceof String) {
            s = ((String) v).trim();
        } else if (v instanceof Long || v instanceof Integer || v instanceof BigInteger) {
            s = v.toString();
        } else {
            return "";
        }
        if (!VALID_ID.matcher(s).matches()) return "";
        return new BigInteger(s).signum() > 0 ? s : "";
    }

    // ==================== 传输与工具 ====================

    private static Reply httpRegister(String url, Map<String, String> headers, byte[] body)
            throws IOException {
        Http.Req req = new Http.Req(url).method("POST").timeout(TIMEOUT_MS);
        req.followRedirect = false;
        for (Map.Entry<String, String> e : headers.entrySet()) req.header(e.getKey(), e.getValue());
        req.body(body);
        Http.Resp res = Http.send(req);
        return new Reply(res.status, res.text());
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    private static long now() {
        return System.currentTimeMillis() + clockOffsetForTest;
    }

    // ==================== 测试钩子（包内可见） ====================

    static synchronized void resetForTest(Transport t) {
        transport = t == null ? PlaybackDevice::httpRegister : t;
        ready = null;
        lastFailure = null;
        lastFailureAt = 0;
        clockOffsetForTest = 0;
    }

    static synchronized void advanceClockForTest(long ms) {
        clockOffsetForTest += ms;
    }
}
