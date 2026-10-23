package com.hongguo.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import javax.net.ssl.HttpsURLConnection

/** Anonymous manifest fetch only. Routing changes transport; the manifest and APK identity stay canonical. */
class UpdateRepository internal constructor(private val connectionFactory: (URI) -> HttpsURLConnection) {
    constructor() : this({ uri -> uri.toURL().openConnection() as HttpsURLConnection })

    suspend fun check(
        manifestUrl: String,
        expectedRepository: String,
        source: UpdateSource = UpdateSource.AUTO,
        onSource: (UpdateSource) -> Unit = {},
    ): AppUpdate {
        val context = currentCoroutineContext()
        context.ensureActive()
        UpdateManifestCodec.validatedRepository(expectedRepository)
        val routes = try { UpdateSources.routes(manifestUrl, source) }
        catch (_: IllegalArgumentException) { throw IOException("UPDATE_CHECK_URL_INVALID") }
        if (!URI(manifestUrl).path.orEmpty().startsWith("/$expectedRepository/releases/", ignoreCase = true)) {
            throw IOException("UPDATE_CHECK_URL_INVALID")
        }
        return runInterruptible(Dispatchers.IO) {
            val ensureNotCancelled = {
                context.ensureActive()
                cancelled()
            }
            var lastError: IOException? = null
            for (route in routes) {
                ensureNotCancelled()
                onSource(route.source)
                ensureNotCancelled()
                try {
                    val json = fetch(route, ensureNotCancelled)
                    ensureNotCancelled()
                    // HTML, damaged metadata and a wrong-repository APK all fail this route too.
                    return@runInterruptible UpdateManifestCodec.parse(json, expectedRepository)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InterruptedIOException) {
                    // An interrupted read can clear the thread flag; never treat it as a failed line.
                    context.ensureActive()
                    throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
                } catch (error: Exception) {
                    ensureNotCancelled()
                    lastError = safeFailure(error)
                }
            }
            ensureNotCancelled()
            throw (lastError ?: IOException("UPDATE_CHECK_NO_SOURCE"))
        }
    }

    private fun fetch(route: UpdateRoute, ensureNotCancelled: () -> Unit): String {
        var uri = transportUri(route.url)
        val direct = route.source == UpdateSource.DIRECT
        val budgetMillis = if (direct) 8_000L else 15_000L
        val connectLimit = if (direct) 3_000 else 5_000
        val readLimit = if (direct) 4_000 else 7_000
        val deadline = System.nanoTime() + budgetMillis * 1_000_000L
        repeat(6) { redirects ->
            ensureNotCancelled()
            val connection = try { connectionFactory(uri) }
            catch (error: CancellationException) { throw error }
            catch (error: SocketTimeoutException) { ensureNotCancelled(); throw IOException("UPDATE_CHECK_TIMEOUT") }
            catch (error: InterruptedIOException) { throw InterruptedIOException("UPDATE_CHECK_CANCELLED") }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
            }
            catch (error: Exception) { ensureNotCancelled(); throw safeFailure(error) }
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = remaining(deadline).coerceAtMost(connectLimit)
                connection.readTimeout = remaining(deadline).coerceAtMost(readLimit)
                connection.setRequestProperty("Accept", "application/json, application/octet-stream")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", "hongguo-android-updater")
                connection.connect()
                ensureNotCancelled()
                connection.readTimeout = remaining(deadline).coerceAtMost(readLimit)
                val status = connection.responseCode
                ensureNotCancelled()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (redirects == 5) throw IOException("UPDATE_CHECK_REDIRECT_LIMIT")
                    val location = connection.getHeaderField("Location") ?: throw IOException("UPDATE_CHECK_REDIRECT_INVALID")
                    uri = try { UpdateSources.resolveRedirect(uri, location) }
                    catch (_: Exception) { throw IOException("UPDATE_CHECK_URL_INVALID") }
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException("UPDATE_CHECK_HTTP_$status")
                val declared = connection.contentLengthLong
                if (declared > UpdateManifestCodec.MAX_MANIFEST_BYTES) throw IOException("UPDATE_MANIFEST_TOO_LARGE")
                val output = ByteArrayOutputStream(4096)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        ensureNotCancelled()
                        connection.readTimeout = remaining(deadline).coerceAtMost(readLimit)
                        val count = input.read(buffer)
                        ensureNotCancelled()
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketTimeoutException) {
                ensureNotCancelled()
                throw IOException("UPDATE_CHECK_TIMEOUT")
            } catch (error: InterruptedIOException) {
                throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
            } catch (error: IOException) {
                ensureNotCancelled()
                throw safeFailure(error)
            } finally { connection.disconnect() }
        }
        throw IOException("UPDATE_CHECK_REDIRECT_LIMIT")
    }

    private fun transportUri(value: String): URI = try { UpdateSources.checkedTransportUri(value) }
    catch (_: IllegalArgumentException) { throw IOException("UPDATE_CHECK_URL_INVALID") }

    private fun safeFailure(error: Exception): IOException {
        val code = error.message.orEmpty()
        return IOException(if (code.matches(Regex("UPDATE_[A-Z0-9_]+"))) code else "UPDATE_CHECK_NETWORK_FAILED")
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("UPDATE_CHECK_CANCELLED")
    }

    private fun remaining(deadline: Long): Int {
        val millis = (deadline - System.nanoTime()) / 1_000_000L
        if (millis <= 0) throw IOException("UPDATE_CHECK_TIMEOUT")
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }
}
