package dev.chofito.wallpurr.wallpaper

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import java.io.File

internal fun MediaPlayer.bindMutedVideo(
    file: File,
    surface: Surface,
    looping: Boolean,
) {
    setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build(),
    )
    setVolume(0f, 0f)
    setDataSource(file.absolutePath)
    setSurface(surface)
    isLooping = looping
    runCatching { setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT) }
}
