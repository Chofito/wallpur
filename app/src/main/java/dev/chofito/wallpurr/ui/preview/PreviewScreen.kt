package dev.chofito.wallpurr.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.editor.DeviceViewport
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.ui.editor.TransformPreview
import dev.chofito.wallpurr.ui.editorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
) {
    val viewModel = editorViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val device = remember { DeviceViewport.current(context) }
    val orientation = state.orientation
    val ratio = device.aspectRatio(orientation)
    val resolution = device.resolutionLabel(orientation)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = orientation == ScreenOrientation.PORTRAIT,
                    onClick = { viewModel.setOrientation(ScreenOrientation.PORTRAIT) },
                    label = { Text(stringResource(R.string.portrait)) },
                )
                FilterChip(
                    selected = orientation == ScreenOrientation.LANDSCAPE,
                    onClick = { viewModel.setOrientation(ScreenOrientation.LANDSCAPE) },
                    label = { Text(stringResource(R.string.landscape)) },
                )
            }
            DeviceFrame(
                aspectRatio = ratio,
                label = stringResource(R.string.this_device, resolution),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                TransformPreview(
                    bitmap = state.bitmap,
                    containBackdrop = state.containBackdrop,
                    transform = state.transform,
                    mediaKind = state.mediaKind,
                    mediaFile = state.storedName.takeIf { it.isNotBlank() }?.let {
                        java.io.File(context.filesDir, "images/$it")
                    },
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun DeviceFrame(
    aspectRatio: Float,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val availW = constraints.maxWidth.toFloat()
            val availH = constraints.maxHeight.toFloat()
            val heightIfFullWidth = availW / aspectRatio
            val (frameW, frameH) = if (heightIfFullWidth <= availH) {
                availW to heightIfFullWidth
            } else {
                (availH * aspectRatio) to availH
            }
            Box(
                Modifier
                    .size(
                        width = with(density) { frameW.toDp() },
                        height = with(density) { frameH.toDp() },
                    )
                    .clip(shape)
                    .border(3.dp, MaterialTheme.colorScheme.outline, shape)
                    .background(Color.Black),
            ) {
                content()
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
