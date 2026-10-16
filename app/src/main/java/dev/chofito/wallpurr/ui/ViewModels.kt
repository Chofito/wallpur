package dev.chofito.wallpurr.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.compose.ui.platform.LocalContext
import dev.chofito.wallpurr.ui.setup.ApplyViewModel
import dev.chofito.wallpurr.ui.editor.EditorViewModel
import dev.chofito.wallpurr.ui.library.LibraryViewModel
import dev.chofito.wallpurr.ui.library.ShareImportViewModel
import dev.chofito.wallpurr.ui.playlist.PlaylistDetailViewModel
import dev.chofito.wallpurr.ui.playlist.PlaylistsViewModel
import dev.chofito.wallpurr.ui.settings.SettingsViewModel

@Composable
fun libraryViewModel(): LibraryViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(container.repository, container.rotation) }
        },
    )
}

@Composable
fun shareImportViewModel(): ShareImportViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer { ShareImportViewModel(container.repository, container.rotation) }
        },
    )
}

@Composable
fun playlistsViewModel(): PlaylistsViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer { PlaylistsViewModel(container.repository) }
        },
    )
}

@Composable
fun playlistDetailViewModel(): PlaylistDetailViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer {
                PlaylistDetailViewModel(
                    container.repository,
                    container.rotation,
                    createSavedStateHandle(),
                )
            }
        },
    )
}

@Composable
fun editorViewModel(): EditorViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer {
                EditorViewModel(container.repository, container.rotation, createSavedStateHandle())
            }
        },
    )
}

@Composable
fun applyViewModel(): ApplyViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer {
                ApplyViewModel(
                    container.applier,
                    container.rotation,
                    createSavedStateHandle(),
                )
            }
        },
    )
}

@Composable
fun settingsViewModel(): SettingsViewModel {
    val container = LocalContext.current.container()
    return viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.repository,
                    container.scheduler,
                    container.notifier,
                    container.wallpaperPulse,
                    container.rotation,
                )
            }
        },
    )
}
