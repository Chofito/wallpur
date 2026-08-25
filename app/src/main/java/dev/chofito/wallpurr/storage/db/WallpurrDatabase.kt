package dev.chofito.wallpurr.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FolderEntity::class,
        ImageAssetEntity::class,
        ImageTransformEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        ShuffleCursorEntity::class,
        SettingEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class WallpurrDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun images(): ImageDao
    abstract fun transforms(): TransformDao
    abstract fun playlists(): PlaylistDao
    abstract fun playlistItems(): PlaylistItemDao
    abstract fun shuffle(): ShuffleDao
    abstract fun settings(): SettingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'IMAGE'")
                db.execSQL("ALTER TABLE images ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE images ADD COLUMN videoPlayMode TEXT NOT NULL DEFAULT 'TIMES'")
                db.execSQL("ALTER TABLE images ADD COLUMN videoPlayCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE images ADD COLUMN videoPlayMinutes INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE transforms ADD COLUMN rotationDegrees REAL NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN lockStyleOverride INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE images ADD COLUMN lockBlurRadius INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE images ADD COLUMN lockOpacity REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE playlists ADD COLUMN lockBlurRadius INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN lockOpacity REAL NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transforms ADD COLUMN containBackgroundMode TEXT NOT NULL DEFAULT 'NONE'",
                )
                db.execSQL(
                    "ALTER TABLE transforms ADD COLUMN containBackgroundColor INTEGER NOT NULL DEFAULT -16777216",
                )
            }
        }
    }
}
