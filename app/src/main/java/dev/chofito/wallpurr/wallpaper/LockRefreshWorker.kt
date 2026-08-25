package dev.chofito.wallpurr.wallpaper

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.chofito.wallpurr.WallpurrApplication

class LockRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? WallpurrApplication ?: return Result.retry()
        val expectedImageId = inputData.getString(WallpaperScheduler.INPUT_EXPECTED_IMAGE_ID)
            ?: return Result.success()
        val rotationToken = inputData.getString(WallpaperScheduler.INPUT_ROTATION_TOKEN)
            ?: return Result.success()
        val result = runCatching {
            app.container.rotation.advanceScheduled(expectedImageId, rotationToken)
        }
        return if (result.isFailure) Result.retry() else Result.success()
    }
}
