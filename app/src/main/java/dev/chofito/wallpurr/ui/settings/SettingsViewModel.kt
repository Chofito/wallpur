package dev.chofito.wallpurr.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.PowerSavePolicy
import dev.chofito.wallpurr.wallpaper.RotationNotifier
import dev.chofito.wallpurr.wallpaper.RotationController
import dev.chofito.wallpurr.wallpaper.WallpaperPulse
import dev.chofito.wallpurr.wallpaper.WallpaperScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: WallpurrRepository,
    private val scheduler: WallpaperScheduler,
    private val notifier: RotationNotifier,
    private val wallpaperPulse: MutableSharedFlow<WallpaperPulse>,
    private val rotation: RotationController,
) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val pauseGifHidden = repository.observeFlag(WallpurrRepository.KEY_PAUSE_GIF_HIDDEN, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val pauseVideoHidden = repository.observeFlag(WallpurrRepository.KEY_PAUSE_VIDEO_HIDDEN, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val playVideoOnLock = repository.observeFlag(WallpurrRepository.KEY_PLAY_VIDEO_ON_LOCK, false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val changeOnHomeTap = repository.observeFlag(WallpurrRepository.KEY_CHANGE_ON_HOME_TAP, true)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val powerSavePolicy = repository.observePowerSavePolicy()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PowerSavePolicy.IGNORE)

    fun setPauseGifHidden(value: Boolean) {
        viewModelScope.launch { repository.setFlag(WallpurrRepository.KEY_PAUSE_GIF_HIDDEN, value) }
    }

    fun setPauseVideoHidden(value: Boolean) {
        viewModelScope.launch { repository.setFlag(WallpurrRepository.KEY_PAUSE_VIDEO_HIDDEN, value) }
    }

    fun setPlayVideoOnLock(value: Boolean) {
        viewModelScope.launch {
            repository.setFlag(WallpurrRepository.KEY_PLAY_VIDEO_ON_LOCK, value)
            rotation.applyCurrentAndNotify()
            message.value = if (value) {
                "Video permitido en bloqueo cuando el wallpaper no tenga blur ni oscurecimiento."
            } else {
                "Los videos usarán un frame estático en la pantalla de bloqueo."
            }
        }
    }

    fun setChangeOnHomeTap(value: Boolean) {
        viewModelScope.launch {
            repository.setFlag(WallpurrRepository.KEY_CHANGE_ON_HOME_TAP, value)
        }
    }

    fun setPowerSavePolicy(policy: PowerSavePolicy) {
        viewModelScope.launch {
            repository.setPowerSavePolicy(policy)
            rotation.powerSavePolicyChanged()
        }
    }

    fun exportJson(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            runCatching { repository.exportJson(uri, context) }
                .onSuccess { message.value = "JSON exportado" }
                .onFailure { message.value = it.message }
        }
    }

    fun exportZip(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            runCatching { repository.exportZip(uri, context) }
                .onSuccess { message.value = "Zip exportado" }
                .onFailure { message.value = it.message }
        }
    }

    fun importConfig(uri: Uri, context: android.content.Context) {
        viewModelScope.launch {
            runCatching { repository.importConfig(uri, context) }
                .onSuccess { result ->
                    message.value = if (result.skippedImages.isEmpty()) {
                        "Importación lista"
                    } else {
                        "Importado. Faltan ${result.skippedImages.size} imágenes."
                    }
                }
                .onFailure { message.value = it.message }
        }
    }

    fun resetAllLocalData() {
        viewModelScope.launch {
            runCatching {
                scheduler.cancel()
                notifier.cancel()
                repository.resetAllLocalData()
                wallpaperPulse.tryEmit(WallpaperPulse.Reload)
            }.onSuccess {
                message.value = "Wallpurr quedó vacío. Los archivos de tu galería no se tocaron."
            }.onFailure {
                message.value = it.message
            }
        }
    }
}
