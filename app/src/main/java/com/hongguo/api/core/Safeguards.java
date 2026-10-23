package com.hongguo.api.core;

import com.hongguo.api.util.Json;
import com.hongguo.api.util.Config;
import com.hongguo.api.util.Log;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 风控与稳定性辅助：内存缓存、节流、风控识别、设备身份池。
 * 移植自 server/src/safeguards.js + device.js 的池化部分。
 */
public final class Safeguards {

    private Safeguards() {}

    // ==================== 缓存 ====================

    /**
     * 内存缓存。
     *
     * 历史问题（内存持续增长的元凶）：只按条数判断是否清理（>20000），
     * 且**没有任何后台清扫** —— 过期条目只有在恰好被 cacheGet 命中时才会移除。
     * 意味着 6 小时 TTL 的 episodes/vmtracks 缓存实际永不回收，只增不减。
     *
     * 现在改为：访问序 LinkedHashMap（LRU）+ 条数与字节双上限 + 定时清扫过期条目。
     */
    private static final Map<String, Entry> MEM = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, Entry>(256, 0.75f, true));

    /** 当前缓存总字节（近似值）。 */
    private static final java.util.concurrent.atomic.AtomicLong MEM_BYTES =
            new java.util.concurrent.atomic.AtomicLong();

    /** 当前缓存条数（近似值，LinkedHashMap.size() 需加锁，故单独维护）。 */
    private static final java.util.concurrent.atomic.AtomicInteger MEM_TOTAL =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 条数上限：HG_MEM_CACHE_MAX，默认 4096。 */
    private static final int MAX_ENTRIES = Config.num("runtime.cache.mem_max", 4096);

    /** 字节上限：HG_MEM_CACHE_MAX_MB，默认 128MB。 */
    private static final long MAX_BYTES =
            (long) Config.num("runtime.cache.mem_max_mb", 128) * 1024L * 1024L;

    /** 清扫周期：HG_SWEEP_INTERVAL_MS，默认 60s。 */
    private static final long SWEEP_INTERVAL_MS =
            Config.num("runtime.sweep_interval_ms", 60) * 1000L;

    static {
        // 后台清扫过期条目：这是让 TTL 真正生效的关键。
        // 单个 daemon 线程，不阻止 JVM 退出。
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    sweep();
                } catch (Throwable ignored) {
                    // 清扫失败不应影响服务
                }
            }
        }, "hg-cache-sweeper");
        t.setDaemon(true);
        t.start();
    }

    private static final class Entry {
        final Object val;
        final long expire;   // 0 = 永不过期
        final int weight;    // 近似字节数，用于字节上限淘汰

        Entry(Object val, long expire, int weight) {
            this.val = val;
            this.expire = expire;
            this.weight = weight;
        }
    }

    /** 拼接缓存键（对齐 JS 的 parts.map(String).join(':')）。 */
    public static String cacheKey(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(stringifyPart(parts[i]));
        }
        return sb.toString();
    }

    private static String stringifyPart(Object p) {
        if (p == null) return "null";
        if (p instanceof Double || p instanceof Float) {
            double d = ((Number) p).doubleValue();
            if (d == Math.floor(d) && Math.abs(d) < 9.007199254740992E15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(p);
    }

    public static Object cacheGet(String key) {
        Entry hit;
        // LinkedHashMap 访问序：get 也会结构性修改，故必须加锁
        synchronized (MEM) {
            hit = MEM.get(key);
        }
        if (hit == null) return null;
        if (hit.expire != 0 && hit.expire < System.currentTimeMillis()) {
            removeEntry(key, hit);
            return null;
        }
        return hit.val;
    }

    /** 缓存写入。ttl 单位为秒，<=0 表示不缓存。 */
    public static void cacheSet(String key, Object val, long ttlSec) {
        if (ttlSec <= 0) return;
        long expire = System.currentTimeMillis() + ttlSec * 1000L;
        Entry e = new Entry(val, expire, weigh(val));
        synchronized (MEM) {
            Entry old = MEM.put(key, e);
            if (old != null) {
                MEM_BYTES.addAndGet(-old.weight);
                MEM_TOTAL.decrementAndGet();
            }
            MEM_BYTES.addAndGet(e.weight);
            MEM_TOTAL.incrementAndGet();
            // Keep eviction and accounting atomic; automatic map eviction loses weights.
            trim();
        }
    }

    private static void removeEntry(String key, Entry e) {
        synchronized (MEM) {
            Entry cur = MEM.get(key);
            if (cur == e) {
                MEM.remove(key);
                MEM_BYTES.addAndGet(-cur.weight);
                MEM_TOTAL.decrementAndGet();
            }
        }
    }

    /** 清扫所有过期条目。 */
    static void sweep() {
        long now = System.currentTimeMillis();
        synchronized (MEM) {
            java.util.Iterator<Map.Entry<String, Entry>> it = MEM.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Entry> en = it.next();
                if (en.getValue().expire != 0 && en.getValue().expire < now) {
                    MEM_BYTES.addAndGet(-en.getValue().weight);
                    MEM_TOTAL.decrementAndGet();
                    it.remove();
                }
            }
        }
    }

    /** 裁剪到上限内（按 LRU 顺序从头淘汰）。 */
    private static void trim() {
        synchronized (MEM) {
            java.util.Iterator<Map.Entry<String, Entry>> it = MEM.entrySet().iterator();
            while (it.hasNext()
                    && (MEM_TOTAL.get() > MAX_ENTRIES || MEM_BYTES.get() > MAX_BYTES)) {
                Map.Entry<String, Entry> en = it.next();
                MEM_BYTES.addAndGet(-en.getValue().weight);
                MEM_TOTAL.decrementAndGet();
                it.remove();
            }
        }
    }

    /**
     * 估算缓存值的近似字节数。
     *
     * 只做量级估算即可（目的是别让单个大value 撑爆堆，不是精确计量）：
     * 字符串按 UTF-16 估2 字节/字符 + 对象头，集合按元素个数摊。
     * 带深度上限，防止自引用结构导致栈溢出。
     */
    public static int weigh(Object v) {
        if (v == null) return 0;
        try {
            return weigh(v, 0);
        } catch (Throwable t) {
            return 256;
        }
    }

    private static int weigh(Object v, int depth) {
        if (v == null) return 4;
        if (depth > 4) return 64;
        if (v instanceof String) return 40 + ((String) v).length() * 2;
        if (v instanceof byte[]) return 16 + ((byte[]) v).length;
        if (v instanceof Number || v instanceof Boolean) return 16;
        if (v instanceof Map) {
            int sum = 48;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                sum += 48 + weigh(e.getKey(), depth + 1) + weigh(e.getValue(), depth + 1);
                if (sum > 1 << 20) return sum;  // 单个 value 超 1MB 时不再细算
            }
            return sum;
        }
        if (v instanceof List) {
            int sum = 48;
            for (Object o : (List<?>) v) {
                sum += 16 + weigh(o, depth + 1);
                if (sum > 1 << 20) return sum;
            }
            return sum;
        }
        if (v instanceof Object[]) {
            int sum = 24;
            for (Object element : (Object[]) v) {
                sum += 16 + weigh(element, depth + 1);
                if (sum > 1 << 20) return sum;
            }
            return sum;
        }
        return 64;
    }

    // ==================== 睡眠 ====================

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 节流 ====================

    /**
     * 串行化节流：保证任意两次外部 API 调用间隔 >= minIntervalMs。
     * 用 synchronized 串行化，对齐 JS 的 Promise 链。
     */
    public static final class Throttle {
        private final long minIntervalMs;
        private long last = 0;

        public Throttle() {
            this.minIntervalMs = Config.num("runtime.throttle_ms", 260);
        }

        public synchronized void wait_() {
            long now = System.currentTimeMillis();
            long gap = now - last;
            if (gap < minIntervalMs) {
                sleep(minIntervalMs - gap);
            }
            last = System.currentTimeMillis();
        }
    }

    private static final Throttle THROTTLE = new Throttle();

    public static Throttle throttle() { return THROTTLE; }

    // ==================== 风控识别 ====================

    private static final Set<Integer> AUTH_CODES = new HashSet<>(Arrays.asList(401, 403, 8, 1001));
    private static final List<String> AUTH_KEYWORDS = Arrays.asList(
            "token", "登录", "login", "未登录", "not login", "unauthor");
    private static final Set<Integer> RISK_CODES = new HashSet<>(Arrays.asList(429, 110001, 110002, 110003));
    private static final List<String> RISK_KEYWORDS = Arrays.asList(
            "verify", "captcha", "risk", "频繁", "稍后", "验证", "rate limit", "too many");

    /** 风控 / 登录态失效异常。 */
    public static class ApiError extends RuntimeException {
        /** 附加在响应里的诊断字段（safe_response）。 */
        public Map<String, Object> safeResponse;
        /** 附加的诊断字段（response）。 */
        public Map<String, Object> diagnostic;
        /** 附加的结构描述（model_shape）。 */
        public Map<String, Object> modelShape;

        public ApiError(String msg) { super(msg); }
    }

    /** 风控 / 限流。 */
    public static final class RiskControlError extends ApiError {
        public RiskControlError(String msg) { super(msg); }
    }

    /** 登录态失效。 */
    public static final class AuthExpiredError extends ApiError {
        public AuthExpiredError(String msg) { super(msg); }
    }

    /** 上游未返回预期内容。 */
    public static final class UpstreamResponseError extends ApiError {
        public UpstreamResponseError(String msg) { super(msg); }
    }

    /**
     * 检查上游业务响应，命中风控/登录态失效则抛对应错误（附安全诊断信息）。
     */
    public static void checkResponse(Map<String, Object> j) {
        if (j == null) return;
        Integer code = null;
        Object c = j.get("code");
        if (c != null) code = Json.optInt(c, -1);
        String msg = Json.optStr(j.containsKey("message") ? j.get("message") : j.get("msg"), "")
                .toLowerCase();

        boolean knownRisk = code != null && RISK_CODES.contains(code);
        boolean riskKeyword = false;
        for (String w : RISK_KEYWORDS) if (msg.contains(w)) { riskKeyword = true; break; }
        if (knownRisk || (code != null && code != 0 && riskKeyword)) {
            RiskControlError err = new RiskControlError("风控/限流 (code=" + code + ")");
            Map<String, Object> safe = Json.obj();
            safe.put("code", code);
            safe.put("known_risk_code", knownRisk);
            safe.put("risk_keyword", riskKeyword);
            err.safeResponse = safe;
            throw err;
        }

        boolean knownAuth = code != null && AUTH_CODES.contains(code);
        boolean authKeyword = false;
        for (String w : AUTH_KEYWORDS) if (msg.contains(w)) { authKeyword = true; break; }
        if (knownAuth || (code != null && code != 0 && authKeyword)) {
            AuthExpiredError err = new AuthExpiredError("登录态失效 (code=" + code + ")");
            Map<String, Object> safe = Json.obj();
            safe.put("code", code);
            safe.put("known_auth_code", knownAuth);
            safe.put("auth_keyword", authKeyword);
            err.safeResponse = safe;
            throw err;
        }
        if (code != null && code != 0) {
            UpstreamResponseError error = new UpstreamResponseError("上游业务错误 (code=" + code + ")");
            Map<String, Object> safe = Json.obj();
            safe.put("code", code);
            error.safeResponse = safe;
            throw error;
        }
    }

    // ==================== 设备身份池 ====================

    private static final String[] UA_TEMPLATES = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
    };

    private static final SecureRandom RND = new SecureRandom();

    /** 池中的一个设备身份。 */
    public static final class PoolDevice {
        public final Map<String, Object> query;
        public final String userAgent;

        PoolDevice(Map<String, Object> query, String userAgent) {
            this.query = query;
            this.userAgent = userAgent;
        }
    }

    /** 设备身份池。 */
    public static final class DevicePool {
        private final List<PoolDevice> devices;
        private int idx = 0;

        DevicePool(List<PoolDevice> devices) { this.devices = devices; }

        public PoolDevice current() { return devices.get(idx % devices.size()); }

        public PoolDevice rotate() {
            idx = (idx + 1) % devices.size();
            return devices.get(idx);
        }

        public int size() { return devices.size(); }
    }

    /**
     * 设备身份池：分散 device_id / cdid，降低风控。
     * size<=0 或托管模式时返回 null（单设备，直接沿用 baseQuery 里的稳定 device_id）。
     *
     * 注：这里刻意不为每个池设备生成 iid —— 实测随机 iid 与 device_id 不匹配时，
     * 上游会直接返回空响应（风控拦截）。
     */
    public static DevicePool loadDevicePool(Map<String, Object> baseQuery,
                                            int size, boolean managed, String baseDeviceId) {
        if (managed || size <= 0) return null;
        List<PoolDevice> devices = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("device_id", String.valueOf(rnd(9_000_000_000_000_000L) + 1_000_000_000_000_000L));
            q.put("cdid", String.valueOf(rnd(9_000_000_000_000_000L) + 1_000_000_000_000_000L));
            q.put("device_type", "audit-windows");
            devices.add(new PoolDevice(q, UA_TEMPLATES[i % UA_TEMPLATES.length]));
        }
        // 单设备池沿用主身份，保持与 baseQuery 一致
        if (size == 1 && baseDeviceId != null && !baseDeviceId.isEmpty()) {
            devices.get(0).query.put("device_id", baseDeviceId);
        }
        return new DevicePool(devices);
    }

    /** [0, n) 的随机长整数（对应 JS 的 randomBytes(8).readBigUInt64BE() % n）。 */
    private static long rnd(long n) {
        long v = RND.nextLong() & 0x7FFFFFFFFFFFFFFFL;
        return v % n;
    }
}
