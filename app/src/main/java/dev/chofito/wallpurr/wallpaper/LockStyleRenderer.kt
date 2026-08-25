package dev.chofito.wallpurr.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.chofito.wallpurr.storage.LockStyle
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal fun blurBitmap(source: Bitmap, requestedRadius: Int): Bitmap {
    val radius = requestedRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS)
    return blurBitmapPixels(source, radius)
}

private fun blurBitmapPixels(source: Bitmap, radius: Int): Bitmap {
    if (radius == 0 || source.width < 2 || source.height < 2) return source
    // Keep the complete source resolution. Strong blur is more expensive this way, but it avoids
    // the enlarged low-resolution color blocks that become especially visible on tablets.
    val width = source.width
    val height = source.height
    val first = IntArray(width * height)
    val second = IntArray(first.size)
    source.getPixels(first, 0, width, 0, 0, width, height)

    // Three smaller box passes produce a smoother Gaussian approximation at the very large radii
    // used by the strongest levels, without reintroducing a low-resolution intermediate.
    val passes = if (radius >= 3) 3 else 1
    val passRadius = (radius / sqrt(passes.toFloat())).roundToInt().coerceAtLeast(1)
    repeat(passes) {
        boxBlur(first, second, width, height, passRadius, horizontal = true)
        boxBlur(second, first, width, height, passRadius, horizontal = false)
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.setPixels(first, 0, width, 0, 0, width, height)
    }
}

internal fun applyLockStyle(
    source: Bitmap,
    style: LockStyle,
    blurRadiusScale: Float = 1f,
): Bitmap {
    val clamped = style.clamped()
    if (!clamped.hasEffects) return source
    val blurred = blurLockBitmap(source, clamped, blurRadiusScale)
    if (clamped.visualDarkness <= 0.001f) return blurred
    val output = Bitmap.createBitmap(blurred.width, blurred.height, Bitmap.Config.ARGB_8888)
    Canvas(output).apply {
        drawBitmap(
            blurred,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        drawColor(Color.argb((clamped.visualDarkness * 255f).roundToInt(), 0, 0, 0))
    }
    if (blurred !== source) blurred.recycle()
    return output
}

internal fun blurLockBitmap(
    source: Bitmap,
    style: LockStyle,
    blurRadiusScale: Float,
): Bitmap {
    val clamped = style.clamped()
    val scaledBlurRadius = (clamped.visualBlurRadiusDp * blurRadiusScale)
        .roundToInt()
        .coerceIn(0, MAX_LOCK_BLUR_RADIUS_PX)
    val blurred = blurBitmapPixels(source, scaledBlurRadius)
    if (blurred !== source) applyOrderedDither(blurred)
    return blurred
}

/**
 * Broad, softly blurred gradients can expose the 8-bit steps of an ARGB wallpaper Surface. A
 * one-LSB ordered dither keeps the average color unchanged while breaking those visible bands.
 * It is intentionally applied only to lock blur output, never to the zero-effect fast path.
 */
private fun applyOrderedDither(bitmap: Bitmap) {
    if (!bitmap.isMutable || bitmap.width <= 0 || bitmap.height <= 0) return
    val width = bitmap.width
    val row = IntArray(width)
    repeat(bitmap.height) { y ->
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        repeat(width) { x ->
            val color = row[x]
            if (Color.alpha(color) != 0) {
                val matrixValue = DITHER_8X8[(y and 7) * 8 + (x and 7)]
                val offset = when {
                    matrixValue < 16 -> -1
                    matrixValue >= 48 -> 1
                    else -> 0
                }
                if (offset != 0) {
                    row[x] = Color.argb(
                        Color.alpha(color),
                        (Color.red(color) + offset).coerceIn(0, 255),
                        (Color.green(color) + offset).coerceIn(0, 255),
                        (Color.blue(color) + offset).coerceIn(0, 255),
                    )
                }
            }
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
}

private fun boxBlur(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val lineCount = if (horizontal) height else width
    val lineLength = if (horizontal) width else height
    val divisor = radius * 2 + 1
    repeat(lineCount) { line ->
        var alpha = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        fun pixel(position: Int): Int {
            val clamped = position.coerceIn(0, lineLength - 1)
            return if (horizontal) input[line * width + clamped] else input[clamped * width + line]
        }
        fun add(color: Int, direction: Int) {
            alpha += Color.alpha(color).toLong() * direction
            red += Color.red(color).toLong() * direction
            green += Color.green(color).toLong() * direction
            blue += Color.blue(color).toLong() * direction
        }
        for (position in -radius..radius) add(pixel(position), 1)
        repeat(lineLength) { position ->
            val index = if (horizontal) line * width + position else position * width + line
            output[index] = Color.argb(
                (alpha / divisor).toInt(),
                (red / divisor).toInt(),
                (green / divisor).toInt(),
                (blue / divisor).toInt(),
            )
            add(pixel(position - radius), -1)
            add(pixel(position + radius + 1), 1)
        }
    }
}

internal const val MAX_LOCK_BLUR_RADIUS_PX = 384

private val DITHER_8X8 = intArrayOf(
    0, 48, 12, 60, 3, 51, 15, 63,
    32, 16, 44, 28, 35, 19, 47, 31,
    8, 56, 4, 52, 11, 59, 7, 55,
    40, 24, 36, 20, 43, 27, 39, 23,
    2, 50, 14, 62, 1, 49, 13, 61,
    34, 18, 46, 30, 33, 17, 45, 29,
    10, 58, 6, 54, 9, 57, 5, 53,
    42, 26, 38, 22, 41, 25, 37, 21,
)
