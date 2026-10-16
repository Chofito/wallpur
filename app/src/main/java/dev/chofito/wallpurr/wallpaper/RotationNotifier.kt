package dev.chofito.wallpurr.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.PowerManager
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.chofito.wallpurr.MainActivity
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.WallpurrApplication
import dev.chofito.wallpurr.EXTRA_OPEN_PLAYLIST_ID
import dev.chofito.wallpurr.playlist.WallpaperTarget
import dev.chofito.wallpurr.storage.MediaKind
import dev.chofito.wallpurr.storage.PowerSavePolicy
import dev.chofito.wallpurr.storage.VideoPlayMode
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RotationNotifier(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private val publishGeneration = AtomicLong(0L)

    init {
        scope.launch {
            for (ignored in publishRequests) {
                // Let the wallpaper decode/render claim I/O and CPU first. During this short window
                // additional requests are collapsed into the same notification refresh.
                delay(PUBLISH_DEBOUNCE_MS)
                while (publishRequests.tryReceive().isSuccess) {
                    // Drain requests already represented by the latest repository state.
                }
                val generation = publishGeneration.get()
                runCatching { publishNow(generation) }
                    .onFailure { Log.e(TAG, "Unable to refresh rotation notification", it) }
            }
        }
    }

    fun requestPublish() {
        publishGeneration.incrementAndGet()
        publishRequests.trySend(Unit)
    }

    private suspend fun publishNow(expectedGeneration: Long) {
        val app = context.applicationContext as? WallpurrApplication ?: return
        val repo = app.container.repository
        val playlistId = repo.activePlaylistId() ?: run {
            if (publishGeneration.get() == expectedGeneration) cancel()
            return
        }
        val playlist = repo.getPlaylist(playlistId) ?: run {
            if (publishGeneration.get() == expectedGeneration) cancel()
            return
        }
        val powerSaveMode =
            context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val powerSaveStatic = powerSaveMode && repo.powerSavePolicy() == PowerSavePolicy.STATIC
        val shuffle = repo.ensureShuffle(playlistId)
        val orderedIds = repo.playlistImageIds(playlistId)
        val imageId = if (powerSaveStatic) {
            repo.powerSaveStaticImage(playlistId)?.id
        } else {
            shuffle.currentImageId
        }
        val image = imageId?.let { repo.getImage(it) }
        val paused = repo.isRotationPaused() || powerSaveStatic
        val total = orderedIds.size
        val position = when {
            total == 0 -> 0
            powerSaveStatic -> imageId
                ?.let(orderedIds::indexOf)
                ?.takeIf { it >= 0 }
                ?.plus(1)
                ?: 1
            else -> (shuffle.historyIds.size + 1).coerceIn(1, total)
        }
        val nextChangeAt = repo.nextChangeAtMillis()
        val scheduleLine = when {
            paused -> context.getString(R.string.notif_paused_progress, position, total)
            nextChangeAt != null -> context.getString(
                R.string.notif_next_at_progress,
                DateFormat.getTimeFormat(context).format(Date(nextChangeAt)),
                position,
                total,
            )
            image != null &&
                MediaKind.from(image.mediaType) == MediaKind.VIDEO &&
                VideoPlayMode.from(image.videoPlayMode) == VideoPlayMode.TIMES ->
                context.getString(R.string.notif_after_video_progress, position, total)
            else -> context.getString(R.string.notif_pending_progress, position, total)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val thumb = image?.let {
            if (repo.fileExists(it)) repo.decodeBitmap(it, maxOf(256, 128.dp)) else null
        }
        val notificationThumb = thumb?.let { source ->
            try {
                roundedThumb(
                    source = source,
                    widthPx = 96.dp,
                    heightPx = 128.dp,
                    radiusPx = 16.dp.toFloat(),
                )
            } finally {
                source.recycle()
            }
        }
        if (publishGeneration.get() != expectedGeneration) {
            notificationThumb?.recycle()
            return
        }
        ensureChannel()
        val notification = build(
            playlistId = playlistId,
            // The shade and the lock screen are visible to anyone nearby.
            playlistName = if (playlist.hidden) context.getString(R.string.app_name) else playlist.name,
            target = WallpaperTarget.from(playlist.target),
            scheduleLine = scheduleLine,
            powerSaveMode = powerSaveMode,
            paused = paused,
            thumb = notificationThumb,
        )
        if (publishGeneration.get() != expectedGeneration) {
            notificationThumb?.recycle()
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun build(
        playlistId: String,
        playlistName: String,
        target: WallpaperTarget,
        scheduleLine: String,
        powerSaveMode: Boolean,
        paused: Boolean,
        thumb: Bitmap?,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYLIST_ID, playlistId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val targetLabel = when (target) {
            WallpaperTarget.HOME -> context.getString(R.string.notif_target_home)
            WallpaperTarget.LOCK -> context.getString(R.string.notif_target_lock)
            WallpaperTarget.BOTH -> context.getString(R.string.notif_target_both)
        }
        val compact = RemoteViews(context.packageName, R.layout.notif_rotation_compact)
        val expanded = RemoteViews(context.packageName, R.layout.notif_rotation_expanded)
        bindCompact(compact, playlistName, thumb, openApp)
        bindExpanded(
            expanded,
            playlistName,
            targetLabel,
            scheduleLine,
            powerSaveMode,
            paused,
            thumb,
            openApp,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallpurr)
            .setColor(ContextCompat.getColor(context, R.color.ginger))
            .setContentTitle(playlistName)
            .setContentText("$targetLabel · $scheduleLine")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .build()
    }

    private fun bindCompact(
        views: RemoteViews,
        title: String,
        thumb: Bitmap?,
        openApp: PendingIntent,
    ) {
        views.setTextViewText(R.id.notif_title, title)
        bindThumb(views, thumb)
        views.setOnClickPendingIntent(R.id.notif_root, openApp)
        views.setOnClickPendingIntent(R.id.notif_prev, action(WallpaperActionReceiver.ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.notif_next, action(WallpaperActionReceiver.ACTION_NEXT, 4))
    }

    private fun bindExpanded(
        views: RemoteViews,
        title: String,
        subtitle: String,
        scheduleLine: String,
        powerSaveMode: Boolean,
        paused: Boolean,
        thumb: Bitmap?,
        openApp: PendingIntent,
    ) {
        views.setTextViewText(R.id.notif_title, title)
        views.setTextViewText(R.id.notif_subtitle, subtitle)
        views.setTextViewText(R.id.notif_schedule, scheduleLine)
        views.setViewVisibility(
            R.id.notif_power_save_chip,
            if (powerSaveMode) View.VISIBLE else View.GONE,
        )
        val playIcon = if (paused) R.drawable.ic_notif_play else R.drawable.ic_notif_pause
        val playLabel = if (paused) {
            context.getString(R.string.notif_play)
        } else {
            context.getString(R.string.notif_pause)
        }
        views.setImageViewResource(R.id.notif_play_icon, playIcon)
        views.setTextViewText(R.id.notif_play_label, playLabel)
        views.setContentDescription(R.id.notif_play, playLabel)
        bindThumb(views, thumb)
        views.setOnClickPendingIntent(R.id.notif_root, openApp)
        views.setOnClickPendingIntent(R.id.notif_prev, action(WallpaperActionReceiver.ACTION_PREV, 1))
        views.setOnClickPendingIntent(
            R.id.notif_play,
            action(
                if (paused) WallpaperActionReceiver.ACTION_PLAY else WallpaperActionReceiver.ACTION_PAUSE,
                2,
            ),
        )
        views.setOnClickPendingIntent(R.id.notif_next, action(WallpaperActionReceiver.ACTION_NEXT, 4))
    }

    private fun bindThumb(views: RemoteViews, thumb: Bitmap?) {
        if (thumb != null) {
            views.setImageViewBitmap(R.id.notif_thumb, thumb)
        } else {
            views.setImageViewResource(R.id.notif_thumb, R.mipmap.ic_launcher)
        }
    }

    private fun action(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, WallpaperActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun roundedThumb(
        source: Bitmap,
        widthPx: Int,
        heightPx: Int,
        radiusPx: Float,
    ): Bitmap {
        val cropped = centerCrop(source, widthPx, heightPx)
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawRoundRect(
            RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
            radiusPx,
            radiusPx,
            paint,
        )
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(cropped, 0f, 0f, paint)
        if (cropped !== source) cropped.recycle()
        return out
    }

    private fun centerCrop(source: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        val targetRatio = widthPx.toFloat() / heightPx
        val sourceRatio = source.width.toFloat() / source.height
        val cropWidth: Int
        val cropHeight: Int
        if (sourceRatio > targetRatio) {
            cropHeight = source.height
            cropWidth = (cropHeight * targetRatio).toInt().coerceAtLeast(1)
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth / targetRatio).toInt().coerceAtLeast(1)
        }
        val left = (source.width - cropWidth) / 2
        val top = (source.height - cropHeight) / 2
        val crop = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        if (crop.width == widthPx && crop.height == heightPx) return crop
        val scaled = Bitmap.createScaledBitmap(crop, widthPx, heightPx, true)
        if (crop !== source && crop !== scaled) crop.recycle()
        return scaled
    }

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WallpurrRotation"
        private const val PUBLISH_DEBOUNCE_MS = 120L
        // Channel settings are immutable after creation. v3 gives existing installs the public,
        // default-importance channel needed by Samsung lock-screen notification filters.
        const val CHANNEL_ID = "wallpurr-rotation-v3"
        const val NOTIFICATION_ID = 41
    }
}
