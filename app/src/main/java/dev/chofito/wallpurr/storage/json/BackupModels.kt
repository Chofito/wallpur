package dev.chofito.wallpurr.storage.json

import kotlinx.serialization.Serializable

@Serializable
data class WallpurrBackup(
    val version: Int = 1,
    val folders: List<FolderDto> = emptyList(),
    val images: List<ImageDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    val activePlaylistId: String? = null,
)

@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val createdAt: Long,
    val hidden: Boolean = false,
)

@Serializable
data class ImageDto(
    val id: String,
    val folderId: String,
    val displayName: String,
    val storedName: String,
    val width: Int,
    val height: Int,
    val addedAt: Long,
    val mediaType: String = "IMAGE",
    val durationMs: Long = 0L,
    val videoPlayMode: String = "TIMES",
    val videoPlayCount: Int = 1,
    val videoPlayMinutes: Int = 1,
    val lockStyleOverride: Boolean = false,
    val lockBlurRadius: Int = 0,
    val lockOpacity: Float = 1f,
    val transforms: List<TransformDto> = emptyList(),
)

@Serializable
data class TransformDto(
    val orientation: String,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
    val fitMode: String,
    val rotationDegrees: Float = 0f,
    val containBackgroundMode: String = "NONE",
    val containBackgroundColor: Int = -0x1000000,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    val intervalMinutes: Int,
    val target: String,
    val createdAt: Long,
    val imageIds: List<String> = emptyList(),
    val lockBlurRadius: Int = 0,
    val lockOpacity: Float = 1f,
    val hidden: Boolean = false,
)
