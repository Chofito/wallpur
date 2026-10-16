package dev.chofito.wallpurr.ui.components

import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import dev.chofito.wallpurr.editor.FitMode
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.wallpaper.bindMutedVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.io.File

fun LazyGridState.fullyVisibleKeys(): List<Any> {
    val info = layoutInfo
    val viewW = info.viewportSize.width
    val viewH = info.viewportSize.height
    if (viewW <= 0 || viewH <= 0) return emptyList()
    return buildList {
        info.visibleItemsInfo.forEach { item ->
            val top = item.offset.y
            val left = item.offset.x
            if (top >= 0 &&
                left >= 0 &&
                top + item.size.height <= viewH &&
                left + item.size.width <= viewW
            ) {
                add(item.key)
            }
        }
    }
}

fun selectActiveGridMediaIds(
    fullyVisibleKeys: List<Any>,
    mediaKindsById: Map<String, MediaKind>,
    maxActive: Int,
): Set<String> = fullyVisibleKeys.asSequence()
    .mapNotNull { it as? String }
    .filter { id -> mediaKindsById[id]?.let { it != MediaKind.IMAGE } == true }
    .take(maxActive.coerceAtLeast(0))
    .toSet()

@Composable
fun rememberSettledFullyVisibleKeys(
    gridState: LazyGridState,
    enabled: Boolean,
    contentKey: Any,
): List<Any> {
    var keys by remember(gridState) { mutableStateOf(emptyList<Any>()) }
    LaunchedEffect(gridState, enabled, contentKey) {
        if (!enabled) {
            keys = emptyList()
            return@LaunchedEffect
        }
        snapshotFlow { gridState.isScrollInProgress }
            .collectLatest { scrolling ->
                if (scrolling) {
                    keys = emptyList()
                } else {
                    delay(GRID_PLAYBACK_SETTLE_MS)
                    keys = gridState.fullyVisibleKeys()
                }
            }
    }
    return keys
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MutedGridVideoView(
    file: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val path = file.absolutePath
    val player = remember(path) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.play()
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            player.pause()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }
    ContentFrame(
        player = player,
        modifier = modifier.fillMaxSize(),
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        contentScale = ContentScale.Crop,
        keepContentOnReset = true,
        shutter = {},
    )
}

@Composable
fun MutedVideoView(
    file: File,
    modifier: Modifier = Modifier,
    looping: Boolean = true,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    transform: ImageTransform = ImageTransform(fitMode = FitMode.CONTAIN),
    transparentBackground: Boolean = false,
) {
    val path = file.absolutePath
    key(path) {
        AndroidView(
            factory = { context ->
                val host = FrameLayout(context).apply {
                    setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.BLACK)
                }
                val texture = TextureView(context).apply {
                    isOpaque = false
                }
                val player = MediaPlayer()
                val state = VideoViewState(
                    player = player,
                    texture = texture,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    transform = transform,
                )
                host.tag = state
                texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> state.applyTransform() }
                player.setOnVideoSizeChangedListener { _, _, _ -> state.applyTransform() }
                texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            state.outputSurface?.release()
                            state.outputSurface = Surface(surface)
                            player.reset()
                            player.bindMutedVideo(File(path), checkNotNull(state.outputSurface), looping)
                            player.setOnPreparedListener {
                                runCatching {
                                    it.setVolume(0f, 0f)
                                    state.applyTransform()
                                    it.start()
                                }
                            }
                            player.prepareAsync()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                        state.applyTransform()
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        state.outputSurface?.release()
                        state.outputSurface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
                host.addView(
                    texture,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                host
            },
            modifier = modifier,
            update = { host ->
                val state = host.tag as? VideoViewState ?: return@AndroidView
                host.setBackgroundColor(
                    if (transparentBackground) Color.TRANSPARENT else Color.BLACK,
                )
                state.sourceWidth = sourceWidth
                state.sourceHeight = sourceHeight
                state.transform = transform
                state.applyTransform()
            },
            onRelease = { host ->
                val state = host.tag as? VideoViewState ?: return@AndroidView
                host.tag = null
                runCatching { state.player.setSurface(null) }
                runCatching {
                    state.player.reset()
                    state.player.release()
                }
                state.outputSurface?.release()
                state.outputSurface = null
            },
        )
    }
}

