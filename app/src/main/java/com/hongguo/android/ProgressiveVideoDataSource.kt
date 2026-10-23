package com.hongguo.android

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.hongguo.api.service.ProgressiveMedia
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/** Reads the decrypted virtual MP4; the owning ViewModel retains the shared download session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgressiveVideoDataSource private constructor(
    private val session: ProgressiveMedia.Session,
    private val ownerCancelled: () -> Boolean,
) : BaseDataSource(false) {
    constructor(session: ProgressiveMedia.Session) : this(session, { false })

    class Factory(private val session: ProgressiveMedia.Session) : DataSource.Factory {
        private val guard = Any()
        private val sources = mutableSetOf<ProgressiveVideoDataSource>()
        @Volatile private var cancelled = false

        override fun createDataSource(): ProgressiveVideoDataSource = synchronized(guard) {
            ProgressiveVideoDataSource(session, { cancelled }).also { if (!cancelled) sources.add(it) }
        }

        /** Terminal for this player's factory; another factory over the same session remains usable. */
        fun cancelActiveReads() {
            val opened = synchronized(guard) {
                cancelled = true
                sources.toList().also { sources.clear() }
            }
            opened.forEach { source -> runCatching { source.close() } }
        }
    }

    private class OpenRead(
        val reader: ProgressiveMedia.Reader,
        val uri: Uri,
        var position: Long,
        var remaining: Long,
    )

    private val stateLock = Any()
    private var active: OpenRead? = null

    override fun open(dataSpec: DataSpec): Long = synchronized(stateLock) {
        if (ownerCancelled()) throw InterruptedIOException("MEDIA_READ_CANCELLED")
        check(active == null) { "MEDIA_SOURCE_ALREADY_OPEN" }
        transferInitializing(dataSpec)
        val total = session.length()
        if (dataSpec.position > total) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val available = total - dataSpec.position
        val remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        val reader = session.newReader()
        active = OpenRead(reader, dataSpec.uri, dataSpec.position, remaining)
        transferStarted(dataSpec)
        // Media3 requires a bounded request's original length, even when the resource ends earlier.
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) remaining else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0
        val opened = synchronized(stateLock) {
            val value = active ?: throw IOException("MEDIA_SOURCE_CLOSED")
            if (value.remaining == 0L) return C.RESULT_END_OF_INPUT
            value
        }
        val wanted = minOf(length.toLong(), opened.remaining).toInt()
        // Never hold stateLock while waiting for a missing range: close must cancel this reader.
        val count = opened.reader.read(opened.position, buffer, offset, wanted)
        return synchronized(stateLock) {
            if (active !== opened) throw InterruptedIOException("MEDIA_READ_CANCELLED")
            if (count == C.RESULT_END_OF_INPUT) throw EOFException("MEDIA_SOURCE_TRUNCATED")
            if (count <= 0 || count > wanted) throw IOException("MEDIA_READ_INVALID")
            opened.position += count
            opened.remaining -= count
            bytesTransferred(count)
            count
        }
    }

    override fun getUri(): Uri? = synchronized(stateLock) { active?.uri }

    override fun close() {
        var opened: OpenRead? = null
        try {
            synchronized(stateLock) {
                opened = active ?: return
                active = null
                transferEnded()
            }
        } finally {
            // Disconnect even if a transfer listener fails. Session.close is deliberately absent:
            // configuration changes and seeks may create another DataSource over this same session.
            opened?.reader?.close()
        }
    }
}

/** A player-scoped cancellation boundary, separate from the ViewModel's reusable session. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoSourceHandle internal constructor(
    val mediaSource: MediaSource,
    private val cancelReads: () -> Unit,
) {
    fun cancelActiveReads() = cancelReads()
}

/** The URI contains no upstream address, authorization data, or content identifier. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun videoSourceHandleFor(video: PreparedVideo): VideoSourceHandle {
    val session = video.session
    val factory: DataSource.Factory
    val cancelReads: () -> Unit
    val uri: Uri
    if (session != null) {
        val progressive = ProgressiveVideoDataSource.Factory(session)
        factory = progressive
        cancelReads = progressive::cancelActiveReads
        uri = Uri.parse("hongguo-progressive://local/video.mp4")
    } else {
        factory = FileDataSource.Factory()
        cancelReads = {}
        uri = Uri.fromFile(File(video.path))
    }
    val item = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP4).build()
    return VideoSourceHandle(ProgressiveMediaSource.Factory(factory).createMediaSource(item), cancelReads)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun mediaSourceFor(video: PreparedVideo): MediaSource = videoSourceHandleFor(video).mediaSource
