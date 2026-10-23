package com.hongguo.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Anonymous manifest fetch only. APK downloading and installation belong to the installer. */
class UpdateRepository internal constructor(private val connectionFactory: (URI) -> HttpsURLConnection) {
    constructor() : this({ uri -> uri.toURL().openConnection() as HttpsURLConnection })

    suspend fun check(manifestUrl: String, expectedRepository: String): AppUpdate = runInterruptible(Dispatchers.IO) {
        UpdateManifestCodec.validatedRepository(expectedRepository)
        UpdateManifestCodec.parse(fetch(manifestUrl), expectedRepository)
    }

    private fun fetch(manifestUrl: String): String {
        var uri = manifestUri(manifestUrl)
        val deadline = System.nanoTime() + 25_000_000_000L
        repeat(6) { redirects ->
            cancelled()
            val connection = try { connectionFactory(uri) }
            catch (_: Exception) { throw IOException("UPDATE_CHECK_URL_INVALID") }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = remaining(deadline).coerceAtMost(8_000)
                connection.readTimeout = remaining(deadline).coerceAtMost(10_000)
                connection.setRequestProperty("Accept", "application/json, application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "hongguo-android-updater")
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw IOException("UPDATE_CHECK_REDIRECT_LIMIT")
                    val location = connection.getHeaderField("Location") ?: throw IOException("UPDATE_CHECK_REDIRECT_INVALID")
                    uri = manifestUri(try { uri.resolve(location).toString() }
                        catch (_: Exception) { throw IOException("UPDATE_CHECK_REDIRECT_INVALID") })
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException("UPDATE_CHECK_HTTP_$status")
                val declared = connection.contentLengthLong
                if (declared > UpdateManifestCodec.MAX_MANIFEST_BYTES) throw IOException("UPDATE_MANIFEST_TOO_LARGE")
                val output = ByteArrayOutputStream(4096)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        cancelled()
                        connection.readTimeout = remaining(deadline).coerceAtMost(10_000)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() > UpdateManifestCodec.MAX_MANIFEST_BYTES - count)
                            throw IOException("UPDATE_MANIFEST_TOO_LARGE")
                        output.write(buffer, 0, count)
                    }
                }
                if (output.size() == 0) throw IOException("UPDATE_MANIFEST_EMPTY")
                return try {
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                } catch (_: Exception) { throw IOException("UPDATE_MANIFEST_ENCODING_INVALID") }
            } catch (_: SocketTimeoutException) {
                throw IOException("UPDATE_CHECK_TIMEOUT")
            } catch (error: InterruptedIOException) {
                throw InterruptedIOException(if (Thread.currentThread().isInterrupted) "UPDATE_CHECK_CANCELLED" else "UPDATE_CHECK_TIMEOUT")
            } catch (error: IOException) {
                // Never leak signed GitHub redirect URLs or platform exception messages.
                val code = error.message.orEmpty()
                if (code.matches(Regex("UPDATE_[A-Z0-9_]+"))) throw IOException(code)
                throw IOException("UPDATE_CHECK_NETWORK_FAILED")
            } finally { connection.disconnect() }
        }
        throw IOException("UPDATE_CHECK_REDIRECT_LIMIT")
    }

    private fun manifestUri(value: String): URI {
        val uri = try { URI(value) }
        catch (_: Exception) { throw IOException("UPDATE_CHECK_URL_INVALID") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty()
            || uri.rawUserInfo != null || uri.rawFragment != null || uri.isOpaque
            || (uri.port != -1 && uri.port != 443)
            || uri.host.lowercase(Locale.ROOT) !in MANIFEST_HOSTS) throw IOException("UPDATE_CHECK_URL_INVALID")
        return uri
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
    }

    private fun remaining(deadline: Long): Int {
        val millis = (deadline - System.nanoTime()) / 1_000_000L
        if (millis <= 0) throw IOException("UPDATE_CHECK_TIMEOUT")
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    private companion object {
        val MANIFEST_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
