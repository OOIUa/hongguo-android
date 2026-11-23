// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.util

import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.hongguo.android.DramaRepository
import com.hongguo.android.Episode
import com.hongguo.android.PlaybackProbeActivity
import com.hongguo.android.PreparedVideo
import com.hongguo.android.createVideoPlayer
import com.hongguo.android.videoSourceHandleFor
import com.hongguo.api.service.PublicEpisodeSource
import java.net.SocketException
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real guest playback. App registration and every request on the native API transport stop in this process. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PublicGuestPlaybackTest {
    @get:Rule val activity = ActivityTestRule(PlaybackProbeActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun guestEpisodeWithDeclaredDuration() =
        sample("铁血信使", "7696083096164125721", "7696085244503411774")

    @Test fun guestEpisodeWithoutDeclaredDuration() =
        sample("铁血信使", "7696083096164125721", "7696085113104239678", index = 2)

    @Test fun mixedAppResponsesAndLandscapeGuestEpisode() =
        sample("灾荒之年，我能往返两界", "7704961272428645401", "7704961301620984856", mixed = true)

    @Test fun laterEpisodeOutsideGuestCoverage() =
        sample("分家住破屋，我赶山带妻女吃肉", "7706488837177887768", "7706488855305653272", index = 31, unavailable = true)

    private fun sample(title: String, series: String, id: String, index: Int = 1,
                       mixed: Boolean = false, unavailable: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publicGuestLive") == "true")
        val context = activity.activity
        val originalDirectory = Config.str("runtime.data_dir", "")
        val originalDevice = Config.str("runtime.device_id", "")
        val config = context.assets.open("upstream.json").bufferedReader().use { it.readText() }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "guest-test-").toFile()
        val empty = AtomicInteger()
        val lost = AtomicInteger()
        val registrations = AtomicInteger()
        val models = AtomicInteger()
        check(Http.transportForTest == null)
        try {
            Config.initialize(config, directory.absolutePath, "1111111111111111")
            Http.transportForTest = Http.TransportFixture { request ->
                val path = URI(request.url).path.trimEnd('/')
                val bytes = when {
                    path.endsWith("/device_register") -> {
                        registrations.incrementAndGet()
                        """{"code":0,"device_id_str":"1111111111111111","install_id_str":"2222222222222222"}""".toByteArray()
                    }
                    mixed && models.incrementAndGet() == 2 -> {
                        lost.incrementAndGet()
                        throw SocketException("Connection reset")
                    }
                    else -> { empty.incrementAndGet(); byteArrayOf() }
                }
                Http.Resp(200, bytes, emptyMap())
            }
            try {
                val video = runBlocking {
                    DramaRepository().prepare(Episode(id, index, "第${index}集"), series, allowFallback = true) { _, _, _ -> }
                }
                try {
                    assertFalse("Guest coverage changed for this control episode", unavailable)
                    val metrics = play(video)
                    android.util.Log.i("GuestPlaybackQA", "$title #$index $metrics registrations=${registrations.get()} empty=${empty.get()} lost=${lost.get()}")
                } finally {
                    video.session?.close()
                }
            } catch (noSource: PublicEpisodeSource.GuestUnavailableException) {
                if (!unavailable) throw noSource
                android.util.Log.i("GuestPlaybackQA", "$title #$index guest_unavailable registrations=${registrations.get()} empty=${empty.get()}")
            }
            assertTrue("No empty native response was injected", empty.get() > 0)
            if (mixed) assertTrue("No transport interruption was injected", lost.get() > 0)
        } finally {
            Http.transportForTest = null
            Config.initialize(config, originalDirectory, originalDevice)
            directory.deleteRecursively()
        }
    }

    private fun play(video: PreparedVideo): String {
        val source = videoSourceHandleFor(video)
        val firstFrame = CountDownLatch(1)
        val pts = AtomicLong(-1)
        val error = AtomicReference<PlaybackException?>()
        lateinit var surface: SurfaceView
        lateinit var player: androidx.media3.exoplayer.ExoPlayer
        instrumentation.runOnMainSync {
            surface = SurfaceView(activity.activity)
            (activity.activity.window.decorView as ViewGroup).addView(surface, FrameLayout.LayoutParams(360, 640))
            player = createVideoPlayer(activity.activity, DefaultLoadControl.Builder().build())
        }
        try {
            instrumentation.runOnMainSync {
                player.volume = 0f
                player.setVideoSurfaceView(surface)
                player.setVideoFrameMetadataListener { presentationUs, _, _, _ -> pts.set(presentationUs / 1000) }
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = firstFrame.countDown()
                    override fun onPlayerError(failure: PlaybackException) {
                        error.set(failure)
                        firstFrame.countDown()
                    }
                })
                player.setMediaSource(source.mediaSource)
                player.prepare()
                player.play()
            }
            assertTrue("No decoded first frame within 45s", firstFrame.await(45, TimeUnit.SECONDS))
            assertNull("Decoder error at startup", error.get()?.errorCodeName)
            fun awaitFrame(predicate: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 35_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    assertNull("Decoder error", error.get()?.errorCodeName)
                    var done = false
                    instrumentation.runOnMainSync { done = predicate() }
                    if (done) return
                    SystemClock.sleep(100)
                }
                fail("Timed out waiting for decoded frames")
            }
            awaitFrame { player.isPlaying && pts.get() >= 11_000 && player.currentPosition >= 11_000 }
            val played = pts.get()
            var duration = 0L
            var renderedBefore = 0
            var size = ""
            instrumentation.runOnMainSync {
                duration = player.duration
                assertTrue("Expected full-length episode", duration > 15_000)
                size = "${player.videoSize.width}x${player.videoSize.height}"
                renderedBefore = player.videoDecoderCounters!!.renderedOutputBufferCount
                player.seekTo(duration - 3_000)
            }
            awaitFrame {
                player.playbackState == Player.STATE_READY && pts.get() >= duration - 3_250 &&
                    player.currentPosition >= duration - 2_900 &&
                    player.videoDecoderCounters!!.renderedOutputBufferCount > renderedBefore
            }
            return "size=$size played_ms=$played tail_frame_ms=${pts.get()} duration_ms=$duration"
        } finally {
            instrumentation.runOnMainSync {
                player.release()
                (surface.parent as? ViewGroup)?.removeView(surface)
            }
            source.cancelActiveReads()
        }
    }
}
