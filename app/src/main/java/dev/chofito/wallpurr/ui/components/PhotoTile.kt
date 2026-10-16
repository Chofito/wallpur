package dev.chofito.wallpurr.ui.components

import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import java.io.File

enum class PhotoMode { Browse, Select, Reorder }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoTile(
    image: ImageAssetEntity,
    filesDir: File,
    mode: PhotoMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    thumbnailLoadingEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val clickable = if (mode == PhotoMode.Reorder) {
        Modifier
    } else {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Box(modifier.then(clickable)) {
        ImageThumb(
            image = image,
            filesDir = filesDir,
            active = active && mode == PhotoMode.Browse,
            thumbnailLoadingEnabled = thumbnailLoadingEnabled,
        )
        if (mode == PhotoMode.Select) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (selected) 0.28f else 0.08f)),
            )
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.7f)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
        if (mode == PhotoMode.Reorder) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (mode == PhotoMode.Browse) overlay()
    }
}

fun pickImagesMaxItems(): Int {
    val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        MediaStore.getPickImagesMaxLimit()
    } else {
        50
    }
    return limit.coerceIn(2, 100)
}
