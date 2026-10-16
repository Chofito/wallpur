package dev.chofito.wallpurr.wallpaper

import android.animation.ValueAnimator
import android.graphics.SurfaceTexture
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.animation.AccelerateDecelerateInterpolator
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.editor.ScreenOrientation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Receives decoded video frames in an external GL texture and draws them onto the wallpaper
 * surface using the same [ImageLayout] contract as still images and animated images.
 *
 * All EGL and SurfaceTexture operations stay on one dedicated thread. The input [Surface]
 * handed to MediaPlayer belongs to this renderer and must not be released by the caller.
 */
internal class TransformedVideoRenderer(
    private val outputSurface: Surface,
    private val callbackHandler: Handler,
    contentWidth: Int,
    contentHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    transform: ImageTransform,
    private var initialFrame: android.graphics.Bitmap? = null,
    private var initialBackdrop: android.graphics.Bitmap? = null,
    private var transitionFromFrame: android.graphics.Bitmap? = null,
    transitionsInitiallyEnabled: Boolean = true,
    private val onReady: (Surface) -> Unit,
    private val onFirstVideoFramePresented: (Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("WallpurrVideoGL").apply { start() }
    private val renderHandler = Handler(thread.looper)
    private val released = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var textureId = 0
    private var maxTextureSize = 0
    private var program = 0
    private var initialFrameTextureId = 0
    private var backdropTextureId = 0
    private var initialFrameProgram = 0
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var textureMatrixLocation = -1
    private var textureSamplerLocation = -1
    private var initialPositionLocation = -1
    private var initialTextureCoordinateLocation = -1
    private var initialTextureMatrixLocation = -1
    private var initialTextureSamplerLocation = -1
    private var initialAlphaLocation = -1
    private var awaitingFirstVideoFrameSession = NO_VIDEO_SESSION
    private var renderVideoFrames = false
    private var hasLatchedVideoFrame = false
    private var viewportReconcileGeneration = 0L
    private var viewportWatchUntilMs = 0L
    private var transitionTextureId = 0
    private var transitionTextureWidth = 0
    private var transitionTextureHeight = 0
    private var transitionTargetTextureId = 0
    private var transitionTargetTextureWidth = 0
    private var transitionTargetTextureHeight = 0
    private var transitionStartedAtMs = 0L
    private var transitionDurationMs = 0L
    private var transitionWaitingForTargetFrame = false
    private var transitionFrameScheduled = false
    private var transitionTargetDirty = false
    private var hasPresentedContent = false
    private var transitionsEnabled = transitionsInitiallyEnabled
    private var layoutChangePending = false
    private var layoutHoldingViewportWidth = 0
    private var layoutHoldingViewportHeight = 0
    private var layoutTargetReady = false
    private var layoutTargetViewportWidth = 0
    private var layoutTargetViewportHeight = 0
    private var layoutTargetOrientation: ScreenOrientation? = null
    private var layoutTargetReadyAtMs = 0L
    private var choreographer: Choreographer? = null
    private val transitionInterpolator = AccelerateDecelerateInterpolator()

    private var contentWidth = contentWidth
    private var contentHeight = contentHeight
    // ImageLayout must only see the committed logical viewport supplied by the engine. EGL can
    // expose the next (or previous) window-buffer size for a few callbacks during tablet rotation;
    // feeding that transient size back into ImageLayout turns a temporary resize into accumulated
    // crop/zoom. The render viewport is tracked separately and is only used for GL buffer bounds.
    private var viewportWidth = viewportWidth
    private var viewportHeight = viewportHeight
    private var renderViewportWidth = viewportWidth.coerceAtLeast(1)
    private var renderViewportHeight = viewportHeight.coerceAtLeast(1)
    private var transform = transform
    private val textureMatrix = FloatArray(16)
    private val bitmapTextureMatrix = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
        android.opengl.Matrix.translateM(it, 0, 0f, 1f, 0f)
        android.opengl.Matrix.scaleM(it, 0, 1f, -1f, 1f)
    }
    private val positionBuffer = floatBuffer(8)
    private val backdropPositionBuffer = floatBuffer(8)
    private val fullScreenPositionBuffer = floatBuffer(8).apply {
        put(
            floatArrayOf(
                -1f, 1f,
                1f, 1f,
                -1f, -1f,
                1f, -1f,
            ),
        )
        position(0)
    }
    private val textureCoordinateBuffer = floatBuffer(16).apply {
        put(
            floatArrayOf(
                0f, 1f, 0f, 1f,
                1f, 1f, 0f, 1f,
                0f, 0f, 0f, 1f,
                1f, 0f, 0f, 1f,
            ),
        )
        position(0)
    }
    private val transitionTextureCoordinateBuffer = floatBuffer(16)
    private val identityTextureMatrix = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
    }
    private val transitionFrameCallback = Choreographer.FrameCallback {
        transitionFrameScheduled = false
        if (released.get() || transitionTextureId == 0 || transitionWaitingForTargetFrame) {
            return@FrameCallback
        }
        if (layoutChangePending) return@FrameCallback
        try {
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                "Unable to restore EGL context for crossfade"
            }
            if (!transitionsEnabled || !ValueAnimator.areAnimatorsEnabled()) {
                releaseTransitionTextures()
                if (hasRenderableScene()) renderCurrentOutput(swapBuffers = true)
                return@FrameCallback
            }
            if (suspendForOrientationChangeIfNeeded()) return@FrameCallback
            syncRenderViewportFromEglSurface()
            if (
                transitionTargetDirty &&
                transitionTextureId != 0 &&
                !transitionWaitingForTargetFrame &&
                transitionTargetTextureId != 0 &&
                renderVideoFrames &&
                hasLatchedVideoFrame
            ) {
                refreshTransitionTargetFromCurrentScene()
                transitionTargetDirty = false
            }
            renderCurrentOutput(swapBuffers = true)
            if (transitionTextureId != 0 && !transitionWaitingForTargetFrame) {
                scheduleTransitionFrame()
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    init {
        renderHandler.post {
            val bridgeFrame = initialFrame
            val bridgeBackdrop = initialBackdrop
            val outgoingFrame = transitionFromFrame
            try {
                initializeGl()
                choreographer = Choreographer.getInstance()
                syncRenderViewportFromEglSurface()
                updatePositions()
                if (
                    transitionsEnabled &&
                    ValueAnimator.areAnimatorsEnabled() &&
                    outgoingFrame != null &&
                    !outgoingFrame.isRecycled
                ) {
                    adoptBitmapAsOutgoingTransition(outgoingFrame)
                }
                if (bridgeFrame != null && !bridgeFrame.isRecycled) {
                    presentInitialFrame(bridgeFrame, bridgeBackdrop)
                }
                scheduleViewportReconciliation()
                val surface = checkNotNull(inputSurface)
                callbackHandler.post {
                    if (!released.get()) onReady(surface)
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                initialFrame = null
                initialBackdrop = null
                transitionFromFrame = null
                if (bridgeFrame?.isRecycled == false) bridgeFrame.recycle()
                if (bridgeBackdrop?.isRecycled == false) bridgeBackdrop.recycle()
                if (outgoingFrame?.isRecycled == false) outgoingFrame.recycle()
            }
        }
    }

    fun updateLayout(
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transform: ImageTransform,
        backdrop: android.graphics.Bitmap? = null,
        replaceBackdrop: Boolean = false,
        expectedOrientation: ScreenOrientation? = null,
    ) {
        if (released.get()) {
            if (backdrop?.isRecycled == false) backdrop.recycle()
            return
        }
        renderHandler.post {
            try {
                val layoutChanged = this.contentWidth != contentWidth ||
                    this.contentHeight != contentHeight ||
                    this.viewportWidth != viewportWidth ||
                    this.viewportHeight != viewportHeight ||
                    this.transform != transform ||
                    replaceBackdrop || backdrop != null
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context for layout update"
                }
                val wasLayoutChangePending = layoutChangePending
                val previousViewportWidth = this.viewportWidth
                val previousViewportHeight = this.viewportHeight
                val deferUntilSurfaceMatches = useTargetViewport(
                    viewportWidth,
                    viewportHeight,
                    expectedOrientation,
                )
                val logicalViewportChanged = previousViewportWidth != this.viewportWidth ||
                    previousViewportHeight != this.viewportHeight
                if (!wasLayoutChangePending && (layoutChanged || logicalViewportChanged)) {
                    beginTransition(
                        durationMs = WallpaperTransitionSpec.ORIENTATION_DURATION_MS,
                        captureCurrentSceneOnly = true,
                    )
                }
                this.contentWidth = contentWidth
                this.contentHeight = contentHeight
                this.transform = transform
                updatePositions()
                updateBackdropTexture(backdrop, clearWhenMissing = replaceBackdrop)
                if (deferUntilSurfaceMatches) {
                    Log.d(
                        ORIENTATION_TAG,
                        "bitmapLayout deferred egl=${eglSurfaceSize()} " +
                            "requested=${viewportWidth}x$viewportHeight expected=$expectedOrientation",
                    )
                    presentLayoutHoldingFrameIfNeeded()
                    scheduleViewportReconciliation()
                    return@post
                }
                clearPendingLayoutState()
                if (transitionWaitingForTargetFrame && hasRenderableScene()) {
                    startWaitingTransition()
                }
                if (
                    renderVideoFrames &&
                    hasLatchedVideoFrame &&
                    awaitingFirstVideoFrameSession != NO_VIDEO_SESSION
                ) {
                    presentLatchedVideoFrame()
                } else {
                    renderCurrentOutput(swapBuffers = true)
                }
                scheduleViewportReconciliation()
            } catch (error: Throwable) {
                fail(error)
            } finally {
                if (backdrop?.isRecycled == false) backdrop.recycle()
            }
        }
    }

    /**
     * Uploads and presents a still frame without giving up this renderer's EGL window surface.
     * Ownership of [frame] transfers to the renderer and it is recycled after the GL upload.
     */
    fun presentBitmap(
        frame: android.graphics.Bitmap,
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transform: ImageTransform,
        backdrop: android.graphics.Bitmap? = null,
        expectedOrientation: ScreenOrientation? = null,
        onPresented: (Boolean) -> Unit,
    ) {
        if (released.get()) {
            if (!frame.isRecycled) frame.recycle()
            if (backdrop?.isRecycled == false) backdrop.recycle()
            callbackHandler.post { onPresented(false) }
            return
        }
        renderHandler.post {
            if (released.get()) {
                if (!frame.isRecycled) frame.recycle()
                if (backdrop?.isRecycled == false) backdrop.recycle()
                callbackHandler.post { onPresented(false) }
                return@post
            }
            var presented = false
            try {
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context for bitmap"
                }
                val deferUntilSurfaceMatches = useTargetViewport(
                    viewportWidth,
                    viewportHeight,
                    expectedOrientation,
                )
                if (!layoutChangePending) {
                    beginTransition(WallpaperTransitionSpec.MEDIA_DURATION_MS)
                }
                renderVideoFrames = false
                hasLatchedVideoFrame = false
                awaitingFirstVideoFrameSession = NO_VIDEO_SESSION
                this.contentWidth = contentWidth
                this.contentHeight = contentHeight
                this.transform = transform
                updatePositions()
                presentInitialFrame(
                    frame,
                    backdrop,
                    presentNow = !deferUntilSurfaceMatches,
                )
                if (!deferUntilSurfaceMatches) clearPendingLayoutState()
                scheduleViewportReconciliation()
                presented = true
            } catch (error: Throwable) {
                fail(error)
            } finally {
                if (!frame.isRecycled) frame.recycle()
                if (backdrop?.isRecycled == false) backdrop.recycle()
                callbackHandler.post {
                    if (!released.get()) onPresented(presented)
                }
            }
        }
    }

    /** Redraws the resident 2D texture after a transform or viewport change. */
    fun updateBitmapLayout(
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transform: ImageTransform,
        backdrop: android.graphics.Bitmap? = null,
        replaceBackdrop: Boolean = false,
        expectedOrientation: ScreenOrientation? = null,
    ) {
        if (released.get()) {
            if (backdrop?.isRecycled == false) backdrop.recycle()
            return
        }
        renderHandler.post {
            try {
                val layoutChanged = this.contentWidth != contentWidth ||
                    this.contentHeight != contentHeight ||
                    this.viewportWidth != viewportWidth ||
                    this.viewportHeight != viewportHeight ||
                    this.transform != transform ||
                    replaceBackdrop || backdrop != null
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context for bitmap redraw"
                }
                val previousViewportWidth = this.viewportWidth
                val previousViewportHeight = this.viewportHeight
                val deferUntilSurfaceMatches = useTargetViewport(
                    viewportWidth,
                    viewportHeight,
                    expectedOrientation,
                )
                val logicalViewportChanged = previousViewportWidth != this.viewportWidth ||
                    previousViewportHeight != this.viewportHeight
                if (initialFrameTextureId != 0) {
                    renderVideoFrames = false
                    val wasLayoutChangePending = layoutChangePending
                    if (!wasLayoutChangePending && (layoutChanged || logicalViewportChanged)) {
                        beginTransition(
                            durationMs = WallpaperTransitionSpec.ORIENTATION_DURATION_MS,
                            captureCurrentSceneOnly = true,
                        )
                    }
                    this.contentWidth = contentWidth
                    this.contentHeight = contentHeight
                    this.transform = transform
                    updatePositions()
                    updateBackdropTexture(backdrop, clearWhenMissing = replaceBackdrop)
                    if (deferUntilSurfaceMatches) {
                        presentLayoutHoldingFrameIfNeeded()
                        scheduleViewportReconciliation()
                        return@post
                    }
                    clearPendingLayoutState()
                    if (transitionWaitingForTargetFrame && hasRenderableScene()) {
                        startWaitingTransition()
                    }
                    renderCurrentOutput(swapBuffers = true)
                    scheduleViewportReconciliation()
                } else if (!transform.usesContainBackground) {
                    this.contentWidth = contentWidth
                    this.contentHeight = contentHeight
                    this.transform = transform
                    releaseBackdropTexture()
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                if (backdrop?.isRecycled == false) backdrop.recycle()
            }
        }
    }

    /**
     * Captures one complete outgoing scene before a configuration/profile change. Decoder frames
     * may continue to latch, but nothing is swapped with the new viewport until the service sends
     * the matching transform and backdrop through [updateLayout] or [updateBitmapLayout].
     */
    fun prepareForLayoutChange(forceBufferSwap: Boolean = false) {
        if (released.get()) return
        renderHandler.post {
            if (released.get()) return@post
            try {
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context before layout change"
                }
                if (layoutChangePending) {
                    presentLayoutHoldingFrameIfNeeded(force = forceBufferSwap)
                    if (forceBufferSwap) scheduleViewportReconciliation()
                    return@post
                }
                val (captureWidth, captureHeight) = orientationCaptureSize()
                val captured = beginTransition(
                    durationMs = WallpaperTransitionSpec.ORIENTATION_DURATION_MS,
                    captureWidth = captureWidth,
                    captureHeight = captureHeight,
                    captureCurrentSceneOnly = true,
                )
                layoutChangePending = captured
                if (captured) {
                    layoutTargetReady = false
                    layoutTargetViewportWidth = 0
                    layoutTargetViewportHeight = 0
                    layoutTargetOrientation = null
                    layoutTargetReadyAtMs = 0L
                    presentLayoutHoldingFrameIfNeeded()
                }
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    /**
     * Keeps this renderer and its EGL window surface alive while a new MediaPlayer replaces the
     * previous one. Posting this as a barrier after releasing the old player lets already queued
     * frames finish before the caller connects the next decoder to the same input Surface.
     */
    fun prepareForNextVideo(
        contentWidth: Int,
        contentHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        transform: ImageTransform,
        backdrop: android.graphics.Bitmap? = null,
        expectedOrientation: ScreenOrientation? = null,
        onInputReady: (Surface?) -> Unit,
    ) {
        if (released.get()) {
            if (backdrop?.isRecycled == false) backdrop.recycle()
            callbackHandler.post { onInputReady(null) }
            return
        }
        renderHandler.post {
            try {
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context before replacing video"
                }
                val deferUntilSurfaceMatches = useTargetViewport(
                    viewportWidth,
                    viewportHeight,
                    expectedOrientation,
                )
                beginTransition(WallpaperTransitionSpec.MEDIA_DURATION_MS)
                this.contentWidth = contentWidth
                this.contentHeight = contentHeight
                this.transform = transform
                updatePositions()
                updateBackdropTexture(backdrop, clearWhenMissing = true)
                if (!deferUntilSurfaceMatches) clearPendingLayoutState()
                val surface = inputSurface
                renderVideoFrames = false
                hasLatchedVideoFrame = false
                awaitingFirstVideoFrameSession = NO_VIDEO_SESSION
                scheduleViewportReconciliation()
                callbackHandler.post {
                    if (!released.get()) {
                        onInputReady(surface?.takeIf { it.isValid })
                    }
                }
            } catch (error: Throwable) {
                fail(error)
            } finally {
                if (backdrop?.isRecycled == false) backdrop.recycle()
            }
        }
    }

    /**
     * Arms the presented-frame acknowledgement only after MediaPlayer reports that the new decoder
     * started rendering. The render-thread post is a barrier: late frames from the detached player
     * already queued ahead of it cannot be mistaken for the first frame of the replacement.
     */
    fun armFirstVideoFrameCallback(sessionId: Long) {
        if (released.get()) return
        renderHandler.post {
            if (!released.get()) {
                awaitingFirstVideoFrameSession = sessionId
                renderVideoFrames = true
                try {
                    check(
                        EGL14.eglMakeCurrent(
                            eglDisplay,
                            eglSurface,
                            eglSurface,
                            eglContext,
                        ),
                    ) { "Unable to restore EGL context for latched video frame" }
                    // MEDIA_INFO_VIDEO_RENDERING_START means the replacement decoder has queued a
                    // frame. Explicitly latch the latest buffer here instead of trusting the
                    // SurfaceTexture state left by the previous decoder. If the buffer is not
                    // observable yet, the normal frame callback will complete the hand-off.
                    hasLatchedVideoFrame = false
                    if (latchLatestVideoFrame()) {
                        if (!layoutChangePending) presentLatchedVideoFrame()
                    }
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
    }

    /**
     * Reads one bounded, fully composed frame for the rare EGL -> Canvas hand-off (primarily GIF or
     * lock-effect destinations). Normal EGL crossfades remain GPU-only; this path avoids recreating
     * the previous media decoder merely to keep it visible during producer replacement.
     */
    fun captureComposedFrame(
        maxSide: Int,
        onCaptured: (android.graphics.Bitmap?) -> Unit,
    ) {
        if (released.get()) {
            callbackHandler.post { onCaptured(null) }
            return
        }
        renderHandler.post {
            var captured: android.graphics.Bitmap? = null
            try {
                check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    "Unable to restore EGL context for hand-off capture"
                }
                syncRenderViewportFromEglSurface()
                val longest = maxOf(renderViewportWidth, renderViewportHeight).coerceAtLeast(1)
                val scale = minOf(1f, maxSide.coerceAtLeast(1) / longest.toFloat())
                val width = (renderViewportWidth * scale).toInt().coerceAtLeast(1)
                val height = (renderViewportHeight * scale).toInt().coerceAtLeast(1)
                drawCompositedOutputForCapture(width, height)
                val bytes = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(
                    0,
                    0,
                    width,
                    height,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    bytes,
                )
                checkGlError("reading composed hand-off frame")
                bytes.position(0)
                val pixels = IntArray(width * height)
                for (targetY in 0 until height) {
                    val sourceY = height - targetY - 1
                    for (x in 0 until width) {
                        val sourceIndex = (sourceY * width + x) * 4
                        val red = bytes.get(sourceIndex).toInt() and 0xff
                        val green = bytes.get(sourceIndex + 1).toInt() and 0xff
                        val blue = bytes.get(sourceIndex + 2).toInt() and 0xff
                        val alpha = bytes.get(sourceIndex + 3).toInt() and 0xff
                        pixels[targetY * width + x] = Color.argb(alpha, red, green, blue)
                    }
                }
                captured = android.graphics.Bitmap.createBitmap(
                    pixels,
                    width,
                    height,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to capture EGL frame for Canvas hand-off", error)
            }
            val result = captured
            callbackHandler.post {
                if (released.get()) {
                    result?.recycle()
                    onCaptured(null)
                } else {
                    onCaptured(result)
                }
            }
        }
    }

    fun setTransitionsEnabled(enabled: Boolean) {
        if (released.get()) return
        renderHandler.post {
            transitionsEnabled = enabled
            if (!enabled) {
                clearPendingLayoutState()
            }
            if (!enabled && transitionTextureId != 0) {
                try {
                    check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        "Unable to restore EGL context while disabling crossfade"
                    }
                    releaseTransitionTextures()
                    if (hasRenderableScene()) renderCurrentOutput(swapBuffers = true)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
    }

    fun release(onReleased: (() -> Unit)? = null) {
        if (!released.compareAndSet(false, true)) {
            onReleased?.let { callback -> callbackHandler.post { callback() } }
            return
        }
        renderHandler.post {
            try {
                cleanupGl()
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to release video renderer", error)
            } finally {
                onReleased?.let { callback -> callbackHandler.post { callback() } }
                thread.quitSafely()
            }
        }
    }

    private fun initializeGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "Unable to acquire EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            "Unable to initialize EGL"
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttributes, 0, configs, 0, 1, configCount, 0)) {
            "Unable to choose EGL config"
        }
        val config = checkNotNull(configs.firstOrNull()) { "No compatible EGL config" }
        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Unable to create EGL context" }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            outputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Unable to create EGL window surface" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Unable to make EGL context current"
        }
        IntArray(1).also { limit ->
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
            maxTextureSize = limit[0].coerceAtLeast(1)
        }
        GLES20.glEnable(GLES20.GL_BLEND)
        // Preserve destination alpha while using standard source-over RGB blending, matching the
        // picture-set composition used by Muzei without making the wallpaper Surface translucent.
        GLES20.glBlendFuncSeparate(
            GLES20.GL_SRC_ALPHA,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE,
            GLES20.GL_ONE,
        )

        textureId = createExternalTexture()
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        textureMatrixLocation = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        textureSamplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        check(
            positionLocation >= 0 &&
                textureCoordinateLocation >= 0 &&
                textureMatrixLocation >= 0 &&
                textureSamplerLocation >= 0,
        ) {
            "Unable to resolve video shader inputs"
        }

        initialFrameProgram = createProgram(VERTEX_SHADER, INITIAL_FRAME_FRAGMENT_SHADER)
        initialPositionLocation = GLES20.glGetAttribLocation(initialFrameProgram, "aPosition")
        initialTextureCoordinateLocation = GLES20.glGetAttribLocation(
            initialFrameProgram,
            "aTextureCoordinate",
        )
        initialTextureMatrixLocation = GLES20.glGetUniformLocation(
            initialFrameProgram,
            "uTextureMatrix",
        )
        initialTextureSamplerLocation = GLES20.glGetUniformLocation(initialFrameProgram, "uTexture")
        initialAlphaLocation = GLES20.glGetUniformLocation(initialFrameProgram, "uAlpha")
        check(
            initialPositionLocation >= 0 &&
                initialTextureCoordinateLocation >= 0 &&
                initialTextureMatrixLocation >= 0 &&
                initialTextureSamplerLocation >= 0 &&
                initialAlphaLocation >= 0,
        ) {
            "Unable to resolve initial frame shader inputs"
        }

        val texture = SurfaceTexture(textureId)
        surfaceTexture = texture
        inputSurface = Surface(texture)
        texture.setOnFrameAvailableListener(
            {
                if (!released.get()) {
                    try {
                        drawFrame()
                    } catch (error: Throwable) {
                        fail(error)
                    }
                }
            },
            renderHandler,
        )
    }

    private fun drawFrame() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Unable to restore EGL context"
        }
        if (layoutChangePending) {
            latchLatestVideoFrame()
            presentLayoutHoldingFrameIfNeeded()
            return
        }
        if (suspendForOrientationChangeIfNeeded()) {
            latchLatestVideoFrame()
            return
        }
        val renderViewportChanged = if (SystemClock.uptimeMillis() <= viewportWatchUntilMs) {
            syncRenderViewportFromEglSurface()
        } else {
            false
        }
        if (!latchLatestVideoFrame()) return
        if (!renderVideoFrames) {
            if (renderViewportChanged && (initialFrameTextureId != 0 || transitionTextureId != 0)) {
                renderCurrentOutput(swapBuffers = true)
            }
            return
        }
        presentLatchedVideoFrame()
    }

    private fun latchLatestVideoFrame(): Boolean {
        val texture = surfaceTexture ?: return false
        return try {
            texture.updateTexImage()
            texture.getTransformMatrix(textureMatrix)
            hasLatchedVideoFrame = true
            if (transitionTextureId != 0 && !transitionWaitingForTargetFrame) {
                transitionTargetDirty = true
            }
            true
        } catch (error: RuntimeException) {
            hasLatchedVideoFrame = false
            false
        }
    }

    private fun presentLatchedVideoFrame() {
        val transitionWasWaiting = transitionWaitingForTargetFrame
        startWaitingTransition()
        val presentedSession = awaitingFirstVideoFrameSession
        // Choreographer owns presentation cadence. Decoder callbacks only mark the resident target
        // dirty, so even high-frame-rate videos perform at most one target copy per display frame;
        // the first successful composed swap remains the player's lifecycle acknowledgement.
        if (transitionTextureId == 0 || transitionWasWaiting || presentedSession != NO_VIDEO_SESSION) {
            renderCurrentOutput(swapBuffers = true)
        }
        releaseInitialFrameTexture()
        if (presentedSession != NO_VIDEO_SESSION) {
            awaitingFirstVideoFrameSession = NO_VIDEO_SESSION
            callbackHandler.post {
                if (!released.get()) onFirstVideoFramePresented(presentedSession)
            }
        }
    }

    private fun drawVideoTexture() {
        if (!hasLatchedVideoFrame) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureSamplerLocation, 0)

        positionBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            positionBuffer,
        )
        textureCoordinateBuffer.position(0)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            textureCoordinateLocation,
            4,
            GLES20.GL_FLOAT,
            false,
            0,
            textureCoordinateBuffer,
        )
        GLES20.glUniformMatrix4fv(textureMatrixLocation, 1, false, textureMatrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
    }

    /**
     * Publishes the Canvas bridge frame from the new EGL producer before MediaPlayer is connected.
     * Samsung can discard the last Canvas buffer during producer hand-off; this guarantees that
     * EGL already owns and has swapped an equivalent frame before video preparation begins.
     */
    private fun presentInitialFrame(
        frame: android.graphics.Bitmap,
        backdrop: android.graphics.Bitmap? = null,
        presentNow: Boolean = true,
    ) {
        val previousTexture = initialFrameTextureId
        val nextTexture = createBitmapTexture(frame)
        // A newly uploaded media frame must never inherit the previous media's background.
        updateBackdropTexture(backdrop, clearWhenMissing = true)
        initialFrameTextureId = nextTexture
        if (presentNow) {
            startWaitingTransition()
            renderCurrentOutput(swapBuffers = true)
        }
        if (previousTexture != 0 && previousTexture != nextTexture) {
            GLES20.glDeleteTextures(1, intArrayOf(previousTexture), 0)
        }
    }

    private fun adoptBitmapAsOutgoingTransition(frame: android.graphics.Bitmap) {
        val uploadedTexture = createBitmapTexture(frame)
        try {
            GLES20.glViewport(
                0,
                0,
                renderViewportWidth,
                renderViewportHeight,
            )
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawTwoDimensionalTexture(
                uploadedTexture,
                fullScreenPositionBuffer,
                bitmapTextureMatrix,
            )
            val captured = copyFramebufferToTexture() ?: return
            releaseTransitionTextures()
            transitionTextureId = captured
            transitionTextureWidth = renderViewportWidth
            transitionTextureHeight = renderViewportHeight
            transitionDurationMs = WallpaperTransitionSpec.MEDIA_DURATION_MS
            transitionWaitingForTargetFrame = true
            hasPresentedContent = true
            renderCurrentOutput(swapBuffers = true)
        } finally {
            GLES20.glDeleteTextures(1, intArrayOf(uploadedTexture), 0)
        }
    }

    private fun drawCurrentScene() {
        val color = if (transform.usesContainBackground) {
            transform.containBackgroundColor
        } else {
            Color.BLACK
        }
        GLES20.glClearColor(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            1f,
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (
            transform.usesContainBackground &&
            transform.containBackgroundMode == ContainBackgroundMode.BLUR &&
            backdropTextureId != 0
        ) {
            drawTwoDimensionalTexture(
                backdropTextureId,
                backdropPositionBuffer,
                bitmapTextureMatrix,
            )
        }
        when {
            renderVideoFrames && hasLatchedVideoFrame -> drawVideoTexture()
            initialFrameTextureId != 0 -> drawTwoDimensionalTexture(
                initialFrameTextureId,
                positionBuffer,
                bitmapTextureMatrix,
            )
        }
    }

    private fun drawTwoDimensionalTexture(
        textureId: Int,
        positions: FloatBuffer,
        textureTransform: FloatArray,
        alpha: Float = 1f,
        textureCoordinates: FloatBuffer = textureCoordinateBuffer,
    ) {
        if (textureId == 0 || alpha <= 0f) return
        GLES20.glUseProgram(initialFrameProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(initialTextureSamplerLocation, 0)
        GLES20.glUniform1f(initialAlphaLocation, alpha.coerceIn(0f, 1f))

        positions.position(0)
        GLES20.glEnableVertexAttribArray(initialPositionLocation)
        GLES20.glVertexAttribPointer(
            initialPositionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            positions,
        )
        textureCoordinates.position(0)
        GLES20.glEnableVertexAttribArray(initialTextureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            initialTextureCoordinateLocation,
            4,
            GLES20.GL_FLOAT,
            false,
            0,
            textureCoordinates,
        )
        GLES20.glUniformMatrix4fv(
            initialTextureMatrixLocation,
            1,
            false,
            textureTransform,
            0,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(initialPositionLocation)
        GLES20.glDisableVertexAttribArray(initialTextureCoordinateLocation)
    }

    private fun drawTransitionTexture(
        textureId: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
        alpha: Float = 1f,
    ) {
        fillCenterCropTextureCoordinates(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            destinationWidth = destinationWidth,
            destinationHeight = destinationHeight,
        )
        drawTwoDimensionalTexture(
            textureId = textureId,
            positions = fullScreenPositionBuffer,
            textureTransform = identityTextureMatrix,
            alpha = alpha,
            textureCoordinates = transitionTextureCoordinateBuffer,
        )
    }

    private fun fillCenterCropTextureCoordinates(
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
    ) {
        val sourceAspect = sourceWidth.coerceAtLeast(1).toFloat() /
            sourceHeight.coerceAtLeast(1)
        val destinationAspect = destinationWidth.coerceAtLeast(1).toFloat() /
            destinationHeight.coerceAtLeast(1)
        var minU = 0f
        var maxU = 1f
        var minV = 0f
        var maxV = 1f
        if (sourceAspect > destinationAspect) {
            val visibleWidth = (destinationAspect / sourceAspect).coerceIn(0f, 1f)
            minU = (1f - visibleWidth) / 2f
            maxU = 1f - minU
        } else if (sourceAspect < destinationAspect) {
            val visibleHeight = (sourceAspect / destinationAspect).coerceIn(0f, 1f)
            minV = (1f - visibleHeight) / 2f
            maxV = 1f - minV
        }
        transitionTextureCoordinateBuffer.position(0)
        transitionTextureCoordinateBuffer.put(
            floatArrayOf(
                minU, maxV, 0f, 1f,
                maxU, maxV, 0f, 1f,
                minU, minV, 0f, 1f,
                maxU, minV, 0f, 1f,
            ),
        )
        transitionTextureCoordinateBuffer.position(0)
    }

    /**
     * Draws the last fully composed output and the current target using the same two-picture-set
     * model as Muzei. The outgoing texture is a GPU-side snapshot of Wallpurr's complete scene, so
     * its crop, contain background and last decoded video frame survive while the incoming scene
     * changes type or dimensions.
     */
    private fun renderCurrentOutput(swapBuffers: Boolean) {
        val width = renderViewportWidth.coerceAtLeast(1)
        val height = renderViewportHeight.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        val outgoingTexture = transitionTextureId
        val progress = if (outgoingTexture != 0 && !transitionWaitingForTargetFrame) {
            transitionProgress()
        } else {
            0f
        }
        when {
            outgoingTexture != 0 &&
                !transitionWaitingForTargetFrame &&
                progress >= 1f -> drawCurrentScene()
            outgoingTexture != 0 -> {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawTransitionTexture(
                    textureId = outgoingTexture,
                    sourceWidth = transitionTextureWidth,
                    sourceHeight = transitionTextureHeight,
                    destinationWidth = width,
                    destinationHeight = height,
                )
                if (!transitionWaitingForTargetFrame && transitionTargetTextureId != 0) {
                    drawTransitionTexture(
                        textureId = transitionTargetTextureId,
                        sourceWidth = transitionTargetTextureWidth,
                        sourceHeight = transitionTargetTextureHeight,
                        destinationWidth = width,
                        destinationHeight = height,
                        alpha = progress,
                    )
                }
            }
            else -> drawCurrentScene()
        }
        if (swapBuffers) {
            check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                "Unable to present composed wallpaper frame"
            }
        }
        if (
            outgoingTexture != 0 &&
            !transitionWaitingForTargetFrame &&
            progress >= 1f
        ) {
            releaseTransitionTextures()
        }
        if (hasRenderableScene() || outgoingTexture != 0) hasPresentedContent = true
    }

    private fun beginTransition(
        durationMs: Long,
        captureWidth: Int = renderViewportWidth.coerceAtLeast(1),
        captureHeight: Int = renderViewportHeight.coerceAtLeast(1),
        captureCurrentSceneOnly: Boolean = false,
    ): Boolean {
        if (!transitionsEnabled || !ValueAnimator.areAnimatorsEnabled()) {
            releaseTransitionTextures()
            return false
        }
        val hasCaptureSource = if (captureCurrentSceneOnly) {
            hasRenderableScene()
        } else {
            hasRenderableOutput()
        }
        if (!hasPresentedContent || !hasCaptureSource) return false
        // A decoder replacement deliberately holds its outgoing frame until the new decoder has a
        // real frame. A resize signal in that window may change the target layout, but must not turn
        // the held frame into a prematurely running orientation animation.
        if (transitionWaitingForTargetFrame) return false
        if (captureCurrentSceneOnly) {
            // Orientation transitions must start from the resident media scene, not from the
            // flattened result of a previous crossfade. Re-capturing that center-cropped snapshot
            // on every tablet rotation compounds the crop and produces permanent incremental zoom.
            GLES20.glViewport(0, 0, captureWidth, captureHeight)
            drawCurrentScene()
        } else {
            drawCompositedOutputForCapture(captureWidth, captureHeight)
        }
        val capturedTexture = copyFramebufferToTexture(captureWidth, captureHeight) ?: return false
        val previousTransition = transitionTextureId
        transitionTextureId = capturedTexture
        transitionTextureWidth = captureWidth
        transitionTextureHeight = captureHeight
        transitionDurationMs = durationMs.coerceAtLeast(1L)
        transitionWaitingForTargetFrame = true
        transitionStartedAtMs = 0L
        if (previousTransition != 0 && previousTransition != capturedTexture) {
            GLES20.glDeleteTextures(1, intArrayOf(previousTransition), 0)
        }
        releaseTransitionTargetTexture()
        return true
    }

    private fun startWaitingTransition() {
        if (transitionTextureId == 0 || !transitionWaitingForTargetFrame) return
        GLES20.glViewport(
            0,
            0,
            renderViewportWidth.coerceAtLeast(1),
            renderViewportHeight.coerceAtLeast(1),
        )
        drawCurrentScene()
        val targetTexture = copyFramebufferToTexture() ?: run {
            releaseTransitionTextures()
            return
        }
        transitionTargetTextureId = targetTexture
        transitionTargetTextureWidth = renderViewportWidth.coerceAtLeast(1)
        transitionTargetTextureHeight = renderViewportHeight.coerceAtLeast(1)
        transitionWaitingForTargetFrame = false
        transitionTargetDirty = false
        transitionStartedAtMs = SystemClock.uptimeMillis()
        scheduleTransitionFrame()
    }

    /**
     * A Muzei picture set is static, but Wallpurr's incoming scene can be a playing video. Keep the
     * already allocated target texture current for the short fade so its final frame cannot jump
     * forward when the live OES scene takes over. No texture is allocated and no buffer is swapped.
     */
    private fun refreshTransitionTargetFromCurrentScene() {
        val target = transitionTargetTextureId
        if (target == 0 || transitionWaitingForTargetFrame) return
        val width = transitionTargetTextureWidth.coerceAtLeast(1)
        val height = transitionTargetTextureHeight.coerceAtLeast(1)
        if (width != renderViewportWidth || height != renderViewportHeight) return
        GLES20.glViewport(0, 0, width, height)
        drawCurrentScene()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target)
        GLES20.glCopyTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            0,
            0,
            width,
            height,
        )
        checkGlError("refreshing crossfade target")
    }

    private fun drawCompositedOutputForCapture(
        width: Int = renderViewportWidth.coerceAtLeast(1),
        height: Int = renderViewportHeight.coerceAtLeast(1),
    ) {
        GLES20.glViewport(
            0,
            0,
            width,
            height,
        )
        val outgoingTexture = transitionTextureId
        if (outgoingTexture == 0) {
            drawCurrentScene()
            return
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawTransitionTexture(
            textureId = outgoingTexture,
            sourceWidth = transitionTextureWidth,
            sourceHeight = transitionTextureHeight,
            destinationWidth = width,
            destinationHeight = height,
        )
        if (!transitionWaitingForTargetFrame && transitionTargetTextureId != 0) {
            drawTransitionTexture(
                textureId = transitionTargetTextureId,
                sourceWidth = transitionTargetTextureWidth,
                sourceHeight = transitionTargetTextureHeight,
                destinationWidth = width,
                destinationHeight = height,
                alpha = transitionProgress(),
            )
        }
    }

    private fun transitionProgress(): Float {
        if (transitionTextureId == 0) return 1f
        if (transitionWaitingForTargetFrame) return 0f
        val elapsed = (SystemClock.uptimeMillis() - transitionStartedAtMs).coerceAtLeast(0L)
        val fraction = (elapsed.toFloat() / transitionDurationMs).coerceIn(0f, 1f)
        return transitionInterpolator.getInterpolation(fraction)
    }

    private fun scheduleTransitionFrame() {
        if (
            transitionFrameScheduled ||
            released.get() ||
            transitionTextureId == 0 ||
            transitionWaitingForTargetFrame
        ) return
        val scheduler = choreographer ?: return
        transitionFrameScheduled = true
        scheduler.postFrameCallback(transitionFrameCallback)
    }

    private fun copyFramebufferToTexture(
        width: Int = renderViewportWidth.coerceAtLeast(1),
        height: Int = renderViewportHeight.coerceAtLeast(1),
    ): Int? {
        if (width > maxTextureSize || height > maxTextureSize) {
            Log.w(TAG, "Skipping crossfade: ${width}x$height exceeds GL texture limit")
            return null
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        if (id == 0) return null
        return try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            configureTwoDimensionalTexture()
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) Unit
            GLES20.glCopyTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGB,
                0,
                0,
                width,
                height,
                0,
            )
            checkGlError("copying crossfade frame")
            id
        } catch (error: Throwable) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            Log.w(TAG, "Unable to retain outgoing frame for crossfade", error)
            null
        }
    }

    private fun hasRenderableScene(): Boolean =
        (renderVideoFrames && hasLatchedVideoFrame) || initialFrameTextureId != 0

    private fun hasRenderableOutput(): Boolean = hasRenderableScene() || transitionTextureId != 0

    private fun updatePositions() {
        if (contentWidth <= 0 || contentHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return
        fillPositions(transform, positionBuffer)
        fillPositions(transform.containBackdropTransform(), backdropPositionBuffer)
    }

    /**
     * SurfaceHolder dimensions can briefly lag behind the EGL window after repeated tablet
     * rotations. GL still has to cover the complete physical buffer, but this size must not replace
     * the logical viewport used by [ImageLayout]: doing so reapplies crop using an intermediate
     * orientation and makes zoom accumulate on every round trip.
     */
    private fun syncRenderViewportFromEglSurface(): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return false
        val size = eglSurfaceSize() ?: return false
        if (renderViewportWidth == size.first && renderViewportHeight == size.second) return false
        renderViewportWidth = size.first
        renderViewportHeight = size.second
        return true
    }

    private fun eglSurfaceSize(): Pair<Int, Int>? {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return null
        val width = IntArray(1)
        val height = IntArray(1)
        val hasWidth = EGL14.eglQuerySurface(
            eglDisplay,
            eglSurface,
            EGL14.EGL_WIDTH,
            width,
            0,
        )
        val hasHeight = EGL14.eglQuerySurface(
            eglDisplay,
            eglSurface,
            EGL14.EGL_HEIGHT,
            height,
            0,
        )
        if (!hasWidth || !hasHeight || width[0] <= 0 || height[0] <= 0) return null
        return width[0] to height[0]
    }

    private fun orientationCaptureSize(): Pair<Int, Int> {
        val oldWidth = viewportWidth.coerceAtLeast(1)
        val oldHeight = viewportHeight.coerceAtLeast(1)
        val available = eglSurfaceSize() ?: return oldWidth to oldHeight
        val scale = minOf(
            1f,
            available.first / oldWidth.toFloat(),
            available.second / oldHeight.toFloat(),
        )
        return (oldWidth * scale).toInt().coerceAtLeast(1) to
            (oldHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun suspendForOrientationChangeIfNeeded(): Boolean {
        if (layoutChangePending) return true
        val actual = eglSurfaceSize() ?: return false
        if (actual.first == viewportWidth && actual.second == viewportHeight) return false
        val oldPortrait = viewportWidth < viewportHeight
        val newPortrait = actual.first < actual.second
        if (oldPortrait == newPortrait) return false
        val (captureWidth, captureHeight) = orientationCaptureSize()
        val captured = beginTransition(
            durationMs = WallpaperTransitionSpec.ORIENTATION_DURATION_MS,
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            captureCurrentSceneOnly = true,
        )
        if (!captured) return false
        layoutChangePending = true
        layoutTargetReady = false
        layoutTargetViewportWidth = 0
        layoutTargetViewportHeight = 0
        layoutTargetOrientation = null
        layoutTargetReadyAtMs = 0L
        presentLayoutHoldingFrameIfNeeded()
        return true
    }

    /**
     * SurfaceHolder and EGL may expose different generations of the window during tablet rotation.
     * Record the final requested profile immediately, but keep presenting the retained scene until
     * EGL reports the same orientation. This prevents a wrong-aspect target from becoming the next
     * transition source and accumulating center-crop zoom on every rotation.
     */
    private fun useTargetViewport(
        requestedWidth: Int,
        requestedHeight: Int,
        expectedOrientation: ScreenOrientation?,
    ): Boolean {
        val safeWidth = requestedWidth.coerceAtLeast(1)
        val safeHeight = requestedHeight.coerceAtLeast(1)
        val actual = eglSurfaceSize()
        val defer = layoutChangePending &&
            !surfaceOrientationMatches(
                actual,
                safeWidth,
                safeHeight,
                expectedOrientation,
            )
        // The requested dimensions and transform are one logical profile. Never substitute the
        // transient EGL size here; it may already belong to the next rotation generation.
        viewportWidth = safeWidth
        viewportHeight = safeHeight
        if (actual != null) {
            renderViewportWidth = actual.first
            renderViewportHeight = actual.second
        }
        if (layoutChangePending) {
            val targetChanged = !layoutTargetReady ||
                layoutTargetViewportWidth != safeWidth ||
                layoutTargetViewportHeight != safeHeight ||
                layoutTargetOrientation != expectedOrientation
            layoutTargetReady = true
            layoutTargetViewportWidth = safeWidth
            layoutTargetViewportHeight = safeHeight
            layoutTargetOrientation = expectedOrientation
            if (targetChanged) layoutTargetReadyAtMs = SystemClock.uptimeMillis()
        }
        return defer
    }

    private fun surfaceOrientationMatches(
        actual: Pair<Int, Int>?,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedOrientation: ScreenOrientation? = null,
    ): Boolean {
        actual ?: return false
        if (expectedOrientation != null) {
            if (actual.first == actual.second) return false
            return (actual.first < actual.second) ==
                (expectedOrientation == ScreenOrientation.PORTRAIT)
        }
        if (actual.first == actual.second || expectedWidth == expectedHeight) {
            return actual.first == expectedWidth && actual.second == expectedHeight
        }
        return (actual.first < actual.second) == (expectedWidth < expectedHeight)
    }

    private fun dimensionsMatchOrientation(
        width: Int,
        height: Int,
        expectedOrientation: ScreenOrientation?,
    ): Boolean {
        if (width <= 0 || height <= 0 || width == height) return false
        if (expectedOrientation == null) return true
        return (width < height) == (expectedOrientation == ScreenOrientation.PORTRAIT)
    }

    private fun clearPendingLayoutState() {
        layoutChangePending = false
        layoutHoldingViewportWidth = 0
        layoutHoldingViewportHeight = 0
        layoutTargetReady = false
        layoutTargetViewportWidth = 0
        layoutTargetViewportHeight = 0
        layoutTargetOrientation = null
        layoutTargetReadyAtMs = 0L
    }

    private fun presentLayoutHoldingFrameIfNeeded(force: Boolean = false) {
        if (!layoutChangePending || transitionTextureId == 0) return
        val actual = eglSurfaceSize() ?: return
        if (
            !force &&
            actual.first == layoutHoldingViewportWidth &&
            actual.second == layoutHoldingViewportHeight
        ) return
        GLES20.glViewport(0, 0, actual.first, actual.second)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawTransitionTexture(
            textureId = transitionTextureId,
            sourceWidth = transitionTextureWidth,
            sourceHeight = transitionTextureHeight,
            destinationWidth = actual.first,
            destinationHeight = actual.second,
        )
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            "Unable to present orientation holding frame"
        }
        layoutHoldingViewportWidth = actual.first
        layoutHoldingViewportHeight = actual.second
    }

    private fun scheduleViewportReconciliation() {
        val generation = ++viewportReconcileGeneration
        viewportWatchUntilMs = SystemClock.uptimeMillis() + VIEWPORT_WATCH_DURATION_MS
        VIEWPORT_RECONCILE_DELAYS_MS.forEach { delayMs ->
            val reconciliation = Runnable reconciliation@{
                if (released.get() || generation != viewportReconcileGeneration) {
                    return@reconciliation
                }
                try {
                    check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                        "Unable to restore EGL context for viewport reconciliation"
                    }
                    if (layoutChangePending) {
                        presentLayoutHoldingFrameIfNeeded()
                        if (!layoutTargetReady) return@reconciliation
                        val actual = eglSurfaceSize() ?: return@reconciliation
                        val surfaceMatches = surfaceOrientationMatches(
                            actual,
                            layoutTargetViewportWidth,
                            layoutTargetViewportHeight,
                            layoutTargetOrientation,
                        )
                        val waitExpired = layoutTargetReadyAtMs != 0L &&
                            SystemClock.uptimeMillis() - layoutTargetReadyAtMs >=
                            SURFACE_ORIENTATION_WAIT_MS
                        val mayUseStableSurfaceFallback =
                            layoutTargetOrientation == null && waitExpired
                        Log.d(
                            ORIENTATION_TAG,
                            "reconcile delay=${delayMs}ms egl=${actual.first}x${actual.second} " +
                                "target=${layoutTargetViewportWidth}x$layoutTargetViewportHeight " +
                                "targetOrientation=$layoutTargetOrientation matches=$surfaceMatches " +
                                "waitExpired=$waitExpired",
                        )
                        if (!surfaceMatches && !mayUseStableSurfaceFallback) {
                            // Samsung updates BLAST/SurfaceHolder before EGL observes the new
                            // window-buffer generation. Swap the retained frame again to dequeue
                            // that buffer, but never commit an explicitly landscape profile against
                            // a portrait EGL surface (or vice versa).
                            presentLayoutHoldingFrameIfNeeded(force = true)
                            return@reconciliation
                        }
                        renderViewportWidth = actual.first
                        renderViewportHeight = actual.second
                        val requestedMatchesFinalSurface = surfaceMatches &&
                            dimensionsMatchOrientation(
                                layoutTargetViewportWidth,
                                layoutTargetViewportHeight,
                                layoutTargetOrientation,
                            )
                        if (requestedMatchesFinalSurface) {
                            viewportWidth = layoutTargetViewportWidth.coerceAtLeast(1)
                            viewportHeight = layoutTargetViewportHeight.coerceAtLeast(1)
                        } else {
                            // Some launchers keep a stable wallpaper surface even though the
                            // configuration profile changed. In that fallback, layout against the
                            // actual buffer so the new profile fills it without being stretched.
                            viewportWidth = actual.first
                            viewportHeight = actual.second
                        }
                        updatePositions()
                        clearPendingLayoutState()
                        if (transitionWaitingForTargetFrame && hasRenderableScene()) {
                            startWaitingTransition()
                        }
                        renderCurrentOutput(swapBuffers = true)
                        return@reconciliation
                    }
                    if (suspendForOrientationChangeIfNeeded()) return@reconciliation
                    if (!syncRenderViewportFromEglSurface()) return@reconciliation
                    renderCurrentOutput(swapBuffers = true)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
            renderHandler.postDelayed(reconciliation, delayMs)
        }
    }

    private fun fillPositions(layoutTransform: ImageTransform, buffer: FloatBuffer) {
        val layout = ImageLayout.layout(
            imageWidth = contentWidth,
            imageHeight = contentHeight,
            viewportWidth = viewportWidth.toFloat(),
            viewportHeight = viewportHeight.toFloat(),
            transform = layoutTransform,
        )
        val points = floatArrayOf(
            0f, 0f,
            contentWidth.toFloat(), 0f,
            0f, contentHeight.toFloat(),
            contentWidth.toFloat(), contentHeight.toFloat(),
        )
        layout.toMatrix().mapPoints(points)
        for (index in points.indices step 2) {
            points[index] = points[index] * 2f / viewportWidth - 1f
            points[index + 1] = 1f - points[index + 1] * 2f / viewportHeight
        }
        buffer.position(0)
        buffer.put(points)
        buffer.position(0)
    }

    private fun cleanupGl() {
        choreographer?.removeFrameCallback(transitionFrameCallback)
        choreographer = null
        transitionFrameScheduled = false
        surfaceTexture?.setOnFrameAvailableListener(null)
        inputSurface?.release()
        inputSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            releaseInitialFrameTexture()
            releaseBackdropTexture()
            releaseTransitionTextures()
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            if (initialFrameProgram != 0) GLES20.glDeleteProgram(initialFrameProgram)
            if (eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        textureId = 0
        maxTextureSize = 0
        program = 0
        initialFrameProgram = 0
    }

    private fun updateBackdropTexture(
        backdrop: android.graphics.Bitmap?,
        clearWhenMissing: Boolean = false,
    ) {
        if (
            !transform.usesContainBackground ||
            transform.containBackgroundMode != ContainBackgroundMode.BLUR
        ) {
            // Keep the small derived texture resident while the same media uses COVER in its other
            // orientation. A new media presentation passes clearWhenMissing=true and releases it.
            if (clearWhenMissing) releaseBackdropTexture()
            return
        }
        if (backdrop == null || backdrop.isRecycled) {
            if (clearWhenMissing) releaseBackdropTexture()
            return
        }
        val previous = backdropTextureId
        backdropTextureId = createBitmapTexture(backdrop)
        if (previous != 0 && previous != backdropTextureId) {
            GLES20.glDeleteTextures(1, intArrayOf(previous), 0)
        }
    }

    private fun fail(error: Throwable) {
        if (!failed.compareAndSet(false, true) || released.get()) return
        callbackHandler.post {
            if (!released.get()) onError(error)
        }
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        check(id != 0) { "Unable to create video texture" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return id
    }

    private fun createBitmapTexture(bitmap: android.graphics.Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        check(id != 0) { "Unable to create initial frame texture" }
        return try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            configureTwoDimensionalTexture()
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            checkGlError("uploading bitmap texture")
            id
        } catch (error: Throwable) {
            GLES20.glDeleteTextures(1, intArrayOf(id), 0)
            throw error
        }
    }

    private fun configureTwoDimensionalTexture() {
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
    }

    private fun releaseInitialFrameTexture() {
        if (initialFrameTextureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(initialFrameTextureId), 0)
        initialFrameTextureId = 0
    }

    private fun releaseBackdropTexture() {
        if (backdropTextureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(backdropTextureId), 0)
        backdropTextureId = 0
    }

    private fun releaseTransitionTextures() {
        if (transitionTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(transitionTextureId), 0)
            transitionTextureId = 0
        }
        transitionTextureWidth = 0
        transitionTextureHeight = 0
        releaseTransitionTargetTexture()
        transitionStartedAtMs = 0L
        transitionDurationMs = 0L
        transitionWaitingForTargetFrame = false
        transitionTargetDirty = false
        choreographer?.removeFrameCallback(transitionFrameCallback)
        transitionFrameScheduled = false
    }

    private fun releaseTransitionTargetTexture() {
        if (transitionTargetTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(transitionTargetTextureId), 0)
        }
        transitionTargetTextureId = 0
        transitionTargetTextureWidth = 0
        transitionTargetTextureHeight = 0
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "OpenGL error 0x${Integer.toHexString(error)} while $operation"
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { created ->
            check(created != 0) { "Unable to create video shader program" }
            GLES20.glAttachShader(created, vertexShader)
            GLES20.glAttachShader(created, fragmentShader)
            GLES20.glLinkProgram(created)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, linkStatus, 0)
            check(linkStatus[0] == GLES20.GL_TRUE) {
                "Unable to link video shader: ${GLES20.glGetProgramInfoLog(created)}"
            }
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            check(shader != 0) { "Unable to create video shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            check(compileStatus[0] == GLES20.GL_TRUE) {
                "Unable to compile video shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    private companion object {
        const val TAG = "WallpurrVideoGL"
        const val ORIENTATION_TAG = "WallpurrOrientGL"
        const val NO_VIDEO_SESSION = Long.MIN_VALUE
        const val VIEWPORT_WATCH_DURATION_MS = 2_000L
        const val SURFACE_ORIENTATION_WAIT_MS = 500L
        val VIEWPORT_RECONCILE_DELAYS_MS = longArrayOf(32L, 120L, 320L, 800L, 1_600L)
        const val VERTEX_SHADER = """
            uniform mat4 uTextureMatrix;
            attribute vec2 aPosition;
            attribute vec4 aTextureCoordinate;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate);
            }
        """
        const val INITIAL_FRAME_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTextureCoordinate;
            void main() {
                vec4 color = texture2D(uTexture, vTextureCoordinate);
                gl_FragColor = vec4(color.rgb, color.a * uAlpha);
            }
        """

        fun floatBuffer(size: Int): FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }
}
