package dev.chofito.wallpurr.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import kotlin.math.roundToInt

internal fun createBlurredContainBackdrop(source: Bitmap): Bitmap {
    val largest = maxOf(source.width, source.height).coerceAtLeast(1)
    val scale = minOf(1f, CONTAIN_BACKDROP_MAX_SIDE / largest.toFloat())
    val working = if (scale < 0.999f) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(2),
            (source.height * scale).roundToInt().coerceAtLeast(2),
            true,
        )
    } else {
        source
    }
    val blurred = blurBitmap(working, CONTAIN_BACKDROP_BLUR_RADIUS)
    val output = if (blurred === source) {
        checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
    } else {
        blurred
    }
    if (working !== source && working !== output && !working.isRecycled) working.recycle()
    return output
}

internal fun Canvas.drawContainBackdrop(
    blurredSource: Bitmap?,
    transform: ImageTransform,
    paint: Paint,
) {
    if (!transform.usesContainBackground) return
    when (transform.containBackgroundMode) {
        ContainBackgroundMode.NONE -> Unit
        ContainBackgroundMode.COLOR -> drawColor(transform.containBackgroundColor)
        ContainBackgroundMode.BLUR -> {
            // The selected/dominant color is the stable fallback while the derived bitmap is
            // being prepared or if a decoder cannot provide a representative frame.
            drawColor(transform.containBackgroundColor)
            val source = blurredSource?.takeUnless { it.isRecycled } ?: return
            val layout = ImageLayout.layout(
                imageWidth = source.width,
                imageHeight = source.height,
                viewportWidth = width.toFloat(),
                viewportHeight = height.toFloat(),
                transform = transform.containBackdropTransform(),
            )
            drawBitmap(source, layout.toMatrix(), paint)
        }
    }
}

internal const val CONTAIN_BACKDROP_MAX_SIDE = 720
private const val CONTAIN_BACKDROP_BLUR_RADIUS = 10
