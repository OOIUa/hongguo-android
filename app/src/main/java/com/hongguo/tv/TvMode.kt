package com.hongguo.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.edit
import com.hongguo.android.MainActivity
import com.hongguo.android.ThemePreferences

/** User-selectable UI form factor. Keys are persisted in SharedPreferences; never rename existing keys. */
enum class DisplayMode(val key: String, val label: String) {
    AUTO("auto", "自动"),
    PHONE("phone", "手机"),
    TV("tv", "电视");

    companion object {
        fun fromKey(key: String?): DisplayMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

internal data class DeviceCapabilities(
    val uiModeTelevision: Boolean,
    val hasLeanback: Boolean,
    val hasTelevisionFeature: Boolean,
    val hasTouchscreen: Boolean,
    val hasPcFeature: Boolean,
) {
    /**
     * Standard Android TV / Google TV reports UI_MODE_TYPE_TELEVISION or FEATURE_LEANBACK.
     * Domestic TV boxes and projectors sometimes omit Leanback but have neither touchscreen nor PC mode.
     */
    val isTelevision: Boolean
        get() = uiModeTelevision || hasLeanback || hasTelevisionFeature || (!hasTouchscreen && !hasPcFeature)
}

internal enum class ActivityRoute { PHONE, TV }

internal fun resolveRouteForPhoneEntry(mode: DisplayMode, capabilities: DeviceCapabilities): ActivityRoute =
    when (mode) {
        DisplayMode.TV -> ActivityRoute.TV
        DisplayMode.PHONE -> ActivityRoute.PHONE
        DisplayMode.AUTO -> if (capabilities.isTelevision) ActivityRoute.TV else ActivityRoute.PHONE
    }

internal fun resolveRouteForTvEntry(mode: DisplayMode): ActivityRoute =
    when (mode) {
        DisplayMode.PHONE -> ActivityRoute.PHONE
        DisplayMode.AUTO, DisplayMode.TV -> ActivityRoute.TV
    }

internal fun modeForSwitchToPhone(capabilities: DeviceCapabilities): DisplayMode =
    if (capabilities.isTelevision) DisplayMode.PHONE else DisplayMode.AUTO

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal object TvMode {
    private const val PREF_KEY = "display_mode"
    private const val FEATURE_PC = "android.hardware.type.pc"

    fun read(context: Context): DisplayMode {
        val prefs = context.getSharedPreferences(ThemePreferences.FILE, Context.MODE_PRIVATE)
        return DisplayMode.fromKey(prefs.getString(PREF_KEY, null))
    }

    fun write(context: Context, mode: DisplayMode) {
        val prefs = context.getSharedPreferences(ThemePreferences.FILE, Context.MODE_PRIVATE)
        prefs.edit { putString(PREF_KEY, mode.key) }
    }

    fun capabilities(context: Context): DeviceCapabilities {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val configTelevision =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val uiModeTelevision =
            uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION || configTelevision
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val televisionFeature = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
        return DeviceCapabilities(
            uiModeTelevision = uiModeTelevision,
            hasLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            hasTelevisionFeature = televisionFeature,
            hasTouchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            hasPcFeature = pm.hasSystemFeature(FEATURE_PC),
        )
    }

    /** Called in MainActivity.onCreate before setContent so TV boxes enter TvActivity seamlessly. */
    fun redirectFromPhoneIfNeeded(activity: Activity): Boolean {
        if (resolveRouteForPhoneEntry(read(activity), capabilities(activity)) != ActivityRoute.TV) return false
        activity.startActivity(
            Intent(activity, TvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        activity.finish()
        return true
    }

    /** Called in TvActivity.onCreate before setContent if the user explicitly locked phone mode. */
    fun redirectFromTvIfNeeded(activity: Activity): Boolean {
        if (resolveRouteForTvEntry(read(activity)) != ActivityRoute.PHONE) return false
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        activity.finish()
        return true
    }

    fun applySelectionFromPhone(activity: Activity, mode: DisplayMode) {
        write(activity, mode)
        if (resolveRouteForPhoneEntry(mode, capabilities(activity)) == ActivityRoute.TV) {
            activity.startActivity(Intent(activity, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            activity.finish()
        }
    }

    fun switchToPhone(activity: Activity) {
        write(activity, modeForSwitchToPhone(capabilities(activity)))
        activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        activity.finish()
    }
}
