package com.hongguo.android

import com.hongguo.api.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
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
    ) : HttpsURLConnection(URI(url).toURL()) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
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
        val update = UpdateRepository { uri -> seen.add(uri); connections[seen.lastIndex] }.check(manifestUrl, repository)
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
                UpdateRepository { calls++; connection }.check(manifestUrl, repository)
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
            }.check(manifestUrl, repository)
            fail("Redirect cycle must fail")
        } catch (error: IOException) { assertEquals("UPDATE_CHECK_REDIRECT_LIMIT", error.message) }
        assertEquals(6, calls)
    }

    @Test fun boundsActualChunkedBodyEvenWithoutContentLength() = runBlocking {
        val body = ByteArray(UpdateManifestCodec.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        val connection = FakeHttps(manifestUrl, 200, body, declaredLength = -1)
        try {
            UpdateRepository { connection }.check(manifestUrl, repository)
            fail("Oversized unknown-length response must fail")
        } catch (error: IOException) { assertEquals("UPDATE_MANIFEST_TOO_LARGE", error.message) }
        assertTrue(connection.closed)
    }

    @Test fun acceptsExactBodyLimitAndRejectsInvalidUtf8() = runBlocking {
        val padding = ByteArray(UpdateManifestCodec.MAX_MANIFEST_BYTES - manifest.size) { ' '.code.toByte() }
        val valid = FakeHttps(manifestUrl, 200, manifest + padding, declaredLength = -1)
        assertEquals(3L, UpdateRepository { valid }.check(manifestUrl, repository).versionCode)
        val invalid = FakeHttps(manifestUrl, 200, byteArrayOf(0xC3.toByte(), 0x28))
        try {
            UpdateRepository { invalid }.check(manifestUrl, repository)
            fail("Malformed UTF-8 must fail")
        } catch (error: IOException) { assertEquals("UPDATE_MANIFEST_ENCODING_INVALID", error.message) }
    }

    @Test fun httpFailureIsPreservedAsSafeStableError() = runBlocking {
        val connection = FakeHttps(manifestUrl, 404)
        try {
            UpdateRepository { connection }.check(manifestUrl, repository)
            fail("HTTP error must fail")
        } catch (error: IOException) {
            assertStableUpdateError(error, "UPDATE_CHECK_HTTP_404")
        }
        assertTrue(connection.closed)
    }
}
