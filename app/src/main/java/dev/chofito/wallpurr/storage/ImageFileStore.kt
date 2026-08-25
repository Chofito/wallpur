package dev.chofito.wallpurr.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class ImageFileStore(private val context: Context) {
    val imagesDir: File
        get() = File(context.filesDir, "images").also { it.mkdirs() }

    fun file(storedName: String): File {
        require(
            storedName.isNotBlank() &&
                storedName != "." &&
                storedName != ".." &&
                '/' !in storedName &&
                '\\' !in storedName,
        ) { "Nombre de archivo no válido" }
        return File(imagesDir, storedName)
    }

    suspend fun importFromUri(uri: Uri, imageId: String): ImportedImage = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val kind = kindFor(mime, uri)
        val ext = extensionFor(mime, kind)
        val dest = file("$imageId.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo leer el archivo")
            val meta = readMeta(dest, kind)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: dest.name
            val actualKind = if (kind == MediaKind.IMAGE && meta.isAnimated) {
                MediaKind.GIF
            } else {
                kind
            }
            ImportedImage(
                storedName = dest.name,
                displayName = name,
                width = meta.width,
                height = meta.height,
                mediaType = actualKind,
                durationMs = meta.durationMs,
            )
        } catch (error: Throwable) {
            dest.delete()
            throw error
        }
    }

    fun importFromStream(input: InputStream, storedName: String): File {
        val dest = file(storedName)
        return try {
            dest.outputStream().use { output -> input.copyTo(output) }
            dest
        } catch (error: Throwable) {
            dest.delete()
            throw error
        }
    }

    fun delete(storedName: String) {
        file(storedName).delete()
    }

    fun clearAll() {
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    fun decodeBitmap(file: File, maxSide: Int, kind: MediaKind = kindFromName(file.name)): Bitmap {
        return if (kind == MediaKind.VIDEO) {
            decodeVideoFrame(file, maxSide)
        } else {
            val source = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h).toFloat()
                if (longest > maxSide) {
                    val scale = maxSide / longest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }

    fun decodeVideoFrame(file: File, maxSide: Int, timeUs: Long = 0L): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val rawWidth = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                    ?: 0
                val rawHeight = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                    ?: 0
                val longest = maxOf(rawWidth, rawHeight)
                if (longest > maxSide && maxSide > 0) {
                    val scale = maxSide / longest.toFloat()
                    val targetWidth = (rawWidth * scale).toInt().coerceAtLeast(1)
                    val targetHeight = (rawHeight * scale).toInt().coerceAtLeast(1)
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetWidth,
                            targetHeight,
                        )
                    }.getOrNull()
                } else {
                    null
                }
            } else {
                null
            }
            if (scaled != null) return scaled
            val raw = runCatching {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: runCatching {
                retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull() ?: error("No se pudo leer el video")
            scaleDown(raw, maxSide)
        } finally {
            retriever.release()
        }
    }

    private fun readMeta(file: File, kind: MediaKind): MediaMeta {
        return if (kind == MediaKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val swapped = rotation == 90 || rotation == 270
                MediaMeta(
                    width = if (swapped) height else width,
                    height = if (swapped) width else height,
                    durationMs = duration,
                )
            } finally {
                retriever.release()
            }
        } else {
            val source = ImageDecoder.createSource(file)
            var width = 0
            var height = 0
            var isAnimated = false
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                isAnimated = info.isAnimated
                decoder.setTargetSize(1, 1)
            }.recycle()
            MediaMeta(width, height, 0L, isAnimated)
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    private fun kindFor(mime: String, uri: Uri): MediaKind {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            mime.startsWith("video") || name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") -> MediaKind.VIDEO
            mime.contains("gif") || name.endsWith(".gif") -> MediaKind.GIF
            else -> MediaKind.IMAGE
        }
    }

    private fun kindFromName(name: String): MediaKind {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".gif") -> MediaKind.GIF
            lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".3gp") -> MediaKind.VIDEO
            else -> MediaKind.IMAGE
        }
    }

    private fun extensionFor(mime: String, kind: MediaKind): String = when (kind) {
        MediaKind.VIDEO -> when {
            mime.contains("webm") -> "webm"
            mime.contains("mkv") -> "mkv"
            else -> "mp4"
        }
        MediaKind.GIF -> "gif"
        MediaKind.IMAGE -> when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("heic") || mime.contains("heif") -> "heic"
            else -> "jpg"
        }
    }
}

data class ImportedImage(
    val storedName: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val mediaType: MediaKind = MediaKind.IMAGE,
    val durationMs: Long = 0L,
)

private data class MediaMeta(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isAnimated: Boolean = false,
)
