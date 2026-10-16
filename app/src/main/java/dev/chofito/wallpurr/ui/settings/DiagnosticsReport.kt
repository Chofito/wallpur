package dev.chofito.wallpurr.ui.settings

import android.content.Context
import android.os.Build
import dev.chofito.wallpurr.storage.WallpurrRepository
import dev.chofito.wallpurr.wallpaper.WallpaperApplier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Builds the user-initiated, local-only diagnostics text. The app has no network access, so this
 * file is the only way to collect field logs. The wallpaper engine runs in the app process, so
 * the process' own logcat includes WallpurrWallpaper / WallpurrOrient / WallpurrOrientGL /
 * WallpurrVideoGL / WallpurrRotation.
 */
class DiagnosticsReport(
    private val context: Context,
    private val repository: WallpurrRepository,
    private val applier: WallpaperApplier,
) {
    suspend fun write(out: OutputStream) = withContext(Dispatchers.IO) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(header())
        writer.write("\n===== logcat (proceso de Wallpurr, hasta ${MAX_LOG_BYTES / 1024} KB) =====\n")
        writer.write(readLogcat())
        writer.flush()
    }

    private suspend fun header(): String = buildString {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        appendLine("Wallpurr diagnóstico")
        appendLine("Generado: ${java.util.Date()}")
        appendLine()
        appendLine("== App ==")
        appendLine("Paquete: ${context.packageName}")
        @Suppress("DEPRECATION")
        appendLine("Versión: ${info?.versionName} (${info?.versionCode})")
        appendLine()
        appendLine("== Dispositivo ==")
        appendLine("Fabricante: ${Build.MANUFACTURER}")
        appendLine("Modelo: ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Parche de seguridad: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine()
        appendLine("== Ajustes ==")
        appendLine("pause_gif_hidden: ${flag(WallpurrRepository.KEY_PAUSE_GIF_HIDDEN, true)}")
        appendLine("pause_video_hidden: ${flag(WallpurrRepository.KEY_PAUSE_VIDEO_HIDDEN, true)}")
        appendLine("play_video_on_lock: ${flag(WallpurrRepository.KEY_PLAY_VIDEO_ON_LOCK, false)}")
        appendLine("change_on_home_tap: ${flag(WallpurrRepository.KEY_CHANGE_ON_HOME_TAP, true)}")
        appendLine("power_save_policy: ${runCatching { repository.powerSavePolicy() }.getOrNull()}")
        appendLine("rotation_paused: ${runCatching { repository.isRotationPaused() }.getOrNull()}")
        appendLine()
        appendLine("== Playlist activa ==")
        runCatching {
            val id = repository.activePlaylistId()
            appendLine("id: ${id ?: "(ninguna)"}")
            if (id != null) appendLine("elementos: ${repository.playlistImageIds(id).size}")
        }.onFailure { appendLine("error: $it") }
        appendLine()
        appendLine("== Propiedad del wallpaper ==")
        appendLine("Wallpurr en Home: ${runCatching { applier.isWallpurrLiveOnHome() }.getOrNull()}")
        appendLine("Wallpurr en Lock: ${runCatching { applier.isWallpurrLiveOnLock() }.getOrNull()}")
    }

    private suspend fun flag(key: String, default: Boolean): Any? =
        runCatching { repository.observeFlag(key, default).first() }.getOrNull()

    /** Keeps the newest lines, dropping the oldest once the byte cap is exceeded. */
    private fun readLogcat(): String {
        val process = runCatching {
            ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return "No se pudo leer logcat: $it\n" }
        val lines = ArrayDeque<String>()
        var size = 0
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                lines.addLast(line)
                size += line.length + 1
                while (size > MAX_LOG_BYTES && lines.size > 1) size -= lines.removeFirst().length + 1
            }
        } catch (error: Exception) {
            lines.addLast("(lectura de logcat interrumpida: $error)")
        } finally {
            process.destroy()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private companion object {
        const val MAX_LOG_BYTES = 2 * 1024 * 1024
    }
}
