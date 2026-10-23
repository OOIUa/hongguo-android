package com.hongguo.android

import com.hongguo.api.util.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateModelsTest {
    private val repository = "sample-owner/hongguo-android"
    private val hash = "aB".repeat(32)

    private fun manifest(vararg changes: Pair<String, Any?>): String = Json.stringify(linkedMapOf<String, Any?>(
        "schemaVersion" to 1, "applicationId" to "com.hongguo.android", "versionCode" to 3,
        "versionName" to "0.3.0", "apkUrl" to "https://github.com/$repository/releases/download/v0.3.0/hongguo.apk",
        "sha256" to hash, "sizeBytes" to 15_000_000, "minSdk" to 26, "notes" to "新增在线更新。",
        "publishedAt" to "2026-10-23T08:00:00Z",
    ).apply { changes.forEach { (key, value) -> put(key, value) } })

    private fun reject(json: String) {
        try { UpdateManifestCodec.parse(json, repository); fail("Invalid manifest must fail") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().startsWith("UPDATE_")) }
    }

    @Test fun validManifestKeepsReleaseMetadataAndNormalizesHash() {
        val update = UpdateManifestCodec.parse(manifest(), repository)
        assertEquals(3L, update.versionCode)
        assertEquals("0.3.0", update.versionName)
        assertEquals(hash.lowercase(), update.sha256)
        assertEquals(15_000_000L, update.sizeBytes)
        assertEquals(26, update.minSdk)
        assertEquals("新增在线更新。", update.notes)
        assertTrue(update.apkUrl.endsWith("/v0.3.0/hongguo.apk"))
    }

    @Test fun newerDecisionOnlyUsesAndroidVersionCode() {
        val update = UpdateManifestCodec.parse(manifest("versionName" to "0.0.1"), repository)
        assertTrue(update.isNewerThan(2))
        assertFalse(update.isNewerThan(3))
        assertFalse(update.isNewerThan(4))
        assertFalse(UpdateManifestCodec.parse(manifest("versionCode" to 2, "versionName" to "999.999.999"), repository).isNewerThan(3))
    }

    @Test fun rejectsWrongApplicationAndUnsupportedSchema() {
        reject(manifest("applicationId" to "com.example.other"))
        reject(manifest("schemaVersion" to 0))
        reject(manifest("schemaVersion" to 2))
        reject(manifest("schemaVersion" to "1"))
    }

    @Test fun requiresPositiveIntegralVersionWithinLongRange() {
        reject(manifest("versionCode" to 0))
        reject(manifest("versionCode" to -1))
        reject(manifest("versionCode" to 3.5))
        reject(manifest("versionCode" to "3"))
        reject(manifest().replace("\"versionCode\":3", "\"versionCode\":9223372036854775808"))
        assertEquals(Long.MAX_VALUE, UpdateManifestCodec.parse(manifest("versionCode" to Long.MAX_VALUE), repository).versionCode)
    }

    @Test fun enforcesApkSizeSdkAndHashBounds() {
        reject(manifest("sizeBytes" to 0))
        reject(manifest("sizeBytes" to UpdateManifestCodec.MAX_APK_BYTES + 1))
        reject(manifest("minSdk" to 0))
        reject(manifest("minSdk" to 101))
        reject(manifest("sha256" to "0".repeat(63)))
        reject(manifest("sha256" to "z".repeat(64)))
        reject(manifest("sha256" to " $hash"))
    }

    @Test fun onlyAcceptsSameRepositoryHttpsReleaseApkWithoutCredentialsOrParameters() {
        val urls = listOf(
            "http://github.com/$repository/releases/download/v1/app.apk",
            "https://github.com.evil.invalid/$repository/releases/download/v1/app.apk",
            "https://github.com/other-owner/hongguo-android/releases/download/v1/app.apk",
            "https://github.com/$repository-extra/releases/download/v1/app.apk",
            "https://token-value@github.com/$repository/releases/download/v1/app.apk",
            "https://github.com:8443/$repository/releases/download/v1/app.apk",
            "https://github.com/$repository/releases/download/v1/app.apk?token=private",
            "https://github.com/$repository/releases/download/v1/app.apk#fragment",
            "https://github.com/$repository/archive/v1/app.apk",
            "https://github.com/$repository/releases/download/v1/app.zip",
        )
        urls.forEach { reject(manifest("apkUrl" to it)) }
    }

    @Test fun decodedTraversalAndEncodedSlashCannotEscapeReleaseAssetPath() {
        listOf(
            "v1/../app.apk", "v1/%2e%2e/app.apk", "v1%2f../app.apk", "v1/app%2fother.apk",
            "v1/app%5cother.apk", "v1/app%00.apk", "/app.apk", "v1/",
        ).forEach { reject(manifest("apkUrl" to "https://github.com/$repository/releases/download/$it")) }
    }

    @Test fun repositoryArgumentCannotBeUrlPathOrTraversal() {
        listOf("", "../repo", "/owner/repo", "owner/../repo", "owner/repo/extra", " owner/repo", "owner/repo?query").forEach {
            try { UpdateManifestCodec.parse(manifest(), it); fail("Invalid repository must fail") }
            catch (error: IllegalArgumentException) { assertEquals("UPDATE_REPOSITORY_INVALID", error.message) }
        }
    }

    @Test fun boundsManifestBytesAndNestedStructureWithoutRejectingBracesInNotes() {
        reject(manifest("notes" to "x".repeat(UpdateManifestCodec.MAX_MANIFEST_BYTES)))
        reject("{\"unused\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}")
        assertEquals("[{release notes}]", UpdateManifestCodec.parse(manifest("notes" to "[{release notes}]"), repository).notes)
    }

    @Test fun optionalReleaseTextDefaultsToEmpty() {
        val root = Json.parseObject(manifest()).apply { remove("notes"); remove("publishedAt") }
        val update = UpdateManifestCodec.parse(Json.stringify(root), repository)
        assertEquals("", update.notes)
        assertEquals("", update.publishedAt)
    }

    @Test fun nonHttpsManifestAndCredentialUrlFailBeforeNetworkWithoutEchoingInput() = runBlocking {
        listOf("http://example.invalid/update.json", "https://private-token@example.invalid/update.json").forEach {
            try { UpdateRepository().check(it, repository); fail("Unsafe manifest URI must fail") }
            catch (error: IOException) {
                assertStableUpdateError(error, "UPDATE_CHECK_URL_INVALID")
                assertFalse(error.message.orEmpty().contains("private-token"))
            }
        }
    }
}
