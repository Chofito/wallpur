package dev.chofito.wallpurr.storage

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When revealed hidden folders and playlists go back into hiding. */
sealed interface HiddenAutoLock {
    /** Hide as soon as the app leaves the foreground. */
    data object OnLeave : HiddenAutoLock

    /** Stay revealed until the user taps the lock. */
    data object Manual : HiddenAutoLock

    /** Hide when the app returns after spending at least [minutes] in the background. */
    data class AfterMinutes(val minutes: Int) : HiddenAutoLock

    fun encode(): String = when (this) {
        OnLeave -> ON_LEAVE
        Manual -> MANUAL
        is AfterMinutes -> "$AFTER_PREFIX$minutes"
    }

    companion object {
        val MINUTE_OPTIONS = listOf(1, 5, 15, 30, 60)
        private const val ON_LEAVE = "ON_LEAVE"
        private const val MANUAL = "MANUAL"
        private const val AFTER_PREFIX = "AFTER:"

        /** Unknown or missing values fall back to the most private policy. */
        fun decode(raw: String?): HiddenAutoLock = when {
            raw == MANUAL -> Manual
            raw?.startsWith(AFTER_PREFIX) == true ->
                raw.removePrefix(AFTER_PREFIX).toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let(::AfterMinutes)
                    ?: OnLeave
            else -> OnLeave
        }
    }
}

/**
 * Process-wide "hidden content is revealed" state. It is never persisted: a cold start, including
 * after the system kills the process, always begins hidden.
 */
class HiddenContentGate(
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    @Volatile
    var policy: HiddenAutoLock = HiddenAutoLock.OnLeave

    private var backgroundedAtMs: Long? = null

    fun reveal() {
        backgroundedAtMs = null
        _revealed.value = true
    }

    fun hide() {
        backgroundedAtMs = null
        _revealed.value = false
    }

    fun onAppBackgrounded() {
        if (!_revealed.value) return
        when (policy) {
            HiddenAutoLock.OnLeave -> hide()
            is HiddenAutoLock.AfterMinutes -> backgroundedAtMs = now()
            HiddenAutoLock.Manual -> Unit
        }
    }

    fun onAppForegrounded() {
        val leftAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        val current = policy
        if (current is HiddenAutoLock.AfterMinutes && now() - leftAt >= current.minutes * 60_000L) {
            hide()
        }
    }
}
