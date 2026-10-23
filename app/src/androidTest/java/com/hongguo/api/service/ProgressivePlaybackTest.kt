package com.hongguo.api.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import com.hongguo.android.MainActivity
import com.hongguo.android.PreparedVideo
import com.hongguo.android.ProgressiveVideoDataSource
import com.hongguo.android.videoSourceHandleFor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A real AVC encoder, the production range/decryption session and a real Media3 decoder. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgressivePlaybackTest {
    @get:Rule val activity = ActivityTestRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun firstFramePrecedesFullDownloadAndSeekFetchesMissingMedia() {
        val root = File(activity.activity.cacheDir, "progressive-device-${System.nanoTime()}").apply { mkdirs() }
        val encoded = encodeAvc(File(root, "origin.mp4"))
        assertTrue("The source must exceed the throttled prefix", encoded.size > 2 * 1024 * 1024)
        val origin = RangeOrigin(encoded, 1024 * 1024L)
        val output = File(root, "video.play.mp4")
        val session = openSession(origin, output)
        val sourceHandle = videoSourceHandleFor(PreparedVideo("1", output.absolutePath, 1, session))
        val firstFrame = CountDownLatch(1)
        val frameBytes = AtomicLong(-1)
        val playbackError = AtomicReference<PlaybackException?>()
        lateinit var player: ExoPlayer
        lateinit var surface: SurfaceView
        var playerCreated = false
        var surfaceAdded = false
        try {
            instrumentation.runOnMainSync {
                surface = SurfaceView(activity.activity)
                (activity.activity.window.decorView as ViewGroup).addView(surface, FrameLayout.LayoutParams(320, 240))
                surfaceAdded = true
                player = ExoPlayer.Builder(activity.activity).build()
                playerCreated = true
                player.setVideoSurfaceView(surface)
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        frameBytes.compareAndSet(-1, session.downloadedBytes())
                        firstFrame.countDown()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        playbackError.set(error)
                        firstFrame.countDown()
                    }
                })
                player.setMediaSource(sourceHandle.mediaSource)
                player.prepare()
                player.playWhenReady = true
            }
            assertTrue("A decoded video frame must arrive while the origin is throttled", firstFrame.await(45, TimeUnit.SECONDS))
            assertNull("The real decoder failed, code=${playbackError.get()?.errorCode}", playbackError.get())
            assertTrue("A rendered frame must be backed by partial downloaded media", frameBytes.get() in 1 until session.length())
            assertFalse("The session must still contain missing ranges at first frame", session.isComplete())

            val beforeSeekBytes = session.downloadedBytes()
            var seekPosition = 0L
            var renderedBeforeSeek = 0
            instrumentation.runOnMainSync {
                assertTrue(player.duration >= 10_000)
                seekPosition = player.duration * 3 / 4
                renderedBeforeSeek = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                player.seekTo(seekPosition)
            }
            origin.release()
            awaitCondition(30_000) {
                var resumed = false
                instrumentation.runOnMainSync {
                    resumed = player.playbackState == Player.STATE_READY && player.isPlaying &&
                        player.currentPosition >= seekPosition + 200 &&
                        (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > renderedBeforeSeek
                }
                resumed || playbackError.get() != null
            }
            assertNull("Seek must continue decoding, code=${playbackError.get()?.errorCode}", playbackError.get())
            assertTrue("Seeking into the later portion must download an uncached range", session.downloadedBytes() > beforeSeekBytes)
        } finally {
            origin.release()
            instrumentation.runOnMainSync {
                sourceHandle.cancelActiveReads()
                if (playerCreated) player.release()
                if (surfaceAdded) (surface.parent as? ViewGroup)?.removeView(surface)
            }
            session.close()
            origin.close()
            root.deleteRecursively()
        }
    }

    @Test fun dataSpecRangesEofAndClosingOneReaderPreserveTheSession() {
        val root = File(activity.activity.cacheDir, "progressive-range-${System.nanoTime()}").apply { mkdirs() }
        val origin = RangeOrigin(encodeAvc(File(root, "origin.mp4")), Long.MAX_VALUE)
        val session = openSession(origin, File(root, "video.play.mp4"))
        val source = ProgressiveVideoDataSource(session)
        val uri = Uri.parse("hongguo-progressive://test/video.mp4")
        try {
            assertEquals(7L, source.open(DataSpec.Builder().setUri(uri).setPosition(4).setLength(7).build()))
            assertEquals(uri, source.uri)
            val buffer = ByteArray(20)
            assertEquals(0, source.read(buffer, 3, 0))
            assertEquals(7, source.read(buffer, 3, 20 - 3))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
            source.close()
            source.close()
            assertNull(source.uri)

            assertEquals(0L, source.open(DataSpec.Builder().setUri(uri).setPosition(session.length()).build()))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
            source.close()
            try {
                source.open(DataSpec.Builder().setUri(uri).setPosition(session.length() + 1).build())
                fail("Position beyond the virtual file must be rejected")
            } catch (error: IOException) {
                assertTrue(DataSourceException.isCausedByPositionOutOfRange(error))
            } finally { source.close() }

            // A bounded request may extend beyond EOF; Media3 still requires its requested length.
            assertEquals(30L, source.open(DataSpec.Builder().setUri(uri).setPosition(session.length() - 2).setLength(30).build()))
            assertEquals(2, source.read(buffer, 0, buffer.size))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
            source.close()
            assertEquals(session.length(), source.open(DataSpec.Builder().setUri(uri).build()))
            assertTrue("A new reader must work after the previous DataSource close", source.read(buffer, 0, 4) > 0)
        } finally {
            source.close()
            session.close()
            origin.close()
            root.deleteRecursively()
        }
    }

    @Test fun cancellingPlayerFactoryUnblocksItsReaderAndPreservesTheReplacementFactory() {
        val root = File(activity.activity.cacheDir, "progressive-cancel-${System.nanoTime()}").apply { mkdirs() }
        val origin = RangeOrigin(encodeAvc(File(root, "origin.mp4")), Long.MAX_VALUE)
        val session = openSession(origin, File(root, "video.play.mp4"))
        val oldFactory = ProgressiveVideoDataSource.Factory(session)
        val source = oldFactory.createDataSource()
        val replacementFactory = ProgressiveVideoDataSource.Factory(session)
        val replacement = replacementFactory.createDataSource()
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            replacement.open(DataSpec.Builder().setUri("hongguo-progressive://test/video.mp4").setLength(4).build())
            origin.blockFurtherReads()
            source.open(DataSpec.Builder().setUri("hongguo-progressive://test/video.mp4")
                .setPosition(session.length() / 2).setLength(128).build())
            val reader = Thread({
                try { source.read(ByteArray(128), 0, 128) }
                catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }, "progressive-device-reader").apply { start() }
            assertTrue("The reader must reach an uncached blocked range", origin.blocked.await(10, TimeUnit.SECONDS))
            oldFactory.cancelActiveReads()
            oldFactory.cancelActiveReads()
            assertTrue("Player cancellation must promptly unblock its reader", completed.await(2, TimeUnit.SECONDS))
            reader.join(1_000)
            assertTrue("The cancelled read must fail", failure.get() is IOException)
            assertEquals("An existing reader owned by the new factory must remain usable", 4, replacement.read(ByteArray(4), 0, 4))
            replacement.close()
            val lateOldSource = oldFactory.createDataSource()
            try {
                lateOldSource.open(DataSpec.Builder().setUri("hongguo-progressive://test/video.mp4").build())
                fail("A released player's late loader must not reopen a range")
            } catch (error: InterruptedIOException) {
                assertEquals("MEDIA_READ_CANCELLED", error.message)
            } finally { lateOldSource.close() }
            origin.release()
            try {
                replacement.open(DataSpec.Builder().setUri("hongguo-progressive://test/video.mp4")
                    .setPosition(session.length() / 2).setLength(128).build())
                assertEquals(128, replacement.read(ByteArray(128), 0, 128))
            } finally { replacement.close() }
        } finally {
            origin.release()
            oldFactory.cancelActiveReads()
            replacementFactory.cancelActiveReads()
            source.close()
            session.close()
            origin.close()
            root.deleteRecursively()
        }
    }

    private fun openSession(origin: RangeOrigin, output: File): ProgressiveMedia.Session {
        val track = Stream.VideoTrack().apply {
            url = "https://progressive-fixture.invalid/video.mp4"
            encrypt = false
            size = origin.bytes.size.toLong()
        }
        return ProgressiveMedia.openTrack(track, output.toPath(), null, origin)
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out waiting for decoded playback", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }

    /** Creates an unlicensed-content-free, deterministic noisy test pattern with Android's AVC encoder. */
    private fun encodeAvc(file: File): ByteArray {
        val width = 320
        val height = 240
        val frames = 240
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 20)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType("video/avc")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var codecStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val info = MediaCodec.BufferInfo()
            var nextFrame = 0
            var inputEnded = false
            var outputEnded = false
            var track = -1
            var seed = 0x12345678
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline) { "AVC_FIXTURE_ENCODING_TIMEOUT" }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        if (nextFrame == frames) {
                            codec.queueInputBuffer(inputIndex, 0, 0, nextFrame * 50_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val image = checkNotNull(codec.getInputImage(inputIndex)) { "AVC_FIXTURE_INPUT_IMAGE" }
                            image.planes.forEachIndexed { index, plane ->
                                val planeWidth = if (index == 0) width else width / 2
                                val planeHeight = if (index == 0) height else height / 2
                                for (row in 0 until planeHeight) for (column in 0 until planeWidth) {
                                    seed = seed xor (seed shl 13)
                                    seed = seed xor (seed ushr 17)
                                    seed = seed xor (seed shl 5)
                                    plane.buffer.put(row * plane.rowStride + column * plane.pixelStride,
                                        if (index == 0) (16 + (seed and 0xCF)).toByte() else 128.toByte())
                                }
                            }
                            codec.queueInputBuffer(inputIndex, 0, width * height * 3 / 2, nextFrame * 50_000L, 0)
                            nextFrame++
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted)
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (outputIndex >= 0) {
                        val buffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            if (codecStarted) codec.stop()
            codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
        return file.readBytes()
    }

    /** Anonymous HTTPS-shaped range transport: data and throttling stay entirely on the device. */
    private class RangeOrigin(val bytes: ByteArray, initialLimit: Long) : ProgressiveMedia.ConnectionFactory, AutoCloseable {
        private val guard = Object()
        private var limit = initialLimit
        private var served = 0L
        private var released = false
        private var closed = false
        val blocked = CountDownLatch(1)

        fun blockFurtherReads() = synchronized(guard) { limit = served }
        fun release() = synchronized(guard) { released = true; guard.notifyAll() }
        override fun close() = synchronized(guard) { closed = true; guard.notifyAll() }

        override fun open(uri: URI): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
            @Volatile private var disconnected = false
            private fun range(): LongRange {
                val requested = getRequestProperty("Range") ?: return 0L..bytes.lastIndex.toLong()
                val parts = requested.removePrefix("bytes=").split('-', limit = 2)
                val start = if (parts[0].isEmpty()) (bytes.size - parts[1].toLong()).coerceAtLeast(0) else parts[0].toLong()
                val end = if (parts[0].isEmpty() || parts[1].isEmpty()) bytes.lastIndex.toLong() else parts[1].toLong()
                return start..minOf(end, bytes.lastIndex.toLong())
            }
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = synchronized(guard) { disconnected = true; guard.notifyAll() }
            override fun getResponseCode(): Int = if (getRequestProperty("Range") == null) 200 else 206
            override fun getContentLengthLong(): Long = range().let { it.last - it.first + 1 }
            override fun getHeaderField(name: String?): String? = when (name?.lowercase(java.util.Locale.ROOT)) {
                "content-range" -> range().let { "bytes ${it.first}-${it.last}/${bytes.size}" }
                "content-length" -> contentLengthLong.toString()
                "accept-ranges" -> "bytes"
                "content-type" -> "video/mp4"
                else -> null
            }
            override fun getInputStream(): InputStream = object : InputStream() {
                private val requested = range()
                private var position = requested.first.toInt()
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    synchronized(guard) {
                        if (position > requested.last) return -1
                        while (!released && served >= limit && !disconnected && !closed) {
                            blocked.countDown()
                            try { guard.wait(100) }
                            catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw InterruptedIOException("FIXTURE_READ_CANCELLED")
                            }
                        }
                        if (disconnected || closed || Thread.currentThread().isInterrupted) throw InterruptedIOException("FIXTURE_READ_CANCELLED")
                        val count = minOf(length, 8192, (requested.last - position + 1).toInt())
                        bytes.copyInto(buffer, offset, position, position + count)
                        position += count
                        served += count
                        return count
                    }
                }
                override fun close() { disconnect() }
            }
        }
    }
}
