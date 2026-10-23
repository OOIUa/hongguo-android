package com.hongguo.api.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Privacy-preserving events: never emits caller text, responses, URLs or identities. */
public final class Log {
    private Log() {}
    public static Charset consoleCharset() { return StandardCharsets.UTF_8; }
    public static void initEncoding() { /* Android owns stdout and logcat encoding. */ }
    public static void info(String message) { write("INFO", message); }
    public static void warn(String message) { write("WARN", message); }
    public static void error(String message) { write("ERROR", message); }
    public static void error(String message, Throwable ignored) { write("ERROR", message); }
    private static void write(String level, String message) {
        String event = "core_event";
        if (message != null) {
            if (message.contains("签名")) event = "signature_event";
            else if (message.contains("缓存")) event = "cache_event";
            else if (message.contains("官网")) event = "web_source_event";
            else if (message.contains("风控")) event = "upstream_risk_event";
            else if (message.contains("登录")) event = "authentication_event";
            else if (message.contains("解密") || message.contains("mp4")) event = "media_event";
        }
        System.err.println("Hongguo [" + level + "] " + event);
    }
    public static String env(String name, String def) {
        try {
            String value = System.getProperty(name);
            if (value == null || value.trim().isEmpty()) value = System.getenv(name);
            return value == null || value.trim().isEmpty() ? def : value.trim();
        } catch (SecurityException ignored) { return def; }
    }
    public static int envInt(String name, int def) {
        try { return Integer.parseInt(env(name, "")); }
        catch (NumberFormatException ignored) { return def; }
    }
    public static boolean envBool(String name, boolean def) {
        String value = env(name, null);
        if (value == null) return def;
        value = value.toLowerCase(Locale.ROOT);
        return value.equals("1") || value.equals("true") || value.equals("yes") || value.equals("on");
    }
}
