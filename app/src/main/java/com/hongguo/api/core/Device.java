package com.hongguo.api.core;

import com.hongguo.api.util.Config;
import java.security.SecureRandom;
import java.util.Map;

/** Stable app-scoped identity; Application persists it in private preferences. */
public final class Device {
    private Device() {}
    private static final SecureRandom RANDOM = new SecureRandom();
    public static String randomDeviceId() {
        return new java.math.BigInteger(64, RANDOM)
                .mod(java.math.BigInteger.valueOf(9000000000000000L))
                .add(java.math.BigInteger.valueOf(1000000000000000L)).toString();
    }
    public static boolean isValidId(String value) {
        return value != null && value.trim().matches("[0-9]{10,20}");
    }
    public static final class Identity {
        public final String deviceId;
        public final String source;
        Identity(String deviceId, String source) { this.deviceId = deviceId; this.source = source; }
    }
    public static Identity ensureDeviceId(Map<String, Object> baseQuery) {
        String id = Config.str("runtime.device_id", "");
        if (!isValidId(id)) throw new IllegalStateException("Application identity is not initialized");
        if (baseQuery != null) {
            baseQuery.put("device_id", id);
            baseQuery.put("iid", "0");
        }
        return new Identity(id, "application");
    }
}
