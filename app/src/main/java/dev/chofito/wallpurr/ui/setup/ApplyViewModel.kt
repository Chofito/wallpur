package dev.chofito.wallpurr.ui.setup

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.wallpaper.ApplyResult
import dev.chofito.wallpurr.wallpaper.RotationController
import dev.chofito.wallpurr.wallpaper.WallpaperApplier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ApplyViewModel(
    private val applier: WallpaperApplier,
    private val rotation: RotationController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
    val message = MutableStateFlow<String?>(null)

    fun prepareLiveWallpaper(onReady: (Intent) -> Unit) {
        viewModelScope.launch {
            // Do not write a static lock wallpaper while Android's live chooser is taking
            // ownership of Home/Lock. That race could leave the first lock surface black.
            activate(refreshStaticLock = false)
            onReady(applier.liveWallpaperChooserIntent())
        }
    }

    fun onLiveWallpaperChooserReturned() {
        viewModelScope.launch {
            // Some OEMs publish the selected component shortly after the chooser closes.
            delay(LIVE_WALLPAPER_SETTLE_MS)
            rotation.reconcileWallpaperDestinations()
        }
    }

    fun applyLock() {
        viewModelScope.launch {
            activate(refreshStaticLock = false)
            message.value = when (val result = applier.applyLockStatic()) {
                ApplyResult.Ok -> "Fondo de bloqueo actualizado"
                is ApplyResult.Error -> result.message
            }
        }
    }

    fun applyHomeStatic() {
        viewModelScope.launch {
            activate(refreshStaticLock = false)
            message.value = when (val result = applier.applyHomeStaticFallback()) {
                ApplyResult.Ok -> "Fondo de inicio estático aplicado"
                is ApplyResult.Error -> result.message
            }
        }
    }

    private suspend fun activate(refreshStaticLock: Boolean) {
        rotation.activate(playlistId, refreshStaticLock)
    }

    private companion object {
        const val LIVE_WALLPAPER_SETTLE_MS = 900L
    }
}
