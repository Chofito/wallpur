package dev.chofito.wallpurr.storage

enum class PowerSavePolicy {
    IGNORE,
    STATIC,
    ;

    companion object {
        fun from(value: String?): PowerSavePolicy =
            entries.firstOrNull { it.name == value } ?: IGNORE
    }
}
