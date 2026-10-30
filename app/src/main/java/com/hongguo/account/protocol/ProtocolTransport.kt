// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.account.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody
import okio.BufferedSink

/** Each owner tracks its own calls; cancellation never cancels unrelated playback traffic. */
internal class ProtocolTransport(http: OkHttpClient) {
    private val client = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val active = Collections.synchronizedSet(mutableSetOf<Call>())

    fun cancelAll() { synchronized(active) { active.toList() }.forEach(Call::cancel) }

    fun <T> execute(request: Request, read: (Response) -> T): T {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("账号请求已取消")
        val call = client.newCall(request)
        active.add(call)
        return try {
            call.execute().use(read)
        } catch (error: AccountProtocolException) {
            throw error
        } catch (_: IOException) {
            if (call.isCanceled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("账号请求已取消")
            throw AccountProtocolException("账号网络请求失败，请稍后重试")
        } finally { active.remove(call) }
    }
}

/** Also blocks HTTP 408/503 follow-ups and authentication-driven body replay in OkHttp. */
internal fun RequestBody.oneShot(): RequestBody {
    val delegate = this
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }
}

/** Hard response cap also applies to unknown-length/chunked responses. */
internal fun cloudResponse(response: Response, limit: Int = 2 * 1024 * 1024): String {
    if (!response.isSuccessful) {
        throw AccountProtocolException("账号请求失败（HTTP ${response.code}）", response.code, response.code == 401)
    }
    val body = response.body ?: throw AccountProtocolException("账号接口响应为空")
    if (body.contentLength() > limit) throw AccountProtocolException("账号接口响应过大")
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    val input = body.byteStream()
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("账号请求已取消")
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (count < 0) break
        out.write(buffer, 0, count)
        if (out.size() > limit) throw AccountProtocolException("账号接口响应过大")
    }
    return String(out.toByteArray(), Charsets.UTF_8).takeIf { it.isNotBlank() }
        ?: throw AccountProtocolException("账号接口返回空响应，请稍后重试")
}
