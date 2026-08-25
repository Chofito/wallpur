package dev.chofito.wallpurr.editor

import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawTransform
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class FitMode {
    CONTAIN,
    COVER,
    STRETCH,
    ;

    companion object {
        fun from(value: String?): FitMode =
            entries.firstOrNull { it.name == value } ?: COVER
    }
}

enum class ContainBackgroundMode {
    NONE,
    COLOR,
    BLUR,
    ;

    companion object {
        fun from(value: String?): ContainBackgroundMode =
            entries.firstOrNull { it.name == value } ?: NONE
    }
}

enum class ScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
}

data class ImageTransform(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val fitMode: FitMode = FitMode.COVER,
    val rotationDegrees: Float = 0f,
    val containBackgroundMode: ContainBackgroundMode = ContainBackgroundMode.NONE,
    val containBackgroundColor: Int = DEFAULT_CONTAIN_BACKGROUND_COLOR,
) {
    fun clamped(): ImageTransform = copy(
        zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM),
        rotationDegrees = normalizeRotation(rotationDegrees),
        containBackgroundColor = containBackgroundColor or OPAQUE_ALPHA_MASK,
    )

    val usesContainBackground: Boolean
        get() = fitMode == FitMode.CONTAIN && containBackgroundMode != ContainBackgroundMode.NONE

    fun containBackdropTransform(): ImageTransform = copy(
        zoom = CONTAIN_BACKDROP_ZOOM,
        offsetX = 0f,
        offsetY = 0f,
        fitMode = FitMode.COVER,
        containBackgroundMode = ContainBackgroundMode.NONE,
    )

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val CONTAIN_BACKDROP_ZOOM = 1.25f
        const val DEFAULT_CONTAIN_BACKGROUND_COLOR = -0x1000000
        private const val OPAQUE_ALPHA_MASK = -0x1000000
        val Default = ImageTransform()
    }
}

fun normalizeRotation(degrees: Float): Float {
    var value = degrees % 360f
    if (value > 180f) value -= 360f
    if (value < -180f) value += 360f
    return value
}

data class LayoutResult(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
    val rotationDegrees: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f,
) {
    fun toMatrix(): Matrix = Matrix().apply {
        setScale(scaleX, scaleY)
        if (rotationDegrees % 360f != 0f) {
            postRotate(rotationDegrees, pivotX, pivotY)
        }
        postTranslate(translateX, translateY)
    }
}

fun DrawTransform.applyLayout(layout: LayoutResult) {
    translate(layout.translateX, layout.translateY)
    if (layout.rotationDegrees % 360f != 0f) {
        rotate(layout.rotationDegrees, Offset(layout.pivotX, layout.pivotY))
    }
    scale(layout.scaleX, layout.scaleY, pivot = Offset.Zero)
}

object ImageLayout {
    /**
     * Largest source dimension needed to render this layout without decoding unused pixels.
     * The result grows for aspect-ratio mismatch, rotation and user zoom, and never exceeds the
     * original media dimensions.
     */
    fun requiredDecodeMaxSide(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transform: ImageTransform,
        minimumSide: Int = 1,
    ): Int {
        if (imageWidth <= 0 || imageHeight <= 0) {
            return max(viewportWidth, viewportHeight).coerceAtLeast(minimumSide)
        }
        val sourceMax = max(imageWidth, imageHeight)
        val layout = layout(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            viewportWidth = viewportWidth.coerceAtLeast(1).toFloat(),
            viewportHeight = viewportHeight.coerceAtLeast(1).toFloat(),
            transform = transform,
        )
        val requiredMax = ceil(
            max(
                imageWidth * layout.scaleX,
                imageHeight * layout.scaleY,
            ).toDouble(),
        ).toInt()
        return min(sourceMax, max(requiredMax, minimumSide)).coerceAtLeast(1)
    }

    fun layout(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: ImageTransform,
    ): LayoutResult {
        if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return LayoutResult(1f, 1f, 0f, 0f)
        }
        val t = clampToViewport(imageWidth, imageHeight, viewportWidth, viewportHeight, transform)
        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()
        val (scaleX, scaleY) = scaledSize(imgW, imgH, viewportWidth, viewportHeight, t)
        val drawnW = imgW * scaleX
        val drawnH = imgH * scaleY
        val centerX = (viewportWidth - drawnW) / 2f
        val centerY = (viewportHeight - drawnH) / 2f
        return LayoutResult(
            scaleX = scaleX,
            scaleY = scaleY,
            translateX = centerX + t.offsetX * viewportWidth / 2f,
            translateY = centerY + t.offsetY * viewportHeight / 2f,
            rotationDegrees = t.rotationDegrees,
            pivotX = drawnW / 2f,
            pivotY = drawnH / 2f,
        )
    }

    fun clampToViewport(
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: ImageTransform,
    ): ImageTransform {
        val t = transform.clamped()
        if (imageWidth <= 0 || imageHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
            return t
        }
        val (scaleX, scaleY) = scaledSize(
            imageWidth.toFloat(),
            imageHeight.toFloat(),
            viewportWidth,
            viewportHeight,
            t,
        )
        val drawnW = imageWidth * scaleX
        val drawnH = imageHeight * scaleY
        val (boxW, boxH) = aabb(drawnW, drawnH, t.rotationDegrees)
        val maxOffsetX = ((boxW - viewportWidth) / viewportWidth).coerceAtLeast(0f)
        val maxOffsetY = ((boxH - viewportHeight) / viewportHeight).coerceAtLeast(0f)
        return t.copy(
            offsetX = t.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
            offsetY = t.offsetY.coerceIn(-maxOffsetY, maxOffsetY),
        )
    }

    private fun scaledSize(
        imgW: Float,
        imgH: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        transform: ImageTransform,
    ): Pair<Float, Float> {
        val (boxW, boxH) = aabb(imgW, imgH, transform.rotationDegrees)
        val (baseScaleX, baseScaleY) = when (transform.fitMode) {
            FitMode.CONTAIN -> {
                val scale = min(viewportWidth / boxW, viewportHeight / boxH)
                scale to scale
            }
            FitMode.COVER -> {
                val scale = max(viewportWidth / boxW, viewportHeight / boxH)
                scale to scale
            }
            FitMode.STRETCH -> {
                (viewportWidth / imgW) to (viewportHeight / imgH)
            }
        }
        return (baseScaleX * transform.zoom) to (baseScaleY * transform.zoom)
    }

    private fun aabb(width: Float, height: Float, degrees: Float): Pair<Float, Float> {
        val r = Math.toRadians(degrees.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        return (width * c + height * s) to (width * s + height * c)
    }
}
