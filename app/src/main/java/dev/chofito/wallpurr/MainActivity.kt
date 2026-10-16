package dev.chofito.wallpurr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.chofito.wallpurr.ui.WallpurrApp
import dev.chofito.wallpurr.ui.theme.WallpurrTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val shareUris = MutableStateFlow<List<Uri>>(emptyList())
    private val openPlaylistId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            shareUris.value = intent.sharedUris()
        }
        openPlaylistId.value = intent.getStringExtra(EXTRA_OPEN_PLAYLIST_ID)
        if (Build.VERSION.SDK_INT >= 33) {
            // While hidden content is revealed, keep it out of the Recents thumbnail.
            lifecycleScope.launch {
                (application as WallpurrApplication).container.hiddenGate.revealed.collect {
                    setRecentsScreenshotEnabled(!it)
                }
            }
        }
        setContent {
            val uris by shareUris.collectAsState()
            val requestedPlaylistId by openPlaylistId.collectAsState()
            WallpurrTheme {
                WallpurrApp(
                    shareUris = uris,
                    onShareConsumed = { shareUris.value = emptyList() },
                    openPlaylistId = requestedPlaylistId,
                    onOpenPlaylistConsumed = {
                        openPlaylistId.value = null
                        intent.removeExtra(EXTRA_OPEN_PLAYLIST_ID)
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as WallpurrApplication).container.hiddenGate.onAppForegrounded()
    }

    override fun onStop() {
        // A rotation recreates the activity; it is not the user leaving the app.
        if (!isChangingConfigurations) {
            (application as WallpurrApplication).container.hiddenGate.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareUris.value = intent.sharedUris()
        openPlaylistId.value = intent.getStringExtra(EXTRA_OPEN_PLAYLIST_ID)
    }

}

internal const val EXTRA_OPEN_PLAYLIST_ID = "dev.chofito.wallpurr.extra.OPEN_PLAYLIST_ID"

internal fun Intent.sharedUris(): List<Uri> {
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
    val fromExtra = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(parcelableUri(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> parcelableUriList(Intent.EXTRA_STREAM)
        else -> emptyList()
    }
    if (fromExtra.isNotEmpty()) return fromExtra.distinct()
    val clip = clipData ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
}

private fun Intent.parcelableUri(key: String): Uri? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

private fun Intent.parcelableUriList(key: String): List<Uri> {
    val list = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableArrayListExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
    return list.orEmpty()
}
