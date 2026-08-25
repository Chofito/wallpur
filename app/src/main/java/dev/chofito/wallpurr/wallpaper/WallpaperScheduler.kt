package dev.chofito.wallpurr.wallpaper

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class WallpaperScheduler(private val context: Context) {
    init {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_UNIQUE_PERIODIC)
    }

    fun schedule(delayMs: Long, expectedImageId: String, rotationToken: String) {
        val request = OneTimeWorkRequestBuilder<LockRefreshWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    INPUT_EXPECTED_IMAGE_ID to expectedImageId,
                    INPUT_ROTATION_TOKEN to rotationToken,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(UNIQUE_ONE_SHOT)
            cancelUniqueWork(LEGACY_UNIQUE_PERIODIC)
        }
    }

    companion object {
        const val UNIQUE_ONE_SHOT = "wallpurr-lock-oneshot"
        private const val LEGACY_UNIQUE_PERIODIC = "wallpurr-lock-periodic"
        const val INPUT_EXPECTED_IMAGE_ID = "expected_image_id"
        const val INPUT_ROTATION_TOKEN = "rotation_token"
    }
}
