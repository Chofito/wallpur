package dev.chofito.wallpurr.ui.editor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.editor.FitMode
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.LockStyle
import dev.chofito.wallpurr.storage.VideoPlayMode
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.wallpaper.RotationController
import dev.chofito.wallpurr.wallpaper.createBlurredContainBackdrop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val displayName: String = "",
    val orientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    val transform: ImageTransform = ImageTransform.Default,
    val bitmap: Bitmap? = null,
    val containBackdrop: Bitmap? = null,
    val paletteColors: List<Int> = emptyList(),
    val mediaKind: MediaKind = MediaKind.IMAGE,
    val storedName: String = "",
    val videoPlayMode: VideoPlayMode = VideoPlayMode.TIMES,
    val videoPlayCount: Int = 1,
    val videoPlayMinutes: Int = 1,
    val lockStyleOverride: Boolean = false,
    val lockBlurRadius: Int = 0,
    val lockOpacity: Float = 1f,
    val inheritedLockBlurRadius: Int = 0,
    val inheritedLockOpacity: Float = 1f,
    val hasPlaylistContext: Boolean = false,
    val loadFailed: Boolean = false,
)

class EditorViewModel(
    private val repository: WallpurrRepository,
    private val rotation: RotationController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val imageId: String = checkNotNull(savedStateHandle["imageId"])
    val sourcePlaylistId = savedStateHandle.get<String>("playlistId").orEmpty()
    private val startOrientation = savedStateHandle.get<String>("orientation")
        ?.let { runCatching { ScreenOrientation.valueOf(it) }.getOrNull() }
        ?: ScreenOrientation.PORTRAIT
    val state = MutableStateFlow(EditorUiState(orientation = startOrientation))
    private var portrait = ImageTransform.Default
    private var landscape = ImageTransform.Default
    // Until the stored profiles are read, portrait/landscape/state hold defaults. Any save before
    // that point (e.g. Back pressed during the initial load) would overwrite the user's framing.
    private var loaded = false
    private var saveJob: Job? = null
    private var lockStyleJob: Job? = null
    private var videoJob: Job? = null
    private var viewportWidth = 0f
    private var viewportHeight = 0f

    init {
        viewModelScope.launch {
            val image = repository.getImage(imageId) ?: return@launch
            val sourcePlaylist = sourcePlaylistId.takeIf { it.isNotBlank() }
                ?.let { repository.getPlaylist(it) }
            val transforms = repository.getTransforms(imageId)
            portrait = transforms[ScreenOrientation.PORTRAIT] ?: ImageTransform.Default
            landscape = transforms[ScreenOrientation.LANDSCAPE] ?: ImageTransform.Default
            // A corrupt file must not crash the app; `loaded` stays false so nothing is saved.
            val bmp = try {
                withContext(Dispatchers.Default) { repository.decodeBitmap(image, 2048) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.update { it.copy(loadFailed = true) }
                return@launch
            }
            val (palette, backdrop) = withContext(Dispatchers.Default) {
                extractPalette(bmp) to runCatching {
                    createBlurredContainBackdrop(bmp)
                }.getOrNull()
            }
            state.value = EditorUiState(
                displayName = image.displayName,
                orientation = startOrientation,
                transform = if (startOrientation == ScreenOrientation.PORTRAIT) portrait else landscape,
                bitmap = bmp,
                containBackdrop = backdrop,
                paletteColors = palette,
                mediaKind = MediaKind.from(image.mediaType),
                storedName = image.storedName,
                videoPlayMode = VideoPlayMode.from(image.videoPlayMode),
                videoPlayCount = image.videoPlayCount.coerceIn(1, 99),
                videoPlayMinutes = image.videoPlayMinutes.coerceIn(1, 180),
                lockStyleOverride = image.lockStyleOverride,
                lockBlurRadius = image.lockBlurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS),
                lockOpacity = image.lockOpacity.coerceIn(LockStyle.MIN_OPACITY, 1f),
                inheritedLockBlurRadius = sourcePlaylist?.lockBlurRadius
                    ?.coerceIn(0, LockStyle.MAX_BLUR_RADIUS) ?: 0,
                inheritedLockOpacity = sourcePlaylist?.lockOpacity
                    ?.coerceIn(LockStyle.MIN_OPACITY, 1f) ?: 1f,
                hasPlaylistContext = sourcePlaylist != null,
            )
            loaded = true
        }
    }

    fun setOrientation(orientation: ScreenOrientation) {
        state.update {
            it.copy(
                orientation = orientation,
                transform = if (orientation == ScreenOrientation.PORTRAIT) portrait else landscape,
            )
        }
    }

    fun update(transform: ImageTransform, persistNow: Boolean = false) {
        if (!loaded) return
        val current = state.value
        val clamped = current.bitmap?.let { bitmap ->
            if (viewportWidth > 0f && viewportHeight > 0f) {
                ImageLayout.clampToViewport(
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    transform = transform,
                )
            } else {
                transform.clamped()
            }
        } ?: transform.clamped()
        if (clamped == current.transform) return
        if (current.orientation == ScreenOrientation.PORTRAIT) portrait = clamped else landscape = clamped
        state.update { it.copy(transform = clamped) }
        val editedOrientation = current.orientation
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (!persistNow) delay(180)
            persistTransform(editedOrientation, clamped)
        }
    }

    fun setViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (viewportWidth == width && viewportHeight == height) return
        viewportWidth = width
        viewportHeight = height
        update(state.value.transform)
    }

    fun gesture(zoom: Float, panX: Float, panY: Float, viewportWidth: Float, viewportHeight: Float) {
        this.viewportWidth = viewportWidth
        this.viewportHeight = viewportHeight
        val current = state.value.transform
        update(
            current.copy(
                zoom = current.zoom * zoom,
                offsetX = current.offsetX + panX / (viewportWidth / 2f),
                offsetY = current.offsetY + panY / (viewportHeight / 2f),
            ),
        )
    }

    fun setFit(mode: FitMode) {
        update(state.value.transform.copy(fitMode = mode), persistNow = true)
    }

    fun setContainBackgroundEnabled(enabled: Boolean) {
        val current = state.value
        update(
            current.transform.copy(
                containBackgroundMode = if (enabled) {
                    ContainBackgroundMode.COLOR
                } else {
                    ContainBackgroundMode.NONE
                },
                containBackgroundColor = current.paletteColors.firstOrNull()
                    ?: current.transform.containBackgroundColor,
            ),
            persistNow = true,
        )
    }

    fun setContainBackgroundMode(mode: ContainBackgroundMode) {
        update(state.value.transform.copy(containBackgroundMode = mode), persistNow = true)
    }

    fun setContainBackgroundColor(color: Int) {
        update(
            state.value.transform.copy(
                containBackgroundMode = ContainBackgroundMode.COLOR,
                containBackgroundColor = color,
            ),
            persistNow = true,
        )
    }

    fun setContainBackgroundHex(value: String): Boolean {
        val normalized = value.trim().removePrefix("#")
        if (normalized.length != 6 || normalized.any { it.digitToIntOrNull(16) == null }) {
            return false
        }
        setContainBackgroundColor(Color.parseColor("#$normalized"))
        return true
    }

    fun setRotation(degrees: Float) {
        update(state.value.transform.copy(rotationDegrees = degrees))
    }

    fun resetRotation() {
        update(state.value.transform.copy(rotationDegrees = 0f), persistNow = true)
    }

    fun toggleZoom() {
        val zoom = state.value.transform.zoom
        update(state.value.transform.copy(zoom = if (zoom < 1.5f) 2f else 1f))
    }

    fun commitTransform(onCommitted: () -> Unit = {}) {
        if (!loaded) {
            onCommitted()
            return
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // Persist both profiles so switching orientation during the debounce cannot cancel the
            // last edit made to the profile that was just left.
            repository.saveTransform(imageId, ScreenOrientation.PORTRAIT, portrait)
            repository.saveTransform(imageId, ScreenOrientation.LANDSCAPE, landscape)
            refreshIfCurrent(visualOnly = true)
            onCommitted()
        }
    }

    fun setVideoPlayMode(mode: VideoPlayMode) {
        state.update { it.copy(videoPlayMode = mode) }
        persistVideo(persistNow = true)
    }

    fun setVideoPlayCount(count: Int) {
        state.update { it.copy(videoPlayCount = count.coerceIn(1, 99)) }
        persistVideo()
    }

    fun setVideoPlayMinutes(minutes: Int) {
        state.update { it.copy(videoPlayMinutes = minutes.coerceIn(1, 180)) }
        persistVideo()
    }

    override fun onCleared() {
        saveJob?.cancel()
        lockStyleJob?.cancel()
        videoJob?.cancel()
        state.value.containBackdrop?.takeUnless { it.isRecycled }?.recycle()
        state.value.bitmap?.takeUnless { it.isRecycled }?.recycle()
        super.onCleared()
    }

    fun setLockStyleOverride(enabled: Boolean) {
        state.update { it.copy(lockStyleOverride = enabled) }
        persistLockStyle()
    }

    fun setLockStyle(blurRadius: Int, opacity: Float) {
        state.update {
            it.copy(
                lockBlurRadius = blurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS),
                lockOpacity = opacity.coerceIn(LockStyle.MIN_OPACITY, 1f),
            )
        }
    }

    fun commitLockStyle() = persistLockStyle()

    private fun persistVideo(persistNow: Boolean = false) {
        if (!loaded) return
        videoJob?.cancel()
        videoJob = viewModelScope.launch {
            if (!persistNow) delay(180)
            val current = state.value
            repository.updateVideoPlayback(
                imageId,
                current.videoPlayMode,
                current.videoPlayCount,
                current.videoPlayMinutes,
            )
            refreshIfCurrent()
        }
    }

    private fun persistLockStyle() {
        if (!loaded) return
        lockStyleJob?.cancel()
        lockStyleJob = viewModelScope.launch {
            val current = state.value
            repository.updateImageLockStyle(
                imageId = imageId,
                overridePlaylist = current.lockStyleOverride,
                blurRadius = current.lockBlurRadius,
                opacity = current.lockOpacity,
            )
            refreshIfCurrent()
        }
    }

    private suspend fun persistTransform(
        orientation: ScreenOrientation,
        transform: ImageTransform,
    ) {
        repository.saveTransform(imageId, orientation, transform)
        refreshIfCurrent(visualOnly = true)
    }

    private suspend fun refreshIfCurrent(visualOnly: Boolean = false) {
        val activePlaylistId = repository.activePlaylistId()
        if (activePlaylistId != null &&
            repository.ensureShuffle(activePlaylistId).currentImageId == imageId
        ) {
            if (visualOnly) {
                rotation.refreshCurrentVisuals()
            } else {
                rotation.applyCurrentAndNotify()
            }
        }
    }

}

