package dev.chofito.wallpurr

import android.app.Application
import androidx.room.Room
import dev.chofito.wallpurr.storage.ImageFileStore
import dev.chofito.wallpurr.storage.MediaDerivativeCache
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.storage.db.WallpurrDatabase
import dev.chofito.wallpurr.wallpaper.RotationController
import dev.chofito.wallpurr.wallpaper.RotationNotifier
import dev.chofito.wallpurr.wallpaper.PowerSaveMonitor
import dev.chofito.wallpurr.wallpaper.WallpaperApplier
import dev.chofito.wallpurr.wallpaper.WallpaperPulse
import dev.chofito.wallpurr.wallpaper.WallpaperScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch

class WallpurrApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.powerSaveMonitor.start()
        applicationScope.launch { container.rotation.restoreSchedule() }
    }
}

class AppContainer(app: Application) {
    val database: WallpurrDatabase = Room.databaseBuilder(
        app,
        WallpurrDatabase::class.java,
        "wallpurr.db",
    ).addMigrations(
        WallpurrDatabase.MIGRATION_1_2,
        WallpurrDatabase.MIGRATION_2_3,
        WallpurrDatabase.MIGRATION_3_4,
    ).build()

    val files = ImageFileStore(app)
    val mediaCache = MediaDerivativeCache(app, files)
    val repository = WallpurrRepository(database, files, mediaCache)
    val applier = WallpaperApplier(app, repository, mediaCache)
    val scheduler = WallpaperScheduler(app)
    val notifier = RotationNotifier(app)
    val rotation = RotationController(app)
    val powerSaveMonitor = PowerSaveMonitor(app, rotation)
    // Reload is state-based: consumers read the latest repository value, so older queued pulses
    // have no meaning. Keeping only the newest prevents bursts from repeatedly decoding media.
    val wallpaperPulse = MutableSharedFlow<WallpaperPulse>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
}
