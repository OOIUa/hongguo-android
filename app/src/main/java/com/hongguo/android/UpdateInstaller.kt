package com.hongguo.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock

/** Safe user-facing errors contain no remote URL, response body, local path, or credentials. */
class UpdateInstallException(message: String) : IOException(message)

/** A dedicated provider is registered by the manifest and exposes cache/updates only. */
class UpdateFileProvider : FileProvider()

/**
 * Downloads only when called by an explicit user action. Installation always goes through system UI.
 * Build installerIntent on an IO thread: it deliberately rechecks the APK before granting access.
 */
class UpdateInstaller(context: Context) {
    private val app = context.applicationContext

    suspend fun download(
        update: AppUpdate,
        source: UpdateSource = UpdateSource.AUTO,
        onSource: (UpdateSource) -> Unit = {},
        onProgress: (Long, Long) -> Unit,
    ): File {
        val coroutine = currentCoroutineContext()
        val checkCancelled = {
            coroutine.ensureActive()
            if (Thread.currentThread().isInterrupted) throw CancellationException("更新下载已取消")
        }
        return runInterruptible(Dispatchers.IO) {
            var locked = false
            try {
                checkCancelled()
                while (!locked) {
                    locked = FILE_LOCK.tryLock(200, TimeUnit.MILLISECONDS)
                    checkCancelled()
                }
                downloadBlocking(update, source, onSource, onProgress, checkCancelled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("更新下载已取消")
            } catch (error: Exception) {
                checkCancelled()
                throw friendly(error)
            } finally {
                if (locked) FILE_LOCK.unlock()
            }
        }
    }

    private fun downloadBlocking(
        update: AppUpdate,
        source: UpdateSource,
        onSource: (UpdateSource) -> Unit,
        onProgress: (Long, Long) -> Unit,
        checkCancelled: () -> Unit,
    ): File {
        UpdateArtifactChecks.validateMetadata(update.sizeBytes, update.sha256)
        val routes = try { UpdateSources.routes(update.apkUrl, source) }
        catch (_: IllegalArgumentException) { throw UpdateInstallException("更新下载地址无效") }
        validateUpdateVersion(update)
        val directory = updatesDirectory()
        // Downloads are process-wide serialized; a previous process can leave only these intermediates.
        directory.listFiles()?.filter { it.name.startsWith("update-") && it.name.endsWith(".part.apk") }
            ?.forEach { it.delete() }
        val final = File(directory, "update-${update.versionCode}-${update.sha256.lowercase(Locale.ROOT)}.apk")
        if (final.canonicalFile.parentFile != directory) throw UpdateInstallException("更新缓存文件无效，请重新下载")
        VERIFIED.remove(final.canonicalPath)
        if (final.isFile) {
            onProgress(0, update.sizeBytes)
            try {
                UpdateArtifactChecks.verify(final, update.sizeBytes, update.sha256, checkCancelled)
                validateArchive(final, update)
                checkCancelled()
                VERIFIED[final.canonicalPath] = update
                onProgress(update.sizeBytes, update.sizeBytes)
                return final
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateInstallException) {
                if (!final.delete()) throw UpdateInstallException("无法清理损坏的更新包，请稍后重试")
                // A verified hash with a wrong package/version is a bad release, not a network error.
                // Stop here; an explicit retry can fetch a fresh copy after invalid cache is removed.
                throw error
            }
        }
        return UpdateRouteExecutor.execute(routes, checkCancelled, onRoute = { route ->
            onSource(route.source)
            onProgress(0, update.sizeBytes)
        }) { route ->
            val temporary = File.createTempFile("update-", ".part.apk", directory)
            try {
                try {
                    transfer(route, temporary, update.sizeBytes, onProgress, checkCancelled)
                    checkCancelled()
                    UpdateArtifactChecks.verify(temporary, update.sizeBytes, update.sha256, checkCancelled)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    checkCancelled()
                    // Some stream implementations clear the interrupt flag while reporting cancellation.
                    // SocketTimeoutException is retryable, but a plain interrupted IO must never switch routes.
                    if (error is InterruptedIOException && error !is SocketTimeoutException) {
                        throw CancellationException("更新下载已取消")
                    }
                    // Changing the network source cannot fix storage permissions or insufficient disk.
                    val message = error.message.orEmpty()
                    if (message.contains(Regex("ENOSPC|no space|EACCES|permission denied|EROFS|read-only file system", RegexOption.IGNORE_CASE))) throw error
                    throw RetryUpdateRoute(error)
                }
                // A matching digest with a different identity/signature means the release itself is bad.
                // Such failures and local commit errors stop; they never fall back to an unchecked APK.
                validateArchive(temporary, update)
                checkCancelled()
                Files.move(temporary.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                VERIFIED[final.canonicalPath] = update
                onProgress(update.sizeBytes, update.sizeBytes)
                final
            } finally {
                temporary.delete()
            }
        }
    }

    private fun transfer(
        route: UpdateRoute,
        destination: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
        checkCancelled: () -> Unit,
    ) {
        var current = UpdateArtifactChecks.checkedUrl(route.url)
        for (redirects in 0..MAX_REDIRECTS) {
            checkCancelled()
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = if (route.source == UpdateSource.DIRECT) 5_000 else 8_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            // Fixed public-download headers only; API keys/cookies from the media core are never used.
            connection.setRequestProperty("User-Agent", "Hongguo-Android-Updater")
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                checkCancelled()
                if (status in REDIRECT_STATUSES) {
                    if (redirects == MAX_REDIRECTS) throw UpdateInstallException("更新下载重定向次数过多，请稍后重试")
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateInstallException("更新下载地址暂不可用")
                    current = try { UpdateSources.resolveRedirect(current, location) }
                    catch (error: UpdateInstallException) { throw error }
                    catch (_: Exception) { throw UpdateInstallException("更新下载地址无效") }
                    continue
                }
                if (status != HttpURLConnection.HTTP_OK) throw UpdateInstallException("更新下载失败，请稍后重试（HTTP $status）")
                val declared = connection.contentLengthLong
                if (declared >= 0 && declared != expectedSize) throw UpdateInstallException("更新包大小与发布信息不一致，请稍后重新检查更新")
                var received = 0L
                var lastProgress = 0L
                val stall = UpdateDownloadStallGuard()
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            checkCancelled()
                            received += count
                            stall.received(received)
                            if (count == 0) continue
                            if (received > expectedSize) throw UpdateInstallException("更新包大小与发布信息不一致，请重新检查更新")
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress > 100_000_000L) {
                                onProgress(received, expectedSize)
                                lastProgress = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                checkCancelled()
                if (received != expectedSize) throw UpdateInstallException("更新包下载不完整，请重试")
                return
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateInstallException("更新下载地址暂不可用")
    }

    /** Invoke only after hasInstallPermission is true and in response to the user's Install action. */
    fun installerIntent(file: File): Intent = FILE_LOCK.withLock {
        try {
            if (!hasInstallPermission()) throw UpdateInstallException("请先允许本应用安装更新，再点击安装")
            val safe = file.canonicalFile
            if (safe.parentFile != updatesDirectory() || !safe.isFile) throw UpdateInstallException("更新文件已失效，请重新下载")
            val update = VERIFIED[safe.path] ?: throw UpdateInstallException("请先下载并校验更新包，再点击安装")
            UpdateArtifactChecks.verify(safe, update.sizeBytes, update.sha256)
            validateArchive(safe, update)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates.fileprovider", safe)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("应用更新", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (error: Exception) {
            throw friendly(error)
        }
    }

    fun hasInstallPermission(): Boolean = try { app.packageManager.canRequestPackageInstalls() } catch (_: Exception) { false }

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun updatesDirectory(): File {
        val cache = app.cacheDir.canonicalFile
        val directory = File(cache, "updates").canonicalFile
        if (directory.parentFile != cache || (!directory.isDirectory && !directory.mkdirs())) {
            throw UpdateInstallException("无法创建更新缓存，请检查设备存储空间")
        }
        return directory
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo = app.packageManager.getPackageInfo(app.packageName, signatureFlags())

    private fun validateUpdateVersion(update: AppUpdate) {
        if (update.versionCode <= 0 || update.versionCode <= versionCode(installedPackage())) {
            throw UpdateInstallException("该版本已安装或已失效，请重新检查更新")
        }
        if (update.versionName.isBlank() || update.minSdk < 1 || update.minSdk > Build.VERSION.SDK_INT) {
            throw UpdateInstallException("此更新不支持当前 Android 版本")
        }
    }

    @Suppress("DEPRECATION")
    private fun validateArchive(file: File, update: AppUpdate) {
        validateUpdateVersion(update)
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw UpdateInstallException("更新包不是有效的 Android 安装包，请重新下载")
        if (archive.packageName != app.packageName || versionCode(archive) != update.versionCode || archive.versionName != update.versionName) {
            throw UpdateInstallException("更新包的应用或版本与发布信息不一致，已停止安装")
        }
        val minimum = archive.applicationInfo?.minSdkVersion
            ?: throw UpdateInstallException("无法读取更新包兼容信息，请重新下载")
        if (minimum != update.minSdk || minimum > Build.VERSION.SDK_INT) {
            throw UpdateInstallException("更新包的 Android 兼容信息与发布信息不一致")
        }
        val installedSigners = signerDigests(installedPackage())
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isEmpty() || archiveSigners.isEmpty() || installedSigners != archiveSigners) {
            throw UpdateInstallException("更新包签名与当前应用不一致，无法覆盖安装")
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) signing.apkContentsSigners.toList()
            else listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        } else info.signatures?.toList().orEmpty()
        return signatures.map { UpdateArtifactChecks.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    private fun friendly(error: Exception): UpdateInstallException = when (error) {
        is UpdateInstallException -> error
        is UnknownHostException -> UpdateInstallException("网络连接不可用，请检查网络后重试")
        is SocketTimeoutException -> UpdateInstallException("下载更新超时，请稍后重试")
        is SSLException -> UpdateInstallException("更新连接验证失败，请检查网络和设备时间")
        is SecurityException -> UpdateInstallException("无法访问更新存储或安装信息，请稍后重试")
        else -> if (error.message.orEmpty().contains("ENOSPC", true) || error.message.orEmpty().contains("no space", true))
            UpdateInstallException("设备存储空间不足，请清理后重试")
        else UpdateInstallException("更新下载或校验失败，请重新下载后重试")
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        private val FILE_LOCK = ReentrantLock(true)
        private val VERIFIED = ConcurrentHashMap<String, AppUpdate>()
    }
}

/** Pure JVM-verifiable network/file boundary shared by fresh download and cached install checks. */
internal object UpdateArtifactChecks {
    const val MAX_APK_BYTES = UpdateManifestCodec.MAX_APK_BYTES

    fun checkedUrl(raw: String): URI {
        return try { UpdateSources.checkedTransportUri(raw) }
        catch (_: IllegalArgumentException) { throw UpdateInstallException("更新下载地址不受支持，已停止下载") }
    }

    fun validateMetadata(size: Long, sha256: String) {
        if (size !in 4..MAX_APK_BYTES || !sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw UpdateInstallException("更新发布信息不完整，请重新检查更新")
        }
    }

    fun verify(file: File, size: Long, sha256: String, checkCancelled: () -> Unit = {}) {
        validateMetadata(size, sha256)
        checkCancelled()
        if (!file.isFile || file.length() != size) throw UpdateInstallException("更新包大小校验失败，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            if (input.read(header) != 4 || !header.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                throw UpdateInstallException("更新包格式无效，请重新下载")
            }
            digest.update(header)
            val buffer = ByteArray(64 * 1024)
            var received = 4L
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) { received += count; digest.update(buffer, 0, count) }
                if (received > size) throw UpdateInstallException("更新包大小校验失败，请重新下载")
            }
            if (received != size) throw UpdateInstallException("更新包大小校验失败，请重新下载")
        }
        checkCancelled()
        val expected = ByteArray(32) { sha256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        if (!MessageDigest.isEqual(expected, digest.digest())) throw UpdateInstallException("更新包完整性校验失败，请重新下载")
    }

    fun hex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) { bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4]); append(alphabet[value and 15])
        } }
    }
}

