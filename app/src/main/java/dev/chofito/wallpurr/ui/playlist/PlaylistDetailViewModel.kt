package dev.chofito.wallpurr.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.wallpaper.RotationController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistDetailViewModel(
    private val repository: WallpurrRepository,
    private val rotation: RotationController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val playlistId: String = checkNotNull(savedStateHandle["playlistId"])
    val playlist = repository.playlist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val images = repository.playlistItems(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shuffle = repository.shuffle(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { repository.ensureShuffle(playlistId) }
    }

    fun setName(name: String) {
        viewModelScope.launch { repository.updatePlaylist(playlistId, name = name) }
    }

    fun setInterval(minutes: Int) {
        viewModelScope.launch {
            repository.updatePlaylist(playlistId, intervalMinutes = minutes)
            refreshIfActive()
        }
    }

    fun setLockStyle(blurRadius: Int, opacity: Float) {
        viewModelScope.launch {
            repository.updatePlaylist(
                playlistId,
                lockBlurRadius = blurRadius,
                lockOpacity = opacity,
            )
            refreshIfActive()
        }
    }

    fun setTarget(target: WallpaperTarget) {
        viewModelScope.launch {
            repository.updatePlaylist(playlistId, target = target)
            refreshIfActive()
        }
    }

    fun remove(imageId: String) {
        viewModelScope.launch {
            rotation.changePlaylistMembership {
                repository.removeImageFromPlaylist(playlistId, imageId)
            }
        }
    }

    fun removeMany(imageIds: List<String>) {
        viewModelScope.launch {
            rotation.changePlaylistMembership {
                repository.removeImagesFromPlaylist(playlistId, imageIds)
            }
        }
    }

    fun reorder(imageIds: List<String>) {
        viewModelScope.launch {
            rotation.changePlaylistMembership {
                repository.reorderPlaylist(playlistId, imageIds)
            }
        }
    }

    fun reshuffle() {
        viewModelScope.launch {
            repository.resetShuffle(playlistId)
            refreshIfActive()
        }
    }

    fun resetOrder() {
        viewModelScope.launch {
            repository.resetShuffleOrdered(playlistId)
            refreshIfActive()
        }
    }

    fun showNow(imageId: String, onNeedsApply: () -> Unit) {
        viewModelScope.launch {
            rotation.selectImage(playlistId, imageId)
            if (repository.activePlaylistId() != playlistId) onNeedsApply()
        }
    }

    private suspend fun refreshIfActive() {
        if (repository.activePlaylistId() == playlistId) {
            rotation.applyCurrentAndNotify()
        }
    }
}
