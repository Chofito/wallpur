package dev.chofito.wallpurr.wallpaper

import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.playlist.ShuffleState
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.PowerSavePolicy
import dev.chofito.wallpurr.storage.VideoPlayMode
import dev.chofito.wallpurr.storage.WallpurrRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface WallpaperPulse {
    data object Reload : WallpaperPulse
}

class RotationController(
    private val app: android.app.Application,
) {
    private val container get() = (app as dev.chofito.wallpurr.WallpurrApplication).container
    private val commands = Mutex()
    private val manualAdvanceInFlight = AtomicBoolean(false)
    private val sideEffectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lockApplyRequests = Channel<LockApplyRequest>(Channel.CONFLATED)
    private var lastAdvanceAt = 0L

    init {
        sideEffectScope.launch {
            for (request in lockApplyRequests) {
                delay(LOCK_APPLY_DEBOUNCE_MS)
                var latest = request
                while (true) {
                    latest = lockApplyRequests.tryReceive().getOrNull() ?: break
                }
                if (!isCurrentLockRequest(latest)) {
                    continue
                }
                val applyResult = runCatching {
                    container.applier.applyLockStatic(latest.imageId)
                }.onFailure {
                    Log.e(TAG, "Unable to refresh lock wallpaper", it)
                }.getOrNull()
                if (applyResult is ApplyResult.Error) {
                    Log.e(TAG, "Unable to refresh lock wallpaper: ${applyResult.message}")
                }
                if (applyResult == ApplyResult.Ok && latest.reloadLiveAfterApply) {
                    commands.withLock {
                        if (isCurrentLockRequest(latest)) {
                            container.wallpaperPulse.emit(WallpaperPulse.Reload)
                        }
                    }
                }
            }
        }
    }

    suspend fun previous(eventAt: Long = SystemClock.elapsedRealtime()) =
        manualAdvance(eventAt, ManualDirection.PREVIOUS, fromHome = false)

    suspend fun next(eventAt: Long = SystemClock.elapsedRealtime()) =
        manualAdvance(eventAt, ManualDirection.NEXT, fromHome = false)

    suspend fun nextFromHomeTap(eventAt: Long) =
        manualAdvance(eventAt, ManualDirection.NEXT, fromHome = true)

    /**
     * Manual controls are intentionally non-queueing. A burst of notification or launcher taps
     * represents one request to move, not a backlog that should keep changing wallpapers after the
     * user has stopped. Scheduled/video advances still use [commands] normally and remain ordered.
     */
    private suspend fun manualAdvance(
        eventAt: Long,
        direction: ManualDirection,
        fromHome: Boolean,
    ) {
        if (!manualAdvanceInFlight.compareAndSet(false, true)) {
            return
        }
        try {
            commands.withLock {
                if (fromHome) {
                    if (isRotationSuspendedLocked()) {
                        return@withLock
                    }
                    if (!container.repository.flag(
                            WallpurrRepository.KEY_CHANGE_ON_HOME_TAP,
                            true,
                        )
                    ) {
                        return@withLock
                    }
                } else if (isStaticPowerSaveActiveLocked()) {
                    return@withLock
                }
                if (eventAt - lastAdvanceAt < MANUAL_ADVANCE_GUARD_MS) {
                    return@withLock
                }
                val playlistId = container.repository.activePlaylistId() ?: return@withLock
                val shuffle = when (direction) {
                    ManualDirection.PREVIOUS -> container.repository.rewindShuffle(playlistId)
                    ManualDirection.NEXT -> container.repository.advanceShuffle(playlistId)
                }
                recordAdvance()
                scheduleCurrentLocked(playlistId, shuffle.currentImageId)
                applyCurrentAndNotifyLocked(playlistId)
            }
        } finally {
            manualAdvanceInFlight.set(false)
        }
    }

    suspend fun advanceExpected(expectedImageId: String) = commands.withLock {
        if (isRotationSuspendedLocked()) {
            return@withLock
        }
        val playlistId = container.repository.activePlaylistId() ?: return@withLock
        val current = container.repository.ensureShuffle(playlistId).currentImageId
        if (current != expectedImageId) {
            return@withLock
        }
        val shuffle = container.repository.advanceShuffle(playlistId)
        recordAdvance()
        scheduleCurrentLocked(playlistId, shuffle.currentImageId)
        applyCurrentAndNotifyLocked(playlistId)
    }

    suspend fun advanceScheduled(expectedImageId: String, rotationToken: String) = commands.withLock {
        if (isRotationSuspendedLocked()) {
            return@withLock
        }
        if (container.repository.rotationToken() != rotationToken) {
            return@withLock
        }
        val playlistId = container.repository.activePlaylistId() ?: return@withLock
        val current = container.repository.ensureShuffle(playlistId).currentImageId
        if (current != expectedImageId) {
            return@withLock
        }
        val shuffle = container.repository.advanceShuffle(playlistId)
        recordAdvance()
        scheduleCurrentLocked(playlistId, shuffle.currentImageId)
        applyCurrentAndNotifyLocked(playlistId)
    }

    suspend fun activate(
        playlistId: String,
        refreshStaticLock: Boolean = true,
    ) = commands.withLock {
        container.repository.activatePlaylist(playlistId)
        container.repository.setRotationPaused(false)
        scheduleCurrentLocked(playlistId)
        applyCurrentAndNotifyLocked(playlistId, refreshStaticLock)
    }

    suspend fun restoreSchedule() = commands.withLock {
        if (isRotationSuspendedLocked()) {
            invalidateScheduleLocked()
            container.wallpaperPulse.emit(WallpaperPulse.Reload)
            publishNotification()
            return@withLock
        }
        // Every cold start (including one triggered by the due worker itself) runs this. Replacing
        // a still-valid timer would restart the countdown and cancel that worker mid-run.
        val token = container.repository.rotationToken()
        val hasValidTimer = token != null &&
            container.repository.nextChangeAtMillis() != null &&
            container.scheduler.hasPendingWork(token)
        if (!hasValidTimer) scheduleCurrentLocked()
        publishNotification()
    }

    /**
     * Runs a playlist membership change (add, remove or delete media) under the rotation lock.
     * The pending timer targets the current image, so it is replaced only when the change moved
     * the current image; otherwise the countdown keeps running and only the notification
     * (position and count) is refreshed.
     */
    suspend fun <T> changePlaylistMembership(block: suspend () -> T): T = commands.withLock {
        val playlistId = container.repository.activePlaylistId()
        val before = playlistId?.let { container.repository.currentShuffle(it)?.currentImageId }
        val result = block()
        if (playlistId != null && container.repository.activePlaylistId() == playlistId) {
            val after = container.repository.ensureShuffle(playlistId).currentImageId
            when {
                after == before -> publishNotification()
                after == null -> {
                    invalidateScheduleLocked()
                    applyCurrentAndNotifyLocked(playlistId)
                }
                else -> {
                    scheduleCurrentLocked(playlistId, after)
                    applyCurrentAndNotifyLocked(playlistId)
                }
            }
        }
        result
    }

    /**
     * Deletes a playlist under the rotation lock. If it was the active one, the pending timer and
     * the ongoing notification would otherwise outlive it, so both are torn down here.
     */
    suspend fun deletePlaylist(id: String) = commands.withLock {
        val wasActive = container.repository.activePlaylistId() == id
        container.repository.deletePlaylist(id)
        if (wasActive) {
            invalidateScheduleLocked()
            container.notifier.cancel()
            container.wallpaperPulse.emit(WallpaperPulse.Reload)
        }
    }

    suspend fun play() = commands.withLock {
        container.repository.setRotationPaused(false)
        scheduleCurrentLocked()
        container.wallpaperPulse.emit(WallpaperPulse.Reload)
        publishNotification()
    }

    suspend fun pause() = commands.withLock {
        container.repository.setRotationPaused(true)
        invalidateScheduleLocked()
        container.wallpaperPulse.emit(WallpaperPulse.Reload)
        publishNotification()
    }

    suspend fun applyCurrentAndNotify() = commands.withLock {
        scheduleCurrentLocked()
        applyCurrentAndNotifyLocked()
    }

    suspend fun selectImage(playlistId: String, imageId: String): ShuffleState = commands.withLock {
        val selected = container.repository.selectShuffleImage(playlistId, imageId)
        if (container.repository.activePlaylistId() == playlistId &&
            selected.currentImageId == imageId
        ) {
            recordAdvance()
            scheduleCurrentLocked(playlistId, imageId)
            applyCurrentAndNotifyLocked(playlistId)
        }
        selected
    }

    suspend fun reconcileWallpaperDestinations() = commands.withLock {
        applyCurrentAndNotifyLocked()
    }

    suspend fun refreshCurrentVisuals() = commands.withLock {
        // Transform/appearance edits do not change timing or notification metadata.
        applyCurrentAndNotifyLocked(refreshNotification = false)
    }

    suspend fun powerSaveModeChanged() = commands.withLock {
        if (container.repository.powerSavePolicy() == PowerSavePolicy.IGNORE) {
            publishNotification()
            return@withLock
        }
        reconcilePowerSaveLocked()
    }

    suspend fun powerSavePolicyChanged() = commands.withLock {
        if (!isPowerSaveModeLocked()) return@withLock
        reconcilePowerSaveLocked()
    }

    private suspend fun reconcilePowerSaveLocked() {
        if (isRotationSuspendedLocked()) {
            invalidateScheduleLocked()
        } else {
            scheduleCurrentLocked()
        }
        applyCurrentAndNotifyLocked()
    }

    private suspend fun scheduleCurrentLocked(
        playlistIdOverride: String? = null,
        imageIdOverride: String? = null,
    ) {
        if (isRotationSuspendedLocked()) {
            invalidateScheduleLocked()
            return
        }
        val playlistId = playlistIdOverride ?: container.repository.activePlaylistId() ?: return
        val playlist = container.repository.getPlaylist(playlistId) ?: return
        val imageId = imageIdOverride
            ?: container.repository.ensureShuffle(playlistId).currentImageId
            ?: return
        val image = container.repository.getImage(imageId) ?: return
        val delayMs: Long? = when (MediaKind.from(image.mediaType)) {
            MediaKind.IMAGE, MediaKind.GIF -> playlist.intervalMinutes.coerceAtLeast(1) * 60_000L
            MediaKind.VIDEO -> when (VideoPlayMode.from(image.videoPlayMode)) {
                VideoPlayMode.MINUTES -> image.videoPlayMinutes.coerceAtLeast(1) * 60_000L
                VideoPlayMode.TIMES -> {
                    val target = WallpaperTarget.from(playlist.target)
                    val lockStyle = container.repository.lockStyle(image, playlist)
                    val playsOnHome = target != WallpaperTarget.LOCK &&
                        container.applier.isWallpurrLiveOnHome()
                    val playsOnLock = target != WallpaperTarget.HOME &&
                        container.applier.isWallpurrLiveOnLock() &&
                        container.repository.flag(WallpurrRepository.KEY_PLAY_VIDEO_ON_LOCK, false) &&
                        !lockStyle.hasEffects
                    if (playsOnHome || playsOnLock) {
                        null
                    } else {
                        playlist.intervalMinutes.coerceAtLeast(1) * 60_000L
                    }
                }
            }
        }
        if (delayMs == null) {
            invalidateScheduleLocked()
            return
        }
        val rotationToken = UUID.randomUUID().toString()
        container.repository.setRotationToken(rotationToken)
        container.repository.setNextChangeAtMillis(System.currentTimeMillis() + delayMs)
        // enqueueUniqueWork(REPLACE) supersedes the old timer. Explicitly cancelling first caused
        // extra WorkManager/database churn on every manual change, especially visible on tablets.
        container.scheduler.schedule(delayMs, imageId, rotationToken)
    }

    private suspend fun invalidateScheduleLocked() {
        container.scheduler.cancel()
        container.repository.setNextChangeAtMillis(null)
        container.repository.setRotationToken(UUID.randomUUID().toString())
    }

    private fun recordAdvance() {
        lastAdvanceAt = SystemClock.elapsedRealtime()
    }

    private suspend fun isRotationSuspendedLocked(): Boolean =
        container.repository.isRotationPaused() || isStaticPowerSaveActiveLocked()

    private suspend fun isStaticPowerSaveActiveLocked(): Boolean {
        return isPowerSaveModeLocked() &&
            container.repository.powerSavePolicy() == PowerSavePolicy.STATIC
    }

    private fun isPowerSaveModeLocked(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private suspend fun applyCurrentAndNotifyLocked(
        playlistIdOverride: String? = null,
        refreshStaticLock: Boolean = true,
        refreshNotification: Boolean = true,
    ) {
        val playlistId = playlistIdOverride ?: container.repository.activePlaylistId() ?: return
        val playlist = container.repository.getPlaylist(playlistId) ?: return
        val powerSaveImageId = if (isStaticPowerSaveActiveLocked()) {
            container.repository.powerSaveStaticImage(playlistId)?.id
        } else {
            null
        }

        val target = WallpaperTarget.from(playlist.target)
        val updatesLock = target == WallpaperTarget.LOCK || target == WallpaperTarget.BOTH
        val updatesStaticLock = refreshStaticLock &&
            updatesLock &&
            !container.applier.isWallpurrLiveOnLock()
        if (updatesStaticLock) {
            // Static WallpaperManager work can take hundreds of ms on large displays. It must not
            // hold the rotation mutex. Keep Home on its previous frame until the newest static Lock
            // is committed, then release one live reload for the same repository state.
            val expectedImageId = powerSaveImageId
                ?: container.repository.ensureShuffle(playlistId).currentImageId
            lockApplyRequests.trySend(
                LockApplyRequest(
                    playlistId = playlistId,
                    imageId = expectedImageId,
                    reloadLiveAfterApply = true,
                ),
            )
        } else {
            container.wallpaperPulse.emit(WallpaperPulse.Reload)
        }
        if (refreshNotification) publishNotification()
    }

    private suspend fun isCurrentLockRequest(request: LockApplyRequest): Boolean {
        if (container.repository.activePlaylistId() != request.playlistId) return false
        val playlist = container.repository.getPlaylist(request.playlistId) ?: return false
        if (WallpaperTarget.from(playlist.target) == WallpaperTarget.HOME) return false
        val currentImageId = if (isStaticPowerSaveActiveLocked()) {
            container.repository.powerSaveStaticImage(request.playlistId)?.id
        } else {
            container.repository.ensureShuffle(request.playlistId).currentImageId
        }
        return currentImageId == request.imageId
    }

    private fun publishNotification() {
        container.notifier.requestPublish()
    }

    private companion object {
        const val TAG = "WallpurrRotation"
        const val LOCK_APPLY_DEBOUNCE_MS = 80L
        const val MANUAL_ADVANCE_GUARD_MS = 750L
    }

    private enum class ManualDirection {
        PREVIOUS,
        NEXT,
    }

    private data class LockApplyRequest(
        val playlistId: String,
        val imageId: String?,
        val reloadLiveAfterApply: Boolean,
    )
}
