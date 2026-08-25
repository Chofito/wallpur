package dev.chofito.wallpurr.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PowerSaveMonitor(
    private val context: Context,
    private val rotation: RotationController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
            val pending = goAsync()
            scope.launch {
                try {
                    rotation.powerSaveModeChanged()
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to apply power-save policy", error)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private companion object {
        const val TAG = "WallpurrPowerSave"
    }
}
