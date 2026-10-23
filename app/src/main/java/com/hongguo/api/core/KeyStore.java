package com.hongguo.api.core;

import com.hongguo.api.util.Config;

/** Compatibility name for app identity. No API/admin credentials or filesystem state. */
public final class KeyStore {
    private static final KeyStore INSTANCE = new KeyStore();
    public static KeyStore store() { return INSTANCE; }
    public KeyStore() {}
    public String deviceId() { return Config.str("runtime.device_id", ""); }
    public void setDeviceId(String id) {
        if (!deviceId().equals(id))
            throw new IllegalStateException("Device identity must be initialized by Application");
    }
}
