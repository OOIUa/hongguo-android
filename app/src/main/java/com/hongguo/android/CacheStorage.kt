package com.hongguo.android

import android.content.Context
import coil.Coil
import coil.annotation.ExperimentalCoilApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.Locale

internal enum class CacheCategory { MEDIA, IMAGE, UPDATES }

internal data class CacheSnapshot(
    val mediaBytes: Long = 0L,
    val imageBytes: Long = 0L,
    val updatesBytes: Long = 0L,
) {
    val totalBytes: Long get() = mediaBytes + imageBytes + updatesBytes
}

/** Measures and clears only temporary cache directories; never touches favorites, history, preferences, or account files. */
internal object CacheStorage {
    suspend fun measure(context: Context): CacheSnapshot = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        CacheSnapshot(
            mediaBytes = directorySize(File(app.cacheDir, "media")),
            imageBytes = directorySize(imageCacheDir(app)),
            updatesBytes = directorySize(File(app.cacheDir, "updates")),
        )
    }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clear(context: Context, category: CacheCategory, allowUpdatesCleanup: Boolean = true): CacheSnapshot =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            when (category) {
                CacheCategory.MEDIA -> deleteChildren(File(app.cacheDir, "media"))
                CacheCategory.IMAGE -> {
                    val loader = Coil.imageLoader(app)
                    runCatching { loader.memoryCache?.clear() }
                    runCatching { loader.diskCache?.clear() }
                    deleteChildren(imageCacheDir(app))
                }
                CacheCategory.UPDATES -> if (allowUpdatesCleanup) deleteChildren(File(app.cacheDir, "updates"))
            }
            measure(app)
        }

    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearAll(context: Context, allowUpdatesCleanup: Boolean = true): CacheSnapshot =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            deleteChildren(File(app.cacheDir, "media"))
            val loader = Coil.imageLoader(app)
            runCatching { loader.memoryCache?.clear() }
            runCatching { loader.diskCache?.clear() }
            deleteChildren(imageCacheDir(app))
            if (allowUpdatesCleanup) deleteChildren(File(app.cacheDir, "updates"))
            measure(app)
        }

    @OptIn(ExperimentalCoilApi::class)
    private fun imageCacheDir(context: Context): File =
        runCatching { Coil.imageLoader(context).diskCache?.directory?.toFile() }.getOrNull()
            ?: File(context.cacheDir, "image_cache")

    internal fun directorySize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        var total = 0L
        dir.listFiles()?.forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) return@forEach
            total += if (child.isDirectory) directorySize(child) else child.length().coerceAtLeast(0L)
        }
        return total
    }

    internal fun deleteChildren(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.forEach { child ->
            if (Files.isSymbolicLink(child.toPath())) {
                runCatching { child.delete() }
                return@forEach
            }
            if (child.isDirectory) {
                deleteChildren(child)
            }
            runCatching { child.delete() }
        }
    }

    internal fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe == 0L -> "0 B"
            safe < 1024L -> "$safe B"
            safe < 1024L * 1024L -> "${safe / 1024L} KB"
            safe < 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", safe / (1024.0 * 1024.0))
            else -> String.format(Locale.ROOT, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
