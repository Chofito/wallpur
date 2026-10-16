package dev.chofito.wallpurr.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.wallpaper.RotationController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(
    private val repository: WallpurrRepository,
    private val rotation: RotationController,
) : ViewModel() {
    val cards = repository.playlistCards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(repository.createPlaylist(name).id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repository.updatePlaylist(id, name = name) }
    }

    fun delete(id: String) {
        viewModelScope.launch { rotation.deletePlaylist(id) }
    }
}
