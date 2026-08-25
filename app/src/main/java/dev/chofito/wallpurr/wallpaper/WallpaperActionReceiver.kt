package dev.chofito.wallpurr.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.chofito.wallpurr.WallpurrApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WallpaperActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? WallpurrApplication ?: return
        val pending = goAsync()
        val eventAt = SystemClock.elapsedRealtime()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val controller = app.container.rotation
                when (intent.action) {
                    ACTION_PREV -> controller.previous(eventAt)
                    ACTION_PAUSE -> controller.pause()
                    ACTION_PLAY -> controller.play()
                    ACTION_NEXT -> controller.next(eventAt)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Wallpaper notification action failed: ${intent.action}", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WallpurrRotation"
        const val ACTION_PREV = "dev.chofito.wallpurr.action.PREV"
        const val ACTION_PAUSE = "dev.chofito.wallpurr.action.PAUSE"
        const val ACTION_PLAY = "dev.chofito.wallpurr.action.PLAY"
        const val ACTION_NEXT = "dev.chofito.wallpurr.action.NEXT"
    }
}
