package dev.chofito.wallpurr.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.storage.PlaylistCard
import dev.chofito.wallpurr.storage.db.PlaylistEntity
import dev.chofito.wallpurr.ui.components.EmptyHint
import dev.chofito.wallpurr.ui.components.HiddenContentLockButton
import dev.chofito.wallpurr.ui.components.HiddenRevealTitle
import dev.chofito.wallpurr.ui.components.IconBubble
import dev.chofito.wallpurr.ui.components.ImageThumb
import dev.chofito.wallpurr.ui.components.NameDialog
import dev.chofito.wallpurr.ui.components.PurrCard
import dev.chofito.wallpurr.ui.components.wallpaperTargetIcon
import dev.chofito.wallpurr.ui.components.wallpaperTargetLabel
import dev.chofito.wallpurr.ui.playlistsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    onOpen: (String) -> Unit,
    viewModel: PlaylistsViewModel = playlistsViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { HiddenRevealTitle(stringResource(R.string.nav_playlists)) },
                actions = { HiddenContentLockButton() },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.new_playlist)) },
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            EmptyHint(stringResource(R.string.empty_playlists), Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(cards, key = { it.playlist.id }) { card ->
                    val playlist = card.playlist
                    PurrCard(
                        onClick = { onOpen(playlist.id) },
                        onLongClick = { menuId = playlist.id },
                        menuExpanded = menuId == playlist.id,
                        onDismissMenu = { menuId = null },
                        onRename = {
                            renameTarget = playlist
                            name = playlist.name
                        },
                        onDelete = { viewModel.delete(playlist.id) },
                        modifier = Modifier.animateItem(),
                    ) {
                        Column {
                            ListItem(
                                headlineContent = {
                                    Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                                },
                                supportingContent = {
                                    Text(
                                        "${playlist.intervalMinutes} ${stringResource(R.string.minutes)} · ${wallpaperTargetLabel(playlist.target)}",
                                    )
                                },
                                leadingContent = {
                                    IconBubble(wallpaperTargetIcon(playlist.target))
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            PlaylistPreviewStrip(
                                card = card,
                                filesDir = context.filesDir,
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            value = name,
            onValueChange = { name = it },
            onConfirm = {
                viewModel.create(name) { showCreate = false; name = "" }
            },
            onDismiss = { showCreate = false; name = "" },
            confirmLabel = stringResource(R.string.create),
            cancelLabel = stringResource(R.string.cancel),
        )
    }
    renameTarget?.let { playlist ->
        NameDialog(
            title = stringResource(R.string.rename),
            value = name,
            onValueChange = { name = it },
            onConfirm = {
                viewModel.rename(playlist.id, name)
                renameTarget = null
                name = ""
            },
            onDismiss = { renameTarget = null; name = "" },
            confirmLabel = stringResource(R.string.save),
            cancelLabel = stringResource(R.string.cancel),
        )
    }
}

@Composable
private fun PlaylistPreviewStrip(
    card: PlaylistCard,
    filesDir: java.io.File,
    modifier: Modifier = Modifier,
) {
    val slot = RoundedCornerShape(14.dp)
    val empty = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(3) { index ->
            val image = card.previews.getOrNull(index)
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(3f / 4f)
                    .clip(slot)
                    .background(empty),
            ) {
                if (image != null) {
                    ImageThumb(
                        image = image,
                        filesDir = filesDir,
                        framed = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
