package dev.chofito.wallpurr.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.wallpaper.RotationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShareImportViewModel(
    private val repository: WallpurrRepository,
    private val rotation: RotationController,
) : ViewModel() {
    val playlists = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _importedIds = MutableStateFlow<List<String>?>(null)
    val importedIds = _importedIds.asStateFlow()

    fun importShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val ids = repository.importImages(uris)
                _importedIds.value = ids.takeIf { it.isNotEmpty() }
            } finally {
                _importing.value = false
            }
        }
    }

    fun keepInLibrary() {
        _importedIds.value = null
    }

    fun addToExisting(playlistId: String) {
        val ids = _importedIds.value ?: return
        viewModelScope.launch {
            rotation.changePlaylistMembership {
                repository.addImagesToPlaylist(playlistId, ids)
            }
            _importedIds.value = null
        }
    }

    fun createAndAdd(name: String) {
        val ids = _importedIds.value ?: return
        viewModelScope.launch {
            val playlist = repository.createPlaylist(name)
            repository.addImagesToPlaylist(playlist.id, ids)
            _importedIds.value = null
        }
    }
}
