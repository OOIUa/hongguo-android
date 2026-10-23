package com.hongguo.android

import com.hongguo.api.util.Json
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int,
    val notes: String,
    val publishedAt: String = "",
) {
    /** Android's installed version code is authoritative; release names are display text. */
    fun isNewerThan(installedVersionCode: Long): Boolean {
        require(installedVersionCode >= 0) { "UPDATE_INSTALLED_VERSION_INVALID" }
        return versionCode > installedVersionCode
    }
}

object UpdateManifestCodec {
    const val MAX_MANIFEST_BYTES = 128 * 1024
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val APPLICATION_ID = "com.hongguo.android"

    fun parse(json: String, expectedRepository: String): AppUpdate {
        val repository = validatedRepository(expectedRepository)
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "UPDATE_MANIFEST_TOO_LARGE" }
        val text = json.trim().removePrefix("\uFEFF")
        validateDepth(text)
        val root = try { Json.parseObject(text) }
        catch (_: RuntimeException) { throw IllegalArgumentException("UPDATE_MANIFEST_INVALID_JSON") }
        require(integer(root, "schemaVersion", 1, 1) == 1L) { "UPDATE_SCHEMA_UNSUPPORTED" }
        require(root["applicationId"] == APPLICATION_ID) { "UPDATE_APPLICATION_MISMATCH" }
        val code = integer(root, "versionCode", 1, Long.MAX_VALUE)
        val name = string(root, "versionName", 80, required = true)
        require(name.none { it.isISOControl() }) { "UPDATE_VERSION_NAME_INVALID" }
        val url = string(root, "apkUrl", 2048, required = true)
        validateApkUrl(url, repository)
        val hash = string(root, "sha256", 64, required = true)
        require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "UPDATE_SHA256_INVALID" }
        val size = integer(root, "sizeBytes", 1, MAX_APK_BYTES)
        val minSdk = integer(root, "minSdk", 21, 100).toInt()
        val notes = string(root, "notes", 32 * 1024)
        val published = string(root, "publishedAt", 128)
        require(published.none { it.isISOControl() }) { "UPDATE_PUBLISHED_AT_INVALID" }
        return AppUpdate(code, name, url, hash.lowercase(Locale.ROOT), size, minSdk, notes, published)
    }

    internal fun validatedRepository(value: String): String {
        require(value == value.trim() && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}"))) {
            "UPDATE_REPOSITORY_INVALID"
        }
        require(value.substringAfter('/') !in listOf(".", "..")) { "UPDATE_REPOSITORY_INVALID" }
        return value
    }

    private fun validateApkUrl(value: String, repository: String) {
        val uri = try { URI(value) }
        catch (_: Exception) { throw IllegalArgumentException("UPDATE_APK_URL_INVALID") }
        val prefix = "/$repository/releases/download/"
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host.equals("github.com", ignoreCase = true)
            && (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null
            && uri.rawQuery == null && uri.rawFragment == null && !uri.isOpaque) { "UPDATE_APK_URL_INVALID" }
        val rawPath = uri.rawPath.orEmpty()
        val path = uri.path.orEmpty()
        require(rawPath.startsWith(prefix, ignoreCase = true) && path.startsWith(prefix, ignoreCase = true)) {
            "UPDATE_APK_REPOSITORY_MISMATCH"
        }
        // Validate decoded segments too: encoded dot/slash/backslash paths must not escape a release asset.
        require(path.none { it.isISOControl() || it == '\\' } && path.split('/').none { it == "." || it == ".." }) {
            "UPDATE_APK_URL_INVALID"
        }
        val suffix = path.substring(prefix.length).split('/')
        require(suffix.size == 2 && suffix.all { it.isNotBlank() }
            && suffix.last().endsWith(".apk", ignoreCase = true)) { "UPDATE_APK_URL_INVALID" }
    }

    private fun integer(root: Map<String, Any?>, key: String, minimum: Long, maximum: Long): Long {
        val value = root[key]
        val parsed = when (value) {
            is Long -> BigInteger.valueOf(value)
            is Int -> BigInteger.valueOf(value.toLong())
            is BigInteger -> value
            else -> throw IllegalArgumentException("UPDATE_${key.uppercase(Locale.ROOT)}_INVALID")
        }
        require(parsed >= BigInteger.valueOf(minimum) && parsed <= BigInteger.valueOf(maximum)) {
            "UPDATE_${key.uppercase(Locale.ROOT)}_INVALID"
        }
        return parsed.toLong()
    }

    private fun string(root: Map<String, Any?>, key: String, maximum: Int, required: Boolean = false): String {
        val value = root[key]
        if (value == null && !required) return ""
        require(value is String && value.length <= maximum && (!required || value.isNotBlank())) {
            "UPDATE_${key.uppercase(Locale.ROOT)}_INVALID"
        }
        return value
    }

    private fun validateDepth(text: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        text.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "UPDATE_MANIFEST_TOO_DEEP" } }
                '}', ']' -> depth--
            }
        }
    }
}