private fun extractPalette(bitmap: Bitmap, count: Int = 5): List<Int> {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
        return List(count) { ImageTransform.DEFAULT_CONTAIN_BACKGROUND_COLOR }
    }
    data class Bucket(var hits: Int = 0, var red: Long = 0, var green: Long = 0, var blue: Long = 0)
    val buckets = mutableMapOf<Int, Bucket>()
    val stride = kotlin.math.sqrt(
        (bitmap.width.toLong() * bitmap.height / 12_000.0).coerceAtLeast(1.0),
    ).toInt().coerceAtLeast(1)
    val row = IntArray(bitmap.width)
    val edgeBandX = (bitmap.width / 8).coerceAtLeast(1)
    val edgeBandY = (bitmap.height / 8).coerceAtLeast(1)
    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val color = row[x]
            if (Color.alpha(color) >= 160) {
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                // Colors near the image boundary make the least noticeable continuation into
                // letterbox/pillarbox space, so give them more influence than the center.
                val weight = if (
                    x < edgeBandX || x >= bitmap.width - edgeBandX ||
                    y < edgeBandY || y >= bitmap.height - edgeBandY
                ) {
                    3
                } else {
                    1
                }
                val key = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
                buckets.getOrPut(key, ::Bucket).apply {
                    hits += weight
                    this.red += red.toLong() * weight
                    this.green += green.toLong() * weight
                    this.blue += blue.toLong() * weight
                }
            }
            x += stride
        }
        y += stride
    }
    val ranked = buckets.values
        .sortedByDescending(Bucket::hits)
        .map { bucket ->
            Color.rgb(
                (bucket.red / bucket.hits).toInt(),
                (bucket.green / bucket.hits).toInt(),
                (bucket.blue / bucket.hits).toInt(),
            )
        }
    val selected = mutableListOf<Int>()
    ranked.forEach { candidate ->
        if (selected.size == count) return@forEach
        if (selected.all { colorDistance(it, candidate) >= 52 }) selected += candidate
    }
    ranked.forEach { candidate ->
        if (selected.size < count && candidate !in selected) selected += candidate
    }
    val base = selected.firstOrNull() ?: ImageTransform.DEFAULT_CONTAIN_BACKGROUND_COLOR
    val hsv = FloatArray(3)
    Color.colorToHSV(base, hsv)
    while (selected.size < count) {
        val index = selected.size
        val adjusted = hsv.copyOf().also {
            it[1] = (it[1] * (0.72f + index * 0.06f)).coerceIn(0f, 1f)
            it[2] = (0.24f + index * 0.16f).coerceIn(0f, 1f)
        }
        val candidate = Color.HSVToColor(adjusted)
        selected += candidate
    }
    return selected.take(count)
}

private fun colorDistance(first: Int, second: Int): Int =
    kotlin.math.abs(Color.red(first) - Color.red(second)) +
        kotlin.math.abs(Color.green(first) - Color.green(second)) +
        kotlin.math.abs(Color.blue(first) - Color.blue(second))
