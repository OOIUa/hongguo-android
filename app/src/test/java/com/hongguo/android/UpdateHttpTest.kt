package com.hongguo.android

import com.hongguo.api.util.Json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.net.URI
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Real repository policy against an injected HTTPS transport, without network or APK downloads. */
class UpdateHttpTest {
    private val repository = "sample-owner/hongguo-android"
    private val manifestUrl = "https://github.com/$repository/releases/latest/download/update.json"
    private val manifest = Json.stringify(mapOf(
        "schemaVersion" to 1, "applicationId" to "com.hongguo.android", "versionCode" to 3,
        "versionName" to "0.3.0", "apkUrl" to "https://github.com/$repository/releases/download/v0.3.0/app.apk",
        "sha256" to "a".repeat(64), "sizeBytes" to 100, "minSdk" to 26, "notes" to "更新说明",
    )).toByteArray(Charsets.UTF_8)

    private class FakeHttps(
        url: String,
        private val status: Int,
        private val bytes: ByteArray = byteArrayOf(),
        private val location: String? = null,
        private val declaredLength: Long = bytes.size.toLong(),
        private val responseAction: (() -> Int)? = null,
    ) : HttpsURLConnection(URI(url).toURL()) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = responseAction?.invoke() ?: status
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", ignoreCase = true)) location else null
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getCipherSuite() = "TLS_FAKE_FOR_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun followsGithubAndSignedReleaseAssetRedirectsWithoutCredentials() = runBlocking {
        val tagged = "https://github.com/$repository/releases/download/v0.3.0/update.json"
        val signedAsset = "https://release-assets.githubusercontent.com/release-asset/update.json?signature=synthetic-test-value"
        val connections = listOf(
            FakeHttps(manifestUrl, 302, location = tagged),
            FakeHttps(tagged, 302, location = signedAsset),
            FakeHttps(signedAsset, 200, manifest),
        )
        val seen = mutableListOf<URI>()
        val update = UpdateRepository { uri -> seen.add(uri); connections[seen.lastIndex] }.check(manifestUrl, repository, UpdateSource.DIRECT)
        assertEquals(3L, update.versionCode)
        assertEquals(listOf("github.com", "github.com", "release-assets.githubusercontent.com"), seen.map { it.host })
        connections.forEach {
            assertTrue(it.closed)
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie"))
            assertFalse(it.instanceFollowRedirects)
        }
    }

    @Test fun rejectsDowngradeAndUnknownRedirectHostsBeforeOpeningSecondConnection() = runBlocking {
        listOf("http://github.com/file.json", "https://untrusted.invalid/file.json").forEach { location ->
            var calls = 0
            val connection = FakeHttps(manifestUrl, 302, location = location)
            try {
                UpdateRepository { calls++; connection }.check(manifestUrl, repository, UpdateSource.DIRECT)
                fail("Unsafe redirect must fail")
            } catch (error: IOException) { assertEquals("UPDATE_CHECK_URL_INVALID", error.message) }
            assertEquals(1, calls)
            assertTrue(connection.closed)
        }
    }

    @Test fun limitsRedirectsToFiveHops() = runBlocking {
        var calls = 0
        try {
            UpdateRepository { uri ->
                calls++
                FakeHttps(uri.toString(), 302, location = "https://github.com/$repository/releases/download/v0.3.0/update.json")
            }.check(manifestUrl, repository, UpdateSource.DIRECT)
            fail("Redirect cycle must fail")
        } catch (error: IOException) { assertEquals("UPDATE_CHECK_REDIRECT_LIMIT", error.message) }
        assertEquals(6, calls)
    }

    @Test fun boundsActualChunkedBodyEvenWithoutContentLength() = runBlocking {
        val body = ByteArray(UpdateManifestCodec.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        val connection = FakeHttps(manifestUrl, 200, body, declaredLength = -1)
        try {
            UpdateRepository { connection }.check(manifestUrl, repository, UpdateSource.DIRECT)
            fail("Oversized unknown-length response must fail")
        } catch (error: IOException) { assertEquals("UPDATE_MANIFEST_TOO_LARGE", error.message) }
        assertTrue(connection.closed)
    }

    @Test fun acceptsExactBodyLimitAndRejectsInvalidUtf8() = runBlocking {
        val padding = ByteArray(UpdateManifestCodec.MAX_MANIFEST_BYTES - manifest.size) { ' '.code.toByte() }
        val valid = FakeHttps(manifestUrl, 200, manifest + padding, declaredLength = -1)
        assertEquals(3L, UpdateRepository { valid }.check(manifestUrl, repository, UpdateSource.DIRECT).versionCode)
        val invalid = FakeHttps(manifestUrl, 200, byteArrayOf(0xC3.toByte(), 0x28))
        try {
            UpdateRepository { invalid }.check(manifestUrl, repository, UpdateSource.DIRECT)
            fail("Malformed UTF-8 must fail")
        } catch (error: IOException) { assertEquals("UPDATE_MANIFEST_ENCODING_INVALID", error.message) }
    }

    @Test fun httpFailureIsPreservedAsSafeStableError() = runBlocking {
        val connection = FakeHttps(manifestUrl, 404)
        try {
            UpdateRepository { connection }.check(manifestUrl, repository, UpdateSource.DIRECT)
            fail("HTTP error must fail")
        } catch (error: IOException) {
            assertStableUpdateError(error, "UPDATE_CHECK_HTTP_404")
        }
        assertTrue(connection.closed)
    }

    @Test fun autoFallsBackFromHttpFailureAndReportsTheActiveTransport() = runBlocking {
        val primary = "https://gh-proxy.com/$manifestUrl"
        val connections = listOf(FakeHttps(manifestUrl, 503), FakeHttps(primary, 200, manifest))
        val seen = mutableListOf<URI>()
        val sources = mutableListOf<UpdateSource>()
        val update = UpdateRepository { uri -> seen.add(uri); connections[seen.lastIndex] }
            .check(manifestUrl, repository, UpdateSource.AUTO) { sources.add(it) }
        assertEquals(3L, update.versionCode)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY), sources)
        assertEquals(listOf("github.com", "gh-proxy.com"), seen.map { it.host })
        connections.forEach {
            assertTrue(it.closed)
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie"))
        }
        assertTrue(connections[0].connectTimeout in 1..3_000)
        assertTrue(connections[0].readTimeout in 1..4_000)
        assertTrue(connections[1].connectTimeout in 1..5_000)
        assertTrue(connections[1].readTimeout in 1..7_000)
    }

    @Test fun timeoutAndHtmlDoNotPreventBackupManifestValidation() = runBlocking {
        val connections = listOf(
            FakeHttps(manifestUrl, 200, responseAction = { throw SocketTimeoutException("synthetic timeout") }),
            FakeHttps("https://gh-proxy.com/$manifestUrl", 200, "<html>temporary proxy page</html>".toByteArray()),
            FakeHttps("https://ghfast.top/$manifestUrl", 200, manifest),
        )
        var calls = 0
        val sources = mutableListOf<UpdateSource>()
        val update = UpdateRepository { connections[calls++] }
            .check(manifestUrl, repository, UpdateSource.AUTO) { sources.add(it) }
        assertEquals(3L, update.versionCode)
        assertEquals(3, calls)
        assertEquals(listOf(UpdateSource.DIRECT, UpdateSource.PROXY_PRIMARY, UpdateSource.PROXY_BACKUP), sources)
        assertTrue(connections.all { it.closed })
    }

    @Test fun invalidMetadataIsAFailedRouteEvenWhenHttpWasSuccessful() = runBlocking {
        val badBodies = listOf(
            manifest.toString(Charsets.UTF_8).replace("a".repeat(64), "g".repeat(64)).toByteArray(),
            manifest.toString(Charsets.UTF_8).replace("com.hongguo.android", "com.example.other").toByteArray(),
            manifest.toString(Charsets.UTF_8).replace("/$repository/releases/download/", "/other-owner/other-app/releases/download/").toByteArray(),
        )
        badBodies.forEach { bad ->
            var calls = 0
            val update = UpdateRepository { uri ->
                calls++
                FakeHttps(uri.toString(), 200, if (calls == 1) bad else manifest)
            }.check(manifestUrl, repository, UpdateSource.AUTO)
            assertEquals(3L, update.versionCode)
            assertEquals(2, calls)
        }
    }

    @Test fun manualProxyDoesNotTryOtherRoutesAfterFailure() = runBlocking {
        var calls = 0
        val sources = mutableListOf<UpdateSource>()
        try {
            UpdateRepository { uri ->
                calls++
                assertEquals("gh-proxy.com", uri.host)
                FakeHttps(uri.toString(), 502)
            }.check(manifestUrl, repository, UpdateSource.PROXY_PRIMARY) { sources.add(it) }
            fail("A failed manual route must fail")
        } catch (error: IOException) { assertStableUpdateError(error, "UPDATE_CHECK_HTTP_502") }
        assertEquals(1, calls)
        assertEquals(listOf(UpdateSource.PROXY_PRIMARY), sources)
    }

    @Test fun rejectsProxyRedirectToAnArbitraryNestedTargetBeforeNextConnection() = runBlocking {
        listOf(
            "https://gh-proxy.com/https://untrusted.invalid/update.json",
            "https://ghfast.top/https://untrusted.invalid/update.json",
            "https://gh-proxy.com/$manifestUrl?forward=elsewhere",
        ).forEach { location ->
            var calls = 0
            try {
                UpdateRepository { uri ->
                    calls++
                    FakeHttps(uri.toString(), 302, location = location)
                }.check(manifestUrl, repository, UpdateSource.PROXY_PRIMARY)
                fail("Unapproved nested redirect must fail")
            } catch (error: IOException) { assertStableUpdateError(error, "UPDATE_CHECK_URL_INVALID") }
            assertEquals(1, calls)
        }
    }

    @Test fun proxyCanRedirectToApprovedSignedGithubAssetWithoutAuth() = runBlocking {
        val primary = "https://gh-proxy.com/$manifestUrl"
        val asset = "https://release-assets.githubusercontent.com/release-asset/update.json?signature=synthetic-test-value"
        val connections = listOf(FakeHttps(primary, 302, location = asset), FakeHttps(asset, 200, manifest))
        var calls = 0
        val update = UpdateRepository { connections[calls++] }.check(manifestUrl, repository, UpdateSource.PROXY_PRIMARY)
        assertEquals(3L, update.versionCode)
        assertEquals(2, calls)
        connections.forEach {
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie"))
        }
    }

    @Test fun coroutineCancellationWithClearedThreadFlagNeverFallsBack() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val sources = mutableListOf<UpdateSource>()
            var calls = 0
            val connection = FakeHttps(manifestUrl, 200, responseAction = {
                entered.complete(Unit)
                try {
                    CountDownLatch(1).await()
                    200
                } catch (_: InterruptedException) {
                    // InterruptedException cleared the flag, as some blocking transports do.
                    throw InterruptedIOException("synthetic cancelled read")
                }
            })
            val job = launch {
                UpdateRepository { calls++; connection }.check(manifestUrl, repository, UpdateSource.AUTO) { sources.add(it) }
            }
            try { withTimeout(3_000) { entered.await() } }
            finally { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, calls)
            assertEquals(listOf(UpdateSource.DIRECT), sources)
            assertTrue(connection.closed)
        }
    }

    @Test fun manifestRepositoryMustMatchExpectedRepositoryBeforeOpeningAnyRoute() = runBlocking {
        var calls = 0
        try {
            UpdateRepository { calls++; FakeHttps(manifestUrl, 200, manifest) }
                .check("https://github.com/other-owner/other-app/releases/latest/download/update.json", repository, UpdateSource.AUTO)
            fail("A foreign canonical manifest must fail")
        } catch (error: IOException) { assertStableUpdateError(error, "UPDATE_CHECK_URL_INVALID") }
        assertEquals(0, calls)
    }

}
