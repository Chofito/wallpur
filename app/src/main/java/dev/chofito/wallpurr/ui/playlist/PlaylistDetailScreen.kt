@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package dev.chofito.wallpurr.ui.playlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.LockStyle
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import dev.chofito.wallpurr.ui.components.GridReorderState
import dev.chofito.wallpurr.ui.components.IntervalSelector
import dev.chofito.wallpurr.ui.components.LockAppearanceSelector
import dev.chofito.wallpurr.ui.components.PhotoMode
import dev.chofito.wallpurr.ui.components.PhotoTile
import dev.chofito.wallpurr.ui.components.gridReorderItem
import dev.chofito.wallpurr.ui.components.rememberGridReorderState
import dev.chofito.wallpurr.ui.components.rememberMediaThumbnailFile
import dev.chofito.wallpurr.ui.components.rememberSettledFullyVisibleKeys
import dev.chofito.wallpurr.ui.components.selectActiveGridMediaIds
import dev.chofito.wallpurr.ui.playlistDetailViewModel
import java.io.File

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onApply: (String) -> Unit,
    viewModel: PlaylistDetailViewModel = playlistDetailViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffle.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isTabletWidth = LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH_DP
    val current = playlist ?: return
    val selectedTarget = WallpaperTarget.from(current.target)
    var intervalDraft by remember(current.id, current.intervalMinutes) {
        mutableStateOf(current.intervalMinutes)
    }
    var lockBlurDraft by remember(current.id, current.lockBlurRadius) {
        mutableStateOf(current.lockBlurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS))
    }
    var lockOpacityDraft by remember(current.id, current.lockOpacity) {
        mutableStateOf(current.lockOpacity.coerceIn(LockStyle.MIN_OPACITY, 1f))
    }
    var mode by remember { mutableStateOf(PhotoMode.Browse) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val gridState = rememberLazyGridState()
    val reorder = rememberGridReorderState { ids -> viewModel.reorder(ids) }
    LaunchedEffect(images) { reorder.sync(images.map { it.id }) }
    val shown = if (mode == PhotoMode.Reorder) {
        val byId = images.associateBy { it.id }
        reorder.orderIds.mapNotNull { byId[it] }.ifEmpty { images }
    } else {
        images
    }
    val shownIds = remember(shown) { shown.map { it.id } }
    val mediaKindsById = remember(shown) {
        shown.associate { it.id to MediaKind.from(it.mediaType) }
    }
    val settledVisibleIds = rememberSettledFullyVisibleKeys(
        gridState = gridState,
        enabled = mode == PhotoMode.Browse,
        contentKey = shownIds,
    )
    val activeMediaIds = remember(settledVisibleIds, mediaKindsById, isTabletWidth) {
        selectActiveGridMediaIds(
            fullyVisibleKeys = settledVisibleIds,
            mediaKindsById = mediaKindsById,
            maxActive = if (isTabletWidth) 1 else 2,
        )
    }
    val cover = images.firstOrNull()
    val isScrolling = gridState.isScrollInProgress
    val effectiveLockBlur = (
        cover?.takeIf { it.lockStyleOverride }?.lockBlurRadius ?: lockBlurDraft
    ).coerceIn(0, LockStyle.MAX_BLUR_RADIUS)
    val effectiveLockOpacity = (
        cover?.takeIf { it.lockStyleOverride }?.lockOpacity ?: lockOpacityDraft
    ).coerceIn(LockStyle.MIN_OPACITY, 1f)

    fun exitModes() {
        mode = PhotoMode.Browse
        selected = emptySet()
    }

    BackHandler(enabled = mode != PhotoMode.Browse) { exitModes() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (mode == PhotoMode.Browse) {
                            current.name
                        } else if (mode == PhotoMode.Reorder) {
                            stringResource(R.string.reorder_hint)
                        } else {
                            stringResource(R.string.selected_count, selected.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (mode == PhotoMode.Browse) onBack() else exitModes() }) {
                        Icon(
                            if (mode == PhotoMode.Browse) {
                                Icons.AutoMirrored.Outlined.ArrowBack
                            } else {
                                Icons.Outlined.Close
                            },
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    if (mode == PhotoMode.Select) {
                        IconButton(
                            onClick = {
                                viewModel.removeMany(selected.toList())
                                exitModes()
                            },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    } else if (mode == PhotoMode.Browse) {
                        IconButton(onClick = { viewModel.resetOrder() }) {
                            Icon(
                                Icons.Outlined.FormatListNumbered,
                                contentDescription = stringResource(R.string.follow_list_order),
                            )
                        }
                        IconButton(onClick = { viewModel.reshuffle() }) {
                            Icon(Icons.Outlined.Shuffle, contentDescription = stringResource(R.string.shuffle_reset))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (mode == PhotoMode.Browse) {
                ExtendedFloatingActionButton(
                    onClick = { onApply(current.id) },
                    icon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                    text = { Text(stringResource(R.string.apply)) },
                )
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = if (mode == PhotoMode.Browse) 112.dp else 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = mode != PhotoMode.Reorder,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (mode == PhotoMode.Browse) {
                item(key = GridReorderState.HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.remaining, shuffle?.remainingIds?.size ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item(key = "destination", span = { GridItemSpan(maxLineSpan) }) {
                    DestinationPicker(
                        selected = selectedTarget,
                        onSelect = { viewModel.setTarget(it) },
                        cover = cover,
                        filesDir = context.filesDir,
                        lockBlurRadius = if (isScrolling) 0 else effectiveLockBlur,
                        lockOpacity = effectiveLockOpacity,
                    )
                }
                item(key = "lock-appearance", span = { GridItemSpan(maxLineSpan) }) {
                    LockAppearanceSelector(
                        blurRadius = lockBlurDraft,
                        opacity = lockOpacityDraft,
                        supportingText = stringResource(R.string.lock_style_playlist_hint),
                        onBlurChange = {
                            lockBlurDraft = it.coerceIn(0, LockStyle.MAX_BLUR_RADIUS)
                        },
                        onBlurCommit = {
                            viewModel.setLockStyle(lockBlurDraft, lockOpacityDraft)
                        },
                        onOpacityChange = {
                            lockOpacityDraft = it.coerceIn(LockStyle.MIN_OPACITY, 1f)
                        },
                        onOpacityCommit = {
                            viewModel.setLockStyle(lockBlurDraft, lockOpacityDraft)
                        },
                    )
                }
                item(key = "interval", span = { GridItemSpan(maxLineSpan) }) {
                    IntervalSelector(
                        minutes = intervalDraft,
                        onSelect = {
                            intervalDraft = it.coerceIn(1, 180)
                            viewModel.setInterval(intervalDraft)
                        },
                    )
                }
            }
            itemsIndexed(shown, key = { _, image -> image.id }) { _, image ->
                val lifting = reorder.draggingId == image.id
                PhotoTile(
                    image = image,
                    filesDir = context.filesDir,
                    mode = mode,
                    selected = image.id in selected,
                    active = image.id in activeMediaIds,
                    thumbnailLoadingEnabled = !isScrolling,
                    modifier = Modifier
                        .then(if (mode == PhotoMode.Reorder) Modifier else Modifier.animateItem())
                        .gridReorderItem(
                            state = reorder,
                            gridState = gridState,
                            id = image.id,
                            enabled = mode == PhotoMode.Reorder,
                        )
                        .zIndex(if (lifting) 1f else 0f)
                        .graphicsLayer {
                            translationX = if (lifting) reorder.dragOffset.x else 0f
                            translationY = if (lifting) reorder.dragOffset.y else 0f
                            shadowElevation = if (lifting) 18f else 0f
                        },
                    onClick = {
                        when (mode) {
                            PhotoMode.Browse -> onEdit(image.id)
                            PhotoMode.Select, PhotoMode.Reorder -> {
                                selected = if (image.id in selected) selected - image.id else selected + image.id
                            }
                        }
                    },
                    onLongClick = {
                        when (mode) {
                            PhotoMode.Browse -> {
                                mode = PhotoMode.Select
                                selected = setOf(image.id)
                            }
                            PhotoMode.Select -> {
                                reorder.sync(images.map { it.id })
                                mode = PhotoMode.Reorder
                            }
                            PhotoMode.Reorder -> mode = PhotoMode.Select
                        }
                    },
                    overlay = {
                        val isCurrentWallpaper = shuffle?.currentImageId == image.id
                        WallpaperNowIndicator(
                            isCurrent = isCurrentWallpaper,
                            onClick = {
                                if (!isCurrentWallpaper) {
                                    viewModel.showNow(image.id) {
                                        onApply(current.id)
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun WallpaperNowIndicator(
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.62f)
            },
            contentColor = if (isCurrent) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                Color.White
            },
        ),
    ) {
        Icon(
            imageVector = if (isCurrent) Icons.Filled.CheckCircle else Icons.Outlined.Wallpaper,
            contentDescription = stringResource(
                if (isCurrent) R.string.current_wallpaper else R.string.use_wallpaper_now,
            ),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun DestinationPicker(
    selected: WallpaperTarget,
    onSelect: (WallpaperTarget) -> Unit,
    cover: ImageAssetEntity?,
    filesDir: File,
    lockBlurRadius: Int,
    lockOpacity: Float,
) {
    val coverFile = cover?.let { File(filesDir, "images/${it.storedName}") }
    val coverThumbnail = if (cover != null && coverFile != null) {
        rememberMediaThumbnailFile(cover, coverFile, maxSide = 480)
    } else {
        null
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.target_where),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
        ) {
            DestinationTile(
                target = WallpaperTarget.HOME,
                label = stringResource(R.string.target_home_short),
                selected = selected == WallpaperTarget.HOME,
                coverThumbnail = coverThumbnail,
                lockBlurRadius = lockBlurRadius,
                lockOpacity = lockOpacity,
                onClick = { onSelect(WallpaperTarget.HOME) },
                modifier = Modifier.weight(1f),
            )
            DestinationTile(
                target = WallpaperTarget.LOCK,
                label = stringResource(R.string.target_lock_short),
                selected = selected == WallpaperTarget.LOCK,
                coverThumbnail = coverThumbnail,
                lockBlurRadius = lockBlurRadius,
                lockOpacity = lockOpacity,
                onClick = { onSelect(WallpaperTarget.LOCK) },
                modifier = Modifier.weight(1f),
            )
            DestinationTile(
                target = WallpaperTarget.BOTH,
                label = stringResource(R.string.target_both_short),
                selected = selected == WallpaperTarget.BOTH,
                coverThumbnail = coverThumbnail,
                lockBlurRadius = lockBlurRadius,
                lockOpacity = lockOpacity,
                onClick = { onSelect(WallpaperTarget.BOTH) },
                modifier = Modifier.weight(1f),
            )
        }
        if (cover?.lockStyleOverride == true) {
            Text(
                stringResource(R.string.target_cover_override_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DestinationTile(
    target: WallpaperTarget,
    label: String,
    selected: Boolean,
    coverThumbnail: File?,
    lockBlurRadius: Int,
    lockOpacity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val border by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant,
        label = "tile-border",
    )
    val labelColor by animateColorAsState(
        if (selected) scheme.primary else scheme.onSurfaceVariant,
        label = "tile-label",
    )
    val scale by animateFloatAsState(
        if (selected) 1f else 0.96f,
        animationSpec = spring(stiffness = 400f),
        label = "tile-scale",
    )
    Column(
        modifier = modifier.scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 14f)
                    .clip(RoundedCornerShape(28.dp))
                    .border(if (selected) 3.dp else 1.dp, border, RoundedCornerShape(28.dp))
                    .background(Color.Black)
                    .clickable(onClick = onClick),
            ) {
                val tileWidth = maxWidth
                val tileHeight = maxHeight
                when (target) {
                    WallpaperTarget.HOME -> {
                        DestinationCover(
                            coverThumbnail = coverThumbnail,
                            label = label,
                            modifier = Modifier.fillMaxSize(),
                        )
                        HomePreviewDots(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp),
                        )
                    }
                    WallpaperTarget.LOCK -> {
                        DestinationCover(
                            coverThumbnail = coverThumbnail,
                            label = label,
                            modifier = Modifier
                                .fillMaxSize()
                                .lockPreviewEffect(lockBlurRadius, lockOpacity),
                        )
                        LockPreviewClock(Modifier.fillMaxSize())
                    }
                    WallpaperTarget.BOTH -> {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .align(Alignment.CenterStart)
                                .clipToBounds(),
                        ) {
                            DestinationCover(
                                coverThumbnail = coverThumbnail,
                                label = label,
                                modifier = Modifier.fillMaxSize(),
                            )
                            HomePreviewDots(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp),
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.5f)
                                .align(Alignment.CenterEnd)
                                .clipToBounds(),
                        ) {
                            DestinationCover(
                                coverThumbnail = coverThumbnail,
                                label = label,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .lockPreviewEffect(lockBlurRadius, lockOpacity),
                            )
                            LockPreviewClock(
                                Modifier
                                    .requiredSize(tileWidth, tileHeight)
                                    .offset(x = -(tileWidth / 2)),
                            )
                        }
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .size(1.dp, tileHeight)
                                .align(Alignment.Center)
                                .background(Color.White.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(18.dp)
                        .background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
                        repeat(2) {
                            Column(verticalArrangement = Arrangement.spacedBy(1.5.dp)) {
                                repeat(2) {
                                    Box(
                                        Modifier
                                            .size(3.dp)
                                            .background(Color.White, CircleShape),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomePreviewDots(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(Color.White.copy(alpha = 0.5f), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun LockPreviewClock(modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.32f)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    ),
                ),
        )
        Text(
            "9:41",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )
    }
}

@Composable
private fun DestinationCover(
    coverThumbnail: File?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when {
        coverThumbnail != null -> {
            AsyncImage(
                model = coverThumbnail,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
        else -> {
            Box(
                modifier.background(
                    Brush.verticalGradient(
                        listOf(scheme.primaryContainer, scheme.secondaryContainer),
                    ),
                ),
            )
        }
    }
}

private fun Modifier.lockPreviewEffect(blurRadius: Int, opacity: Float): Modifier {
    val style = LockStyle(blurRadius, opacity).clamped()
    if (!style.hasEffects) return this
    return this
        .then(
            if (style.blurRadius > 0) {
                Modifier.blur(
                    radius = style.visualBlurRadiusDp.dp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                )
            } else {
                Modifier
            },
        )
        .then(
            if (style.visualDarkness > 0.001f) {
                Modifier.drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = style.visualDarkness))
                }
            } else {
                Modifier
            },
        )
}

private const val TABLET_MIN_WIDTH_DP = 600
