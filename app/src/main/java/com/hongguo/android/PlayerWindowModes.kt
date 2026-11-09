package com.hongguo.android

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer

/** Only a picture wider than tall gains anything from landscape, so only such episodes offer 全屏观看. */
internal fun isLandscapeVideo(width: Int, height: Int): Boolean = width > 0 && height > 0 && width > height

/**
 * Top of the 全屏观看 button, in the same unit as the container: just under a picture fitted to the container's
 * width and centred vertically, as the player surface draws it.
 */
internal fun fullScreenButtonTop(containerWidth: Float, containerHeight: Float, videoWidth: Int, videoHeight: Int, gap: Float): Float {
    if (videoWidth <= 0 || videoHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) return containerHeight / 2 + gap
    val pictureHeight = minOf(containerHeight, containerWidth * videoHeight / videoWidth)
    return (containerHeight + pictureHeight) / 2 + gap
}

/** The platform rejects small-window ratios outside 1:2.39 – 2.39:1; vertical dramas default to 9:16. */
internal fun pictureInPictureRatio(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 9 to 16
    val ratio = width.toDouble() / height
    return when {
        ratio > 2.39 -> 239 to 100
        ratio < 1 / 2.39 -> 100 to 239
        else -> width to height
    }
}

/** 全屏观看 turns the screen to landscape; leaving it returns to portrait even while the phone is held sideways. */
internal fun Activity.requestPlayerFullScreen(fullScreen: Boolean) {
    requestedOrientation = if (fullScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
}

@Composable
internal fun FullScreenWatchButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, color = Color.White.copy(alpha = .14f), contentColor = Color.White, shape = RoundedCornerShape(8.dp),
        modifier = modifier.testTag("player_full_screen").semantics { role = Role.Button; contentDescription = "全屏观看" }) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ScreenRotation, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("全屏观看", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Stable
internal class PlayerPictureInPicture(val supported: Boolean) {
    var active by mutableStateOf(false)
        internal set
    internal var enterAction: () -> Boolean = { false }

    /** False when the system refuses, for example after the user turned 画中画 off for this app. */
    fun enter(): Boolean = enterAction()
}

/**
 * 小窗播放 for the open player. The button enters it directly; leaving the app while a video plays enters it
 * automatically (Android 12+ through the platform, older versions on the user-leave hint). The user can turn this
 * off per app in the system's 画中画 settings. The small window carries a play/pause action.
 */
@Composable
internal fun rememberPlayerPictureInPicture(
    enabled: Boolean,
    playing: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    onTogglePlay: () -> Unit,
): PlayerPictureInPicture {
    val activity = LocalActivity.current as? ComponentActivity
    val context = LocalContext.current
    val supported = remember(activity) {
        activity != null && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    val pip = remember(activity) { PlayerPictureInPicture(supported) }
    val toggle by rememberUpdatedState(onTogglePlay)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPlaying by rememberUpdatedState(playing)
    // Between episodes the size is 0 until the next one's first format; keeping the last known size stops the
    // small window from jumping to 9:16 and back (issue #4).
    val lastSize = remember(activity) { IntArray(2) }
    if (videoWidth > 0 && videoHeight > 0) { lastSize[0] = videoWidth; lastSize[1] = videoHeight }
    val shownWidth = lastSize[0]
    val shownHeight = lastSize[1]
    if (activity == null || !supported) return pip

    fun params(autoEnter: Boolean): PictureInPictureParams {
        val (width, height) = pictureInPictureRatio(shownWidth, shownHeight)
        val intent = Intent(PipToggleAction).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = if (playing) "暂停" else "播放"
        val icon = Icon.createWithResource(context, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .setActions(listOf(RemoteAction(icon, label, label, pending)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    pip.enterAction = {
        runCatching { activity.enterPictureInPictureMode(params(enabled && playing)) }.getOrDefault(false)
    }

    DisposableEffect(activity) {
        pip.active = activity.isInPictureInPictureMode
        val listener = Consumer<PictureInPictureModeChangedInfo> { pip.active = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        // Android 11 and older have no auto-enter: going home while a video plays enters the small window here.
        val leaving = Runnable {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && currentEnabled && currentPlaying && !pip.active) pip.enter()
        }
        activity.addOnUserLeaveHintListener(leaving)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { if (intent.action == PipToggleAction) toggle() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PipToggleAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(listener)
            activity.removeOnUserLeaveHintListener(leaving)
            runCatching { context.unregisterReceiver(receiver) }
            // Closing the player must stop the app from shrinking into a small window when the user leaves it.
            runCatching { activity.setPictureInPictureParams(params(false)) }
        }
    }
    // Keeps the ratio, the play/pause action and auto-enter current, including while the window is small.
    LaunchedEffect(enabled, playing, shownWidth, shownHeight) {
        runCatching { activity.setPictureInPictureParams(params(enabled && playing)) }
    }
    return pip
}

private const val PipToggleAction = "com.hongguo.android.action.PIP_TOGGLE"
