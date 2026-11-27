package com.hongguo.android

import com.hongguo.api.service.ProgressiveMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One next-episode source. The player can take ownership as soon as resolution finishes. */
internal class EpisodePreload(val episodeId: String, private val release: (PreparedVideo) -> Unit) {
    private val source = Channel<PreparedVideo>(1, onUndeliveredElement = release)
    private var job: Job? = null
    @Volatile private var warmAllowed = true
    @Volatile private var reader: ProgressiveMedia.Reader? = null

    fun start(
        scope: CoroutineScope,
        prepare: suspend () -> PreparedVideo,
        warm: suspend (PreparedVideo, (ProgressiveMedia.Reader?) -> Unit) -> Unit,
    ) {
        job = scope.launch {
            try {
                val prepared = prepare()
                if (source.trySend(prepared).isFailure) {
                    release(prepared)
                    return@launch
                }
                if (warmAllowed) warm(prepared) { activeReader ->
                    reader = activeReader
                    if (!warmAllowed) activeReader?.close()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The foreground path can retry with compatibility playback enabled.
                source.cancel()
            } finally {
                source.close()
            }
        }
    }

    fun takeReady(): PreparedVideo? = source.tryReceive().getOrNull()?.also { discard() }

    suspend fun awaitVideo(): PreparedVideo? {
        warmAllowed = false
        try {
            return source.receiveCatching().getOrNull()
        } finally {
            discard()
        }
    }

    fun discard() {
        warmAllowed = false
        reader?.close()
        job?.cancel()
        source.cancel()
    }
}
