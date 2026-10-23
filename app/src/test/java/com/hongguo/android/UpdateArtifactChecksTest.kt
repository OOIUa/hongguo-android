package com.hongguo.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
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

    @Test fun onlyExplicitPublicGithubHttpsHostsAreAccepted() {
        for (host in listOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            assertEquals(host, UpdateArtifactChecks.checkedUrl("https://$host/example.apk").host)
        }
        val rejected = listOf(
            "http://github.com/example.apk",
            "https://github.com.evil.invalid/example.apk",
            "https://evil.invalid@github.com/example.apk",
            "https://github.com@evil.invalid/example.apk",
            "https://github.com:444/example.apk",
            "https://raw.githubusercontent.com/example.apk",
            "https://github.com/example.apk#fragment",
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

    private fun assertFails(action: () -> Unit): UpdateInstallException {
        try { action() } catch (error: UpdateInstallException) { return error }
        throw AssertionError("expected a safe validation error")
    }
}
