package dev.chofito.wallpurr.editor

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

data class DeviceScreen(
    val widthPx: Int,
    val heightPx: Int,
) {
    val shortSide: Int get() = min(widthPx, heightPx)
    val longSide: Int get() = max(widthPx, heightPx)

    fun aspectRatio(orientation: ScreenOrientation): Float {
        return if (orientation == ScreenOrientation.PORTRAIT) {
            shortSide.toFloat() / longSide.toFloat()
        } else {
            longSide.toFloat() / shortSide.toFloat()
        }
    }

    fun fittedSize(availableWidth: Float, availableHeight: Float, orientation: ScreenOrientation): Pair<Float, Float> {
        val aspect = aspectRatio(orientation)
        val heightIfFullWidth = availableWidth / aspect
        return if (heightIfFullWidth <= availableHeight) {
            availableWidth to heightIfFullWidth
        } else {
            (availableHeight * aspect) to availableHeight
        }
    }

    fun resolutionLabel(orientation: ScreenOrientation): String {
        return if (orientation == ScreenOrientation.PORTRAIT) {
            "$shortSide × $longSide"
        } else {
            "$longSide × $shortSide"
        }
    }
}

object DeviceViewport {
    fun current(context: Context): DeviceScreen {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            DeviceScreen(bounds.width().coerceAtLeast(1), bounds.height().coerceAtLeast(1))
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            DeviceScreen(
                metrics.widthPixels.coerceAtLeast(1),
                metrics.heightPixels.coerceAtLeast(1),
            )
        }
    }
}
