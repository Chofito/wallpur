package dev.chofito.wallpurr.wallpaper

/** Shared visual contract for EGL and Canvas so both pipelines remain perceptually identical. */
internal object WallpaperTransitionSpec {
    /** Matches Muzei's artwork crossfade duration. */
    const val MEDIA_DURATION_MS = 750L

    /** Short enough to hide a viewport resize without making device rotation feel delayed. */
    const val ORIENTATION_DURATION_MS = 240L

    /** Bounds the one-time CPU bridge used only when a transition leaves EGL for Canvas. */
    const val CANVAS_BRIDGE_MAX_SIDE = 2_048

    const val LOW_RAM_CANVAS_BRIDGE_MAX_SIDE = 1_024
}
