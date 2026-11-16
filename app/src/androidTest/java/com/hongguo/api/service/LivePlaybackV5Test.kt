// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.service

import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.hongguo.android.PlaybackProbeActivity
import com.hongguo.android.PreparedVideo
import com.hongguo.android.videoSourceHandleFor
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit anonymous live acceptance: production stream, range decryptor and Media3 renderer. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LivePlaybackV5Test {
    @get:Rule val activity = ActivityTestRule(PlaybackProbeActivity::class.java)

    @Test fun realEpisodesRenderSeekAndContinue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("playbackLiveProbe") == "true")
        // The reported ByteVC2-only title, both opening episodes; plus a separate previously working title.
        for (id in listOf("7703065456646556696", "7703065456650767384", "7674087321058167832")) {
            val session = ProgressiveMedia.open(id, "best", null)
            val source = videoSourceHandleFor(PreparedVideo(id, "", 1, session))
            val first = CountDownLatch(1)
            val failure = AtomicReference<PlaybackException?>()
            lateinit var player: ExoPlayer
            lateinit var surface: SurfaceView
            try {
                instrumentation.runOnMainSync {
                    surface = SurfaceView(activity.activity)
                    (activity.activity.window.decorView as ViewGroup).addView(surface, FrameLayout.LayoutParams(360,640))
                    player = ExoPlayer.Builder(activity.activity).build()
                    player.volume = 0f
                    player.setVideoSurfaceView(surface)
                    player.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { first.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error); first.countDown() }
                    })
                    player.setMediaSource(source.mediaSource); player.prepare(); player.playWhenReady = true
                }
                assertTrue("Real first frame did not arrive", first.await(45, TimeUnit.SECONDS))
                assertNull("Decoder error code=${failure.get()?.errorCode}", failure.get())
                fun awaitPosition(minimum: Long) {
                    val deadline = SystemClock.elapsedRealtime() + 20000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        var position = 0L
                        instrumentation.runOnMainSync { position = player.currentPosition }
                        if (position >= minimum) return
                        assertNull("Decoder error", failure.get()); SystemClock.sleep(200)
                    }
                    fail("Real playback did not advance")
                }
                awaitPosition(3000)
                instrumentation.runOnMainSync { player.seekTo(15000) }
                awaitPosition(18000)
                instrumentation.runOnMainSync { player.pause() }
                SystemClock.sleep(300)
                instrumentation.runOnMainSync { assertFalse(player.isPlaying); player.play() }
                awaitPosition(19000)
                println("PASS live v5 episode=$id first_frame=true seek=true resume=true downloaded=${session.downloadedBytes()} total=${session.length()}")
            } finally {
                instrumentation.runOnMainSync { player.release(); (surface.parent as? ViewGroup)?.removeView(surface) }
                source.cancelActiveReads(); session.close()
            }
        }
    }
}