@Composable
fun MutedGifView(
    file: File,
    modifier: Modifier = Modifier,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
    transform: ImageTransform = ImageTransform(fitMode = FitMode.CONTAIN),
    transparentBackground: Boolean = false,
) {
    val path = file.absolutePath
    key(path) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.MATRIX
                    adjustViewBounds = false
                    setBackgroundColor(if (transparentBackground) Color.TRANSPARENT else Color.BLACK)
                    val drawable = runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path)))
                    }.getOrNull()
                    tag = GifViewState(sourceWidth, sourceHeight, transform)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val state = tag as? GifViewState ?: return@addOnLayoutChangeListener
                        applyImageTransform(state.sourceWidth, state.sourceHeight, state.transform)
                    }
                }
            },
            update = { view ->
                val state = view.tag as? GifViewState ?: return@AndroidView
                view.setBackgroundColor(
                    if (transparentBackground) Color.TRANSPARENT else Color.BLACK,
                )
                state.sourceWidth = sourceWidth
                state.sourceHeight = sourceHeight
                state.transform = transform
                view.applyImageTransform(state.sourceWidth, state.sourceHeight, state.transform)
            },
            modifier = modifier,
            onRelease = { view ->
                (view.drawable as? Animatable)?.stop()
                view.setImageDrawable(null)
                view.tag = null
            },
        )
    }
}

private class VideoViewState(
    val player: MediaPlayer,
    val texture: TextureView,
    var sourceWidth: Int,
    var sourceHeight: Int,
    var transform: ImageTransform,
) {
    var outputSurface: Surface? = null

    fun applyTransform() {
        texture.applyVideoTransform(
            contentWidth = sourceWidth.takeIf { it > 0 } ?: player.videoWidth,
            contentHeight = sourceHeight.takeIf { it > 0 } ?: player.videoHeight,
            transform = transform,
        )
    }
}

private class GifViewState(
    var sourceWidth: Int,
    var sourceHeight: Int,
    var transform: ImageTransform,
)

/** TextureView first stretches its buffer to its bounds; map that rectangle to ImageLayout. */
private fun TextureView.applyVideoTransform(
    contentWidth: Int,
    contentHeight: Int,
    transform: ImageTransform,
) {
    if (contentWidth <= 0 || contentHeight <= 0 || width <= 0 || height <= 0) return
    val layoutMatrix = ImageLayout.layout(
        imageWidth = contentWidth,
        imageHeight = contentHeight,
        viewportWidth = width.toFloat(),
        viewportHeight = height.toFloat(),
        transform = transform,
    ).toMatrix()
    val target = floatArrayOf(
        0f, 0f,
        contentWidth.toFloat(), 0f,
        0f, contentHeight.toFloat(),
        contentWidth.toFloat(), contentHeight.toFloat(),
    )
    layoutMatrix.mapPoints(target)
    val source = floatArrayOf(
        0f, 0f,
        width.toFloat(), 0f,
        0f, height.toFloat(),
        width.toFloat(), height.toFloat(),
    )
    setTransform(Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) })
}

private fun ImageView.applyImageTransform(
    sourceWidth: Int,
    sourceHeight: Int,
    transform: ImageTransform,
) {
    val drawable = drawable ?: return
    val contentWidth = sourceWidth.takeIf { it > 0 } ?: drawable.intrinsicWidth
    val contentHeight = sourceHeight.takeIf { it > 0 } ?: drawable.intrinsicHeight
    if (contentWidth <= 0 || contentHeight <= 0 || width <= 0 || height <= 0) return
    imageMatrix = ImageLayout.layout(
        imageWidth = contentWidth,
        imageHeight = contentHeight,
        viewportWidth = width.toFloat(),
        viewportHeight = height.toFloat(),
        transform = transform,
    ).toMatrix()
}

private const val GRID_PLAYBACK_SETTLE_MS = 220L
