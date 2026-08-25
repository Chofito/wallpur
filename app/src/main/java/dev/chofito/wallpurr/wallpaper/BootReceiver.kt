package dev.chofito.wallpurr.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.chofito.wallpurr.WallpurrApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? WallpurrApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.rotation.restoreSchedule()
            } finally {
                pending.finish()
            }
        }
    }
}
