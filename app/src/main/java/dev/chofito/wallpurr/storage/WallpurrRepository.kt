package dev.chofito.wallpurr.storage

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dev.chofito.wallpurr.editor.FitMode
import dev.chofito.wallpurr.editor.ContainBackgroundMode
import dev.chofito.wallpurr.editor.ImageTransform
import dev.chofito.wallpurr.editor.ScreenOrientation
import dev.chofito.wallpurr.playlist.ShuffleEngine
import dev.chofito.wallpurr.playlist.ShuffleState
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.db.FolderEntity
import dev.chofito.wallpurr.storage.db.ImageAssetEntity
import dev.chofito.wallpurr.storage.db.ImageTransformEntity
import dev.chofito.wallpurr.storage.db.PlaylistEntity
import dev.chofito.wallpurr.storage.db.PlaylistItemEntity
import dev.chofito.wallpurr.storage.db.SettingEntity
import dev.chofito.wallpurr.storage.db.ShuffleCursorEntity
import dev.chofito.wallpurr.storage.db.WallpurrDatabase
import dev.chofito.wallpurr.storage.json.FolderDto
import dev.chofito.wallpurr.storage.json.ImageDto
import dev.chofito.wallpurr.storage.json.PlaylistDto
import dev.chofito.wallpurr.storage.json.TransformDto
import dev.chofito.wallpurr.storage.json.WallpurrBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WallpurrRepository(
    private val db: WallpurrDatabase,
    private val files: ImageFileStore,
    private val mediaCache: MediaDerivativeCache,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true },
) {
    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()
    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()
    val images: Flow<List<ImageAssetEntity>> = db.images().observeAll()
    val playlistCards: Flow<List<PlaylistCard>> = combine(
        playlists,
        db.playlistItems().observeAll(),
        images,
    ) { lists, items, allImages ->
        val byId = allImages.associateBy { it.id }
        val grouped = items.groupBy { it.playlistId }
        lists.map { playlist ->
            PlaylistCard(
                playlist = playlist,
                previews = grouped[playlist.id]
                    .orEmpty()
                    .sortedBy { it.sortOrder }
                    .mapNotNull { byId[it.imageId] }
                    .take(3),
            )
        }
    }

    fun imagesInFolder(folderId: String): Flow<List<ImageAssetEntity>> =
        db.images().observeByFolder(folderId)

    fun playlist(id: String): Flow<PlaylistEntity?> = db.playlists().observe(id)

    fun playlistItems(playlistId: String): Flow<List<ImageAssetEntity>> =
        combine(
            db.playlistItems().observeItems(playlistId),
            images,
        ) { items, all ->
            val byId = all.associateBy { it.id }
            items.mapNotNull { byId[it.imageId] }
        }

    fun shuffle(playlistId: String): Flow<ShuffleState?> =
        db.shuffle().observe(playlistId).map { it?.toState() }

    fun transforms(imageId: String): Flow<Map<ScreenOrientation, ImageTransform>> =
        db.transforms().observeForImage(imageId).map { rows ->
            rows.mapNotNull { row ->
                ScreenOrientation.entries.firstOrNull { it.name == row.orientation }
                    ?.let { it to row.toModel() }
            }.toMap()
        }

    suspend fun libraryFolderId(): String {
        val stored = db.settings().get(KEY_LIBRARY_FOLDER)
        if (stored != null && db.folders().get(stored) != null) return stored
        val folder = createFolder("Biblioteca")
        db.settings().upsert(SettingEntity(KEY_LIBRARY_FOLDER, folder.id))
        return folder.id
    }

    suspend fun resetAllLocalData() {
        // clearAllTables() is a blocking call that Room rejects on the main thread.
        withContext(Dispatchers.IO) {
            db.clearAllTables()
            files.clearAll()
            mediaCache.clear()
        }
        libraryFolderId()
    }

    suspend fun deleteImages(ids: List<String>) {
        ids.forEach { deleteImage(it) }
    }

    suspend fun reorderPlaylist(playlistId: String, imageIds: List<String>) {
        db.playlistItems().upsertAll(
            imageIds.mapIndexed { index, imageId ->
                PlaylistItemEntity(playlistId = playlistId, imageId = imageId, sortOrder = index)
            },
        )
        // The new order becomes the cycle, but the wallpaper on screen stays current instead of
        // jumping back to the first item.
        val ordered = ShuffleEngine.start(imageIds)
        val current = db.shuffle().get(playlistId)?.currentImageId
        persistShuffle(
            playlistId,
            current?.let { ShuffleEngine.select(ordered, it, imageIds) } ?: ordered,
        )
    }

    suspend fun createFolder(name: String): FolderEntity {
        val folder = FolderEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Carpeta" },
            createdAt = System.currentTimeMillis(),
        )
        db.folders().upsert(folder)
        return folder
    }

    suspend fun createFolderWithImages(name: String, imageIds: List<String>): FolderEntity =
        db.withTransaction {
            val folder = createFolder(name)
            if (imageIds.isNotEmpty()) db.images().moveToFolder(imageIds, folder.id)
            folder
        }

    suspend fun renameFolder(id: String, name: String) {
        val existing = db.folders().get(id) ?: return
        db.folders().upsert(existing.copy(name = name.trim().ifEmpty { existing.name }))
    }

    suspend fun setFolderHidden(id: String, hidden: Boolean) {
        // The library root holds unfiled media; hiding it would hide the whole library.
        if (id == libraryFolderId()) return
        val existing = db.folders().get(id) ?: return
        db.folders().update(existing.copy(hidden = hidden))
    }

    suspend fun deleteFolder(id: String) {
        val rootId = libraryFolderId()
        if (id == rootId) return
        db.withTransaction {
            db.images().moveFolderContents(id, rootId)
            db.folders().delete(id)
        }
    }

    suspend fun moveImagesToFolder(ids: List<String>, folderId: String?) {
        if (ids.isEmpty()) return
        val targetId = folderId?.takeIf { db.folders().get(it) != null } ?: libraryFolderId()
        db.images().moveToFolder(ids, targetId)
    }

    suspend fun importImages(uris: List<Uri>, folderId: String? = null): List<String> {
        val targetId = folderId?.let { db.folders().get(it)?.id } ?: libraryFolderId()
        val imported = mutableListOf<String>()
        uris.forEach { uri ->
            val id = UUID.randomUUID().toString()
            val importedFile = runCatching { files.importFromUri(uri, id) }.getOrNull() ?: return@forEach
            db.images().upsert(
                ImageAssetEntity(
                    id = id,
                    folderId = targetId,
                    displayName = importedFile.displayName,
                    storedName = importedFile.storedName,
                    width = importedFile.width,
                    height = importedFile.height,
                    addedAt = System.currentTimeMillis(),
                    mediaType = importedFile.mediaType.name,
                    durationMs = importedFile.durationMs,
                ),
            )
            db.transforms().upsertAll(defaultTransforms(id))
            imported += id
        }
        return imported
    }

    /**
     * Pre-generates grid thumbnails for [ids], or for the most recent library items when null.
     * Grids then only read small cached files instead of decoding originals while scrolling.
     */
    suspend fun warmThumbnails(ids: List<String>? = null) {
        val images = if (ids == null) {
            db.images().observeAll().first()
        } else {
            db.images().getMany(ids)
        }
        mediaCache.warmThumbnails(
            images
                .filter { files.isValidStoredName(it.storedName) }
                .map { files.file(it.storedName) to MediaKind.from(it.mediaType) },
        )
    }

    suspend fun deleteImage(id: String) {
        val image = db.images().get(id) ?: return
        // Row first: a row without its file would surface as broken media, whereas an orphan file
        // left by a failed delete is invisible and harmless.
        db.images().delete(id)
        mediaCache.invalidateSource(image.storedName)
        files.delete(image.storedName)
    }

    suspend fun saveTransform(
        imageId: String,
        orientation: ScreenOrientation,
        transform: ImageTransform,
    ) {
        val storedName = db.images().get(imageId)?.storedName
        val t = transform.clamped()
        db.transforms().upsert(
            ImageTransformEntity(
                imageId = imageId,
                orientation = orientation.name,
                zoom = t.zoom,
                offsetX = t.offsetX,
                offsetY = t.offsetY,
                fitMode = t.fitMode.name,
                rotationDegrees = t.rotationDegrees,
                containBackgroundMode = t.containBackgroundMode.name,
                containBackgroundColor = t.containBackgroundColor,
            ),
        )
        storedName?.let(mediaCache::invalidateRenders)
    }

    suspend fun getImage(id: String): ImageAssetEntity? = db.images().get(id)

    suspend fun getTransforms(imageId: String): Map<ScreenOrientation, ImageTransform> =
        db.transforms().getForImage(imageId).mapNotNull { row ->
            ScreenOrientation.entries.firstOrNull { it.name == row.orientation }
                ?.let { it to row.toModel() }
        }.toMap()

    suspend fun createPlaylist(name: String): PlaylistEntity {
        val playlist = PlaylistEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Playlist" },
            intervalMinutes = 30,
            target = WallpaperTarget.BOTH.name,
            createdAt = System.currentTimeMillis(),
        )
        db.playlists().upsert(playlist)
        return playlist
    }

    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        intervalMinutes: Int? = null,
        target: WallpaperTarget? = null,
        lockBlurRadius: Int? = null,
        lockOpacity: Float? = null,
    ) {
        val existing = db.playlists().get(id) ?: return
        db.playlists().update(
            existing.copy(
                name = name?.trim()?.ifEmpty { existing.name } ?: existing.name,
                intervalMinutes = intervalMinutes?.coerceIn(1, 180) ?: existing.intervalMinutes,
                target = target?.name ?: existing.target,
                lockBlurRadius = lockBlurRadius?.coerceIn(0, LockStyle.MAX_BLUR_RADIUS)
                    ?: existing.lockBlurRadius,
                lockOpacity = lockOpacity?.coerceIn(LockStyle.MIN_OPACITY, 1f)
                    ?: existing.lockOpacity,
            ),
        )
        if (lockBlurRadius != null || lockOpacity != null) {
            mediaCache.invalidateRenders(
                db.playlistItems().getItems(id).mapNotNull { item ->
                    db.images().get(item.imageId)?.storedName
                },
            )
        }
    }

    suspend fun setPlaylistHidden(id: String, hidden: Boolean) {
        val existing = db.playlists().get(id) ?: return
        db.playlists().update(existing.copy(hidden = hidden))
    }

    suspend fun deletePlaylist(id: String) {
        db.playlists().delete(id)
        db.shuffle().delete(id)
        if (activePlaylistId() == id) {
            db.settings().upsert(SettingEntity(KEY_ACTIVE_PLAYLIST, ""))
        }
    }

    suspend fun addImagesToPlaylist(playlistId: String, imageIds: List<String>) {
        var order = db.playlistItems().maxOrder(playlistId) + 1
        imageIds.forEach { imageId ->
            db.playlistItems().upsert(
                PlaylistItemEntity(playlistId = playlistId, imageId = imageId, sortOrder = order),
            )
            order += 1
        }
        ensureShuffle(playlistId)
    }

    suspend fun removeImageFromPlaylist(playlistId: String, imageId: String) {
        db.playlistItems().delete(playlistId, imageId)
        ensureShuffle(playlistId)
    }

    suspend fun removeImagesFromPlaylist(playlistId: String, imageIds: List<String>) {
        imageIds.forEach { db.playlistItems().delete(playlistId, it) }
        ensureShuffle(playlistId)
    }

    suspend fun playlistImageIds(playlistId: String): List<String> =
        db.playlistItems().getItems(playlistId).map { it.imageId }

    suspend fun activatePlaylist(playlistId: String) {
        db.settings().upsert(SettingEntity(KEY_ACTIVE_PLAYLIST, playlistId))
        ensureShuffle(playlistId)
    }

    suspend fun activePlaylistId(): String? =
        db.settings().get(KEY_ACTIVE_PLAYLIST)?.ifBlank { null }

    suspend fun getPlaylist(id: String): PlaylistEntity? = db.playlists().get(id)

    /**
     * Returns the cursor aligned with the playlist's current items. Membership changes keep the
     * current image and the upcoming (possibly shuffled) order; restarting the cycle here would
     * move the wallpaper and orphan the pending rotation timer, which targets the current image.
     */
    suspend fun ensureShuffle(playlistId: String): ShuffleState {
        val ids = playlistImageIds(playlistId)
        val existing = db.shuffle().get(playlistId)?.toState()
        val aligned = existing?.let { ShuffleEngine.reconcile(it, ids) } ?: ShuffleEngine.start(ids)
        if (aligned != existing) persistShuffle(playlistId, aligned)
        return aligned
    }

    suspend fun resetShuffle(playlistId: String): ShuffleState {
        val started = ShuffleEngine.start(playlistImageIds(playlistId), shuffle = true)
        persistShuffle(playlistId, started)
        return started
    }

    suspend fun resetShuffleOrdered(playlistId: String): ShuffleState {
        val started = ShuffleEngine.start(playlistImageIds(playlistId), shuffle = false)
        persistShuffle(playlistId, started)
        return started
    }

    suspend fun advanceShuffle(playlistId: String): ShuffleState {
        val ids = playlistImageIds(playlistId)
        val current = db.shuffle().get(playlistId)?.toState() ?: ShuffleEngine.start(ids)
        val next = ShuffleEngine.next(current, ids)
        persistShuffle(playlistId, next)
        return next
    }

    suspend fun rewindShuffle(playlistId: String): ShuffleState {
        val ids = playlistImageIds(playlistId)
        val current = db.shuffle().get(playlistId)?.toState()
            ?: return ShuffleEngine.start(ids)
        val previous = ShuffleEngine.previous(current, ids)
        persistShuffle(playlistId, previous)
        return previous
    }

    suspend fun selectShuffleImage(playlistId: String, imageId: String): ShuffleState {
        val ids = playlistImageIds(playlistId)
        val current = db.shuffle().get(playlistId)?.toState() ?: ShuffleEngine.start(ids)
        val selected = ShuffleEngine.select(current, imageId, ids)
        persistShuffle(playlistId, selected)
        return selected
    }

    suspend fun isRotationPaused(): Boolean =
        db.settings().get(KEY_ROTATION_PAUSED) == "1"

    suspend fun setRotationPaused(paused: Boolean) {
        db.settings().upsert(SettingEntity(KEY_ROTATION_PAUSED, if (paused) "1" else "0"))
    }

    suspend fun rotationToken(): String? = db.settings().get(KEY_ROTATION_TOKEN)

    suspend fun setRotationToken(token: String) {
        db.settings().upsert(SettingEntity(KEY_ROTATION_TOKEN, token))
    }

    suspend fun nextChangeAtMillis(): Long? =
        db.settings().get(KEY_NEXT_CHANGE_AT)?.toLongOrNull()

    suspend fun setNextChangeAtMillis(value: Long?) {
        db.settings().upsert(SettingEntity(KEY_NEXT_CHANGE_AT, value?.toString().orEmpty()))
    }

    suspend fun currentShuffle(playlistId: String): ShuffleState? =
        db.shuffle().get(playlistId)?.toState()

    fun decodeBitmap(image: ImageAssetEntity, maxSide: Int) =
        files.decodeBitmap(files.file(image.storedName), maxSide, MediaKind.from(image.mediaType))

    fun sourceFile(image: ImageAssetEntity) = files.file(image.storedName)

    suspend fun updateVideoPlayback(
        imageId: String,
        mode: VideoPlayMode,
        playCount: Int,
        playMinutes: Int,
    ) {
        val existing = db.images().get(imageId) ?: return
        db.images().update(
            existing.copy(
                videoPlayMode = mode.name,
                videoPlayCount = playCount.coerceIn(1, 99),
                videoPlayMinutes = playMinutes.coerceIn(1, 180),
            ),
        )
    }

    suspend fun updateImageLockStyle(
        imageId: String,
        overridePlaylist: Boolean,
        blurRadius: Int,
        opacity: Float,
    ) {
        val existing = db.images().get(imageId) ?: return
        db.images().update(
            existing.copy(
                lockStyleOverride = overridePlaylist,
                lockBlurRadius = blurRadius.coerceIn(0, LockStyle.MAX_BLUR_RADIUS),
                lockOpacity = opacity.coerceIn(LockStyle.MIN_OPACITY, 1f),
            ),
        )
        mediaCache.invalidateRenders(existing.storedName)
    }

    fun lockStyle(image: ImageAssetEntity, playlist: PlaylistEntity): LockStyle =
        if (image.lockStyleOverride) {
            LockStyle(image.lockBlurRadius, image.lockOpacity).clamped()
        } else {
            LockStyle(playlist.lockBlurRadius, playlist.lockOpacity).clamped()
        }

    fun observeFlag(key: String, default: Boolean) = db.settings().observeAll().map { rows ->
        rows.find { it.key == key }?.value?.let { it == "1" } ?: default
    }

    fun observeLibraryColumns(): Flow<Int> = db.settings().observeAll().map { rows ->
        rows.find { it.key == KEY_LIBRARY_COLUMNS }
            ?.value
            ?.toIntOrNull()
            ?.coerceIn(MIN_LIBRARY_COLUMNS, MAX_LIBRARY_COLUMNS)
            ?: DEFAULT_LIBRARY_COLUMNS
    }

    fun observePowerSavePolicy(): Flow<PowerSavePolicy> = db.settings().observeAll().map { rows ->
        PowerSavePolicy.from(rows.find { it.key == KEY_POWER_SAVE_POLICY }?.value)
    }

    suspend fun flag(key: String, default: Boolean): Boolean =
        db.settings().get(key)?.let { it == "1" } ?: default

    suspend fun setFlag(key: String, value: Boolean) {
        db.settings().upsert(SettingEntity(key, if (value) "1" else "0"))
    }

    suspend fun setLibraryColumns(columns: Int) {
        db.settings().upsert(
            SettingEntity(
                key = KEY_LIBRARY_COLUMNS,
                value = columns.coerceIn(MIN_LIBRARY_COLUMNS, MAX_LIBRARY_COLUMNS).toString(),
            ),
        )
    }

    suspend fun powerSavePolicy(): PowerSavePolicy =
        PowerSavePolicy.from(db.settings().get(KEY_POWER_SAVE_POLICY))

    suspend fun setPowerSavePolicy(policy: PowerSavePolicy) {
        db.settings().upsert(SettingEntity(KEY_POWER_SAVE_POLICY, policy.name))
    }

    suspend fun powerSaveStaticImage(playlistId: String): ImageAssetEntity? {
        val orderedIds = playlistImageIds(playlistId)
        if (orderedIds.isEmpty()) return null
        val byId = db.images().getMany(orderedIds).associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        return ordered.firstOrNull { MediaKind.from(it.mediaType) == MediaKind.IMAGE }
            ?: ordered.firstOrNull()
    }

    fun fileExists(image: ImageAssetEntity): Boolean = files.file(image.storedName).exists()

    suspend fun exportJson(uri: Uri, context: Context) = withContext(Dispatchers.IO) {
        val backup = buildBackup()
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.encodeToString(WallpurrBackup.serializer(), backup).toByteArray())
        } ?: error("No se pudo escribir el JSON")
    }

    suspend fun exportZip(uri: Uri, context: Context) = withContext(Dispatchers.IO) {
        val backup = buildBackup()
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("config.json"))
                zip.write(json.encodeToString(WallpurrBackup.serializer(), backup).toByteArray())
                zip.closeEntry()
                backup.images.forEach { image ->
                    val file = files.file(image.storedName)
                    if (!file.exists()) return@forEach
                    zip.putNextEntry(ZipEntry("images/${image.storedName}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("No se pudo escribir el zip")
    }

    suspend fun importConfig(uri: Uri, context: Context): ImportResult {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = uri.lastPathSegment.orEmpty()
        return if (name.endsWith(".zip", ignoreCase = true) || mime.contains("zip")) {
            importZip(uri, context)
        } else {
            importJson(uri, context)
        }
    }

    private suspend fun importJson(uri: Uri, context: Context): ImportResult {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBoundedBytes(MAX_CONFIG_BYTES).decodeToString()
            } ?: error("No se pudo leer el JSON")
        }
        return applyBackup(
            json.decodeFromString(WallpurrBackup.serializer(), text),
            missingImagesOk = true,
        )
    }

    /**
     * Restores a ZIP backup atomically from the user's point of view: media is extracted into a
     * staging directory under a byte budget, the archive must contain a valid config.json, and
     * only then are new files moved into the library and the rows written. Existing library files
     * are never overwritten, and any failure leaves the library exactly as it was.
     */
    private suspend fun importZip(uri: Uri, context: Context): ImportResult {
        val staging = files.createStagingDir()
        try {
            val parsed = withContext(Dispatchers.IO) {
                var backup: WallpurrBackup? = null
                var budget = (files.availableBytes() - IMPORT_FREE_SPACE_RESERVE_BYTES)
                    .coerceAtLeast(0L)
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    ZipInputStream(BufferedInputStream(raw)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val storedName = entry.name.removePrefix("images/")
                            when {
                                entry.isDirectory -> Unit
                                entry.name == "config.json" -> {
                                    backup = json.decodeFromString(
                                        WallpurrBackup.serializer(),
                                        zip.readBoundedBytes(MAX_CONFIG_BYTES).decodeToString(),
                                    )
                                }
                                entry.name.startsWith("images/") &&
                                    files.isValidStoredName(storedName) &&
                                    !files.file(storedName).exists() -> {
                                    budget -= files.stageFromStream(zip, storedName, staging, budget)
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("No se pudo leer el zip")
                backup ?: error("El zip no contiene config.json")
            }
            val added = withContext(Dispatchers.IO) {
                files.commitStaged(staging, parsed.images.map { it.storedName })
            }
            return try {
                applyBackup(parsed, missingImagesOk = true)
            } catch (error: Throwable) {
                // The rows were rolled back by the transaction; drop the files they would own.
                withContext(NonCancellable + Dispatchers.IO) { added.forEach(files::delete) }
                throw error
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
        }
    }

    private suspend fun applyBackup(backup: WallpurrBackup, missingImagesOk: Boolean): ImportResult {
        val skipped = mutableListOf<String>()
        db.withTransaction {
            backup.folders.forEach { db.folders().upsert(it.toEntity()) }
            backup.images.forEach { image ->
                if (!files.isValidStoredName(image.storedName) ||
                    !files.file(image.storedName).exists()
                ) {
                    skipped += image.displayName
                    if (missingImagesOk) return@forEach
                }
                db.images().upsert(image.toEntity())
                db.transforms().upsertAll(
                    image.transforms.map {
                        ImageTransformEntity(
                            imageId = image.id,
                            orientation = it.orientation,
                            zoom = it.zoom,
                            offsetX = it.offsetX,
                            offsetY = it.offsetY,
                            fitMode = it.fitMode,
                            rotationDegrees = it.rotationDegrees,
                            containBackgroundMode = it.containBackgroundMode,
                            containBackgroundColor = it.containBackgroundColor,
                        )
                    }.ifEmpty { defaultTransforms(image.id) },
                )
            }
            backup.playlists.forEach { playlist ->
                db.playlists().upsert(playlist.toEntity())
                db.playlistItems().deleteAll(playlist.id)
                playlist.imageIds.forEachIndexed { index, imageId ->
                    if (db.images().get(imageId) != null) {
                        db.playlistItems().upsert(PlaylistItemEntity(playlist.id, imageId, index))
                    }
                }
                db.shuffle().delete(playlist.id)
            }
            backup.activePlaylistId
                ?.takeIf { id -> db.playlists().get(id) != null }
                ?.let { db.settings().upsert(SettingEntity(KEY_ACTIVE_PLAYLIST, it)) }
        }
        return ImportResult(skippedImages = skipped)
    }

    private suspend fun buildBackup(): WallpurrBackup {
        val folderList = db.folders().observeAll().first()
        val imageList = db.images().observeAll().first()
        val playlists = db.playlists().observeAll().first()
        val items = db.playlistItems().getAll().groupBy { it.playlistId }
        val transforms = db.transforms().getAll().groupBy { it.imageId }
        return WallpurrBackup(
            folders = folderList.map { it.toDto() },
            images = imageList.map { image -> image.toDto(transforms[image.id].orEmpty()) },
            playlists = playlists.map { playlist ->
                playlist.toDto(
                    items[playlist.id].orEmpty().sortedBy { it.sortOrder }.map { it.imageId },
                )
            },
            activePlaylistId = activePlaylistId(),
        )
    }

    private suspend fun persistShuffle(playlistId: String, state: ShuffleState) {
        db.shuffle().upsert(state.toEntity(playlistId))
    }

    private fun defaultTransforms(imageId: String): List<ImageTransformEntity> =
        ScreenOrientation.entries.map { orientation ->
            val t = ImageTransform.Default
            ImageTransformEntity(
                imageId = imageId,
                orientation = orientation.name,
                zoom = t.zoom,
                offsetX = t.offsetX,
                offsetY = t.offsetY,
                fitMode = t.fitMode.name,
                rotationDegrees = t.rotationDegrees,
                containBackgroundMode = t.containBackgroundMode.name,
                containBackgroundColor = t.containBackgroundColor,
            )
        }

    companion object {
        private const val MAX_CONFIG_BYTES = 16 * 1024 * 1024
        // Keeps the device usable after a large restore instead of filling internal storage.
        private const val IMPORT_FREE_SPACE_RESERVE_BYTES = 512L * 1024 * 1024
        const val KEY_ACTIVE_PLAYLIST = "active_playlist"
        const val KEY_ROTATION_PAUSED = "rotation_paused"
        const val KEY_ROTATION_TOKEN = "rotation_token"
        private const val KEY_NEXT_CHANGE_AT = "next_change_at"
        const val KEY_LIBRARY_FOLDER = "library_folder"
        const val KEY_PAUSE_GIF_HIDDEN = "pause_gif_hidden"
        const val KEY_PAUSE_VIDEO_HIDDEN = "pause_video_hidden"
        const val KEY_PLAY_VIDEO_ON_LOCK = "play_video_on_lock"
        const val KEY_CHANGE_ON_HOME_TAP = "change_on_home_tap"
        private const val KEY_POWER_SAVE_POLICY = "power_save_policy"
        const val DEFAULT_LIBRARY_COLUMNS = 2
        const val MIN_LIBRARY_COLUMNS = 2
        const val MAX_LIBRARY_COLUMNS = 4
        private const val KEY_LIBRARY_COLUMNS = "library_columns"
    }
}

private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "El archivo de configuración es demasiado grande" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

data class ImportResult(val skippedImages: List<String>)

data class PlaylistCard(
    val playlist: PlaylistEntity,
    val previews: List<ImageAssetEntity>,
)

private fun ImageTransformEntity.toModel() = ImageTransform(
    zoom = zoom,
    offsetX = offsetX,
    offsetY = offsetY,
    fitMode = FitMode.from(fitMode),
    rotationDegrees = rotationDegrees,
    containBackgroundMode = ContainBackgroundMode.from(containBackgroundMode),
    containBackgroundColor = containBackgroundColor,
).clamped()

private fun ShuffleCursorEntity.toState() = ShuffleState(
    remainingIds = remainingCsv.split(',').filter { it.isNotBlank() },
    historyIds = historyCsv.split(',').filter { it.isNotBlank() },
    currentImageId = currentImageId,
)

private fun ShuffleState.toEntity(playlistId: String) = ShuffleCursorEntity(
    playlistId = playlistId,
    remainingCsv = remainingIds.joinToString(","),
    historyCsv = historyIds.joinToString(","),
    currentImageId = currentImageId,
)

private fun FolderEntity.toDto() = FolderDto(id, name, createdAt, hidden)
private fun FolderDto.toEntity() = FolderEntity(id, name, createdAt, hidden)
private fun ImageAssetEntity.toDto(transforms: List<ImageTransformEntity>) = ImageDto(
    id = id,
    folderId = folderId,
    displayName = displayName,
    storedName = storedName,
    width = width,
    height = height,
    addedAt = addedAt,
    mediaType = mediaType,
    durationMs = durationMs,
    videoPlayMode = videoPlayMode,
    videoPlayCount = videoPlayCount,
    videoPlayMinutes = videoPlayMinutes,
    lockStyleOverride = lockStyleOverride,
    lockBlurRadius = lockBlurRadius,
    lockOpacity = lockOpacity,
    transforms = transforms.map {
        TransformDto(
            orientation = it.orientation,
            zoom = it.zoom,
            offsetX = it.offsetX,
            offsetY = it.offsetY,
            fitMode = it.fitMode,
            rotationDegrees = it.rotationDegrees,
            containBackgroundMode = it.containBackgroundMode,
            containBackgroundColor = it.containBackgroundColor,
        )
    },
)
private fun ImageDto.toEntity() = ImageAssetEntity(
    id = id,
    folderId = folderId,
    displayName = displayName,
    storedName = storedName,
    width = width,
    height = height,
    addedAt = addedAt,
    mediaType = mediaType,
    durationMs = durationMs,
    videoPlayMode = videoPlayMode,
    videoPlayCount = videoPlayCount,
    videoPlayMinutes = videoPlayMinutes,
    lockStyleOverride = lockStyleOverride,
    lockBlurRadius = lockBlurRadius,
    lockOpacity = lockOpacity,
)
private fun PlaylistEntity.toDto(imageIds: List<String>) =
    PlaylistDto(
        id = id,
        name = name,
        intervalMinutes = intervalMinutes,
        target = target,
        createdAt = createdAt,
        imageIds = imageIds,
        lockBlurRadius = lockBlurRadius,
        lockOpacity = lockOpacity,
        hidden = hidden,
    )
private fun PlaylistDto.toEntity() = PlaylistEntity(
    id = id,
    name = name,
    intervalMinutes = intervalMinutes.coerceIn(1, 180),
    target = target,
    createdAt = createdAt,
    lockBlurRadius = lockBlurRadius,
    lockOpacity = lockOpacity,
    hidden = hidden,
)