/** Only explicitly retryable network/content failures move to a different route. */
internal class RetryUpdateRoute(val failure: Exception) : Exception("Update route failed")

/** Pure blocking seam; the caller already runs inside cancellable Dispatchers.IO. */
internal object UpdateRouteExecutor {
    fun <T> execute(
        routes: List<UpdateRoute>,
        checkCancelled: () -> Unit = {},
        onRoute: (UpdateRoute) -> Unit = {},
        attempt: (UpdateRoute) -> T,
    ): T {
        var lastFailure: Exception? = null
        for (route in routes) {
            checkCancelled()
            onRoute(route)
            checkCancelled()
            try {
                val result = attempt(route)
                checkCancelled()
                return result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retry: RetryUpdateRoute) {
                checkCancelled()
                if (retry.failure is CancellationException) throw retry.failure
                if (retry.failure is InterruptedIOException && retry.failure !is SocketTimeoutException) {
                    throw CancellationException("更新下载已取消")
                }
                lastFailure = retry.failure
            }
        }
        throw lastFailure ?: UpdateInstallException("没有可用的更新下载线路")
    }
}

/** Allow slow downloads to finish, but reject connections that only drip a few bytes forever. */
internal class UpdateDownloadStallGuard(private val nanoTime: () -> Long = System::nanoTime) {
    private var progressAt = nanoTime()
    private var progressBytes = 0L

    fun received(totalBytes: Long) {
        val now = nanoTime()
        if (totalBytes - progressBytes >= 4 * 1024) {
            progressBytes = totalBytes
            progressAt = now
        } else if (now - progressAt >= 30_000_000_000L) {
            throw SocketTimeoutException("更新下载停滞，请稍后重试")
        }
    }
}
