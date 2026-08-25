package dev.chofito.wallpurr.storage

data class LockStyle(
    val blurRadius: Int = 0,
    val opacity: Float = 1f,
) {
    val darkness: Float
        get() = (1f - opacity).coerceIn(0f, MAX_DARKNESS)

    /**
     * The persisted value is an intensity level, not a literal dp radius. Levels 0–10 preserve the
     * original curve. Levels 11–20 deliberately become much steeper so their broad color fields
     * dissolve recognizable objects without making the useful low end too sensitive.
     */
    val visualBlurRadiusDp: Float
        get() {
            val level = blurRadius.coerceIn(0, MAX_BLUR_RADIUS)
            if (level <= BASE_MAX_BLUR_RADIUS) {
                val fraction = level / BASE_MAX_BLUR_RADIUS.toFloat()
                return BASE_MAX_VISUAL_BLUR_RADIUS_DP * fraction * fraction
            }
            val extension = (level - BASE_MAX_BLUR_RADIUS) /
                (MAX_BLUR_RADIUS - BASE_MAX_BLUR_RADIUS).toFloat()
            return BASE_MAX_VISUAL_BLUR_RADIUS_DP +
                (MAX_VISUAL_BLUR_RADIUS_DP - BASE_MAX_VISUAL_BLUR_RADIUS_DP) * extension
        }

    /** One UI combines blur with a light/dark dim layer instead of using blur in isolation. */
    val visualDarkness: Float
        get() {
            val level = blurRadius.coerceIn(0, MAX_BLUR_RADIUS)
            val ambientVeil = BASE_MAX_AMBIENT_VEIL *
                (level / BASE_MAX_BLUR_RADIUS.toFloat()).coerceAtMost(1f)
            return (1f - (1f - darkness) * (1f - ambientVeil)).coerceIn(0f, 1f)
        }

    val hasEffects: Boolean
        get() = blurRadius > 0 || darkness > EFFECT_EPSILON

    fun clamped(): LockStyle = copy(
        blurRadius = blurRadius.coerceIn(0, MAX_BLUR_RADIUS),
        opacity = opacity.coerceIn(MIN_OPACITY, 1f),
    )

    companion object {
        const val MAX_BLUR_RADIUS = 20
        const val MAX_VISUAL_BLUR_RADIUS_DP = 100f
        const val MIN_OPACITY = 0.8f
        const val MAX_DARKNESS = 1f - MIN_OPACITY
        private const val BASE_MAX_BLUR_RADIUS = 10
        private const val BASE_MAX_VISUAL_BLUR_RADIUS_DP = 22f
        private const val BASE_MAX_AMBIENT_VEIL = 0.08f
        private const val EFFECT_EPSILON = 0.001f
        val None = LockStyle()
    }
}
