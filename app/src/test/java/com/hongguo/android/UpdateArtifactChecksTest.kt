package com.hongguo.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Uses synthetic ZIP-header bytes only; Android PackageManager separately verifies real APKs. */
class UpdateArtifactChecksTest {
    private lateinit var file: File

    @Before fun setup() { file = Files.createTempFile("hongguo-update-test-", ".apk").toFile() }
    @After fun cleanup() { file.delete() }

    private fun data(size: Int = 100): ByteArray = ByteArray(size) { (it % 113).toByte() }.apply {
        byteArrayOf(0x50, 0x4b, 0x03, 0x04).copyInto(this)
    }
    private fun sha(data: ByteArray) = UpdateArtifactChecks.hex(MessageDigest.getInstance("SHA-256").digest(data))

    @Test fun exactSizeHeaderAndDigestPassIncludingUppercaseDigest() {
        val data = data()
        file.writeBytes(data)
        UpdateArtifactChecks.verify(file, data.size.toLong(), sha(data).uppercase())
    }

    @Test fun truncatedOrExtendedCacheIsRejected() {
        val data = data()
        file.writeBytes(data.copyOf(data.size - 1))
        assertFails { UpdateArtifactChecks.verify(file, data.size.toLong(), sha(data)) }
        file.writeBytes(data + byteArrayOf(1))
        assertFails { UpdateArtifactChecks.verify(file, data.size.toLong(), sha(data)) }
    }

    @Test fun sameSizeCachedTamperingIsRejected() {
        val original = data()
        val changed = original.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        file.writeBytes(changed)
        assertFails { UpdateArtifactChecks.verify(file, original.size.toLong(), sha(original)) }
    }

    @Test fun htmlErrorPageCannotBecomeApkEvenWithMatchingSizeAndDigest() {
        val html = "<!doctype html><title>Error</title>".toByteArray()
        file.writeBytes(html)
        assertFails { UpdateArtifactChecks.verify(file, html.size.toLong(), sha(html)) }
    }

    @Test fun invalidOrUnboundedPublishingMetadataIsRejected() {
        assertFails { UpdateArtifactChecks.validateMetadata(0, "0".repeat(64)) }
        assertFails { UpdateArtifactChecks.validateMetadata(UpdateArtifactChecks.MAX_APK_BYTES + 1, "0".repeat(64)) }
        assertFails { UpdateArtifactChecks.validateMetadata(1024, "not-a-digest") }
        assertFails { UpdateArtifactChecks.validateMetadata(1024, "z".repeat(64)) }
    }

    @Test fun digestReadHonorsCancellationWithoutTurningItIntoValidationFailure() {
        val bytes = data(256 * 1024)
        file.writeBytes(bytes)
        var checks = 0
        try {
            UpdateArtifactChecks.verify(file, bytes.size.toLong(), sha(bytes)) {
                if (++checks == 3) throw CancellationException("synthetic cancellation")
            }
            fail("cancelled digest verification must stop")
        } catch (expected: CancellationException) {
            assertEquals(3, checks)
        }
    }

    private val canonicalApk = "https://github.com/example/project/releases/download/v2/example.apk"

    @Test fun onlyExplicitPublicGithubAndProxyHttpsTransportsAreAccepted() {
        assertEquals("github.com", UpdateArtifactChecks.checkedUrl(canonicalApk).host)
        for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            assertEquals(host, UpdateArtifactChecks.checkedUrl("https://$host/example.apk?sig=public-expiring-signature").host)
        for (host in listOf("gh-proxy.com", "ghfast.top"))
            assertEquals(host, UpdateArtifactChecks.checkedUrl("https://$host/$canonicalApk").host)
        val rejected = listOf(
            "http://github.com/example.apk",
            "https://github.com.evil.invalid/example.apk",
            "https://evil.invalid@github.com/example.apk",
            "https://github.com@evil.invalid/example.apk",
            "https://github.com:444/example.apk",
            "https://raw.githubusercontent.com/example.apk",
            "https://github.com/example.apk#fragment",
            "https://github.com/example.apk",
            "https://gh-proxy.com/https://evil.invalid/example.apk",
            "https://ghfast.top/https://github.com/example/project/contents/private.apk",
            "https://ghfast.top.evil.invalid/$canonicalApk",
            "file:///private/example.apk",
        )
        for (url in rejected) assertFails { UpdateArtifactChecks.checkedUrl(url) }
    }

