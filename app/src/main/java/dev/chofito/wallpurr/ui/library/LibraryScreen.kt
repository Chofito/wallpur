package dev.chofito.wallpurr.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.db.FolderEntity
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import dev.chofito.wallpurr.ui.components.ImageThumb
import dev.chofito.wallpurr.ui.components.NameDialog
import dev.chofito.wallpurr.ui.components.PhotoMode
import dev.chofito.wallpurr.ui.components.PhotoTile
import dev.chofito.wallpurr.ui.components.PurrCard
import dev.chofito.wallpurr.ui.components.pickImagesMaxItems
import dev.chofito.wallpurr.ui.components.rememberSettledFullyVisibleKeys
import dev.chofito.wallpurr.ui.libraryViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onEdit: (String) -> Unit,
    viewModel: LibraryViewModel = libraryViewModel(),
) {
    val images by viewModel.images.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val rootFolderId by viewModel.rootFolderId.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentFolder = folders.firstOrNull { it.id == currentFolderId }
    val userFolders = folders.filter { it.id != rootFolderId }
    val imagesByFolder = remember(images) { images.groupBy(ImageAssetEntity::folderId) }
    val shownFolderId = currentFolderId ?: rootFolderId
    val shown = shownFolderId?.let(imagesByFolder::get).orEmpty()
    var mode by remember { mutableStateOf(PhotoMode.Browse) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var addMenu by remember { mutableStateOf(false) }
    var moveMenu by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showColumnsMenu by remember { mutableStateOf(false) }
    var folderDialogMode by remember { mutableStateOf<FolderDialogMode?>(null) }
    var folderName by remember { mutableStateOf("") }
    var confirmFolderDelete by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(currentFolderId, rootFolderId, folders) {
        if (currentFolderId != null && rootFolderId != null && currentFolder == null) {
            currentFolderId = null
        }
    }
    LaunchedEffect(currentFolderId) { gridState.scrollToItem(0) }

    val settledVisibleIds = rememberSettledFullyVisibleKeys(
        gridState = gridState,
        enabled = mode == PhotoMode.Browse,
        contentKey = userFolders.map { it.id } + shown.map { it.id },
    )
    val activeVideoIds = shown.asSequence()
        .filter { it.id in settledVisibleIds && MediaKind.from(it.mediaType) == MediaKind.VIDEO }
        .take(MAX_ACTIVE_GRID_VIDEOS)
        .map { it.id }
        .toSet()
    val activeGifIds = shown.asSequence()
        .filter { it.id in settledVisibleIds && MediaKind.from(it.mediaType) == MediaKind.GIF }
        .take(MAX_ACTIVE_GRID_GIFS)
        .map { it.id }
        .toSet()
    val activeIds = activeVideoIds + activeGifIds

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(pickImagesMaxItems()),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris, currentFolderId)
    }
    val filesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris, currentFolderId)
    }

    fun launchGallery() {
        if (PickVisualMedia.isPhotoPickerAvailable(context)) {
            galleryPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
        } else {
            filesPicker.launch(arrayOf("image/*", "video/*"))
        }
    }

    fun exitModes() {
        mode = PhotoMode.Browse
        selected = emptySet()
        addMenu = false
        moveMenu = false
    }

    fun openFolderDialog(dialogMode: FolderDialogMode, initialName: String = "") {
        folderName = initialName
        folderDialogMode = dialogMode
        showActionsMenu = false
        moveMenu = false
    }

    BackHandler(enabled = mode != PhotoMode.Browse || currentFolderId != null) {
        if (mode != PhotoMode.Browse) exitModes() else currentFolderId = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            mode != PhotoMode.Browse -> stringResource(R.string.selected_count, selected.size)
                            currentFolder != null -> currentFolder.name
                            else -> stringResource(R.string.nav_library)
                        },
                    )
                },
                navigationIcon = {
                    if (mode != PhotoMode.Browse || currentFolderId != null) {
                        IconButton(
                            onClick = {
                                if (mode != PhotoMode.Browse) exitModes() else currentFolderId = null
                            },
                        ) {
                            Icon(
                                if (mode != PhotoMode.Browse) {
                                    Icons.Outlined.Close
                                } else {
                                    Icons.AutoMirrored.Outlined.ArrowBack
                                },
                                contentDescription = stringResource(R.string.cancel),
                            )
                        }
                    }
                },
                actions = {
                    if (mode == PhotoMode.Select) {
                        IconButton(onClick = { moveMenu = true }) {
                            Icon(
                                Icons.Outlined.DriveFileMove,
                                contentDescription = stringResource(R.string.move_to_folder),
                            )
                        }
                        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                            if (currentFolderId != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.without_folder)) },
                                    onClick = {
                                        viewModel.moveToFolder(selected.toList(), null)
                                        exitModes()
                                    },
                                )
                            }
                            userFolders.filter { it.id != currentFolderId }.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        viewModel.moveToFolder(selected.toList(), folder.id)
                                        exitModes()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_folder)) },
                                onClick = { openFolderDialog(FolderDialogMode.CREATE_AND_MOVE) },
                            )
                        }
                        IconButton(onClick = { addMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.PlaylistAdd,
                                contentDescription = stringResource(R.string.add_to_playlist),
                            )
                        }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            playlists.forEach { playlist ->
                                DropdownMenuItem(
                                    text = { Text(playlist.name) },
                                    onClick = {
                                        viewModel.addToPlaylist(playlist, selected.toList())
                                        exitModes()
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_playlist)) },
                                onClick = {
                                    viewModel.createPlaylistAndAdd(
                                        context.getString(R.string.new_playlist),
                                        selected.toList(),
                                    )
                                    exitModes()
                                },
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.delete(selected.toList())
                                exitModes()
                            },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else {
                        IconButton(onClick = { showColumnsMenu = true }) {
                            Icon(
                                Icons.Outlined.GridView,
                                contentDescription = stringResource(R.string.library_grid_columns),
                            )
                        }
                        DropdownMenu(
                            expanded = showColumnsMenu,
                            onDismissRequest = { showColumnsMenu = false },
                        ) {
                            (WallpurrRepository.MIN_LIBRARY_COLUMNS..WallpurrRepository.MAX_LIBRARY_COLUMNS)
                                .forEach { count ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.library_grid_columns_value,
                                                    count,
                                                ),
                                            )
                                        },
                                        onClick = {
                                            viewModel.setColumns(count)
                                            showColumnsMenu = false
                                        },
                                        trailingIcon = {
                                            RadioButton(selected = count == columns, onClick = null)
                                        },
                                    )
                                }
                        }
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.more_actions),
                            )
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                        ) {
                            if (currentFolder != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.rename)) },
                                    onClick = {
                                        openFolderDialog(FolderDialogMode.RENAME, currentFolder.name)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete_folder)) },
                                    onClick = {
                                        showActionsMenu = false
                                        confirmFolderDelete = true
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.new_folder)) },
                                    onClick = { openFolderDialog(FolderDialogMode.CREATE) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_gallery)) },
                                onClick = {
                                    showActionsMenu = false
                                    launchGallery()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_files)) },
                                onClick = {
                                    showActionsMenu = false
                                    filesPicker.launch(arrayOf("image/*", "video/*"))
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (mode == PhotoMode.Browse) {
                ExtendedFloatingActionButton(
                    onClick = { launchGallery() },
                    icon = { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null) },
                    text = {
                        Text(
                            if (currentFolder == null) {
                                stringResource(R.string.import_from_gallery)
                            } else {
                                stringResource(R.string.import_into_folder)
                            },
                        )
                    },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 112.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (currentFolderId == null) {
                item(key = "folders-title", span = { GridItemSpan(maxLineSpan) }) {
                    LibrarySectionHeader(
                        title = stringResource(R.string.folders),
                        action = stringResource(R.string.new_folder),
                        onAction = { openFolderDialog(FolderDialogMode.CREATE) },
                    )
                }
                if (userFolders.isEmpty()) {
                    item(key = "folders-empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.empty_folders),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                } else {
                    items(
                        items = userFolders,
                        key = { "folder-" + it.id },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { folder ->
                        val folderImages = imagesByFolder[folder.id].orEmpty()
                        FolderRow(
                            folder = folder,
                            count = folderImages.size,
                            preview = folderImages.firstOrNull {
                                MediaKind.from(it.mediaType) == MediaKind.IMAGE
                            } ?: folderImages.firstOrNull(),
                            filesDir = context.filesDir,
                            onClick = {
                                exitModes()
                                currentFolderId = folder.id
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                item(key = "unfiled-title", span = { GridItemSpan(maxLineSpan) }) {
                    LibrarySectionHeader(title = stringResource(R.string.without_folder))
                }
                if (shown.isEmpty()) {
                    item(key = "unfiled-empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(R.string.empty_unfiled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (shown.isEmpty()) {
                item(key = "folder-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.empty_folder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, image -> image.id }) { _, image ->
                PhotoTile(
                    image = image,
                    filesDir = context.filesDir,
                    mode = mode,
                    selected = image.id in selected,
                    active = image.id in activeIds,
                    modifier = Modifier.animateItem(),
                    onClick = {
                        when (mode) {
                            PhotoMode.Browse -> onEdit(image.id)
                            PhotoMode.Select, PhotoMode.Reorder -> {
                                selected = if (image.id in selected) selected - image.id else selected + image.id
                            }
                        }
                    },
                    onLongClick = {
                        if (mode == PhotoMode.Browse) {
                            mode = PhotoMode.Select
                            selected = setOf(image.id)
                        }
                    },
                )
            }
        }
    }

    folderDialogMode?.let { dialogMode ->
        NameDialog(
            title = when (dialogMode) {
                FolderDialogMode.RENAME -> stringResource(R.string.rename_folder)
                FolderDialogMode.CREATE, FolderDialogMode.CREATE_AND_MOVE ->
                    stringResource(R.string.new_folder)
            },
            value = folderName,
            onValueChange = { folderName = it },
            onConfirm = {
                when (dialogMode) {
                    FolderDialogMode.CREATE -> {
                        viewModel.createFolder(folderName) { currentFolderId = it.id }
                    }
                    FolderDialogMode.CREATE_AND_MOVE -> {
                        viewModel.createFolderAndMove(folderName, selected.toList()) {
                            exitModes()
                        }
                    }
                    FolderDialogMode.RENAME -> {
                        currentFolderId?.let { viewModel.renameFolder(it, folderName) }
                    }
                }
                folderDialogMode = null
                folderName = ""
            },
            onDismiss = {
                folderDialogMode = null
                folderName = ""
            },
            confirmLabel = stringResource(
                if (dialogMode == FolderDialogMode.RENAME) R.string.save else R.string.create,
            ),
            cancelLabel = stringResource(R.string.cancel),
        )
    }

    if (confirmFolderDelete && currentFolder != null) {
        AlertDialog(
            onDismissRequest = { confirmFolderDelete = false },
            title = { Text(stringResource(R.string.delete_folder)) },
            text = { Text(stringResource(R.string.delete_folder_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmFolderDelete = false
                        viewModel.deleteFolder(currentFolder.id) { currentFolderId = null }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFolderDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LibrarySectionHeader(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderEntity,
    count: Int,
    preview: ImageAssetEntity?,
    filesDir: java.io.File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PurrCard(onClick = onClick, modifier = modifier) {
        ListItem(
            headlineContent = {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Text(stringResource(R.string.folder_item_count, count))
            },
            leadingContent = {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (preview != null) {
                        ImageThumb(
                            image = preview,
                            filesDir = filesDir,
                            framed = false,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

private enum class FolderDialogMode {
    CREATE,
    CREATE_AND_MOVE,
    RENAME,
}

private const val MAX_ACTIVE_GRID_VIDEOS = 2
private const val MAX_ACTIVE_GRID_GIFS = 4
