package dev.chofito.wallpurr.wallpaper

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.RequiresApi
import dev.chofito.wallpurr.WallpurrApplication
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.PowerSavePolicy
import dev.chofito.wallpurr.storage.LockStyle
import dev.chofito.wallpurr.storage.VideoPlayMode
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt

class WallpurrWallpaperService : WallpaperService() {
    // Without a handler, any uncaught exception in a launched coroutine kills the process.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "Uncaught exception in service scope", error)
        },
    )
    private val activeEngines = linkedSetOf<WallpurrEngine>()
    private var initialDestinationReconcileScheduled = false

    override fun onCreateEngine(): Engine = WallpurrEngine()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = newConfig.screenOrientationOrNull() ?: return
        // Some tablet launchers keep the wallpaper Surface dimensions stable across rotation.
        // Configuration is therefore a second, independent signal that the persisted profile
        // must switch even when onSurfaceChanged does not report a new aspect ratio.
        activeEngines.toList().forEach { it.onDeviceConfigurationChanged(orientation) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerEngine(engine: WallpurrEngine) {
        activeEngines += engine
    }

    private fun unregisterEngine(engine: WallpurrEngine) {
        activeEngines -= engine
    }

    inner class WallpurrEngine : Engine() {
        private val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Uncaught exception in engine scope", error)
            },
        )
        // Consecutive renderer failures for one image; bounds the release -> reload loop.
        private var rendererFailureImageId: String? = null
        private var rendererFailureCount = 0
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val transitionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val transitionSourceRect = Rect()
        private val transitionDestinationRect = Rect()
        private val transitionInterpolator = AccelerateDecelerateInterpolator()
        private val canvasTransitionMaxSide by lazy(LazyThreadSafetyMode.NONE) {
            val lowRam = applicationContext.getSystemService(ActivityManager::class.java)
                ?.isLowRamDevice == true
            if (lowRam) {
                WallpaperTransitionSpec.LOW_RAM_CANVAS_BRIDGE_MAX_SIDE
            } else {
                WallpaperTransitionSpec.CANVAS_BRIDGE_MAX_SIDE
            }
        }
        private var bitmap: Bitmap? = null
        private var containBackdrop: Bitmap? = null
        private var containBackdropImageId: String? = null
        private var gif: Drawable? = null
        private var player: MediaPlayer? = null
        private var videoRenderer: TransformedVideoRenderer? = null
        private var videoInputSurface: Surface? = null
        private var canvasSurface: Surface? = null
        // Keep the engine field API-neutral; HardwareBlurCache resolves RenderEffect (API 31+).
        private var hardwareBlurCache: Any? = null
        private var transform: ImageTransform = ImageTransform.Default
        private var lockStyle: LockStyle = LockStyle.None
        private var mediaWidth = 0
        private var mediaHeight = 0
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var loadedViewportWidth = 0
        private var loadedViewportHeight = 0
        private var loadedOrientation: ScreenOrientation? = null
        private var desiredOrientation: ScreenOrientation? = null
        private var orientationReloadScheduled = false
        private var orientationReloadRunning = false
        private var surfaceReady = false
        private var kind = MediaKind.IMAGE
        private var playlistTarget = WallpaperTarget.BOTH
        private var allowCanvas = false
        private var videoOwnsSurface = false
        private var rendererShowsBitmap = false
        private var rendererBitmapPending = false
        private var rendererBitmapMaxSide = 0
        private var rendererBlurBackdropImageId: String? = null
        private var videoPrepared = false
        private var videoFramePresented = false
        private var pauseAfterFirstVideoFrame = false
        private var weBelieveStarted = false
        private var consecutiveVideoErrors = 0
        private var videoLoops = 0
        private var videoSessionId = 0L
        private var visible = false
        private var loadedImageId: String? = null
        private var tapDownX = 0f
        private var tapDownY = 0f
        private var tapDownAt = 0L
        private var loadGeneration = 0L
        private var drawRetryCount = 0
        private var canvasTransitionFrom: Bitmap? = null
        private var canvasTransitionTarget: Bitmap? = null
        private var canvasTransitionStartedAtMs = 0L
        private var canvasTransitionDurationMs = 0L
        private var canvasTransitionFrameScheduled = false
        private var canvasTargetLoadGeneration = Long.MIN_VALUE
        private var canvasTargetOrientation: ScreenOrientation? = null
        private var canvasTargetWaitStartedAtMs = 0L
        private var rendererReleasesInFlight = 0
        private val rendererReleaseWaiters = mutableListOf<() -> Unit>()
        private var screenStateReceiverRegistered = false
        private var sharedLockStyleVisible = false
        private val redrawForScreenState = Runnable {
            if (surfaceReady && allowCanvas) drawFrame()
        }
        private val reconcileSharedLockState = Runnable {
            val keyguard = applicationContext.getSystemService(KeyguardManager::class.java)
            setSharedLockStyleVisible(keyguard?.isKeyguardLocked == true)
        }
        private val retryFirstFrame = Runnable {
            if (surfaceReady && allowCanvas) drawFrame()
        }
        private val redrawAfterCanvasTransition = Runnable {
            if (surfaceReady && allowCanvas && canvasTransitionFrom == null) drawFrame()
        }
        private val canvasTransitionFrameCallback = Choreographer.FrameCallback {
            canvasTransitionFrameScheduled = false
            if (surfaceReady && allowCanvas && canvasTransitionFrom != null) {
                drawFrame()
            }
        }
        private val retryCanvasTransitionTarget = Runnable {
            val generation = canvasTargetLoadGeneration
            val orientation = canvasTargetOrientation
            if (generation == Long.MIN_VALUE || orientation == null) return@Runnable
            scope.launch {
                prepareCanvasTransitionTargetIfNeeded(generation, orientation)
                if (canvasTransitionTarget != null && isCurrentLoad(generation)) drawFrame()
            }
        }
        private val reloadForOrientation = Runnable {
            orientationReloadScheduled = false
            val orientation = desiredOrientation ?: return@Runnable
            if (!surfaceReady || loadedOrientation == orientation || orientationReloadRunning) {
                return@Runnable
            }
            orientationReloadRunning = true
            scope.launch {
                try {
                    loadCurrent(orientation)
                } finally {
                    orientationReloadRunning = false
                    scheduleOrientationReloadIfNeeded()
                }
            }
        }
        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                handler.removeCallbacks(redrawForScreenState)
                handler.removeCallbacks(reconcileSharedLockState)
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // KeyguardManager can still report the previous unlocked state while the
                        // display is turning off. Treat SCREEN_OFF as the start of lock rendering.
                        sharedLockStyleVisible = true
                        handler.postDelayed(redrawForScreenState, SCREEN_OFF_REDRAW_DELAY_MS)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        val keyguard = applicationContext
                            .getSystemService(KeyguardManager::class.java)
                        if (keyguard?.isKeyguardLocked == true) {
                            sharedLockStyleVisible = true
                        }
                        handler.post(redrawForScreenState)
                        // Reconcile after Samsung finishes its keyguard transition. USER_PRESENT
                        // normally clears it sooner; this also covers devices without a secure lock.
                        handler.postDelayed(
                            reconcileSharedLockState,
                            KEYGUARD_RECONCILE_DELAY_MS,
                        )
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        setSharedLockStyleVisible(false)
                    }
                }
            }
        }
        private val gifCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                if (allowCanvas && canvasTransitionFrom == null) drawFrame()
            }
            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
                handler.postAtTime(what, time)
            }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                handler.removeCallbacks(what)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            registerEngine(this)
            sharedLockStyleVisible = applicationContext
                .getSystemService(KeyguardManager::class.java)
                ?.isKeyguardLocked == true
            registerScreenStateReceiver()
            setTouchEventsEnabled(true)
            scope.launch {
                val app = applicationContext as? WallpurrApplication ?: return@launch
                app.container.wallpaperPulse.collectLatest {
                    loadCurrent()
                }
            }
        }

        override fun onDestroy() {
            loadGeneration += 1
            surfaceReady = false
            desiredOrientation = null
            orientationReloadScheduled = false
            handler.removeCallbacks(reloadForOrientation)
            handler.removeCallbacks(reconcileSharedLockState)
            clearCanvasTransition()
            unregisterEngine(this)
            unregisterScreenStateReceiver()
            releaseMedia()
            scope.cancel()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            allowCanvas = false
            drawRetryCount = 0
            consecutiveVideoErrors = 0
            scope.launch { loadCurrent() }
            scheduleInitialDestinationReconcile()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val requestedOrientation = currentDeviceOrientation(width, height)
            if (loadedOrientation != null && loadedOrientation != requestedOrientation) {
                videoRenderer?.prepareForLayoutChange(forceBufferSwap = true)
            }
            surfaceWidth = width
            surfaceHeight = height
            if (!surfaceReady) return
            if (!observeOrientation(requestedOrientation)) {
                if (orientationReloadRunning) loadGeneration += 1
                scheduleOrientationReloadIfNeeded(restartDelay = true)
                if (allowCanvas && canvasTransitionFrom != null) drawFrame()
                return
            }
            redrawLoadedMediaForViewport()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            loadGeneration += 1
            surfaceReady = false
            loadedOrientation = null
            desiredOrientation = null
            orientationReloadScheduled = false
            allowCanvas = false
            drawRetryCount = 0
            handler.removeCallbacks(reloadForOrientation)
            handler.removeCallbacks(retryFirstFrame)
            handler.removeCallbacks(redrawAfterCanvasTransition)
            clearCanvasTransition()
            runCatching { player?.setSurface(null) }
            releaseMedia()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            val requestedOrientation = currentDeviceOrientation(surfaceWidth, surfaceHeight)
            if (!observeOrientation(requestedOrientation)) return
            redrawLoadedMediaForViewport()
        }

        private fun redrawLoadedMediaForViewport() {
            if (!surfaceReady || surfaceWidth <= 0 || surfaceHeight <= 0) return
            if (videoOwnsSurface && (rendererShowsBitmap || rendererBitmapPending)) {
                videoRenderer?.updateBitmapLayout(
                    contentWidth = mediaWidth,
                    contentHeight = mediaHeight,
                    viewportWidth = surfaceWidth,
                    viewportHeight = surfaceHeight,
                    transform = transform,
                    expectedOrientation = loadedOrientation,
                )
            } else if (kind == MediaKind.VIDEO) {
                attachVideoDisplay()
            } else if (allowCanvas) {
                drawFrame()
            }
        }

        internal fun onDeviceConfigurationChanged(orientation: ScreenOrientation) {
            if (!surfaceReady) return
            if (loadedOrientation != null && loadedOrientation != orientation) {
                videoRenderer?.prepareForLayoutChange()
            }
            if (observeOrientation(orientation)) redrawLoadedMediaForViewport()
        }

        private fun observeOrientation(orientation: ScreenOrientation): Boolean {
            if (desiredOrientation != orientation) {
                // Even when the requested profile equals the last committed one, invalidate a
                // still-running load for the opposite profile. Without this, a late landscape
                // decode can commit after the tablet has already returned to portrait.
                desiredOrientation = orientation
                loadGeneration += 1
                handler.removeCallbacks(reloadForOrientation)
                orientationReloadScheduled = false
            }
            if (loadedOrientation != orientation) {
                scheduleOrientationReloadIfNeeded()
                return false
            }
            return true
        }

        private fun scheduleOrientationReloadIfNeeded(
            restartDelay: Boolean = false,
        ) {
            if (restartDelay && orientationReloadScheduled) {
                handler.removeCallbacks(reloadForOrientation)
                orientationReloadScheduled = false
            }
            val orientation = desiredOrientation ?: return
            if (
                !surfaceReady || loadedOrientation == orientation ||
                orientationReloadScheduled || orientationReloadRunning
            ) {
                return
            }
            orientationReloadScheduled = true
            handler.postDelayed(reloadForOrientation, ORIENTATION_RELOAD_DEBOUNCE_MS)
        }

        private fun orientationForSize(width: Int, height: Int): ScreenOrientation {
            if (width > 0 && height > 0 && width != height) {
                return if (width < height) {
                    ScreenOrientation.PORTRAIT
                } else {
                    ScreenOrientation.LANDSCAPE
                }
            }
            return desiredOrientation ?: loadedOrientation ?: ScreenOrientation.PORTRAIT
        }

        private fun currentDeviceOrientation(width: Int, height: Int): ScreenOrientation =
            resources.configuration.screenOrientationOrNull()
                ?: orientationForSize(width, height)

        override fun onVisibilityChanged(isVisible: Boolean) {
            super.onVisibilityChanged(isVisible)
            visible = isVisible
            if (isVisible && applicationContext
                    .getSystemService(KeyguardManager::class.java)
                    ?.isKeyguardLocked == true
            ) {
                sharedLockStyleVisible = true
            }
            videoRenderer?.setTransitionsEnabled(transitionsAllowed())
            if (!isVisible && !isPreview) clearCanvasTransition()
            scope.launch {
                if (isVisible && surfaceReady && isLoadedMediaMissing()) {
                    loadCurrent()
                } else {
                    applyVisibility()
                }
            }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            scope.launch { loadCurrent() }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) skipFromHomeTap()
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapDownX = event.x
                    tapDownY = event.y
                    tapDownAt = SystemClock.elapsedRealtime()
                }
                MotionEvent.ACTION_UP -> {
                    val dt = SystemClock.elapsedRealtime() - tapDownAt
                    val dist = hypot((event.x - tapDownX).toDouble(), (event.y - tapDownY).toDouble())
                    if (dt < 350 && dist < 48.0) skipFromHomeTap()
                }
            }
            super.onTouchEvent(event)
        }

        private fun skipFromHomeTap() {
            if (isPreview || !rendersHomeScreen() || playlistTarget == WallpaperTarget.LOCK) return
            val eventAt = SystemClock.elapsedRealtime()
            scope.launch {
                val app = applicationContext as? WallpurrApplication ?: return@launch
                app.container.rotation.nextFromHomeTap(eventAt)
            }
        }

        private suspend fun applyVisibility() {
            val app = applicationContext as? WallpurrApplication ?: return
            val repo = app.container.repository
            val pauseGif = repo.flag(WallpurrRepository.KEY_PAUSE_GIF_HIDDEN, true)
            val pauseVideo = repo.flag(WallpurrRepository.KEY_PAUSE_VIDEO_HIDDEN, true)
            when (kind) {
                MediaKind.GIF -> {
                    val anim = gif as? Animatable ?: return
                    if (visible || !pauseGif) anim.start() else anim.stop()
                    if (visible && allowCanvas) drawFrame()
                }
                MediaKind.VIDEO -> applyVideoPlayback(!pauseVideo)
                MediaKind.IMAGE -> if (visible && allowCanvas) drawFrame()
            }
        }

        /**
         * MediaPlayer may report prepared before its first frame reaches the GL surface. Some OEMs
         * reject pause() during that window with error -38, which tears down the new video and
         * leaves the previous wallpaper visible until the next visibility cycle.
         *
         * When a video is loaded while the wallpaper is hidden (for example, from the notification
         * shade), briefly start it. MEDIA_INFO_VIDEO_RENDERING_START arms a render-thread barrier;
         * only the callback after a successful EGL swap makes a later pause safe.
         */
        private fun applyVideoPlayback(allowWhenHidden: Boolean) {
            val media = player ?: return
            if (!videoPrepared) return
            val shouldPlay = visible || allowWhenHidden
            try {
                if (shouldPlay) {
                    pauseAfterFirstVideoFrame = false
                    if (!weBelieveStarted) {
                        media.start()
                        weBelieveStarted = true
                    }
                } else if (!videoFramePresented) {
                    pauseAfterFirstVideoFrame = true
                    if (!weBelieveStarted) {
                        media.start()
                        weBelieveStarted = true
                    }
                } else {
                    pauseAfterFirstVideoFrame = false
                    if (weBelieveStarted) {
                        media.pause()
                        weBelieveStarted = false
                    }
                }
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Ignoring invalid MediaPlayer visibility transition", error)
            }
        }

        /**
         * Decoders can throw on corrupt files or OOM. An escaped exception would kill the process
         * and the restarted wallpaper would reload the same file, so fail safe here: log and leave
         * whatever is currently presented. Each branch below already recycles what it owns in its
         * own `finally`.
         */
        private suspend fun loadCurrent(
            requestedOrientation: ScreenOrientation? = null,
        ) {
            try {
                loadCurrentUnsafe(requestedOrientation)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Wallpaper load failed; keeping previous media", error)
            }
        }

        private suspend fun loadCurrentUnsafe(
            requestedOrientation: ScreenOrientation? = null,
        ) {
            if (!surfaceReady) return
            val generation = ++loadGeneration
            val orientation = requestedOrientation
                ?: desiredOrientation
                ?: resources.configuration.screenOrientationOrNull()
                ?: orientationForSize(surfaceWidth, surfaceHeight)
            if (desiredOrientation == null) desiredOrientation = orientation
            val app = applicationContext as? WallpurrApplication ?: return
            val repo = app.container.repository
            val playlistId = repo.activePlaylistId()
            if (!isCurrentLoad(generation)) return
            if (playlistId == null) {
                loadedImageId = null
                loadedOrientation = orientation
                kind = MediaKind.IMAGE
                allowCanvas = true
                releaseMediaAndAwait()
                if (!isCurrentLoad(generation)) return
                drawFrame()
                return
            }
            val playlist = repo.getPlaylist(playlistId) ?: return
            playlistTarget = WallpaperTarget.from(playlist.target)
            val usePowerSaveStatic =
                applicationContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true &&
                    repo.powerSavePolicy() == PowerSavePolicy.STATIC
            val imageId = if (usePowerSaveStatic) {
                repo.powerSaveStaticImage(playlistId)?.id
            } else {
                repo.ensureShuffle(playlistId).currentImageId
            }
            if (imageId == null) {
                loadedImageId = null
                loadedOrientation = orientation
                kind = MediaKind.IMAGE
                allowCanvas = true
                releaseMediaAndAwait()
                if (isCurrentLoad(generation)) drawFrame()
                return
            }
            val image = repo.getImage(imageId) ?: return
            val nextTransform = repo.getTransforms(imageId)[orientation] ?: ImageTransform.Default
            if (!repo.fileExists(image)) return
            if (!isCurrentLoad(generation)) return
            val file = File(app.filesDir, "images/${image.storedName}")
            val sourceKind = MediaKind.from(image.mediaType)
            val nextLockStyle = if (
                rendersLockScreen() && playlistTarget != WallpaperTarget.HOME
            ) {
                repo.lockStyle(image, playlist)
            } else {
                LockStyle.None
            }
            val playVideoOnLock = repo.flag(WallpurrRepository.KEY_PLAY_VIDEO_ON_LOCK, false)
            val nextKind = if (usePowerSaveStatic) {
                MediaKind.IMAGE
            } else if (
                sourceKind == MediaKind.VIDEO &&
                isLockOnlyEngine() &&
                (!playVideoOnLock || nextLockStyle.hasEffects)
            ) {
                MediaKind.IMAGE
            } else if (
                sourceKind == MediaKind.GIF &&
                isLockOnlyEngine() &&
                nextLockStyle.blurRadius > 0
            ) {
                MediaKind.IMAGE
            } else {
                sourceKind
            }
            val lockStyleRequiresDecode = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                isLockOnlyEngine() &&
                nextLockStyle.blurRadius != lockStyle.blurRadius
            val canvasNeedsSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                isLockOnlyEngine() &&
                nextLockStyle.blurRadius > 0
            val cachedCanvasResolutionIsEnough = when (nextKind) {
                MediaKind.GIF -> true
                MediaKind.IMAGE -> bitmap?.let { cached ->
                    maxOf(cached.width, cached.height) >= decodeMaxSide(
                        image,
                        nextTransform,
                        canvasNeedsSoftwareBlur,
                    )
                } == true
                MediaKind.VIDEO -> false
            }
            val sameCanvasSource = imageId == loadedImageId &&
                nextKind == kind &&
                nextKind != MediaKind.VIDEO &&
                !videoOwnsSurface &&
                !lockStyleRequiresDecode &&
                (bitmap != null || gif != null) &&
                cachedCanvasResolutionIsEnough
            val sameRendererBitmap = imageId == loadedImageId &&
                nextKind == MediaKind.IMAGE &&
                !nextLockStyle.hasEffects &&
                !usePowerSaveStatic &&
                rendererShowsBitmap &&
                videoOwnsSurface &&
                videoRenderer != null &&
                rendererBitmapMaxSide >= decodeMaxSide(
                    image,
                    nextTransform,
                    needsSoftwareBlur = false,
                )
            val sameVideo = imageId == loadedImageId &&
                nextKind == MediaKind.VIDEO &&
                player != null &&
                videoRenderer != null
            if (sameRendererBitmap) {
                val hadBackdrop = rendererBlurBackdropImageId == imageId
                val replaceBackdrop = nextTransform.usesBlurContainBackground() && !hadBackdrop
                val backdrop = if (
                    replaceBackdrop
                ) {
                    decodeContainBackdrop(repo, image, nextTransform)
                } else {
                    null
                }
                if (!isCurrentLoad(generation)) {
                    backdrop?.recycle()
                    return
                }
                commitLoadedMedia(
                    imageId,
                    image,
                    nextKind,
                    nextTransform,
                    nextLockStyle,
                    orientation,
                )
                allowCanvas = false
                videoOwnsSurface = true
                videoRenderer?.updateBitmapLayout(
                    contentWidth = image.width,
                    contentHeight = image.height,
                    viewportWidth = surfaceWidth.coerceAtLeast(1),
                    viewportHeight = surfaceHeight.coerceAtLeast(1),
                    transform = nextTransform,
                    backdrop = backdrop,
                    replaceBackdrop = replaceBackdrop,
                    expectedOrientation = orientation,
                )
                rendererBlurBackdropImageId = imageId.takeIf {
                    hadBackdrop || backdrop != null
                }
                applyVisibility()
                return
            }
            if (sameCanvasSource) {
                val needsBackdropRefresh =
                    nextTransform.usesBlurContainBackground() &&
                    containBackdropImageId != imageId
                val needsVisualTransition =
                    transform != nextTransform ||
                    loadedOrientation != orientation ||
                    lockStyle != nextLockStyle ||
                    needsBackdropRefresh
                if (needsVisualTransition) {
                    beginCanvasTransition(
                        durationMs = WallpaperTransitionSpec.ORIENTATION_DURATION_MS,
                        expectedLoadGeneration = generation,
                        captureCurrentSceneOnly = true,
                    )
                    if (!isCurrentLoad(generation)) return
                    // Hold a complete, center-cropped outgoing scene while a new contain backdrop
                    // is decoded. Never expose the previous transform at the new Surface size.
                    if (canvasTransitionFrom != null) drawFrame()
                }
                val backdrop = if (needsBackdropRefresh) {
                    decodeContainBackdrop(repo, image, nextTransform)
                } else {
                    null
                }
                if (!isCurrentLoad(generation)) {
                    backdrop?.recycle()
                    return
                }
                commitLoadedMedia(
                    imageId,
                    image,
                    nextKind,
                    nextTransform,
                    nextLockStyle,
                    orientation,
                )
                if (backdrop != null) adoptCanvasBackdrop(imageId, backdrop)
                prepareCanvasTransitionTargetIfNeeded(generation, orientation)
                if (!isCurrentLoad(generation)) return
                // Resolution sufficiency was checked before entering this branch, so transform and
                // target become visible in one commit rather than a low-res step plus a second fade.
                drawFrame()
                applyVisibility()
                return
            }
            if (sameVideo) {
                val hadBackdrop = rendererBlurBackdropImageId == imageId
                val replaceBackdrop = nextTransform.usesBlurContainBackground() && !hadBackdrop
                val backdrop = if (
                    replaceBackdrop
                ) {
                    decodeContainBackdrop(repo, image, nextTransform)
                } else {
                    null
                }
                if (!isCurrentLoad(generation)) {
                    backdrop?.recycle()
                    return
                }
                commitLoadedMedia(
                    imageId,
                    image,
                    nextKind,
                    nextTransform,
                    nextLockStyle,
                    orientation,
                )
                attachVideoDisplay(backdrop, replaceBackdrop = replaceBackdrop)
                rendererBlurBackdropImageId = imageId.takeIf {
                    hadBackdrop || backdrop != null
                }
                applyVisibility()
                return
            }
            if (
                nextKind == MediaKind.VIDEO &&
                videoRenderer != null &&
                videoInputSurface?.isValid == true &&
                videoOwnsSurface
            ) {
                // Keep the EGL window producer and its last presented frame. Only replace the
                // decoder feeding its SurfaceTexture, avoiding the video -> Canvas -> EGL cycle.
                val backdrop = decodeContainBackdrop(repo, image, nextTransform)
                if (!isCurrentLoad(generation)) {
                    backdrop?.recycle()
                    return
                }
                // Commit only after the generation check: if a superseded load had published
                // video B's id while the player still plays A, `sameVideo` would later match
                // and never replace the player. The backdrop decode does not read this state.
                commitLoadedMedia(
                    imageId,
                    image,
                    nextKind,
                    nextTransform,
                    nextLockStyle,
                    orientation,
                )
                replaceVideoInPlace(generation, file, image, backdrop)
                rendererBlurBackdropImageId = imageId.takeIf {
                    nextTransform.usesBlurContainBackground() && backdrop != null
                }
                return
            }
            when (nextKind) {
                MediaKind.VIDEO -> {
                    // Decode a bridge frame while the old media remains visible. It is presented
                    // before the Canvas/EGL producer hand-off, so OEMs that clear a disconnected
                    // Surface never expose a black buffer while the new MediaPlayer prepares.
                    val (poster, posterBackdrop) = withCancellationSafeResult(
                        dispatcher = Dispatchers.Default,
                        produce = {
                            val decodedPoster = repo.decodeBitmap(
                                image,
                                minOf(
                                    decodeMaxSide(image, nextTransform, needsSoftwareBlur = false),
                                    maxOf(surfaceWidth, surfaceHeight, MIN_BITMAP_DECODE_SIDE),
                                ),
                            )
                            decodedPoster to createContainBackdrop(decodedPoster, nextTransform)
                        },
                        release = { (decodedPoster, decodedBackdrop) ->
                            decodedPoster.recycle()
                            decodedBackdrop?.recycle()
                        },
                    )
                    if (!isCurrentLoad(generation)) {
                        poster.recycle()
                        posterBackdrop?.recycle()
                        return
                    }
                    var rendererTransitionFrame: Bitmap? = null
                    var adopted = false
                    var backdropAdopted = false
                    try {
                        rendererTransitionFrame = detachCanvasTransitionForRenderer(generation)
                        if (!isCurrentLoad(generation)) return
                        releaseVideoPipelineIfNeeded()
                        if (!isCurrentLoad(generation)) return
                        val oldBitmap = bitmap
                        val oldGif = gif
                        (oldGif as? Animatable)?.stop()
                        oldGif?.callback = null
                        bitmap = poster
                        gif = null
                        commitLoadedMedia(
                            imageId,
                            image,
                            MediaKind.IMAGE,
                            nextTransform,
                            nextLockStyle,
                            orientation,
                        )
                        adoptCanvasBackdrop(imageId, posterBackdrop)
                        backdropAdopted = true
                        if (rendererTransitionFrame == null) drawFrame()
                        adopted = true
                        if (oldBitmap !== poster) oldBitmap?.recycle()
                        commitLoadedMedia(
                            imageId,
                            image,
                            nextKind,
                            nextTransform,
                            nextLockStyle,
                            orientation,
                        )
                        startVideo(file, image, rendererTransitionFrame)
                        rendererTransitionFrame = null
                    } finally {
                        if (!adopted) poster.recycle()
                        if (!backdropAdopted) posterBackdrop?.recycle()
                        rendererTransitionFrame?.recycle()
                    }
                }
                MediaKind.GIF -> {
                    val drawable = withCancellationSafeResult(
                        dispatcher = Dispatchers.IO,
                        produce = {
                            ImageDecoder.decodeDrawable(
                                ImageDecoder.createSource(file),
                            ) { decoder, _, _ ->
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        },
                        release = { decoded ->
                            (decoded as? Animatable)?.stop()
                            decoded.callback = null
                        },
                    )
                    var adopted = false
                    var backdropAdopted = false
                    var backdrop: Bitmap? = null
                    var rendererTransitionFrame: Bitmap? = null
                    try {
                        backdrop = decodeContainBackdrop(repo, image, nextTransform)
                        if (!isCurrentLoad(generation)) return
                        rendererTransitionFrame = videoRenderer?.let {
                            captureRendererTransitionFrame(it)
                        }
                        if (!isCurrentLoad(generation)) return
                        releaseVideoPipelineIfNeeded()
                        if (!isCurrentLoad(generation)) return
                        if (rendererTransitionFrame != null) {
                            adoptCanvasTransition(rendererTransitionFrame)
                            rendererTransitionFrame = null
                        } else {
                            beginCanvasTransition(expectedLoadGeneration = generation)
                            if (!isCurrentLoad(generation)) return
                        }
                        val oldBitmap = bitmap
                        val oldGif = gif
                        (oldGif as? Animatable)?.stop()
                        oldGif?.callback = null
                        bitmap = null
                        gif = null
                        commitLoadedMedia(
                            imageId,
                            image,
                            nextKind,
                            nextTransform,
                            nextLockStyle,
                            orientation,
                        )
                        adoptCanvasBackdrop(imageId, backdrop)
                        backdropAdopted = true
                        startGif(drawable, drawImmediately = false)
                        prepareCanvasTransitionTargetIfNeeded(generation, orientation)
                        if (!isCurrentLoad(generation)) return
                        drawFrame()
                        adopted = true
                        oldBitmap?.recycle()
                    } finally {
                        if (!adopted) {
                            (drawable as? Animatable)?.stop()
                            drawable.callback = null
                            clearCanvasTransition()
                        }
                        if (!backdropAdopted) backdrop?.recycle()
                        rendererTransitionFrame?.recycle()
                    }
                }
                MediaKind.IMAGE -> {
                    val (decoded, decodedBackdrop) = withCancellationSafeResult(
                        dispatcher = Dispatchers.Default,
                        produce = {
                            val needsSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                                isLockOnlyEngine() &&
                                nextLockStyle.blurRadius > 0
                            val maxSide = decodeMaxSide(image, nextTransform, needsSoftwareBlur)
                            val source = repo.decodeBitmap(image, maxSide)
                            val rendered = if (needsSoftwareBlur) {
                                blurLockBitmap(
                                    source,
                                    nextLockStyle,
                                    resources.displayMetrics.density,
                                )
                            } else {
                                source
                            }.also { blurred ->
                                if (blurred !== source) source.recycle()
                            }
                            rendered to createContainBackdrop(rendered, nextTransform)
                        },
                        release = { (decodedImage, decodedBackdrop) ->
                            decodedImage.recycle()
                            decodedBackdrop?.recycle()
                        },
                    )
                    if (!isCurrentLoad(generation)) {
                        decoded.recycle()
                        decodedBackdrop?.recycle()
                        return
                    }
                    var adopted = false
                    var backdropAdopted = false
                    var canvasHandoffFrame: Bitmap? = null
                    try {
                        val persistentRenderer = videoRenderer?.takeIf {
                            videoOwnsSurface &&
                                videoInputSurface?.isValid == true &&
                                !nextLockStyle.hasEffects &&
                                !usePowerSaveStatic
                        }
                        if (persistentRenderer != null) {
                            // Upload the decoded image through the existing EGL producer. The old
                            // video/image buffer remains visible until this swap completes.
                            videoSessionId += 1
                            releasePlayerOnly(player)
                            player = null
                            videoPrepared = false
                            videoFramePresented = false
                            pauseAfterFirstVideoFrame = false
                            weBelieveStarted = false
                            val oldBitmap = bitmap
                            val oldGif = gif
                            (oldGif as? Animatable)?.stop()
                            oldGif?.callback = null
                            bitmap = null
                            gif = null
                            commitLoadedMedia(
                                imageId,
                                image,
                                nextKind,
                                nextTransform,
                                nextLockStyle,
                                orientation,
                            )
                            allowCanvas = false
                            videoOwnsSurface = true
                            rendererShowsBitmap = false
                            rendererBitmapPending = true
                            rendererBitmapMaxSide = maxOf(decoded.width, decoded.height)
                            adopted = true // The render thread now owns and recycles decoded.
                            backdropAdopted = true
                            persistentRenderer.presentBitmap(
                                frame = decoded,
                                contentWidth = image.width,
                                contentHeight = image.height,
                                viewportWidth = surfaceWidth.coerceAtLeast(1),
                                viewportHeight = surfaceHeight.coerceAtLeast(1),
                                transform = nextTransform,
                                backdrop = decodedBackdrop,
                                expectedOrientation = orientation,
                            ) presented@{ presented ->
                                if (
                                    !isCurrentLoad(generation) ||
                                    videoRenderer !== persistentRenderer
                                ) return@presented
                                rendererBitmapPending = false
                                if (presented) {
                                    rendererFailureCount = 0
                                    rendererShowsBitmap = true
                                    scope.launch { applyVisibility() }
                                } else {
                                    releaseMedia { reloadAfterRendererFailure() }
                                }
                            }
                            rendererBlurBackdropImageId = imageId.takeIf {
                                nextTransform.usesBlurContainBackground() && decodedBackdrop != null
                            }
                            oldBitmap?.recycle()
                            return
                        }

                        if (
                            videoRenderer == null &&
                            !nextLockStyle.hasEffects &&
                            !usePowerSaveStatic &&
                            surfaceHolder.surface.isValid
                        ) {
                            var rendererTransitionFrame = detachCanvasTransitionForRenderer(generation)
                            val oldBitmap = bitmap
                            val oldGif = gif
                            commitLoadedMedia(
                                imageId,
                                image,
                                nextKind,
                                nextTransform,
                                nextLockStyle,
                                orientation,
                            )
                            adopted = startBitmapRenderer(
                                decoded,
                                decodedBackdrop,
                                image,
                                generation,
                                rendererTransitionFrame,
                            )
                            if (adopted) rendererTransitionFrame = null
                            rendererTransitionFrame?.recycle()
                            if (adopted) {
                                backdropAdopted = true
                                rendererBlurBackdropImageId = imageId.takeIf {
                                    nextTransform.usesBlurContainBackground() && decodedBackdrop != null
                                }
                                (oldGif as? Animatable)?.stop()
                                oldGif?.callback = null
                                bitmap = null
                                gif = null
                                oldBitmap?.recycle()
                                return
                            }
                        }

                        canvasHandoffFrame = videoRenderer?.let {
                            captureRendererTransitionFrame(it)
                        }
                        if (!isCurrentLoad(generation)) {
                            canvasHandoffFrame?.recycle()
                            canvasHandoffFrame = null
                            return
                        }
                        releaseVideoPipelineIfNeeded()
                        if (!isCurrentLoad(generation)) {
                            canvasHandoffFrame?.recycle()
                            canvasHandoffFrame = null
                            return
                        }
                        if (canvasHandoffFrame != null) {
                            adoptCanvasTransition(canvasHandoffFrame)
                            canvasHandoffFrame = null
                        } else {
                            beginCanvasTransition(expectedLoadGeneration = generation)
                            if (!isCurrentLoad(generation)) return
                        }
                        val oldBitmap = bitmap
                        val oldGif = gif
                        (oldGif as? Animatable)?.stop()
                        oldGif?.callback = null
                        bitmap = decoded
                        gif = null
                        commitLoadedMedia(
                            imageId,
                            image,
                            nextKind,
                            nextTransform,
                            nextLockStyle,
                            orientation,
                        )
                        adoptCanvasBackdrop(imageId, decodedBackdrop)
                        backdropAdopted = true
                        prepareCanvasTransitionTargetIfNeeded(generation, orientation)
                        if (!isCurrentLoad(generation)) return
                        drawFrame()
                        adopted = true
                        if (oldBitmap !== decoded) oldBitmap?.recycle()
                    } finally {
                        if (!adopted) {
                            decoded.recycle()
                            clearCanvasTransition()
                        }
                        if (!backdropAdopted) decodedBackdrop?.recycle()
                        canvasHandoffFrame?.recycle()
                    }
                }
            }
            if (!isCurrentLoad(generation)) return
            applyVisibility()
        }

        /**
         * Decode only the pixels the configured layout can actually place on screen. A fixed 2x
         * viewport target decoded many portrait/tablet images at roughly four times the necessary
         * pixel count; mismatched aspect ratios and user zoom still request the extra detail they
         * genuinely need.
         */
        private fun decodeMaxSide(
            image: ImageAssetEntity,
            imageTransform: ImageTransform,
            needsSoftwareBlur: Boolean,
        ): Int {
            val qualityTarget = ImageLayout.requiredDecodeMaxSide(
                imageWidth = image.width,
                imageHeight = image.height,
                viewportWidth = surfaceWidth,
                viewportHeight = surfaceHeight,
                transform = imageTransform,
                minimumSide = MIN_BITMAP_DECODE_SIDE,
            )
            return if (needsSoftwareBlur) {
                minOf(qualityTarget, SOFTWARE_BLUR_DECODE_SIDE)
            } else {
                qualityTarget
            }
        }

        private fun commitLoadedMedia(
            imageId: String,
            image: ImageAssetEntity,
            mediaKind: MediaKind,
            imageTransform: ImageTransform,
            imageLockStyle: LockStyle,
            imageOrientation: ScreenOrientation,
        ) {
            invalidateBlurCache()
            if (containBackdropImageId != imageId) {
                containBackdrop?.recycle()
                containBackdrop = null
                containBackdropImageId = null
            }
            if (rendererBlurBackdropImageId != imageId) rendererBlurBackdropImageId = null
            // A different image gets a fresh failure budget.
            if (rendererFailureImageId != imageId) {
                rendererFailureImageId = null
                rendererFailureCount = 0
            }
            loadedImageId = imageId
            loadedOrientation = imageOrientation
            kind = mediaKind
            transform = imageTransform
            lockStyle = imageLockStyle
            mediaWidth = image.width
            mediaHeight = image.height
            if (surfaceWidth > 0 && surfaceHeight > 0) {
                loadedViewportWidth = surfaceWidth
                loadedViewportHeight = surfaceHeight
            }
            allowCanvas = mediaKind != MediaKind.VIDEO
        }

        private suspend fun releaseVideoPipelineIfNeeded() {
            if (player != null || videoRenderer != null || videoOwnsSurface) {
                releaseMediaAndAwait()
            }
        }

        private fun isCurrentLoad(generation: Long): Boolean =
            generation == loadGeneration && surfaceReady

        /**
         * A persistent EGL failure (oversized texture, BufferQueue already connected, bad alloc)
         * would otherwise loop decode -> EGL init -> fail -> release forever. Retry a couple of
         * times with backoff, then stop until a different image is loaded.
         */
        private fun reloadAfterRendererFailure() {
            if (!surfaceReady) return
            val failedImageId = loadedImageId
            if (failedImageId != rendererFailureImageId) {
                rendererFailureImageId = failedImageId
                rendererFailureCount = 0
            }
            val attempt = rendererFailureCount
            rendererFailureCount += 1
            if (attempt >= RENDERER_RELOAD_BACKOFF_MS.size) {
                Log.e(TAG, "Renderer failed repeatedly for $failedImageId; not reloading")
                return
            }
            val delayMs = RENDERER_RELOAD_BACKOFF_MS[attempt]
            scope.launch {
                delay(delayMs)
                if (surfaceReady) loadCurrent()
            }
        }

        /**
         * `withContext` has a prompt-cancellation hand-off: work can finish on the worker thread
         * but its result can still be discarded before Main receives it. Keep a temporary owner so
         * decoded native resources are released in that narrow window instead of waiting for GC.
         */
        private suspend fun <T : Any> withCancellationSafeResult(
            dispatcher: CoroutineDispatcher,
            produce: () -> T,
            release: (T) -> Unit,
        ): T {
            var completed: T? = null
            var ownershipTransferred = false
            try {
                val result = withContext(dispatcher) {
                    produce().also { completed = it }
                }
                ownershipTransferred = true
                return result
            } finally {
                if (!ownershipTransferred) {
                    completed?.let { resource ->
                        runCatching { release(resource) }
                            .onFailure { Log.w(TAG, "Unable to release cancelled decode", it) }
                    }
                }
            }
        }

        private suspend fun decodeContainBackdrop(
            repository: WallpurrRepository,
            image: ImageAssetEntity,
            imageTransform: ImageTransform,
        ): Bitmap? {
            if (!imageTransform.usesBlurContainBackground()) return null
            var completed: Bitmap? = null
            var ownershipTransferred = false
            try {
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val source = repository.decodeBitmap(image, CONTAIN_BACKDROP_MAX_SIDE)
                        try {
                            createContainBackdrop(source, imageTransform)
                        } finally {
                            if (!source.isRecycled) source.recycle()
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to prepare contain backdrop; using solid color", error)
                    }.getOrNull().also { completed = it }
                }
                ownershipTransferred = true
                return result
            } finally {
                if (!ownershipTransferred && completed?.isRecycled == false) completed?.recycle()
            }
        }

        private fun createContainBackdrop(
            source: Bitmap,
            imageTransform: ImageTransform,
        ): Bitmap? = if (imageTransform.usesBlurContainBackground()) {
            runCatching {
                createBlurredContainBackdrop(source)
            }.onFailure { error ->
                Log.w(TAG, "Unable to blur contain backdrop; using solid color", error)
            }.getOrNull()
        } else {
            null
        }

        private fun ImageTransform.usesBlurContainBackground(): Boolean =
            usesContainBackground && containBackgroundMode == ContainBackgroundMode.BLUR

        private fun adoptCanvasBackdrop(imageId: String, backdrop: Bitmap?) {
            if (containBackdrop !== backdrop) containBackdrop?.recycle()
            containBackdrop = backdrop
            containBackdropImageId = imageId.takeIf { backdrop != null }
        }

        private fun scheduleInitialDestinationReconcile() {
            if (isPreview || initialDestinationReconcileScheduled) return
            initialDestinationReconcileScheduled = true
            serviceScope.launch {
                // WallpaperInfo/flags can lag slightly behind the first real engine callback on
                // Samsung and other OEM launchers. Re-read destinations after ownership settles.
                delay(INITIAL_DESTINATION_RECONCILE_MS)
                val app = applicationContext as? WallpurrApplication ?: return@launch
                app.container.rotation.reconcileWallpaperDestinations()
            }
        }

        private fun isLoadedMediaMissing(): Boolean {
            if (loadedImageId == null) return false
            return when (kind) {
                MediaKind.IMAGE -> if (videoOwnsSurface) {
                    videoRenderer == null || (!rendererShowsBitmap && !rendererBitmapPending)
                } else {
                    bitmap == null
                }
                MediaKind.GIF -> gif == null
                MediaKind.VIDEO -> player == null || videoRenderer == null
            }
        }

        private fun startGif(drawable: Drawable, drawImmediately: Boolean = true) {
            drawable.callback = gifCallback
            (drawable as? AnimatedImageDrawable)?.repeatCount =
                AnimatedImageDrawable.REPEAT_INFINITE
            gif = drawable
            drawable.alpha = 255
            allowCanvas = true
            (drawable as? Animatable)?.start()
            if (drawImmediately) drawFrame()
        }

        private fun startBitmapRenderer(
            frame: Bitmap,
            backdrop: Bitmap?,
            image: ImageAssetEntity,
            generation: Long,
            transitionFromFrame: Bitmap? = null,
        ): Boolean {
            val outputSurface = surfaceHolder.surface
            if (!surfaceReady || !outputSurface.isValid) return false
            releaseCanvasSurface()
            allowCanvas = false
            videoOwnsSurface = true
            rendererShowsBitmap = false
            rendererBitmapPending = true
            rendererBitmapMaxSide = maxOf(frame.width, frame.height)
            videoPrepared = false
            videoFramePresented = false
            lateinit var renderer: TransformedVideoRenderer
            return try {
                renderer = TransformedVideoRenderer(
                    outputSurface = outputSurface,
                    callbackHandler = handler,
                    contentWidth = image.width,
                    contentHeight = image.height,
                    viewportWidth = surfaceWidth.coerceAtLeast(1),
                    viewportHeight = surfaceHeight.coerceAtLeast(1),
                    transform = transform,
                    initialFrame = frame,
                    initialBackdrop = backdrop,
                    transitionFromFrame = transitionFromFrame,
                    transitionsInitiallyEnabled = transitionsAllowed(),
                    onReady = ready@{ inputSurface ->
                        if (
                            videoRenderer !== renderer ||
                            !isCurrentLoad(generation)
                        ) return@ready
                        videoInputSurface = inputSurface
                        rendererBitmapPending = false
                        rendererShowsBitmap = true
                        scope.launch { applyVisibility() }
                    },
                    onFirstVideoFramePresented = presented@{ presentedSessionId ->
                        if (
                            videoRenderer !== renderer ||
                            videoSessionId != presentedSessionId ||
                            !surfaceReady
                        ) return@presented
                        videoFramePresented = true
                        rendererFailureCount = 0
                        rendererShowsBitmap = false
                        rendererBitmapPending = false
                        rendererBitmapMaxSide = 0
                        if (pauseAfterFirstVideoFrame) {
                            scope.launch { applyVisibility() }
                        }
                    },
                    onError = rendererError@{ error ->
                        if (videoRenderer !== renderer) return@rendererError
                        Log.e(TAG, "Wallpaper GL renderer failed", error)
                        releaseMedia { reloadAfterRendererFailure() }
                    },
                )
                videoRenderer = renderer
                true
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to create bitmap EGL renderer", error)
                allowCanvas = true
                videoOwnsSurface = false
                rendererBitmapPending = false
                rendererBitmapMaxSide = 0
                false
            }
        }

        private fun startVideo(
            file: File,
            image: ImageAssetEntity,
            transitionFromFrame: Bitmap? = null,
        ) {
            val outputSurface = surfaceHolder.surface
            if (!surfaceReady || !outputSurface.isValid) {
                transitionFromFrame?.recycle()
                return
            }
            // Transfer ownership to the EGL renderer. It uploads and presents this frame before
            // MediaPlayer is connected, then recycles it on its render thread.
            val initialFrame = bitmap
            bitmap = null
            val initialBackdrop = containBackdrop
            containBackdrop = null
            containBackdropImageId = null
            releaseCanvasSurface()
            allowCanvas = false
            videoOwnsSurface = true
            rendererShowsBitmap = false
            rendererBitmapPending = false
            rendererBitmapMaxSide = 0
            videoPrepared = false
            videoFramePresented = false
            pauseAfterFirstVideoFrame = false
            weBelieveStarted = false
            videoLoops = 0
            val media = MediaPlayer()
            player = media
            val sessionId = ++videoSessionId
            lateinit var renderer: TransformedVideoRenderer
            renderer = TransformedVideoRenderer(
                outputSurface = outputSurface,
                callbackHandler = handler,
                contentWidth = image.width,
                contentHeight = image.height,
                viewportWidth = surfaceWidth.coerceAtLeast(1),
                viewportHeight = surfaceHeight.coerceAtLeast(1),
                transform = transform,
                initialFrame = initialFrame,
                initialBackdrop = initialBackdrop,
                transitionFromFrame = transitionFromFrame,
                transitionsInitiallyEnabled = transitionsAllowed(),
                onReady = ready@{ inputSurface ->
                    if (videoRenderer !== renderer || player !== media || !surfaceReady) return@ready
                    videoInputSurface = inputSurface
                    bindVideoPlayer(media, renderer, inputSurface, file, image, sessionId)
                },
                onFirstVideoFramePresented = presented@{ presentedSessionId ->
                    if (
                        videoRenderer !== renderer ||
                        videoSessionId != presentedSessionId ||
                        !surfaceReady
                    ) return@presented
                    videoFramePresented = true
                    rendererFailureCount = 0
                    rendererShowsBitmap = false
                    rendererBitmapPending = false
                    rendererBitmapMaxSide = 0
                    if (pauseAfterFirstVideoFrame) {
                        scope.launch { applyVisibility() }
                    }
                },
                onError = rendererError@{ error ->
                    if (videoRenderer !== renderer) return@rendererError
                    Log.e(TAG, "Video renderer failed", error)
                    // This renderer can now outlive several videos, so the constructor's file/image
                    // may no longer be current. Reload repository state instead of retrying stale
                    // media captured by this callback.
                    releaseMedia { reloadAfterRendererFailure() }
                },
            )
            videoRenderer = renderer
            rendererBlurBackdropImageId = loadedImageId.takeIf {
                transform.usesBlurContainBackground() && initialBackdrop != null
            }
        }

        private fun replaceVideoInPlace(
            generation: Long,
            file: File,
            image: ImageAssetEntity,
            backdrop: Bitmap? = null,
        ) {
            val renderer = videoRenderer ?: run {
                backdrop?.recycle()
                return
            }
            videoSessionId += 1
            releasePlayerOnly(player)
            player = null
            videoPrepared = false
            videoFramePresented = false
            pauseAfterFirstVideoFrame = false
            weBelieveStarted = false
            videoLoops = 0
            videoOwnsSurface = true
            rendererShowsBitmap = false
            rendererBitmapPending = false
            rendererBitmapMaxSide = 0
            allowCanvas = false
            renderer.prepareForNextVideo(
                contentWidth = image.width,
                contentHeight = image.height,
                viewportWidth = surfaceWidth.coerceAtLeast(1),
                viewportHeight = surfaceHeight.coerceAtLeast(1),
                transform = transform,
                backdrop = backdrop,
                expectedOrientation = loadedOrientation,
                onInputReady = next@{ inputSurface ->
                    if (!isCurrentLoad(generation) || videoRenderer !== renderer) return@next
                    if (inputSurface == null) {
                        Log.w(TAG, "Video input surface became unavailable; recreating EGL")
                        releaseMedia { reloadAfterRendererFailure() }
                        return@next
                    }
                    videoInputSurface = inputSurface
                    val media = MediaPlayer()
                    player = media
                    val sessionId = ++videoSessionId
                    bindVideoPlayer(media, renderer, inputSurface, file, image, sessionId)
                },
            )
        }

        private fun bindVideoPlayer(
            media: MediaPlayer,
            renderer: TransformedVideoRenderer,
            inputSurface: Surface,
            file: File,
            image: ImageAssetEntity,
            sessionId: Long,
        ) {
            if (
                videoRenderer !== renderer ||
                player !== media ||
                videoSessionId != sessionId ||
                !surfaceReady
            ) return
            val mode = VideoPlayMode.from(image.videoPlayMode)
            try {
                media.bindMutedVideo(
                    file = file,
                    surface = inputSurface,
                    looping = mode == VideoPlayMode.MINUTES,
                )
                media.setOnErrorListener { _, what, extra ->
                    if (
                        player !== media ||
                        videoRenderer !== renderer ||
                        videoSessionId != sessionId
                    ) {
                        return@setOnErrorListener true
                    }
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    handleVideoError(file, image, what)
                    true
                }
                media.setOnInfoListener { _, what, _ ->
                    if (
                        player !== media ||
                        videoRenderer !== renderer ||
                        videoSessionId != sessionId
                    ) {
                        return@setOnInfoListener false
                    }
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        renderer.armFirstVideoFrameCallback(sessionId)
                    }
                    false
                }
                media.setOnVideoSizeChangedListener { _, width, height ->
                    if (
                        player !== media ||
                        videoRenderer !== renderer ||
                        videoSessionId != sessionId
                    ) {
                        return@setOnVideoSizeChangedListener
                    }
                    renderer.updateLayout(
                        contentWidth = image.width.takeIf { it > 0 } ?: width,
                        contentHeight = image.height.takeIf { it > 0 } ?: height,
                        viewportWidth = surfaceWidth,
                        viewportHeight = surfaceHeight,
                        transform = transform,
                        expectedOrientation = loadedOrientation,
                    )
                }
                media.setOnCompletionListener {
                    if (
                        player !== media ||
                        videoSessionId != sessionId ||
                        mode != VideoPlayMode.TIMES
                    ) {
                        return@setOnCompletionListener
                    }
                    videoLoops += 1
                    if (videoLoops >= image.videoPlayCount.coerceAtLeast(1)) {
                        scope.launch { advanceExpected(image.id) }
                    } else {
                        weBelieveStarted = false
                        runCatching {
                            media.seekTo(0)
                            media.start()
                            weBelieveStarted = true
                        }
                    }
                }
                media.setOnPreparedListener {
                    if (
                        player !== media ||
                        videoRenderer !== renderer ||
                        videoSessionId != sessionId
                    ) {
                        return@setOnPreparedListener
                    }
                    consecutiveVideoErrors = 0
                    videoPrepared = true
                    runCatching { it.setVolume(0f, 0f) }
                    scope.launch { applyVisibility() }
                }
                media.prepareAsync()
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to start video wallpaper", error)
                handleVideoError(file, image, MediaPlayer.MEDIA_ERROR_UNKNOWN)
            }
        }

        private fun releasePlayerOnly(media: MediaPlayer?) {
            media?.setOnCompletionListener(null)
            media?.setOnPreparedListener(null)
            media?.setOnInfoListener(null)
            media?.setOnVideoSizeChangedListener(null)
            media?.setOnErrorListener(null)
            runCatching { media?.setSurface(null) }
            runCatching {
                media?.reset()
                media?.release()
            }
        }

        private fun handleVideoError(
            file: File,
            image: ImageAssetEntity,
            what: Int,
        ) {
            videoPrepared = false
            videoSessionId += 1
            videoFramePresented = false
            pauseAfterFirstVideoFrame = false
            weBelieveStarted = false
            val broken = player
            player = null
            releasePlayerOnly(broken)
            val transient = what == MediaPlayer.MEDIA_ERROR_UNKNOWN || what == -38
            val canKeepRenderer = transient &&
                videoRenderer != null &&
                videoInputSurface?.isValid == true &&
                videoOwnsSurface
            if (canKeepRenderer && consecutiveVideoErrors < MAX_VIDEO_RETRIES) {
                consecutiveVideoErrors += 1
                val retryGeneration = loadGeneration
                Log.w(
                    TAG,
                    "Retrying video decoder without recreating EGL " +
                        "($consecutiveVideoErrors/$MAX_VIDEO_RETRIES)",
                )
                handler.postDelayed({
                    if (
                        isCurrentLoad(retryGeneration) &&
                        kind == MediaKind.VIDEO &&
                        player == null &&
                        loadedImageId == image.id
                    ) {
                        replaceVideoInPlace(retryGeneration, file, image)
                    }
                }, VIDEO_RETRY_DELAY_MS)
                return
            }
            videoOwnsSurface = false
            rendererShowsBitmap = false
            rendererBitmapPending = false
            rendererBitmapMaxSide = 0
            val brokenRenderer = videoRenderer
            videoRenderer = null
            videoInputSurface = null
            val retry = {
                if (transient && consecutiveVideoErrors < MAX_VIDEO_RETRIES) {
                    consecutiveVideoErrors += 1
                    Log.w(TAG, "Retrying video wallpaper ($consecutiveVideoErrors/$MAX_VIDEO_RETRIES)")
                    handler.postDelayed({
                        if (surfaceReady &&
                            kind == MediaKind.VIDEO &&
                            player == null &&
                            loadedImageId == image.id
                        ) {
                            startVideo(file, image)
                        }
                    }, VIDEO_RETRY_DELAY_MS)
                }
            }
            releaseRenderer(brokenRenderer, retry)
        }

        private fun attachVideoDisplay(
            backdrop: Bitmap? = null,
            replaceBackdrop: Boolean = false,
        ) {
            if (!surfaceReady || player == null) {
                backdrop?.recycle()
                return
            }
            val surface = surfaceHolder.surface
            if (!surface.isValid) {
                backdrop?.recycle()
                return
            }
            val renderer = videoRenderer ?: run {
                backdrop?.recycle()
                return
            }
            renderer.updateLayout(
                contentWidth = mediaWidth,
                contentHeight = mediaHeight,
                viewportWidth = surfaceWidth,
                viewportHeight = surfaceHeight,
                transform = transform,
                backdrop = backdrop,
                replaceBackdrop = replaceBackdrop,
                expectedOrientation = loadedOrientation,
            )
            videoOwnsSurface = true
            allowCanvas = false
        }

        private suspend fun advanceExpected(imageId: String) {
            if (!isRotationOwner()) return
            val app = applicationContext as? WallpurrApplication ?: return
            app.container.rotation.advanceExpected(imageId)
        }

        private fun isRotationOwner(): Boolean {
            val realEngines = activeEngines.filterNot { it.isPreview }
                .filter { it.participatesInActiveTarget() }
            val owner = realEngines.firstOrNull {
                it.isVisibleForRotation() && it.rendersHomeScreen()
            } ?: realEngines.firstOrNull { it.isVisibleForRotation() }
                ?: realEngines.firstOrNull { it.rendersHomeScreen() }
                ?: realEngines.firstOrNull()
            return owner === this
        }

        private fun destinationFlags(): Int {
            if (isPreview || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return WallpaperManager.FLAG_SYSTEM
            }
            return wallpaperFlags
        }

        private fun rendersHomeScreen(): Boolean =
            destinationFlags() and WallpaperManager.FLAG_SYSTEM != 0

        private fun rendersLockScreen(): Boolean =
            destinationFlags() and WallpaperManager.FLAG_LOCK != 0

        private fun isLockOnlyEngine(): Boolean =
            rendersLockScreen() && !rendersHomeScreen()

        /**
         * Android can expose a single engine for both destinations. In that case the bitmap must
         * stay neutral: applying the lock style while decoding permanently leaks it into Home.
         * Select the style at draw time so a shared engine remains clear while the device is
         * unlocked and gains the lock treatment only while keyguard is showing.
         */
        private fun visibleLockStyle(): LockStyle {
            if (!rendersLockScreen() || playlistTarget == WallpaperTarget.HOME) {
                return LockStyle.None
            }
            if (isLockOnlyEngine()) return lockStyle
            return if (sharedLockStyleVisible) lockStyle else LockStyle.None
        }

        private fun setSharedLockStyleVisible(show: Boolean) {
            if (sharedLockStyleVisible == show) return
            sharedLockStyleVisible = show
            if (surfaceReady && allowCanvas) drawFrame()
        }

        private fun registerScreenStateReceiver() {
            if (screenStateReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    applicationContext.registerReceiver(
                        screenStateReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    applicationContext.registerReceiver(screenStateReceiver, filter)
                }
            }.onSuccess {
                screenStateReceiverRegistered = true
            }.onFailure {
                Log.w(TAG, "Unable to observe keyguard transitions", it)
            }
        }

        private fun unregisterScreenStateReceiver() {
            handler.removeCallbacks(redrawForScreenState)
            handler.removeCallbacks(reconcileSharedLockState)
            if (!screenStateReceiverRegistered) return
            screenStateReceiverRegistered = false
            runCatching { applicationContext.unregisterReceiver(screenStateReceiver) }
        }

        private fun isVisibleForRotation(): Boolean = visible

        private fun participatesInActiveTarget(): Boolean =
            (rendersHomeScreen() && playlistTarget != WallpaperTarget.LOCK) ||
                (rendersLockScreen() && playlistTarget != WallpaperTarget.HOME)

        private suspend fun releaseMediaAndAwait() {
            suspendCancellableCoroutine { continuation ->
                releaseMedia {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }

        private fun releaseMedia(onReleased: (() -> Unit)? = null) {
            invalidateBlurCache()
            clearCanvasTransition()
            videoSessionId += 1
            (gif as? Animatable)?.stop()
            gif?.callback = null
            gif = null
            bitmap?.recycle()
            bitmap = null
            containBackdrop?.recycle()
            containBackdrop = null
            containBackdropImageId = null
            videoOwnsSurface = false
            rendererShowsBitmap = false
            rendererBitmapPending = false
            rendererBitmapMaxSide = 0
            rendererBlurBackdropImageId = null
            videoPrepared = false
            videoFramePresented = false
            pauseAfterFirstVideoFrame = false
            weBelieveStarted = false
            val media = player
            player = null
            val renderer = videoRenderer
            videoRenderer = null
            videoInputSurface = null
            releasePlayerOnly(media)
            releaseRenderer(renderer, onReleased)
            releaseCanvasSurface()
        }

        private fun releaseRenderer(
            renderer: TransformedVideoRenderer?,
            onReleased: (() -> Unit)?,
        ) {
            onReleased?.let(rendererReleaseWaiters::add)
            if (renderer != null) {
                rendererReleasesInFlight += 1
                renderer.release {
                    rendererReleasesInFlight -= 1
                    dispatchRendererReleaseWaiters()
                }
            } else {
                dispatchRendererReleaseWaiters()
            }
        }

        private fun dispatchRendererReleaseWaiters() {
            if (rendererReleasesInFlight != 0 || rendererReleaseWaiters.isEmpty()) return
            val waiters = rendererReleaseWaiters.toList()
            rendererReleaseWaiters.clear()
            waiters.forEach { it() }
        }

        /**
         * Retains the fully composed Canvas frame before its media fields are replaced. Canvas-only
         * paths use the same current/next crossfade contract as EGL; cross-producer hand-offs pass
         * this bitmap to the EGL thread, which immediately uploads and owns it.
         */
        private suspend fun beginCanvasTransition(
            durationMs: Long = WallpaperTransitionSpec.MEDIA_DURATION_MS,
            expectedLoadGeneration: Long? = null,
            captureCurrentSceneOnly: Boolean = false,
        ) {
            val retainedOutgoing = canvasTransitionFrom
            if (
                retainedOutgoing?.isRecycled == false &&
                canvasTransitionTarget == null &&
                transitionsAllowed() &&
                (expectedLoadGeneration == null || isCurrentLoad(expectedLoadGeneration))
            ) {
                canvasTransitionDurationMs = durationMs.coerceAtLeast(1L)
                canvasTransitionStartedAtMs = 0L
                return
            }
            val outgoing = captureCanvasOutputForTransition(captureCurrentSceneOnly) ?: return
            if (
                !transitionsAllowed() ||
                (expectedLoadGeneration != null && !isCurrentLoad(expectedLoadGeneration))
            ) {
                outgoing.recycle()
                return
            }
            adoptCanvasTransition(outgoing, durationMs)
        }

        private fun adoptCanvasTransition(
            outgoing: Bitmap?,
            durationMs: Long = WallpaperTransitionSpec.MEDIA_DURATION_MS,
        ) {
            if (outgoing == null || outgoing.isRecycled) return
            if (!transitionsAllowed()) {
                outgoing.recycle()
                return
            }
            clearCanvasTransition()
            canvasTransitionFrom = outgoing
            canvasTransitionDurationMs = durationMs.coerceAtLeast(1L)
            canvasTransitionStartedAtMs = 0L
        }

        private suspend fun captureRendererTransitionFrame(
            renderer: TransformedVideoRenderer,
        ): Bitmap? {
            if (!transitionsAllowed()) return null
            return suspendCancellableCoroutine { continuation ->
                renderer.captureComposedFrame(canvasTransitionMaxSide) { captured ->
                    if (continuation.isActive) {
                        continuation.resume(captured)
                    } else {
                        captured?.recycle()
                    }
                }
            }
        }

        private suspend fun detachCanvasTransitionForRenderer(
            expectedLoadGeneration: Long,
        ): Bitmap? {
            val frame = captureCanvasOutputForTransition() ?: return null
            if (!isCurrentLoad(expectedLoadGeneration) || !transitionsAllowed()) {
                frame.recycle()
                return null
            }
            clearCanvasTransition()
            return frame
        }

        private suspend fun captureCanvasOutputForTransition(
            captureCurrentSceneOnly: Boolean = false,
        ): Bitmap? {
            if (!surfaceReady || !allowCanvas || videoOwnsSurface || !transitionsAllowed()) {
                return null
            }
            val sourceViewport = canvasTransitionSourceViewport()
            val (width, height) = canvasTransitionSize(
                sourceViewport.first,
                sourceViewport.second,
            ) ?: return null
            val output = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: return null
            val canvas = Canvas(output)
            val outgoing = canvasTransitionFrom
            val target = canvasTransitionTarget
            if (!captureCurrentSceneOnly && outgoing != null && !outgoing.isRecycled) {
                canvas.drawColor(Color.BLACK)
                drawTransitionBitmap(canvas, outgoing, alpha = 255)
                if (target != null && !target.isRecycled && canvasTransitionStartedAtMs != 0L) {
                    drawTransitionBitmap(
                        canvas,
                        target,
                        alpha = (canvasTransitionProgress() * 255f).toInt(),
                    )
                }
                transitionPaint.alpha = 255
                return output
            }
            canvas.drawColor(Color.BLACK)
            if (!drawMedia(canvas)) {
                output.recycle()
                return null
            }
            val style = transitionLockStyle()
            return try {
                styleTransitionBitmap(
                    source = output,
                    style = style,
                    referenceViewportWidth = sourceViewport.first,
                    referenceViewportHeight = sourceViewport.second,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Unable to prepare outgoing Canvas transition", error)
                null
            }
        }

        private suspend fun prepareCanvasTransitionTargetIfNeeded(
            expectedLoadGeneration: Long,
            expectedOrientation: ScreenOrientation,
        ) {
            val expectedOutgoing = canvasTransitionFrom
                ?.takeUnless { it.isRecycled }
                ?: return
            if (canvasTransitionTarget != null) return
            if (!isCurrentLoad(expectedLoadGeneration)) {
                if (canvasTargetLoadGeneration == expectedLoadGeneration) clearCanvasTargetWait()
                return
            }
            val now = SystemClock.uptimeMillis()
            val targetChanged = canvasTargetLoadGeneration != expectedLoadGeneration ||
                canvasTargetOrientation != expectedOrientation
            if (targetChanged) canvasTargetWaitStartedAtMs = now
            canvasTargetLoadGeneration = expectedLoadGeneration
            canvasTargetOrientation = expectedOrientation
            val waitExpired = canvasTargetWaitStartedAtMs != 0L &&
                now - canvasTargetWaitStartedAtMs >= CANVAS_SURFACE_ORIENTATION_WAIT_MS
            val surfaceMatches = canvasSurfaceMatches(expectedOrientation)
            if (!surfaceMatches && !waitExpired) {
                handler.removeCallbacks(retryCanvasTransitionTarget)
                handler.postDelayed(
                    retryCanvasTransitionTarget,
                    CANVAS_SURFACE_ORIENTATION_RETRY_MS,
                )
                return
            }
            clearCanvasTargetWait()
            val (width, height) = canvasTransitionSize() ?: return
            val target = runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }.getOrNull() ?: run {
                clearCanvasTransition()
                return
            }
            val canvas = Canvas(target)
            canvas.drawColor(Color.BLACK)
            if (!drawMedia(canvas)) {
                target.recycle()
                clearCanvasTransition()
                return
            }
            val style = transitionLockStyle()
            val styled = try {
                styleTransitionBitmap(
                    source = target,
                    style = style,
                    referenceViewportWidth = surfaceWidth,
                    referenceViewportHeight = surfaceHeight,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Unable to prepare incoming Canvas transition", error)
                if (canvasTransitionFrom === expectedOutgoing) clearCanvasTransition()
                return
            }
            if (
                !isCurrentLoad(expectedLoadGeneration) ||
                !transitionsAllowed() ||
                canvasTransitionFrom !== expectedOutgoing
            ) {
                styled.recycle()
                return
            }
            canvasTransitionTarget?.recycle()
            canvasTransitionTarget = styled
            canvasTransitionStartedAtMs = SystemClock.uptimeMillis()
        }

        private fun canvasSurfaceMatches(expectedOrientation: ScreenOrientation): Boolean {
            if (surfaceWidth <= 0 || surfaceHeight <= 0 || surfaceWidth == surfaceHeight) return false
            return (surfaceWidth < surfaceHeight) ==
                (expectedOrientation == ScreenOrientation.PORTRAIT)
        }

        private fun clearCanvasTargetWait() {
            handler.removeCallbacks(retryCanvasTransitionTarget)
            canvasTargetLoadGeneration = Long.MIN_VALUE
            canvasTargetOrientation = null
            canvasTargetWaitStartedAtMs = 0L
        }

        private suspend fun styleTransitionBitmap(
            source: Bitmap,
            style: LockStyle,
            referenceViewportWidth: Int,
            referenceViewportHeight: Int,
        ): Bitmap {
            var styled: Bitmap? = null
            var ownershipReturned = false
            val blurRadiusScale = transitionBlurRadiusScale(
                source,
                referenceViewportWidth,
                referenceViewportHeight,
            )
            try {
                withContext(Dispatchers.Default) {
                    styled = applyLockStyle(source, style, blurRadiusScale)
                }
                val result = styled ?: source
                if (result !== source && !source.isRecycled) source.recycle()
                ownershipReturned = true
                return result
            } finally {
                if (!ownershipReturned) {
                    val unfinished = styled
                    if (unfinished !== source && unfinished?.isRecycled == false) unfinished.recycle()
                    if (!source.isRecycled) source.recycle()
                }
            }
        }

        private fun transitionBlurRadiusScale(
            source: Bitmap,
            referenceViewportWidth: Int,
            referenceViewportHeight: Int,
        ): Float {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
            val widthScale = if (referenceViewportWidth > 0) {
                source.width / referenceViewportWidth.toFloat()
            } else {
                1f
            }
            val heightScale = if (referenceViewportHeight > 0) {
                source.height / referenceViewportHeight.toFloat()
            } else {
                1f
            }
            // The stable RenderEffect uses physical pixels. The transition bitmap may be bounded
            // below the Surface resolution, so blur in its pixel space and account for the final
            // filtered upscale to keep the radius perceptually identical at the hand-off.
            return resources.displayMetrics.density * minOf(widthScale, heightScale, 1f)
        }

        private fun transitionLockStyle(): LockStyle {
            val style = visibleLockStyle().clamped()
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && isLockOnlyEngine()) {
                // On Android 8-11 this engine's source bitmap was already blurred during decode.
                style.copy(blurRadius = 0)
            } else {
                style
            }
        }

        private fun transitionsAllowed(): Boolean =
            (visible || isPreview) && ValueAnimator.areAnimatorsEnabled()

        private fun canvasTransitionSourceViewport(): Pair<Int, Int> {
            val orientationChanging = desiredOrientation != null &&
                loadedOrientation != null &&
                desiredOrientation != loadedOrientation
            return if (
                orientationChanging &&
                loadedViewportWidth > 0 &&
                loadedViewportHeight > 0
            ) {
                loadedViewportWidth to loadedViewportHeight
            } else {
                surfaceWidth to surfaceHeight
            }
        }

        private fun canvasTransitionSize(
            viewportWidth: Int = surfaceWidth,
            viewportHeight: Int = surfaceHeight,
        ): Pair<Int, Int>? {
            if (viewportWidth <= 0 || viewportHeight <= 0) return null
            val longest = maxOf(viewportWidth, viewportHeight).coerceAtLeast(1)
            val scale = minOf(
                1f,
                canvasTransitionMaxSide / longest.toFloat(),
            )
            return Pair(
                (viewportWidth * scale).toInt().coerceAtLeast(1),
                (viewportHeight * scale).toInt().coerceAtLeast(1),
            )
        }

        private fun canvasTransitionProgress(): Float {
            if (canvasTransitionStartedAtMs == 0L) return 0f
            val elapsed = (SystemClock.uptimeMillis() - canvasTransitionStartedAtMs).coerceAtLeast(0L)
            val fraction = (elapsed.toFloat() / canvasTransitionDurationMs).coerceIn(0f, 1f)
            return transitionInterpolator.getInterpolation(fraction)
        }

        private fun scheduleCanvasTransitionFrame() {
            if (canvasTransitionFrameScheduled || canvasTransitionFrom == null || !surfaceReady) return
            canvasTransitionFrameScheduled = true
            Choreographer.getInstance().postFrameCallback(canvasTransitionFrameCallback)
        }

        private fun clearCanvasTransition() {
            clearCanvasTargetWait()
            if (canvasTransitionFrameScheduled) {
                Choreographer.getInstance().removeFrameCallback(canvasTransitionFrameCallback)
            }
            canvasTransitionFrameScheduled = false
            val outgoing = canvasTransitionFrom
            val target = canvasTransitionTarget
            canvasTransitionFrom = null
            canvasTransitionTarget = null
            if (outgoing?.isRecycled == false) outgoing.recycle()
            if (target !== outgoing && target?.isRecycled == false) target.recycle()
            canvasTransitionStartedAtMs = 0L
            canvasTransitionDurationMs = 0L
            transitionPaint.alpha = 255
        }

        private fun drawFrame() {
            if (!allowCanvas || videoOwnsSurface || kind == MediaKind.VIDEO) return
            if (canvasTransitionFrom != null && !transitionsAllowed()) clearCanvasTransition()
            val hasDrawableFrame = bitmap?.isRecycled == false ||
                gif?.let { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 } == true
            // Never acquire and publish a buffer that only contains the black clear color. Loads,
            // Surface callbacks and renderer hand-offs can briefly leave the media fields empty;
            // keeping the last system buffer is always preferable to flashing a synthetic frame.
            if (!hasDrawableFrame) {
                scheduleFirstFrameRetry()
                return
            }
            val surface = canvasSurface() ?: run {
                scheduleFirstFrameRetry()
                return
            }
            val style = visibleLockStyle().clamped()
            val wantsHardwareBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                style.blurRadius > 0
            // The common 0/0 case stays on a direct Canvas path: no hardware layer or RenderNode.
            // Muzei's renderer keeps full-screen composition on the GPU. A hardware Canvas gives
            // stills/GIFs the same cheap full-frame upload without paying for a RenderNode when
            // blur is disabled; older devices retain the software fallback below.
            val preferHardwareCanvas = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val canvas = lockWallpaperCanvas(surface, preferHardwareCanvas) ?: run {
                scheduleFirstFrameRetry()
                return
            }
            var drewFrame = false
            try {
                val outgoing = canvasTransitionFrom
                if (outgoing != null && !outgoing.isRecycled) {
                    val target = canvasTransitionTarget
                    if (target != null && !target.isRecycled) {
                        canvas.drawColor(Color.BLACK)
                        drawTransitionBitmap(canvas, outgoing, alpha = 255)
                        val progress = canvasTransitionProgress()
                        drawTransitionBitmap(
                            canvas,
                            target,
                            alpha = (progress * 255f).toInt(),
                        )
                        transitionPaint.alpha = 255
                        drewFrame = true
                        if (progress < 1f) {
                            scheduleCanvasTransitionFrame()
                        } else {
                            clearCanvasTransition()
                            handler.post(redrawAfterCanvasTransition)
                        }
                    } else {
                        // Orientation/profile work may still be decoding its backdrop. Publish the
                        // retained scene as a center-crop holding frame instead of redrawing the old
                        // transform into the new viewport (which creates a side band/stretch step).
                        canvas.drawColor(Color.BLACK)
                        drawTransitionBitmap(canvas, outgoing, alpha = 255)
                        drewFrame = true
                    }
                } else {
                    if (outgoing != null) clearCanvasTransition()
                    drewFrame = if (wantsHardwareBlur && canvas.isHardwareAccelerated) {
                        drawHardwareBlurredFrame(canvas, style).also { drew ->
                            if (drew && style.visualDarkness > 0.001f) {
                                drawCanvasDarkness(canvas, style)
                            }
                        }
                    } else {
                        drawCanvasScene(canvas, allowHardwareBlur = false)
                    }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to draw wallpaper frame", error)
            } finally {
                val posted = runCatching { surface.unlockCanvasAndPost(canvas) }.isSuccess
                if (drewFrame && posted) {
                    drawRetryCount = 0
                    handler.removeCallbacks(retryFirstFrame)
                } else {
                    scheduleFirstFrameRetry()
                }
            }
        }

        private fun drawTransitionBitmap(canvas: Canvas, source: Bitmap, alpha: Int) {
            val destinationWidth = canvas.width.coerceAtLeast(1)
            val destinationHeight = canvas.height.coerceAtLeast(1)
            val sourceAspect = source.width.toFloat() / source.height.coerceAtLeast(1)
            val destinationAspect = destinationWidth.toFloat() / destinationHeight
            if (sourceAspect > destinationAspect) {
                val cropWidth = (source.height * destinationAspect).toInt()
                    .coerceIn(1, source.width)
                val left = (source.width - cropWidth) / 2
                transitionSourceRect.set(left, 0, left + cropWidth, source.height)
            } else {
                val cropHeight = (source.width / destinationAspect).toInt()
                    .coerceIn(1, source.height)
                val top = (source.height - cropHeight) / 2
                transitionSourceRect.set(0, top, source.width, top + cropHeight)
            }
            transitionDestinationRect.set(0, 0, destinationWidth, destinationHeight)
            transitionPaint.alpha = alpha.coerceIn(0, 255)
            canvas.drawBitmap(
                source,
                transitionSourceRect,
                transitionDestinationRect,
                transitionPaint,
            )
        }

        private fun drawCanvasScene(canvas: Canvas, allowHardwareBlur: Boolean): Boolean {
            canvas.drawColor(Color.BLACK)
            val style = visibleLockStyle().clamped()
            val drew = if (
                allowHardwareBlur &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                style.blurRadius > 0 &&
                canvas.isHardwareAccelerated
            ) {
                drawHardwareBlurredFrame(canvas, style)
            } else {
                drawMedia(canvas)
            }
            if (drew && style.visualDarkness > 0.001f) drawCanvasDarkness(canvas, style)
            return drew
        }

        private fun drawCanvasDarkness(canvas: Canvas, style: LockStyle) {
            // Darkening is a uniform overlay across the entire destination, independent of crop,
            // transparent pixels or blur edges.
            canvas.drawColor(Color.argb((style.visualDarkness * 255f).toInt(), 0, 0, 0))
        }

        private fun scheduleFirstFrameRetry() {
            if (!surfaceReady || !allowCanvas || drawRetryCount >= MAX_FIRST_FRAME_RETRIES) return
            drawRetryCount += 1
            handler.removeCallbacks(retryFirstFrame)
            handler.postDelayed(
                retryFirstFrame,
                FIRST_FRAME_RETRY_DELAY_MS * drawRetryCount,
            )
        }

        private fun lockWallpaperCanvas(surface: Surface, preferHardware: Boolean): Canvas? {
            if (preferHardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { surface.lockHardwareCanvas() }.getOrNull()?.let { return it }
            }
            return runCatching { surface.lockCanvas(null) }.getOrNull()
        }

        private fun drawMedia(canvas: Canvas): Boolean {
            canvas.drawContainBackdrop(
                blurredSource = containBackdrop?.takeIf {
                    containBackdropImageId == loadedImageId
                },
                transform = transform,
                paint = paint,
            )
            val drawable = gif
            if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                val layout = ImageLayout.layout(
                    imageWidth = drawable.intrinsicWidth,
                    imageHeight = drawable.intrinsicHeight,
                    viewportWidth = canvas.width.toFloat(),
                    viewportHeight = canvas.height.toFloat(),
                    transform = transform,
                )
                val previousAlpha = drawable.alpha
                drawable.alpha = 255
                val saved = canvas.save()
                try {
                    canvas.concat(layout.toMatrix())
                    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    drawable.draw(canvas)
                } finally {
                    canvas.restoreToCount(saved)
                    drawable.alpha = previousAlpha
                }
                return true
            }

            val src = bitmap ?: return false
            paint.alpha = 255
            val layout = ImageLayout.layout(
                imageWidth = src.width,
                imageHeight = src.height,
                viewportWidth = canvas.width.toFloat(),
                viewportHeight = canvas.height.toFloat(),
                transform = transform,
            )
            canvas.drawBitmap(src, layout.toMatrix(), paint)
            paint.alpha = 255
            return true
        }

        @Suppress("NewApi")
        private fun drawHardwareBlurredFrame(canvas: Canvas, style: LockStyle): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return drawMedia(canvas)
            val staticSource = bitmap?.takeIf { gif == null && !it.isRecycled }
            val radiusPx = minOf(
                style.visualBlurRadiusDp * resources.displayMetrics.density,
                MAX_LOCK_BLUR_RADIUS_PX.toFloat(),
            )
            val cache = (hardwareBlurCache as? HardwareBlurCache) ?: HardwareBlurCache().also {
                hardwareBlurCache = it
            }
            return cache.draw(
                canvas = canvas,
                source = staticSource,
                transform = transform,
                blurLevel = style.blurRadius,
                radiusPx = radiusPx,
                cacheFrame = staticSource != null,
                drawMedia = ::drawMedia,
            )
        }

        private fun invalidateBlurCache() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (hardwareBlurCache as? HardwareBlurCache)?.clear()
            }
            hardwareBlurCache = null
        }

        /**
         * [SurfaceHolder.lockCanvas] keeps the holder's Surface connected to the CPU producer.
         * A later MediaPlayer then cannot connect its media producer to that same BufferQueue on
         * Samsung/Android 16 ("already connected", CPU api=2 versus media api=3).
         *
         * Parceling creates a separate local Surface handle for Canvas. Releasing this handle when
         * the media kind changes disconnects the CPU producer without releasing the system-owned
         * SurfaceHolder surface, so MediaPlayer can take ownership of the BufferQueue.
         */
        private fun canvasSurface(): Surface? {
            canvasSurface?.let { existing ->
                if (existing.isValid) return existing
                runCatching { existing.release() }
                canvasSurface = null
            }
            if (!surfaceReady) return null
            val holderSurface = surfaceHolder.surface
            if (!holderSurface.isValid) return null
            val parcel = Parcel.obtain()
            return try {
                holderSurface.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                Surface.CREATOR.createFromParcel(parcel)?.also { created ->
                    canvasSurface = created
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to create Canvas surface", error)
                null
            } finally {
                parcel.recycle()
            }
        }

        private fun releaseCanvasSurface() {
            val surface = canvasSurface
            canvasSurface = null
            runCatching { surface?.release() }
        }
    }

    private companion object {
        const val TAG = "WallpurrWallpaper"
        val RENDERER_RELOAD_BACKOFF_MS = longArrayOf(250L, 1_000L)
        const val MAX_VIDEO_RETRIES = 3
        const val VIDEO_RETRY_DELAY_MS = 200L
        const val MIN_BITMAP_DECODE_SIDE = 1080
        const val SOFTWARE_BLUR_DECODE_SIDE = 1600
        const val INITIAL_DESTINATION_RECONCILE_MS = 600L
        const val MAX_FIRST_FRAME_RETRIES = 3
        const val FIRST_FRAME_RETRY_DELAY_MS = 80L
        const val ORIENTATION_RELOAD_DEBOUNCE_MS = 48L
        const val CANVAS_SURFACE_ORIENTATION_WAIT_MS = 500L
        const val CANVAS_SURFACE_ORIENTATION_RETRY_MS = 32L
        const val SCREEN_OFF_REDRAW_DELAY_MS = 96L
        const val KEYGUARD_RECONCILE_DELAY_MS = 320L
    }
}

