package dev.chofito.wallpurr.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.ui.editor.EditorScreen
import dev.chofito.wallpurr.ui.library.LibraryScreen
import dev.chofito.wallpurr.ui.library.ShareImportDialogs
import dev.chofito.wallpurr.ui.playlist.PlaylistDetailScreen
import dev.chofito.wallpurr.ui.playlist.PlaylistsScreen
import dev.chofito.wallpurr.ui.preview.PreviewScreen
import dev.chofito.wallpurr.ui.settings.SettingsScreen
import dev.chofito.wallpurr.ui.setup.ApplyScreen
import kotlinx.coroutines.launch

private data class TopDest(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun WallpurrApp(
    shareUris: List<Uri> = emptyList(),
    onShareConsumed: () -> Unit = {},
    openPlaylistId: String? = null,
    onOpenPlaylistConsumed: () -> Unit = {},
) {
    val shareViewModel = shareImportViewModel()
    LaunchedEffect(shareUris) {
        if (shareUris.isNotEmpty()) {
            shareViewModel.importShared(shareUris)
            onShareConsumed()
        }
    }
    val nav = rememberNavController()
    LaunchedEffect(openPlaylistId) {
        val playlistId = openPlaylistId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val currentEntry = nav.currentBackStackEntry
        val alreadyOpen = currentEntry?.destination?.route == "playlist/{playlistId}" &&
            currentEntry.arguments?.getString("playlistId") == playlistId
        if (!alreadyOpen) {
            nav.navigate("playlist/${Uri.encode(playlistId)}") {
                launchSingleTop = true
            }
        }
        onOpenPlaylistConsumed()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val top = listOf(
        TopDest(R.string.nav_library, Icons.Outlined.PhotoLibrary),
        TopDest(R.string.nav_playlists, Icons.AutoMirrored.Outlined.QueueMusic),
        TopDest(R.string.nav_settings, Icons.Outlined.Settings),
    )
    val pagerState = rememberPagerState(pageCount = { top.size })
    val scope = rememberCoroutineScope()
    val showBar = route == "tabs"

    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (showBar && !useRail) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            top.forEachIndexed { index, dest ->
                                NavigationBarItem(
                                    selected = pagerState.currentPage == index,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    icon = { Icon(dest.icon, contentDescription = null) },
                                    label = { Text(stringResource(dest.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    if (showBar && useRail) {
                        NavigationRail {
                            top.forEachIndexed { index, dest ->
                                NavigationRailItem(
                                    selected = pagerState.currentPage == index,
                                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                    icon = { Icon(dest.icon, contentDescription = null) },
                                    label = { Text(stringResource(dest.label)) },
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        NavHost(navController = nav, startDestination = "tabs") {
                            composable("tabs") {
                                TabsPager(
                                    pagerState = pagerState,
                                    onEdit = { nav.navigate("editor/$it") },
                                    onOpenPlaylist = { nav.navigate("playlist/$it") },
                                )
                            }
                            composable(
                                "playlist/{playlistId}",
                                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                            ) { playlistEntry ->
                                val playlistId = playlistEntry.arguments?.getString("playlistId").orEmpty()
                                PlaylistDetailScreen(
                                    onBack = { nav.popBackStack() },
                                    onEdit = { nav.navigate("editor/$it?playlistId=$playlistId") },
                                    onApply = { nav.navigate("apply/$it") },
                                )
                            }
                            composable(
                                "editor/{imageId}?playlistId={playlistId}",
                                arguments = listOf(
                                    navArgument("imageId") { type = NavType.StringType },
                                    navArgument("playlistId") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    },
                                ),
                            ) {
                                EditorScreen(
                                    onBack = { nav.popBackStack() },
                                    onPreview = { imageId, orientation, playlistId ->
                                        nav.navigate(
                                            "preview/$imageId/${orientation.name}?playlistId=$playlistId",
                                        )
                                    },
                                )
                            }
                            composable(
                                "preview/{imageId}/{orientation}?playlistId={playlistId}",
                                arguments = listOf(
                                    navArgument("imageId") { type = NavType.StringType },
                                    navArgument("orientation") { type = NavType.StringType },
                                    navArgument("playlistId") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    },
                                ),
                            ) {
                                PreviewScreen(onBack = { nav.popBackStack() })
                            }
                            composable(
                                "apply/{playlistId}",
                                arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                            ) {
                                ApplyScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
        ShareImportDialogs(shareViewModel)
    }
}

@Composable
private fun TabsPager(
    pagerState: PagerState,
    onEdit: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 -> LibraryScreen(onEdit = onEdit)
            1 -> PlaylistsScreen(onOpen = onOpenPlaylist)
            else -> SettingsScreen()
        }
    }
}
