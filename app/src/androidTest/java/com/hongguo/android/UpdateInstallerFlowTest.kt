package com.hongguo.android

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Device PackageManager/FileProvider regression using only the APK already installed on the device.
 * Cached rejections happen before HTTP; no release request, installer launch, or update is performed.
 */
@RunWith(AndroidJUnit4::class)
class UpdateInstallerFlowTest {
    private lateinit var context: Context
    private lateinit var installer: UpdateInstaller
    private lateinit var source: File
    private lateinit var installed: PackageInfo
    private lateinit var sourceHash: String
    private val created = mutableListOf<File>()

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        installer = UpdateInstaller(context)
        source = File(context.applicationInfo.sourceDir)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        installed = info
        sourceHash = sha256(source)
        assertTrue("installed APK fixture is absent", source.isFile)
        assertTrue("installed APK fixture is empty", source.length() > 4)
    }

    @After fun cleanup() { created.forEach { it.delete() } }

    @Test(timeout = 30_000) fun cachedInstalledApkCannotPretendToBeHigherPublishedVersion() = runBlocking {
        val update = metadata()
        val cached = cacheCopy(update)
        var progressCalls = 0
        val error = expectInstallError { installer.download(update) { _, _ -> progressCalls++ } }
        assertTrue("archive version mismatch must be the rejection", error.message.orEmpty().contains("应用或版本"))
        assertEquals("cached rejection must not start a fresh transfer", 1, progressCalls)
        assertFalse("rejected cache must be removed", cached.exists())
        assertNoParts()
        assertEquals(versionCode(installed), versionCode(context.packageManager.getPackageInfo(context.packageName, 0)))
    }

    @Test(timeout = 30_000) fun sameLengthTamperedCacheIsRejectedBeforePackageParsingOrNetwork() = runBlocking {
        val update = metadata()
        val cached = cacheCopy(update)
        RandomAccessFile(cached, "rw").use { file ->
            val offset = (file.length() / 2).coerceAtLeast(4)
            file.seek(offset)
            val original = file.readUnsignedByte()
            file.seek(offset)
            file.writeByte(original xor 1)
        }
        assertEquals(source.length(), cached.length())
        val error = expectInstallError { installer.download(update) { _, _ -> } }
        assertTrue("tampered bytes must fail SHA-256", error.message.orEmpty().contains("完整性"))
        assertFalse(cached.exists())
        assertNoParts()
    }

    @Test(timeout = 30_000) fun cachedInstrumentationApkCannotReplaceTargetApplication() = runBlocking {
        // The test APK is already installed to run this suite; no extra candidate build is needed.
        val testApk = File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
        val archive = context.packageManager.getPackageArchiveInfo(testApk.absolutePath, 0)
        assertNotNull("instrumentation APK fixture must parse locally", archive)
        assertNotEquals(context.packageName, archive!!.packageName)
        val update = metadata().copy(sha256 = sha256(testApk), sizeBytes = testApk.length())
        val cached = cacheCopy(update, testApk)
        val error = expectInstallError { installer.download(update) { _, _ -> } }
        assertTrue("different package must be rejected after its hash passes", error.message.orEmpty().contains("应用或版本"))
        assertFalse(cached.exists())
        assertNoParts()
    }

    @Test(timeout = 30_000) fun truncatedCacheIsRejectedWithoutOpeningDownloadConnection() = runBlocking {
        val update = metadata()
        val cached = cacheCopy(update)
        RandomAccessFile(cached, "rw").use { it.setLength(it.length() - 1) }
        val error = expectInstallError { installer.download(update) { _, _ -> } }
        assertTrue(error.message.orEmpty().contains("大小"))
        assertFalse(cached.exists())
        assertNoParts()
    }

    @Test(timeout = 30_000) fun apkHeaderIsRecheckedForCachedFiles() = runBlocking {
        val update = metadata()
        val cached = cacheCopy(update)
        RandomAccessFile(cached, "rw").use { it.writeByte(0) }
        val error = expectInstallError { installer.download(update) { _, _ -> } }
        assertTrue(error.message.orEmpty().contains("格式"))
        assertFalse(cached.exists())
        assertNoParts()
    }

    @Test(timeout = 30_000) fun unverifiedValidApkCannotProduceInstallerUriEvenWithInstallPermission() {
        val file = cacheCopy(metadata())
        withInstallPermission {
            assertTrue(installer.hasInstallPermission())
            val error = expectSynchronousInstallError { installer.installerIntent(file) }
            assertTrue("a copied APK must be rejected as unregistered", error.message.orEmpty().contains("下载并校验"))
            assertTrue("rejected install must not alter the fixture", file.isFile)
        }
    }

    @Test(timeout = 30_000) fun installerRejectsFilesOutsideDedicatedUpdateDirectory() {
        val outside = File.createTempFile("update-outside-", ".apk", context.cacheDir).also { created += it }
        source.copyTo(outside, overwrite = true)
        withInstallPermission {
            val error = expectSynchronousInstallError { installer.installerIntent(outside) }
            assertTrue("only cache/updates may be shared", error.message.orEmpty().contains("更新文件已失效"))
        }
    }

    private fun metadata(): AppUpdate = AppUpdate(
        versionCode = versionCode(installed) + 1,
        versionName = installed.versionName.orEmpty(),
        // Valid public-host syntax only. Every test must reject its existing cache before HTTP.
        apkUrl = "https://github.com/invalid-update-fixture/no-network/releases/download/fixture/update.apk",
        sha256 = sourceHash,
        sizeBytes = source.length(),
        minSdk = context.applicationInfo.minSdkVersion,
        notes = "Device-only cached APK validation fixture",
    )

    private fun cacheCopy(update: AppUpdate, apk: File = source): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "update-${update.versionCode}-${update.sha256.lowercase(Locale.ROOT)}.apk")
        apk.copyTo(file, overwrite = true)
        created += file
        return file
    }

    private fun assertNoParts() {
        val parts = File(context.cacheDir, "updates").listFiles().orEmpty().filter {
            it.name.startsWith("update-") && it.name.endsWith(".part.apk")
        }
        assertTrue("rejection must not leave a download intermediate", parts.isEmpty())
    }

    private suspend fun expectInstallError(action: suspend () -> Unit): UpdateInstallException {
        try { action() } catch (error: UpdateInstallException) { return error }
        throw AssertionError("expected safe update rejection")
    }

    private fun expectSynchronousInstallError(action: () -> Unit): UpdateInstallException {
        try { action() } catch (error: UpdateInstallException) { return error }
        throw AssertionError("unverified APK must not produce an installation Intent")
    }

    /**
     * Grant REQUEST_INSTALL_PACKAGES from host adb before starting instrumentation, then restore it
     * externally afterward. Changing this app-op inside Android 16 instrumentation kills the app.
     */
    private fun withInstallPermission(action: () -> Unit) {
        Assume.assumeTrue("Host adb must allow this app's REQUEST_INSTALL_PACKAGES before instrumentation",
            installer.hasInstallPermission())
        action()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val alphabet = "0123456789abcdef"
        return buildString(64) { digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4]); append(alphabet[value and 15])
        } }
    }
}
