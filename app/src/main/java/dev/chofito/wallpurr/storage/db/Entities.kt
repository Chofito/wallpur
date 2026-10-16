package dev.chofito.wallpurr.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    // Hidden folders and playlists only appear after the user reveals hidden content.
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folderId")],
)
data class ImageAssetEntity(
    @PrimaryKey val id: String,
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
)

@Entity(
    tableName = "transforms",
    primaryKeys = ["imageId", "orientation"],
    foreignKeys = [
        ForeignKey(
            entity = ImageAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("imageId")],
)
data class ImageTransformEntity(
    val imageId: String,
    val orientation: String,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
    val fitMode: String,
    val rotationDegrees: Float = 0f,
    val containBackgroundMode: String = "NONE",
    val containBackgroundColor: Int = -0x1000000,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val intervalMinutes: Int,
    val target: String,
    val createdAt: Long,
    val lockBlurRadius: Int = 0,
    val lockOpacity: Float = 1f,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "imageId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ImageAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId"), Index("imageId")],
)
data class PlaylistItemEntity(
    val playlistId: String,
    val imageId: String,
    val sortOrder: Int,
)

@Entity(tableName = "shuffle_cursors")
data class ShuffleCursorEntity(
    @PrimaryKey val playlistId: String,
    val remainingCsv: String,
    val historyCsv: String,
    val currentImageId: String?,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
