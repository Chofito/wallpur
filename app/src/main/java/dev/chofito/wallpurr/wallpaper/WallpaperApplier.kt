package dev.chofito.wallpurr.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import dev.chofito.wallpurr.editor.DeviceViewport
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.storage.WallpurrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperApplier(
    private val context: Context,
    private val repository: WallpurrRepository,
) {
    fun liveWallpaperChooserIntent(): Intent {
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(context, WallpurrWallpaperService::class.java),
        )
    }

    fun isWallpurrLiveOnLock(): Boolean {
        val expected = ComponentName(context, WallpurrWallpaperService::class.java)
        return runCatching {
            val manager = WallpaperManager.getInstance(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.component == expected
            ) {
                return@runCatching true
            }

            // A shared Home+Lock live wallpaper has no lock-specific wallpaper record. Samsung
            // commonly reports null for getWallpaperInfo(FLAG_LOCK) in this valid configuration;
            // treating that as a static lock caused setBitmap(FLAG_LOCK) to race the live engine
            // on every rotation and briefly tear down the lock surface.
            val homeInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            } else {
                @Suppress("DEPRECATION")
                manager.wallpaperInfo
            }
            homeInfo?.component == expected &&
                manager.getWallpaperId(WallpaperManager.FLAG_LOCK) < 0
        }.getOrDefault(false)
    }

    fun isWallpurrLiveOnHome(): Boolean {
        val expected = ComponentName(context, WallpurrWallpaperService::class.java)
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WallpaperManager.getInstance(context)
                    .getWallpaperInfo(WallpaperManager.FLAG_SYSTEM)
            } else {
                WallpaperManager.getInstance(context).wallpaperInfo
            }
            info?.component == expected
        }.getOrDefault(false)
    }

    suspend fun applyLockStatic(imageIdOverride: String? = null): ApplyResult = withContext(Dispatchers.Default) {
        val playlistId = repository.activePlaylistId()
            ?: return@withContext ApplyResult.Error("No hay una playlist activa")
        val playlist = repository.getPlaylist(playlistId)
            ?: return@withContext ApplyResult.Error("No se encontró la playlist")
        val imageId = imageIdOverride ?: repository.ensureShuffle(playlistId).currentImageId
            ?: return@withContext ApplyResult.Error("La playlist no tiene imágenes")
        val image = repository.getImage(imageId)
            ?: return@withContext ApplyResult.Error("Falta el archivo de la imagen")
        if (!repository.fileExists(image)) {
            return@withContext ApplyResult.Error("El archivo copiado ya no está")
        }
        val orientation = currentOrientation()
        val transform = repository.getTransforms(imageId)[orientation] ?: ImageTransform.Default
        val screen = DeviceViewport.current(context)
        val width = screen.widthPx.coerceAtLeast(1)
        val height = screen.heightPx.coerceAtLeast(1)
        val decodeSide = ImageLayout.requiredDecodeMaxSide(
            imageWidth = image.width,
            imageHeight = image.height,
            viewportWidth = width,
            viewportHeight = height,
            transform = transform,
            minimumSide = MIN_BITMAP_DECODE_SIDE,
        )
        val source = repository.decodeBitmap(image, decodeSide)
        val style = repository.lockStyle(image, playlist)
        try {
            val rendered = render(source, width, height, transform)
            try {
                val blurScale = context.resources.displayMetrics.density
                val styled = applyLockStyle(rendered, style, blurScale)
                try {
                    WallpaperManager.getInstance(context).setBitmap(
                        styled,
                        null,
                        true,
                        WallpaperManager.FLAG_LOCK,
                    )
                } finally {
                    if (styled !== rendered) styled.recycle()
                }
            } finally {
                rendered.recycle()
            }
        } finally {
            source.recycle()
        }
        ApplyResult.Ok
    }

    suspend fun applyHomeStaticFallback(): ApplyResult = withContext(Dispatchers.Default) {
        val playlistId = repository.activePlaylistId()
            ?: return@withContext ApplyResult.Error("No hay una playlist activa")
        val shuffle = repository.ensureShuffle(playlistId)
        val imageId = shuffle.currentImageId
            ?: return@withContext ApplyResult.Error("La playlist no tiene imágenes")
        val image = repository.getImage(imageId)
            ?: return@withContext ApplyResult.Error("Falta el archivo de la imagen")
        val orientation = currentOrientation()
        val transform = repository.getTransforms(imageId)[orientation] ?: ImageTransform.Default
        val screen = DeviceViewport.current(context)
        val decodeSide = ImageLayout.requiredDecodeMaxSide(
            imageWidth = image.width,
            imageHeight = image.height,
            viewportWidth = screen.widthPx,
            viewportHeight = screen.heightPx,
            transform = transform,
            minimumSide = MIN_BITMAP_DECODE_SIDE,
        )
        val source = repository.decodeBitmap(image, decodeSide)
        try {
            val rendered = render(source, screen.widthPx, screen.heightPx, transform)
            try {
                WallpaperManager.getInstance(context)
                    .setBitmap(rendered, null, true, WallpaperManager.FLAG_SYSTEM)
            } finally {
                rendered.recycle()
            }
        } finally {
            source.recycle()
        }
        ApplyResult.Ok
    }

    fun render(
        source: Bitmap,
        width: Int,
        height: Int,
        transform: ImageTransform,
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val blurredBackdrop = if (
            transform.usesContainBackground &&
            transform.containBackgroundMode == dev.chofito.wallpurr.editor.ContainBackgroundMode.BLUR
        ) {
            runCatching { createBlurredContainBackdrop(source) }.getOrNull()
        } else {
            null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawContainBackdrop(blurredBackdrop, transform, paint)
        val layout = ImageLayout.layout(
            imageWidth = source.width,
            imageHeight = source.height,
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            transform = transform,
        )
        canvas.drawBitmap(source, layout.toMatrix(), paint)
        if (blurredBackdrop !== source) blurredBackdrop?.recycle()
        return out
    }

    private fun currentOrientation(): ScreenOrientation {
        val screen = DeviceViewport.current(context)
        return if (screen.widthPx < screen.heightPx) {
            ScreenOrientation.PORTRAIT
        } else {
            ScreenOrientation.LANDSCAPE
        }
    }

    private companion object {
        const val MIN_BITMAP_DECODE_SIDE = 1080
    }
}

sealed interface ApplyResult {
    data object Ok : ApplyResult
    data class Error(val message: String) : ApplyResult
}
