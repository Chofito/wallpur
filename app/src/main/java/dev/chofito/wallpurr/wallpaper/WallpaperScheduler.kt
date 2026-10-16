package dev.chofito.wallpurr.wallpaper

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
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
            .addTag(tokenTag(rotationToken))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * True while the unique timer created for [rotationToken] is still waiting or running. Lets a
     * cold process start keep the countdown instead of replacing (and cancelling) the very worker
     * that may have started the process.
     */
    suspend fun hasPendingWork(rotationToken: String): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_ONE_SHOT)
            .first()
            .any { info -> !info.state.isFinished && tokenTag(rotationToken) in info.tags }

    private fun tokenTag(rotationToken: String) = "rotation-token:$rotationToken"

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
