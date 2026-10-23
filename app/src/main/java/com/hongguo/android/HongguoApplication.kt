package com.hongguo.android

import android.app.Application
import com.hongguo.api.util.Config
import java.security.SecureRandom

class HongguoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("installation", MODE_PRIVATE)
        val deviceId = preferences.getString("device_id", null) ?: run {
            val random = SecureRandom()
            val number = (random.nextLong().ushr(1) % 9_000_000_000_000_000L) + 1_000_000_000_000_000L
            number.toString().also { preferences.edit().putString("device_id", it).apply() }
        }
        val config = assets.open("upstream.json").bufferedReader().use { it.readText() }
        Config.initialize(config, cacheDir.resolve("media").absolutePath, deviceId)
    }
}
