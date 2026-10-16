package dev.chofito.wallpurr.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.HiddenAutoLock
import dev.chofito.wallpurr.storage.PowerSavePolicy
import dev.chofito.wallpurr.wallpaper.RotationNotifier
import dev.chofito.wallpurr.wallpaper.RotationController
import dev.chofito.wallpurr.wallpaper.WallpaperApplier
import dev.chofito.wallpurr.wallpaper.WallpaperPulse
import dev.chofito.wallpurr.wallpaper.WallpaperScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repository: WallpurrRepository,
    private val scheduler: WallpaperScheduler,
    private val notifier: RotationNotifier,
    private val wallpaperPulse: MutableSharedFlow<WallpaperPulse>,
    private val rotation: RotationController,
    private val applier: WallpaperApplier,
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

    val hiddenAutoLock = repository.observeHiddenAutoLock()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiddenAutoLock.OnLeave)

    fun setHiddenAutoLock(policy: HiddenAutoLock) {
        viewModelScope.launch { repository.setHiddenAutoLock(policy) }
    }

    fun setPowerSavePolicy(policy: PowerSavePolicy) {
        viewModelScope.launch {
            repository.setPowerSavePolicy(policy)
            rotation.powerSavePolicyChanged()
        }
    }

    fun exportJson(uri: Uri, context: Context) {
        viewModelScope.launch {
            runCatching { repository.exportJson(uri, context) }
                .onSuccess { message.value = context.getString(R.string.export_json_done) }
                .onFailure { message.value = it.userMessage(context, R.string.export_json_failed) }
        }
    }

    fun exportZip(uri: Uri, context: Context) {
        viewModelScope.launch {
            runCatching { repository.exportZip(uri, context) }
                .onSuccess { message.value = context.getString(R.string.export_zip_done) }
                .onFailure { message.value = it.userMessage(context, R.string.export_zip_failed) }
        }
    }

    fun exportDiagnostics(uri: Uri, context: Context) {
        viewModelScope.launch {
            runCatching {
                val report = DiagnosticsReport(context.applicationContext, repository, applier)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { report.write(it) }
                        ?: error("openOutputStream returned null")
                }
            }
                .onSuccess { message.value = context.getString(R.string.diagnostics_done) }
                .onFailure { message.value = it.userMessage(context, R.string.diagnostics_failed) }
        }
    }

    fun importConfig(uri: Uri, context: Context) {
        viewModelScope.launch {
            runCatching {
                repository.importConfig(uri, context).also {
                    // The import replaces playlists and cursors; the pending timer targets an
                    // image that may no longer be current.
                    rotation.applyCurrentAndNotify()
                }
            }
                .onSuccess { result ->
                    message.value = if (result.skippedImages.isEmpty()) {
                        context.getString(R.string.import_done)
                    } else {
                        context.getString(R.string.import_done_missing, result.skippedImages.size)
                    }
                }
                .onFailure { message.value = it.userMessage(context, R.string.import_failed) }
        }
    }

    fun resetAllLocalData(context: Context) {
        viewModelScope.launch {
            runCatching {
                scheduler.cancel()
                notifier.cancel()
                repository.resetAllLocalData()
                wallpaperPulse.tryEmit(WallpaperPulse.Reload)
            }.onSuccess {
                message.value = context.getString(R.string.developer_reset_done)
            }.onFailure {
                message.value = it.userMessage(context, R.string.developer_reset_failed)
            }
        }
    }
}

/** Failures can carry a null/blank message; never leave the user without feedback. */
private fun Throwable.userMessage(context: Context, fallbackRes: Int): String =
    message?.takeIf { it.isNotBlank() } ?: context.getString(fallbackRes)
