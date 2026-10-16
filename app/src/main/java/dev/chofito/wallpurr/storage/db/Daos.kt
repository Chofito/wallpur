package dev.chofito.wallpurr.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun get(id: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    // Never REPLACE a parent row: SQLite implements it as DELETE + INSERT, and the CASCADE foreign
    // keys would silently delete every image (and its transforms/playlist items) in the folder.
    @Transaction
    suspend fun upsert(folder: FolderEntity) {
        if (insert(folder) == -1L) update(folder)
    }

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM images WHERE folderId = :folderId ORDER BY addedAt DESC")
    fun observeByFolder(folderId: String): Flow<List<ImageAssetEntity>>

    @Query("SELECT * FROM images ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ImageAssetEntity>>

    @Query("SELECT * FROM images WHERE id = :id")
    suspend fun get(id: String): ImageAssetEntity?

    @Query("SELECT * FROM images WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<ImageAssetEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(image: ImageAssetEntity): Long

    @Update
    suspend fun update(image: ImageAssetEntity)

    @Transaction
    suspend fun upsert(image: ImageAssetEntity) {
        if (insert(image) == -1L) update(image)
    }

    @Query("DELETE FROM images WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE images SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun moveToFolder(ids: List<String>, folderId: String)

    @Query("UPDATE images SET folderId = :targetFolderId WHERE folderId = :sourceFolderId")
    suspend fun moveFolderContents(sourceFolderId: String, targetFolderId: String)

    @Query("SELECT COUNT(*) FROM images WHERE folderId = :folderId")
    fun observeCount(folderId: String): Flow<Int>
}

@Dao
interface TransformDao {
    @Query("SELECT * FROM transforms WHERE imageId = :imageId")
    fun observeForImage(imageId: String): Flow<List<ImageTransformEntity>>

    @Query("SELECT * FROM transforms WHERE imageId = :imageId")
    suspend fun getForImage(imageId: String): List<ImageTransformEntity>

    @Query("SELECT * FROM transforms")
    suspend fun getAll(): List<ImageTransformEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transform: ImageTransformEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(transforms: List<ImageTransformEntity>)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    // See FolderDao.upsert: REPLACE would cascade-delete every item of the playlist.
    @Transaction
    suspend fun upsert(playlist: PlaylistEntity) {
        if (insert(playlist) == -1L) update(playlist)
    }

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaylistItemDao {
    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    fun observeItems(playlistId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY sortOrder ASC")
    suspend fun getItems(playlistId: String): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items")
    fun observeAll(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items")
    suspend fun getAll(): List<PlaylistItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlaylistItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND imageId = :imageId")
    suspend fun delete(playlistId: String, imageId: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun deleteAll(playlistId: String)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxOrder(playlistId: String): Int
}

@Dao
interface ShuffleDao {
    @Query("SELECT * FROM shuffle_cursors WHERE playlistId = :playlistId")
    suspend fun get(playlistId: String): ShuffleCursorEntity?

    @Query("SELECT * FROM shuffle_cursors WHERE playlistId = :playlistId")
    fun observe(playlistId: String): Flow<ShuffleCursorEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: ShuffleCursorEntity)

    @Query("DELETE FROM shuffle_cursors WHERE playlistId = :playlistId")
    suspend fun delete(playlistId: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingEntity)
}