    @Test fun urlErrorsNeverExposeRemoteAddressOrTokens() {
        val secret = "synthetic-sensitive-query"
        val error = assertFails { UpdateArtifactChecks.checkedUrl("https://evil.invalid/file.apk?token=$secret") }
        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.message.orEmpty().contains("evil.invalid"))
    }

    @Test fun automaticRoutesTryDirectThenPrimaryAndStopAfterSuccess() {
        val seen = mutableListOf<UpdateSource>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = UpdateRouteExecutor.execute(UpdateSources.routes(canonicalApk, UpdateSource.AUTO),
            onRoute = { seen += it.source; progress += 0L to 100L }) { route ->
            progress += 40L to 100L
            if (route.source == UpdateSource.DIRECT) throw RetryUpdateRoute(SocketTimeoutException("synthetic timeout"))
            "verified-file"
        }
        assertEquals("verified-file", result)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY), seen)
        assertEquals(listOf(0L to 100L, 40L to 100L, 0L to 100L, 40L to 100L), progress)
    }

    @Test fun manualRouteNeverFallsBackToADifferentSource() {
        val seen = mutableListOf<UpdateSource>()
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.PROXY_PRIMARY),
                onRoute = { seen += it.source }) { throw RetryUpdateRoute(IOException("synthetic route failure")) }
            fail("manual route must propagate its failure")
        } catch (_: IOException) { }
        assertEquals(listOf(UpdateSource.PROXY_PRIMARY), seen)
    }

    @Test fun invalidHtmlAndWrongHashCandidatesFallBackBeforeReturningVerifiedBytes() {
        val expected = data()
        val attempts = mutableListOf<UpdateSource>()
        val paths = mutableListOf<File>()
        val verified = UpdateRouteExecutor.execute(UpdateSources.routes(canonicalApk, UpdateSource.AUTO)) { route ->
            attempts += route.source
            val temporary = Files.createTempFile("update-route-test-", ".part.apk").toFile().also { paths += it }
            try {
                temporary.writeBytes(when (route.source) {
                    UpdateSource.DIRECT -> "<html>bad upstream response</html>".toByteArray().copyOf(expected.size)
                    UpdateSource.PROXY_PRIMARY -> expected.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
                    else -> expected
                })
                try { UpdateArtifactChecks.verify(temporary, expected.size.toLong(), sha(expected)) }
                catch (error: UpdateInstallException) { throw RetryUpdateRoute(error) }
                temporary.readBytes()
            } finally { temporary.delete() }
        }
        assertArrayEquals(expected, verified)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY, UpdateSource.PROXY_BACKUP), attempts)
        assertEquals(3, paths.map { it.path }.distinct().size)
        assertTrue(paths.none { it.exists() })
    }

    @Test fun cancellationStopsFallbackAndCleansTheAttemptFile() {
        val attempted = mutableListOf<UpdateSource>()
        var temporary: File? = null
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.AUTO)) { route ->
                attempted += route.source
                temporary = Files.createTempFile("update-cancel-test-", ".part.apk").toFile()
                try { throw CancellationException("synthetic cancellation") }
                finally { temporary!!.delete() }
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(UpdateSource.DIRECT), attempted)
        assertNotNull(temporary)
        assertFalse(temporary!!.exists())
    }

    @Test fun truncatedFreshCandidateFallsBackToAnIndependentlyCheckedCandidate() {
        val bytes = data()
        val attempts = mutableListOf<UpdateSource>()
        val result = UpdateRouteExecutor.execute(UpdateSources.routes(canonicalApk, UpdateSource.AUTO)) { route ->
            attempts += route.source
            val temporary = Files.createTempFile("update-truncated-test-", ".part.apk").toFile()
            try {
                temporary.writeBytes(if (route.source == UpdateSource.DIRECT) bytes.copyOf(bytes.size - 1) else bytes)
                try { UpdateArtifactChecks.verify(temporary, bytes.size.toLong(), sha(bytes)) }
                catch (error: UpdateInstallException) { throw RetryUpdateRoute(error) }
                temporary.readBytes()
            } finally { temporary.delete() }
        }
        assertArrayEquals(bytes, result)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY), attempts)
    }

    @Test fun accidentallyWrappedCancellationStillCannotTriggerFallback() {
        val seen = mutableListOf<UpdateSource>()
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.AUTO),
                onRoute = { seen += it.source }) { throw RetryUpdateRoute(CancellationException("synthetic cancellation")) }
            fail("wrapped cancellation must still propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(UpdateSource.DIRECT), seen)
    }

    @Test fun interruptedIoCannotFallbackEvenWhenTheThreadFlagWasCleared() {
        assertFalse(Thread.currentThread().isInterrupted)
        val seen = mutableListOf<UpdateSource>()
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.AUTO),
                onRoute = { seen += it.source }) { throw RetryUpdateRoute(InterruptedIOException("synthetic cancelled read")) }
            fail("interrupted IO must not switch routes")
        } catch (_: CancellationException) { }
        assertFalse(Thread.currentThread().isInterrupted)
        assertEquals(listOf(UpdateSource.DIRECT), seen)
    }

    @Test fun cancellationBetweenFailedRoutesPreventsStartingTheNextOne() {
        var cancelled = false
        val seen = mutableListOf<UpdateSource>()
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.AUTO),
                checkCancelled = { if (cancelled) throw CancellationException("synthetic cancellation") },
                onRoute = { seen += it.source }) {
                cancelled = true
                throw RetryUpdateRoute(IOException("synthetic route failure"))
            }
            fail("cancellation must prevent fallback")
        } catch (_: CancellationException) { }
        assertEquals(listOf(UpdateSource.DIRECT), seen)
    }

    @Test fun validatedIdentityOrCommitFailureDoesNotFallBack() {
        val seen = mutableListOf<UpdateSource>()
        val terminal = UpdateInstallException("synthetic package identity mismatch")
        try {
            UpdateRouteExecutor.execute<Unit>(UpdateSources.routes(canonicalApk, UpdateSource.AUTO),
                onRoute = { seen += it.source }) { throw terminal }
            fail("identity failures must stop")
        } catch (error: UpdateInstallException) { assertSame(terminal, error) }
        assertEquals(listOf(UpdateSource.DIRECT), seen)
    }

    @Test fun activeProgressCanContinuePastThirtySecondsWithoutATotalDeadline() {
        var now = 0L
        val guard = UpdateDownloadStallGuard { now }
        repeat(12) { step ->
            now += 20_000_000_000L
            guard.received((step + 1) * 4096L)
        }
        assertEquals(240_000_000_000L, now)
    }

    @Test fun tinyDripWithoutMeaningfulProgressTriggersStallTimeout() {
        var now = 0L
        val guard = UpdateDownloadStallGuard { now }
        now = 10_000_000_000L; guard.received(1)
        now = 20_000_000_000L; guard.received(2)
        now = 30_000_000_000L
        try {
            guard.received(3)
            fail("a near-stalled connection must time out")
        } catch (_: SocketTimeoutException) { }
    }

    private fun assertFails(action: () -> Unit): UpdateInstallException {
        try { action() } catch (error: UpdateInstallException) { return error }
        throw AssertionError("expected a safe validation error")
    }
}
