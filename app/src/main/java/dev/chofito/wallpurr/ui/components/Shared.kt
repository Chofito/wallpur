package dev.chofito.wallpurr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.ui.graphics.asImageBitmap
import android.util.LruCache
import coil3.compose.AsyncImage
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    val paw = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Canvas(Modifier.size(72.dp)) {
                val r = size.minDimension / 8f
                drawCircle(paw, r, Offset(size.width * 0.30f, size.height * 0.32f))
                drawCircle(paw, r, Offset(size.width * 0.50f, size.height * 0.24f))
                drawCircle(paw, r, Offset(size.width * 0.70f, size.height * 0.32f))
                drawOval(
                    paw,
                    topLeft = Offset(size.width * 0.32f, size.height * 0.48f),
                    size = Size(size.width * 0.36f, size.height * 0.36f),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ImageThumb(
    image: ImageAssetEntity,
    filesDir: File,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    active: Boolean = false,
) {
    val file = File(filesDir, "images/${image.storedName}")
    val kind = MediaKind.from(image.mediaType)
    val shape = RoundedCornerShape(if (framed) 20.dp else 14.dp)
    Box(
        modifier
            .then(if (framed) Modifier.fillMaxWidth().aspectRatio(3f / 4f) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        when {
            kind == MediaKind.VIDEO && active -> {
                rememberVideoPoster(file, maxSide = if (framed) 360 else 240)?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                MutedGridVideoView(file = file, modifier = Modifier.fillMaxSize())
            }
            kind == MediaKind.GIF && active -> {
                MutedGifView(file = file, modifier = Modifier.fillMaxSize())
            }
            kind == MediaKind.VIDEO -> {
                val poster = rememberVideoPoster(file, maxSide = if (framed) 360 else 240)
                poster?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {
                AsyncImage(
                    model = file,
                    contentDescription = image.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (kind != MediaKind.IMAGE && !active) {
            Icon(
                imageVector = if (kind == MediaKind.GIF) Icons.Outlined.Gif else Icons.Outlined.PlayCircle,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(if (framed) 8.dp else 4.dp)
                    .size(if (framed) 22.dp else 16.dp),
            )
        }
    }
}

@Composable
fun rememberVideoPoster(file: File, maxSide: Int = 720): android.graphics.Bitmap? {
    val cacheKey = "${file.absolutePath}:${file.lastModified()}:$maxSide"
    val poster by produceState(videoPosterCache.get(cacheKey), cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val raw = runCatching {
                    retriever.getFrameAtTime(
                        0L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull() ?: runCatching {
                    retriever.getFrameAtTime(
                        -1L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull() ?: return@withContext null
                val longest = maxOf(raw.width, raw.height)
                if (longest <= maxSide) {
                    raw
                } else {
                    val scale = maxSide / longest.toFloat()
                    android.graphics.Bitmap.createScaledBitmap(
                        raw,
                        (raw.width * scale).toInt().coerceAtLeast(1),
                        (raw.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { scaled ->
                        if (scaled !== raw) raw.recycle()
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        value?.let { videoPosterCache.put(cacheKey, it) }
    }
    return poster
}

private val videoPosterCache = object : LruCache<String, android.graphics.Bitmap>(16 * 1024) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
        value.allocationByteCount / 1024
}

@Composable
fun IconBubble(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PurrCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    menuExpanded: Boolean = false,
    onDismissMenu: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    } else {
                        Modifier.clickable(onClick = onClick)
                    },
                ),
        ) {
            content()
        }
        if (onLongClick != null) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    onClick = {
                        onDismissMenu()
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    onClick = {
                        onDismissMenu()
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
fun wallpaperTargetLabel(raw: String): String {
    val target = WallpaperTarget.from(raw)
    return when (target) {
        WallpaperTarget.HOME -> stringResource(R.string.target_home)
        WallpaperTarget.LOCK -> stringResource(R.string.target_lock)
        WallpaperTarget.BOTH -> stringResource(R.string.target_both)
    }
}

@Composable
fun wallpaperTargetIcon(raw: String): ImageVector {
    return when (WallpaperTarget.from(raw)) {
        WallpaperTarget.HOME -> Icons.Outlined.Home
        WallpaperTarget.LOCK -> Icons.Outlined.Lock
        else -> Icons.Outlined.Wallpaper
    }
}

@Composable
fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String,
    cancelLabel: String,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}
