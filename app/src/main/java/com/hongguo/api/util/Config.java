package com.hongguo.api.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Android configuration injected by Application before any service is used. */
public final class Config {
    private Config() {}
    private static volatile Map<String, Object> config;
    private static final Map<String, String> ENV_OF = new LinkedHashMap<>();
    static {
        ENV_OF.put("runtime.cache.max_files", "HONGGUO_CACHE_MAX_FILES");
        ENV_OF.put("runtime.cache.mem_max", "HG_MEM_CACHE_MAX");
        ENV_OF.put("runtime.cache.mem_max_mb", "HG_MEM_CACHE_MAX_MB");
        ENV_OF.put("runtime.cache.img_max_mb", "HG_IMG_CACHE_MAX_MB");
        ENV_OF.put("runtime.cache.img_max_kb", "HG_IMG_MAX_KB");
        ENV_OF.put("runtime.throttle_ms", "HG_THROTTLE_MS");
        ENV_OF.put("runtime.sweep_interval_ms", "HG_SWEEP_INTERVAL_MS");
    }
    /** Identity is persisted by Android; this core never creates server credentials. */
    public static synchronized void initialize(String json, String dataDir, String deviceId) {
        if (dataDir == null || !new File(dataDir).isAbsolute())
            throw new IllegalArgumentException("Application data directory must be absolute");
        if (deviceId == null || !deviceId.trim().matches("[0-9]{10,20}"))
            throw new IllegalArgumentException("Application device identity is invalid");
        String text = json == null ? "{}" : json.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        Map<String, Object> parsed = Json.parseObject(text);
        if (parsed == null) throw new IllegalArgumentException("Application configuration is invalid");
        Map<String, Object> next = new LinkedHashMap<>(parsed);
        Map<String, Object> runtime = Json.optObj(next.get("runtime"));
        runtime = runtime == null ? Json.obj() : new LinkedHashMap<>(runtime);
        runtime.put("data_dir", new File(dataDir).getAbsolutePath());
        runtime.put("device_id", deviceId.trim());
        runtime.put("transcode", false);
        Map<String, Object> cache = Json.optObj(runtime.get("cache"));
        cache = cache == null ? Json.obj() : new LinkedHashMap<>(cache);
        cache.put("mem_max", Math.max(16, Math.min(512, Json.optInt(cache.get("mem_max"), 512))));
        cache.put("mem_max_mb", Math.max(1, Math.min(24, Json.optInt(cache.get("mem_max_mb"), 24))));
        runtime.put("cache", cache);
        next.put("runtime", runtime);
        config = Collections.unmodifiableMap(next);
    }
    public static boolean loaded() { return config != null; }
    public static String loadedFrom() { return loaded() ? "android-assets" : null; }
    public static Object get(String key) {
        Object value = config;
        if (value == null || key == null) return null;
        for (String segment : key.split("\\.")) {
            if (!(value instanceof Map)) return null;
            value = ((Map<?, ?>) value).get(segment);
        }
        return value;
    }
    private static String override(String key) {
        String name = ENV_OF.get(key);
        return name == null ? null : Log.env(name, null);
    }
    public static String str(String key, String def) {
        String value = override(key);
        if (value == null) value = Json.optStr(get(key), "").trim();
        return value.isEmpty() ? def : value;
    }
    public static int num(String key, int def) {
        String value = override(key);
        return Json.optInt(value == null ? get(key) : value, def);
    }
    public static boolean bool(String key, boolean def) {
        String value = override(key);
        Object raw = value == null ? get(key) : value;
        if (raw instanceof Boolean) return (Boolean) raw;
        String text = Json.optStr(raw, "").toLowerCase(Locale.ROOT);
        if (text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on")) return true;
        if (text.equals("false") || text.equals("0") || text.equals("no") || text.equals("off")) return false;
        return def;
    }
    public static String version() { return str("version", "android"); }
    public static List<String> keys() {
        List<String> keys = new ArrayList<>(ENV_OF.keySet());
        Collections.sort(keys);
        return keys;
    }
}
