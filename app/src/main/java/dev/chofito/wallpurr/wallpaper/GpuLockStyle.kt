package dev.chofito.wallpurr.wallpaper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import dev.chofito.wallpurr.storage.LockStyle
import kotlin.math.roundToInt

private const val MIN_GPU_BLUR_RADIUS_PX = 0.5f

/**
 * Applies the static lock style (blur + darkness) on the GPU into an offscreen surface, using the
 * same RenderEffect blur as the live engine. Returns a software ARGB_8888 bitmap, or null if the
 * GPU path is unavailable so callers can fall back to [applyLockStyle]. [source] is not recycled.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun applyLockStyleGpu(
    source: Bitmap,
    style: LockStyle,
    blurRadiusScale: Float,
): Bitmap? {
    val clamped = style.clamped()
    val width = source.width
    val height = source.height
    if (width < 2 || height < 2 || source.isRecycled) return null
    // Same formula as the live engine (visualBlurRadiusDp * density, capped).
    val radiusPx = minOf(
        clamped.visualBlurRadiusDp * blurRadiusScale,
        MAX_LOCK_BLUR_RADIUS_PX.toFloat(),
    )
    var reader: ImageReader? = null
    var renderer: HardwareRenderer? = null
    var root: RenderNode? = null
    var blurNode: RenderNode? = null
    var image: Image? = null
    var buffer: HardwareBuffer? = null
    var hardwareBitmap: Bitmap? = null
    try {
        val imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        reader = imageReader
        val blur = RenderNode("WallpurrLockStyleStatic").apply {
            setPosition(0, 0, width, height)
            setUseCompositingLayer(true, null)
            if (radiusPx > 0f) {
                val radius = radiusPx.coerceAtLeast(MIN_GPU_BLUR_RADIUS_PX)
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
        }
        blurNode = blur
        val blurCanvas = blur.beginRecording(width, height)
        try {
            blurCanvas.drawBitmap(source, 0f, 0f, null)
        } finally {
            blur.endRecording()
        }
        val rootNode = RenderNode("WallpurrLockStyleRoot").apply { setPosition(0, 0, width, height) }
        root = rootNode
        val rootCanvas = rootNode.beginRecording(width, height)
        try {
            rootCanvas.drawRenderNode(blur)
            if (clamped.visualDarkness > 0.001f) {
                rootCanvas.drawColor(
                    Color.argb((clamped.visualDarkness * 255f).roundToInt(), 0, 0, 0),
                )
            }
        } finally {
            rootNode.endRecording()
        }
        val hw = HardwareRenderer()
        renderer = hw
        hw.setContentRoot(rootNode)
        hw.setSurface(imageReader.surface)
        hw.isOpaque = true
        hw.start()
        val frame = hw.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        if (frame != HardwareRenderer.SYNC_OK) return null
        val acquired = imageReader.acquireLatestImage() ?: return null
        image = acquired
        val hardwareBuffer = acquired.hardwareBuffer ?: return null
        buffer = hardwareBuffer
        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, null) ?: return null
        hardwareBitmap = wrapped
        val output = wrapped.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        applyOrderedDither(output)
        return output
    } catch (_: Throwable) {
        return null
    } finally {
        hardwareBitmap?.recycle()
        runCatching { buffer?.close() }
        runCatching { image?.close() }
        runCatching { renderer?.destroy() }
        runCatching { root?.discardDisplayList() }
        runCatching { blurNode?.discardDisplayList() }
        runCatching { reader?.close() }
    }
}
