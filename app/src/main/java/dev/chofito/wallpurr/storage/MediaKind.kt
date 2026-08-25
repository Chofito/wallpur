package dev.chofito.wallpurr.storage

enum class MediaKind {
    IMAGE,
    GIF,
    VIDEO,
    ;

    companion object {
        fun from(raw: String): MediaKind = entries.find { it.name == raw } ?: IMAGE
    }
}

enum class VideoPlayMode {
    TIMES,
    MINUTES,
    ;

    companion object {
        fun from(raw: String): VideoPlayMode = entries.find { it.name == raw } ?: TIMES
    }
}
