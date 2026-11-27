package com.hongguo.android

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodePreloadTest {
    private val video = PreparedVideo("next", "fixture.mp4")
    private val released = mutableListOf<PreparedVideo>()
    private val preload = EpisodePreload(video.episodeId) { released += it }

    @Test fun switchingDuringResolutionReusesTheRequestAndSkipsWarmup() = runTest {
        val response = CompletableDeferred<PreparedVideo>()
        var requests = 0
        var warmups = 0
        preload.start(this, { requests++; response.await() }, { _, _ -> warmups++ })
        runCurrent()
        assertEquals(1, requests)
        assertNull(preload.takeReady())
        val foreground = async { preload.awaitVideo() }
        runCurrent()
        assertFalse(foreground.isCompleted)

        response.complete(video)
        assertSame(video, foreground.await())
        assertEquals(1, requests)
        assertEquals(0, warmups)
        assertTrue(released.isEmpty())
    }

    @Test fun readySourceTransfersImmediatelyWhileWarmupIsBlocked() = runTest {
        var warming = false
        preload.start(this, { video }) { _, _ ->
            warming = true
            try { awaitCancellation() } finally { warming = false }
        }
        runCurrent()
        assertTrue(warming)
        assertSame(video, preload.takeReady())
        runCurrent()
        assertFalse(warming)
        assertTrue(released.isEmpty())
    }

    @Test fun discardReleasesAnUnclaimedPreparedSourceExactlyOnce() = runTest {
        preload.start(this, { video }) { _, _ -> }
        runCurrent()
        assertTrue(released.isEmpty())
        preload.discard()
        preload.discard()
        assertEquals(listOf(video), released)
    }

    @Test fun cancellingTheWaiterDisposesOfALateSourceExactlyOnce() = runTest {
        // Model a prepare call that returns a resource even as cancellation arrives.
        preload.start(this, {
            try { awaitCancellation() } catch (_: CancellationException) { video }
        }) { _, _ -> }
        runCurrent()
        assertNull(preload.takeReady())
        val foreground = async { preload.awaitVideo() }
        runCurrent()
        foreground.cancel()
        runCurrent()
        assertTrue(foreground.isCancelled)
        assertEquals(listOf(video), released)
        preload.discard()
        assertEquals(1, released.size)
    }

    @Test fun cancellationAfterDeliveryBeforeTheWaiterResumesReleasesTheSource() = runTest {
        val response = CompletableDeferred<PreparedVideo>()
        preload.start(this, { response.await() }) { _, _ -> }
        assertNull(preload.takeReady())
        val foreground = async { preload.awaitVideo() }
        runCurrent()

        // Queue cancellation behind the producer and ahead of the receiver's resumption.
        response.complete(video)
        launch { foreground.cancel() }
        runCurrent()
        assertTrue(foreground.isCancelled)
        assertEquals(listOf(video), released)
    }

    @Test fun failedPreparationLetsTheWaitingForegroundRetry() = runTest {
        val response = CompletableDeferred<PreparedVideo>()
        var warmups = 0
        preload.start(this, { response.await() }) { _, _ -> warmups++ }
        assertNull(preload.takeReady())
        val foreground = async { preload.awaitVideo() }
        runCurrent()
        response.completeExceptionally(IOException("fixture unavailable"))
        assertNull(foreground.await())
        assertEquals(0, warmups)
        assertTrue(released.isEmpty())
    }
}
