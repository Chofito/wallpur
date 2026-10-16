package dev.chofito.wallpurr.ui.editor

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chofito.wallpurr.ui.components.MutedGifView
import dev.chofito.wallpurr.ui.components.MutedVideoView
import dev.chofito.wallpurr.ui.components.LockAppearanceSelector
import dev.chofito.wallpurr.ui.components.ValueStepper
import java.io.File
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.editor.DeviceViewport
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.editor.FitMode
import dev.chofito.wallpurr.editor.ImageLayout
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.applyLayout
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.LockStyle
import dev.chofito.wallpurr.storage.VideoPlayMode
import dev.chofito.wallpurr.ui.editorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onPreview: (String, ScreenOrientation, String) -> Unit,
    viewModel: EditorViewModel = editorViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current
    var showColorDialog by remember { mutableStateOf(false) }
    var colorHex by remember(state.transform.containBackgroundColor) {
        mutableStateOf("#%06X".format(state.transform.containBackgroundColor and 0xFFFFFF))
    }
    var colorHexError by remember { mutableStateOf(false) }
    val consumeScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
            override suspend fun onPreFling(available: Velocity): Velocity = available
        }
    }
    BackHandler {
        viewModel.commitTransform(onBack)
    }

    DisposableEffect(view) {
        val previousVertical = view.isVerticalScrollBarEnabled
        val previousHorizontal = view.isHorizontalScrollBarEnabled
        val previousOverscroll = view.overScrollMode
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        onDispose {
            view.isVerticalScrollBarEnabled = previousVertical
            view.isHorizontalScrollBarEnabled = previousHorizontal
            view.overScrollMode = previousOverscroll
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.displayName.ifBlank { stringResource(R.string.edit_image) }) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.commitTransform(onBack) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.commitTransform {
                                onPreview(
                                    viewModel.imageId,
                                    state.orientation,
                                    viewModel.sourcePlaylistId,
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.PhoneIphone, contentDescription = stringResource(R.string.preview))
                    }
                },
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CropStage(
                    bitmap = state.bitmap,
                    containBackdrop = state.containBackdrop,
                    transform = state.transform,
                    orientation = state.orientation,
                    imageId = viewModel.imageId,
                    mediaKind = state.mediaKind,
                    mediaFile = state.storedName.takeIf { it.isNotBlank() }?.let { stored ->
                        java.io.File(context.filesDir, "images/$stored")
                    },
                    onViewport = viewModel::setViewport,
                    onGesture = viewModel::gesture,
                    onDoubleTap = viewModel::toggleZoom,
                    lockBlurRadius = if (state.lockStyleOverride) {
                        state.lockBlurRadius
                    } else {
                        state.inheritedLockBlurRadius
                    },
                    lockOpacity = if (state.lockStyleOverride) {
                        state.lockOpacity
                    } else {
                        state.inheritedLockOpacity
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(consumeScroll),
                )
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.orientation == ScreenOrientation.PORTRAIT,
                                onClick = { viewModel.setOrientation(ScreenOrientation.PORTRAIT) },
                                label = { Text(stringResource(R.string.portrait)) },
                            )
                            FilterChip(
                                selected = state.orientation == ScreenOrientation.LANDSCAPE,
                                onClick = { viewModel.setOrientation(ScreenOrientation.LANDSCAPE) },
                                label = { Text(stringResource(R.string.landscape)) },
                            )
                        }
                        Text(stringResource(R.string.fit_mode), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FitMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.transform.fitMode == mode,
                                    onClick = { viewModel.setFit(mode) },
                                    label = {
                                        Text(
                                            when (mode) {
                                                FitMode.CONTAIN -> stringResource(R.string.fit_contain)
                                                FitMode.COVER -> stringResource(R.string.fit_cover)
                                                FitMode.STRETCH -> stringResource(R.string.fit_stretch)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        if (state.transform.fitMode == FitMode.CONTAIN) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.contain_background_title))
                                    Text(
                                        stringResource(R.string.contain_background_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = state.transform.containBackgroundMode !=
                                        ContainBackgroundMode.NONE,
                                    onCheckedChange = viewModel::setContainBackgroundEnabled,
                                )
                            }
                            if (state.transform.containBackgroundMode != ContainBackgroundMode.NONE) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = state.transform.containBackgroundMode ==
                                            ContainBackgroundMode.COLOR,
                                        onClick = {
                                            viewModel.setContainBackgroundMode(
                                                ContainBackgroundMode.COLOR,
                                            )
                                        },
                                        label = { Text(stringResource(R.string.contain_background_colors)) },
                                    )
                                    FilterChip(
                                        selected = state.transform.containBackgroundMode ==
                                            ContainBackgroundMode.BLUR,
                                        onClick = {
                                            viewModel.setContainBackgroundMode(
                                                ContainBackgroundMode.BLUR,
                                            )
                                        },
                                        label = { Text(stringResource(R.string.contain_background_blur)) },
                                    )
                                }
                                if (state.transform.containBackgroundMode == ContainBackgroundMode.COLOR) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        state.paletteColors.take(5).forEach { paletteColor ->
                                            val selected = paletteColor ==
                                                state.transform.containBackgroundColor
                                            Box(
                                                Modifier
                                                    .size(38.dp)
                                                    .background(Color(paletteColor), CircleShape)
                                                    .border(
                                                        width = if (selected) 3.dp else 1.dp,
                                                        color = if (selected) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.outline
                                                        },
                                                        shape = CircleShape,
                                                    )
                                                    .clickable {
                                                        viewModel.setContainBackgroundColor(paletteColor)
                                                    },
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                colorHex = "#%06X".format(
                                                    state.transform.containBackgroundColor and 0xFFFFFF,
                                                )
                                                colorHexError = false
                                                showColorDialog = true
                                            },
                                        ) {
                                            Text(stringResource(R.string.contain_background_custom))
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            stringResource(
                                R.string.rotation_value,
                                state.transform.rotationDegrees.toInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Slider(
                                value = state.transform.rotationDegrees,
                                onValueChange = { viewModel.setRotation(it) },
                                onValueChangeFinished = { viewModel.commitTransform() },
                                valueRange = -180f..180f,
                                steps = 360,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.resetRotation() }) {
                                Text(stringResource(R.string.reset))
                            }
                        }
                        if (state.mediaKind == MediaKind.VIDEO) {
                            Text(stringResource(R.string.video_duration), style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.videoPlayMode == VideoPlayMode.TIMES,
                                    onClick = { viewModel.setVideoPlayMode(VideoPlayMode.TIMES) },
                                    label = { Text(stringResource(R.string.video_times)) },
                                )
                                FilterChip(
                                    selected = state.videoPlayMode == VideoPlayMode.MINUTES,
                                    onClick = { viewModel.setVideoPlayMode(VideoPlayMode.MINUTES) },
                                    label = { Text(stringResource(R.string.video_minutes_mode)) },
                                )
                            }
                            if (state.videoPlayMode == VideoPlayMode.TIMES) {
                                ValueStepper(
                                    value = state.videoPlayCount,
                                    valueLabel = stringResource(
                                        R.string.video_times_value,
                                        state.videoPlayCount,
                                    ),
                                    range = 1..20,
                                    onSelect = viewModel::setVideoPlayCount,
                                )
                            } else {
                                ValueStepper(
                                    value = state.videoPlayMinutes,
                                    valueLabel = stringResource(
                                        R.string.video_minutes_value,
                                        state.videoPlayMinutes,
                                    ),
                                    range = 1..180,
                                    onSelect = viewModel::setVideoPlayMinutes,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.lock_style_override))
                                Text(
                                    stringResource(
                                        if (state.hasPlaylistContext) {
                                            R.string.lock_style_inherited_preview_hint
                                        } else {
                                            R.string.lock_style_override_hint
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.lockStyleOverride,
                                onCheckedChange = viewModel::setLockStyleOverride,
                            )
                        }
                        if (state.lockStyleOverride) {
                            LockAppearanceSelector(
                                blurRadius = state.lockBlurRadius,
                                opacity = state.lockOpacity,
                                supportingText = stringResource(R.string.lock_style_editor_hint),
                                onBlurChange = {
                                    viewModel.setLockStyle(it, state.lockOpacity)
                                },
                                onBlurCommit = viewModel::commitLockStyle,
                                onOpacityChange = {
                                    viewModel.setLockStyle(state.lockBlurRadius, it)
                                },
                                onOpacityCommit = viewModel::commitLockStyle,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text(stringResource(R.string.contain_background_custom_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val previewColor = runCatching {
                        android.graphics.Color.parseColor(colorHex)
                    }.getOrDefault(state.transform.containBackgroundColor)
                    Box(
                        Modifier
                            .size(48.dp)
                            .background(Color(previewColor), CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    OutlinedTextField(
                        value = colorHex,
                        onValueChange = {
                            colorHex = it.uppercase().take(7)
                            colorHexError = false
                        },
                        label = { Text("#RRGGBB") },
                        isError = colorHexError,
                        singleLine = true,
                        modifier = Modifier.width(180.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.setContainBackgroundHex(colorHex)) {
                            showColorDialog = false
                        } else {
                            colorHexError = true
                        }
                    },
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CropStage(
    bitmap: android.graphics.Bitmap?,
    containBackdrop: android.graphics.Bitmap?,
    transform: ImageTransform,
    orientation: ScreenOrientation,
    imageId: String,
    mediaKind: MediaKind,
    mediaFile: File?,
    onViewport: (Float, Float) -> Unit,
    onGesture: (Float, Float, Float, Float, Float) -> Unit,
    onDoubleTap: () -> Unit,
    lockBlurRadius: Int,
    lockOpacity: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val device = remember { DeviceViewport.current(context) }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    var previewLockScreen by remember(imageId) { mutableStateOf(false) }
    val previewBlurRadius = if (previewLockScreen) lockBlurRadius else 0
    val previewOpacity = if (previewLockScreen) lockOpacity else 1f
    val hasLockEffects = previewBlurRadius > 0 || previewOpacity < 0.999f
    val showLivePreview = mediaFile != null && when (mediaKind) {
        MediaKind.VIDEO -> !hasLockEffects
        MediaKind.GIF -> previewBlurRadius == 0
        MediaKind.IMAGE -> false
    }

    BoxWithConstraints(
        modifier
            .background(Color.Black)
            .clipToBounds(),
    ) {
        val padH = with(density) { 24.dp.toPx() }
        val padV = with(density) { 20.dp.toPx() }
        val availW = (constraints.maxWidth.toFloat() - padH * 2f).coerceAtLeast(1f)
        val availH = (constraints.maxHeight.toFloat() - padV * 2f).coerceAtLeast(1f)
        val (frameW, frameH) = device.fittedSize(availW, availH, orientation)
        LaunchedEffect(frameW, frameH) { onViewport(frameW, frameH) }
        val left = (constraints.maxWidth - frameW) / 2f
        val top = (constraints.maxHeight - frameH) / 2f

        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() })
                .clip(RoundedCornerShape(28.dp))
                .clipToBounds()
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .previewLockEffect(previewBlurRadius, previewOpacity),
            ) {
                ContainBackgroundLayer(containBackdrop, transform)
                if (showLivePreview) {
                    if (mediaKind == MediaKind.VIDEO && mediaFile != null) {
                        MutedVideoView(
                            file = mediaFile,
                            modifier = Modifier.fillMaxSize(),
                            looping = true,
                            sourceWidth = bitmap?.width ?: 0,
                            sourceHeight = bitmap?.height ?: 0,
                            transform = transform,
                            transparentBackground = transform.usesContainBackground,
                        )
                    } else if (mediaFile != null) {
                        MutedGifView(
                            file = mediaFile,
                            modifier = Modifier.fillMaxSize(),
                            transform = transform,
                            transparentBackground = transform.usesContainBackground,
                        )
                    }
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        if (image != null) {
                            val layout = ImageLayout.layout(
                                imageWidth = image.width,
                                imageHeight = image.height,
                                viewportWidth = size.width,
                                viewportHeight = size.height,
                                transform = transform,
                            )
                            withTransform({ applyLayout(layout) }) {
                                drawImage(image)
                            }
                        }
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(orientation, imageId, frameW, frameH) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        onGesture(zoom, pan.x, pan.y, frameW, frameH)
                    }
                }
                .pointerInput(imageId) {
                    detectTapGestures(onDoubleTap = { onDoubleTap() })
                },
        ) {
            val frameLeft = (size.width - frameW) / 2f
            val frameTop = (size.height - frameH) / 2f
            val frame = Rect(frameLeft, frameTop, frameLeft + frameW, frameTop + frameH)
            val radius = 28.dp.toPx()
            val corner = CornerRadius(radius, radius)
            val hole = Path().apply { addRoundRect(RoundRect(frame, corner)) }

            drawRect(Color.Black.copy(alpha = 0.55f))
            clipPath(hole) {
                drawRect(Color.Black, blendMode = BlendMode.Clear)
                val grid = Color.White.copy(alpha = 0.35f)
                val stroke = 1.dp.toPx()
                for (i in 1..2) {
                    val x = frameLeft + frameW * i / 3f
                    val y = frameTop + frameH * i / 3f
                    drawLine(grid, Offset(x, frameTop), Offset(x, frameTop + frameH), stroke)
                    drawLine(grid, Offset(frameLeft, y), Offset(frameLeft + frameW, y), stroke)
                }
            }

            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = frame.topLeft,
                size = frame.size,
                cornerRadius = corner,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() }),
        ) {
            CropPreviewTargetToggle(
                previewLockScreen = previewLockScreen,
                onPreviewLockScreenChange = { previewLockScreen = it },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
        }
        Text(
            text = stringResource(R.string.editor_crop_hint),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun CropPreviewTargetToggle(
    previewLockScreen: Boolean,
    onPreviewLockScreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 5.dp,
    ) {
        Row(Modifier.padding(3.dp)) {
            CropPreviewTargetButton(
                selected = !previewLockScreen,
                onClick = { onPreviewLockScreenChange(false) },
                icon = Icons.Outlined.Home,
                contentDescription = stringResource(R.string.target_home_short),
            )
            CropPreviewTargetButton(
                selected = previewLockScreen,
                onClick = { onPreviewLockScreenChange(true) },
                icon = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.target_lock_short),
            )
        }
    }
}

@Composable
private fun CropPreviewTargetButton(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 42.dp, height = 36.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun TransformPreview(
    bitmap: android.graphics.Bitmap?,
    transform: ImageTransform,
    containBackdrop: android.graphics.Bitmap? = null,
    modifier: Modifier = Modifier,
    mediaKind: MediaKind = MediaKind.IMAGE,
    mediaFile: File? = null,
    lockBlurRadius: Int = 0,
    lockOpacity: Float = 1f,
) {
    val image = bitmap?.asImageBitmap()
    val hasLockEffects = lockBlurRadius > 0 || lockOpacity < 0.999f
    val showLivePreview = mediaFile != null && when (mediaKind) {
        MediaKind.VIDEO -> !hasLockEffects
        MediaKind.GIF -> lockBlurRadius == 0
        MediaKind.IMAGE -> false
    }
    Box(modifier.clipToBounds().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxSize()
                .previewLockEffect(lockBlurRadius, lockOpacity),
        ) {
            ContainBackgroundLayer(containBackdrop, transform)
            if (showLivePreview && mediaFile != null) {
                if (mediaKind == MediaKind.VIDEO) {
                    MutedVideoView(
                        file = mediaFile,
                        modifier = Modifier.fillMaxSize(),
                        looping = true,
                        sourceWidth = bitmap?.width ?: 0,
                        sourceHeight = bitmap?.height ?: 0,
                        transform = transform,
                        transparentBackground = transform.usesContainBackground,
                    )
                } else {
                    MutedGifView(
                        file = mediaFile,
                        modifier = Modifier.fillMaxSize(),
                        transform = transform,
                        transparentBackground = transform.usesContainBackground,
                    )
                }
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    clipRect {
                        if (image != null) {
                            val layout = ImageLayout.layout(
                                imageWidth = image.width,
                                imageHeight = image.height,
                                viewportWidth = size.width,
                                viewportHeight = size.height,
                                transform = transform,
                            )
                            withTransform({
                                applyLayout(layout)
                            }) {
                                drawImage(image)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ContainBackgroundLayer(
    blurredBitmap: android.graphics.Bitmap?,
    transform: ImageTransform,
) {
    if (!transform.usesContainBackground) return
    val blurredImage = remember(blurredBitmap) {
        blurredBitmap?.takeUnless { it.isRecycled }?.asImageBitmap()
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(transform.containBackgroundColor))
        if (
            transform.containBackgroundMode == ContainBackgroundMode.BLUR &&
            blurredImage != null
        ) {
            val layout = ImageLayout.layout(
                imageWidth = blurredImage.width,
                imageHeight = blurredImage.height,
                viewportWidth = size.width,
                viewportHeight = size.height,
                transform = transform.containBackdropTransform(),
            )
            withTransform({ applyLayout(layout) }) {
                drawImage(blurredImage)
            }
        }
    }
}

private fun Modifier.previewLockEffect(blurRadius: Int, opacity: Float): Modifier {
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