private fun Configuration.screenOrientationOrNull(): ScreenOrientation? = when (orientation) {
    Configuration.ORIENTATION_PORTRAIT -> ScreenOrientation.PORTRAIT
    Configuration.ORIENTATION_LANDSCAPE -> ScreenOrientation.LANDSCAPE
    else -> null
}

/**
 * Isolated from [WallpurrWallpaperService.WallpurrEngine] so Android 8-9 never need to resolve
 * RenderNode fields while loading the engine class. Instances are created only on Android 12+.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class HardwareBlurCache {
    private data class Key(
        val source: Bitmap,
        val canvasWidth: Int,
        val canvasHeight: Int,
        val transform: ImageTransform,
        val blurLevel: Int,
        val radiusPx: Float,
    )

    private var node: RenderNode? = null
    private var key: Key? = null
    private val colorDitherPaint = Paint(Paint.DITHER_FLAG).apply {
        shader = BitmapShader(
            COLOR_DITHER_TEXTURE,
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
        setBlendMode(BlendMode.SOFT_LIGHT)
        alpha = COLOR_DITHER_ALPHA
    }

    fun draw(
        canvas: Canvas,
        source: Bitmap?,
        transform: ImageTransform,
        blurLevel: Int,
        radiusPx: Float,
        cacheFrame: Boolean,
        drawMedia: (Canvas) -> Boolean,
    ): Boolean {
        // Strong blur removes high-frequency detail before the final image reaches the Surface.
        // Render those expensive levels on a conservative intermediate and scale the already
        // blurred result with filtering. Unlike the old low-resolution bitmap path, media is
        // composed directly into this GPU node and full-resolution dithering is applied afterward.
        val renderScale = renderScaleFor(blurLevel)
        val renderWidth = (canvas.width * renderScale).roundToInt().coerceAtLeast(1)
        val renderHeight = (canvas.height * renderScale).roundToInt().coerceAtLeast(1)
        val nextKey = source?.let {
            Key(it, canvas.width, canvas.height, transform, blurLevel, radiusPx)
        }
        val cached = node
        // HWUI frees a RenderNode's display list once the node leaves the drawn tree. A shared
        // Home/Lock engine draws Home frames without this node after unlocking, so on the next
        // lock the cached node is empty: drawing it renders nothing and the blur disappears.
        if (cacheFrame && cached != null && key == nextKey && cached.hasDisplayList()) {
            drawBlurNode(canvas, cached, renderWidth, renderHeight)
            drawColorDither(canvas)
            return true
        }

        clear()
        val nextNode = RenderNode("WallpurrLockStyle")
        nextNode.setPosition(0, 0, renderWidth, renderHeight)
        nextNode.setUseCompositingLayer(true, null)
        val blurRadius = (radiusPx * renderScale)
            .coerceAtLeast(MIN_HARDWARE_BLUR_RADIUS_PX)
        nextNode.setRenderEffect(
            RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                Shader.TileMode.CLAMP,
            ),
        )
        val recording = nextNode.beginRecording(renderWidth, renderHeight)
        val recordedMedia = try {
            drawMedia(recording)
        } finally {
            nextNode.endRecording()
        }
        if (!recordedMedia) {
            nextNode.discardDisplayList()
            return false
        }
        drawBlurNode(canvas, nextNode, renderWidth, renderHeight)
        drawColorDither(canvas)
        if (cacheFrame) {
            node = nextNode
            key = nextKey
        } else {
            // GIF frames change on every invalidation, so never retain their display list.
            nextNode.discardDisplayList()
        }
        return true
    }

    private fun drawBlurNode(
        canvas: Canvas,
        blurNode: RenderNode,
        renderWidth: Int,
        renderHeight: Int,
    ) {
        val saved = canvas.save()
        try {
            canvas.scale(
                canvas.width.toFloat() / renderWidth.coerceAtLeast(1),
                canvas.height.toFloat() / renderHeight.coerceAtLeast(1),
            )
            canvas.drawRenderNode(blurNode)
        } finally {
            canvas.restoreToCount(saved)
        }
    }

    /**
     * Adds a neutral high-frequency pattern after the full-resolution blur. The stronger two-tone
     * signal is intentional: Samsung's lock-screen display pipeline can quantize away the subtler
     * variant even though it remains visible in screenshots. Soft-light blending keeps the average
     * luminance neutral while giving the physical panel enough variation to break color bands.
     */
    private fun drawColorDither(canvas: Canvas) {
        canvas.drawRect(
            0f,
            0f,
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            colorDitherPaint,
        )
    }

    fun clear() {
        node?.discardDisplayList()
        node = null
        key = null
    }

    private companion object {
        const val MIN_HARDWARE_BLUR_RADIUS_PX = 0.5f
        const val MEDIUM_BLUR_RENDER_SCALE = 0.82f
        const val STRONG_BLUR_RENDER_SCALE = 0.67f
        const val FULL_RESOLUTION_BLUR_LEVEL = 10
        const val MEDIUM_RESOLUTION_BLUR_LEVEL = 15
        const val COLOR_DITHER_SIZE = 64
        const val COLOR_DITHER_ALPHA = 12

        fun renderScaleFor(blurLevel: Int): Float = when {
            blurLevel <= FULL_RESOLUTION_BLUR_LEVEL -> 1f
            blurLevel <= MEDIUM_RESOLUTION_BLUR_LEVEL -> MEDIUM_BLUR_RENDER_SCALE
            else -> STRONG_BLUR_RENDER_SCALE
        }

        val COLOR_DITHER_TEXTURE: Bitmap by lazy {
            var state = 0x13579BDF
            val pixels = IntArray(COLOR_DITHER_SIZE * COLOR_DITHER_SIZE) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                val value = if ((state and 1) == 0) 88 else 168
                Color.rgb(value, value, value)
            }
            Bitmap.createBitmap(
                pixels,
                COLOR_DITHER_SIZE,
                COLOR_DITHER_SIZE,
                Bitmap.Config.ARGB_8888,
            )
        }
    }
}
