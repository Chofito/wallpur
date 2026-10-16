package dev.chofito.wallpurr.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded, disposable storage for media derived from app-owned originals.
 *
 * Originals remain in filesDir. Everything here lives in cacheDir, so Android may remove it under
 * storage pressure and every caller must be able to regenerate a missing entry. Cache keys include
 * the source fingerprint and every setting that affects the output; an edit therefore becomes a
 * cache miss immediately without risking reuse of a stale render.
 */
class MediaDerivativeCache(
    context: Context,
    private val files: ImageFileStore,
) {
    private val thumbnailDir = File(context.cacheDir, "media_thumbnails/$CACHE_VERSION")
    private val renderDir = File(context.cacheDir, "wallpaper_renders/$CACHE_VERSION")
    private val generators = Semaphore(2)
    private val writesSinceTrim = AtomicInteger()
    private val renderInvalidatedAt = ConcurrentHashMap<String, Long>()

    suspend fun thumbnail(
        source: File,
        kind: MediaKind,
        maxSide: Int,
    ): File? = withContext(Dispatchers.IO) {
        val boundedSide = maxSide.coerceIn(MIN_THUMBNAIL_SIDE, MAX_THUMBNAIL_SIDE)
        val target = cacheFile(
            directory = thumbnailDir,
            source = source,
            extension = "webp",
            parts = listOf("thumbnail", kind.name, boundedSide.toString()),
        )
        if (target.isUsable()) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }

        generators.withPermit {
            if (target.isUsable()) {
                target.setLastModified(System.currentTimeMillis())
                return@withPermit target
            }
            val bitmap = runCatching {
                files.decodeBitmap(source, boundedSide, kind)
            }.getOrNull() ?: return@withPermit null
            try {
                // A fast fling disposes off-screen tiles. Do not finish encoding work for a tile
                // whose requesting composition has already been cancelled.
                currentCoroutineContext().ensureActive()
                writeBitmapAtomically(
                    target = target,
                    bitmap = bitmap,
                    format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    },
                    quality = THUMBNAIL_QUALITY,
                )
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    suspend fun renderedBitmap(
        source: File,
        keyParts: List<String>,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val target = cacheFile(renderDir, source, "png", listOf("render") + keyParts)
        if (!target.isUsable()) return@withContext null
        decodeCachedBitmap(target)?.also {
            target.setLastModified(System.currentTimeMillis())
        } ?: run {
            target.delete()
            null
        }
    }

    suspend fun storeRenderedBitmap(
        source: File,
        keyParts: List<String>,
        bitmap: Bitmap,
    ): File? = withContext(Dispatchers.IO) {
        val sourceToken = sourceToken(source.name)
        val invalidatedAt = renderInvalidatedAt[sourceToken]
        if (invalidatedAt != null &&
            SystemClock.elapsedRealtime() - invalidatedAt < RENDER_WRITE_QUIET_PERIOD_MS
        ) {
            return@withContext null
        }
        if (invalidatedAt != null) renderInvalidatedAt.remove(sourceToken, invalidatedAt)
        val target = cacheFile(renderDir, source, "png", listOf("render") + keyParts)
        if (target.isUsable()) return@withContext target
        generators.withPermit {
            if (target.isUsable()) return@withPermit target
            // PNG is deliberate: the lock-screen blur exposes color banding in lossy/full-screen
            // intermediates, so thumbnails and final wallpaper renders use different formats.
            writeBitmapAtomically(target, bitmap, Bitmap.CompressFormat.PNG, quality = 100)
        }
    }

    fun invalidateSource(storedName: String) {
        val token = sourceToken(storedName)
        renderInvalidatedAt.remove(token)
        val prefix = token + "-"
        listOf(thumbnailDir, renderDir).forEach { directory ->
            directory.listFiles()
                ?.filter { it.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
    }

    fun invalidateRenders(storedName: String) {
        invalidateRenders(listOf(storedName))
    }

    fun invalidateRenders(storedNames: Collection<String>) {
        if (storedNames.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val prefixes = storedNames.mapTo(HashSet()) {
            val token = sourceToken(it)
            renderInvalidatedAt[token] = now
            token + "-"
        }
        renderDir.listFiles()
            ?.filter { file -> prefixes.any(file.name::startsWith) }
            ?.forEach { it.delete() }
    }

    fun clear() {
        renderInvalidatedAt.clear()
        thumbnailDir.deleteRecursively()
        renderDir.deleteRecursively()
    }

    private fun cacheFile(
        directory: File,
        source: File,
        extension: String,
        parts: List<String>,
    ): File {
        directory.mkdirs()
        val fingerprint = buildList {
            add(CACHE_VERSION)
            add(source.name)
            add(source.length().toString())
            add(source.lastModified().toString())
            addAll(parts)
        }.joinToString("|")
        return File(directory, "${sourceToken(source.name)}-${sha256(fingerprint)}.$extension")
    }

    private fun writeBitmapAtomically(
        target: File,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): File? {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        return try {
            val encoded = temporary.outputStream().buffered().use { output ->
                bitmap.compress(format, quality, output)
            }
            if (!encoded || temporary.length() == 0L) return null
            if (!temporary.renameTo(target)) {
                if (!target.isUsable()) temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            trimAfterWrite(target)
            target.takeIf { it.isUsable() }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun decodeCachedBitmap(file: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private fun trimAfterWrite(target: File) {
        if (target.parentFile == renderDir) {
            // Full-screen PNGs are large enough that the limit must be enforced per write.
            trimToSize(renderDir, MAX_RENDER_CACHE_BYTES)
            return
        }
        if (writesSinceTrim.incrementAndGet() < TRIM_EVERY_WRITES) return
        writesSinceTrim.set(0)
        trimToSize(thumbnailDir, MAX_THUMBNAIL_CACHE_BYTES)
    }

    private fun trimToSize(directory: File, maxBytes: Long) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            val length = file.length()
            if (file.delete()) total -= length
            if (total <= maxBytes) break
        }
    }

    private fun File.isUsable(): Boolean = isFile && length() > 0L

    private fun sourceToken(storedName: String): String = storedName
        .substringBeforeLast('.')
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(64)
        .ifBlank { "media" }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val CACHE_VERSION = "v1"
        const val MIN_THUMBNAIL_SIDE = 128
        const val MAX_THUMBNAIL_SIDE = 720
        const val THUMBNAIL_QUALITY = 88
        const val TRIM_EVERY_WRITES = 16
        const val RENDER_WRITE_QUIET_PERIOD_MS = 750L
        const val MAX_THUMBNAIL_CACHE_BYTES = 96L * 1024L * 1024L
        const val MAX_RENDER_CACHE_BYTES = 256L * 1024L * 1024L
    }
}
