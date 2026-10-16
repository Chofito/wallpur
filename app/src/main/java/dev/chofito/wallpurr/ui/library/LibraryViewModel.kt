package dev.chofito.wallpurr.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.db.FolderEntity
import dev.chofito.wallpurr.storage.db.PlaylistEntity
import dev.chofito.wallpurr.wallpaper.RotationController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: WallpurrRepository,
    private val rotation: RotationController,
) : ViewModel() {
    val images = repository.images
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists = repository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folders = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _rootFolderId = MutableStateFlow<String?>(null)
    val rootFolderId = _rootFolderId.asStateFlow()
    val columns = repository.observeLibraryColumns()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WallpurrRepository.DEFAULT_LIBRARY_COLUMNS,
        )

    init {
        viewModelScope.launch { _rootFolderId.value = repository.libraryFolderId() }
    }

    fun import(uris: List<Uri>, folderId: String? = null) {
        viewModelScope.launch { repository.importImages(uris, folderId) }
    }

    fun delete(ids: List<String>) {
        viewModelScope.launch {
            rotation.changePlaylistMembership { repository.deleteImages(ids) }
        }
    }

    fun addToPlaylist(playlist: PlaylistEntity, imageIds: List<String>) {
        viewModelScope.launch {
            rotation.changePlaylistMembership {
                repository.addImagesToPlaylist(playlist.id, imageIds)
            }
        }
    }

    fun createPlaylistAndAdd(name: String, imageIds: List<String>) {
        viewModelScope.launch {
            val playlist = repository.createPlaylist(name)
            repository.addImagesToPlaylist(playlist.id, imageIds)
        }
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { repository.setLibraryColumns(columns) }
    }

    fun createFolder(name: String, onCreated: (FolderEntity) -> Unit = {}) {
        viewModelScope.launch { onCreated(repository.createFolder(name)) }
    }

    fun renameFolder(id: String, name: String) {
        viewModelScope.launch { repository.renameFolder(id, name) }
    }

    fun deleteFolder(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            repository.deleteFolder(id)
            onDeleted()
        }
    }

    fun moveToFolder(imageIds: List<String>, folderId: String?) {
        viewModelScope.launch { repository.moveImagesToFolder(imageIds, folderId) }
    }

    fun createFolderAndMove(name: String, imageIds: List<String>, onMoved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.createFolderWithImages(name, imageIds)
            onMoved()
        }
    }
}
