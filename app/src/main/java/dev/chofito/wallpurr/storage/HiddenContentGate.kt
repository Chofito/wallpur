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
    private var externalActivityAtMs: Long? = null
    private var backgroundedForExternalActivity = false

    fun reveal() {
        backgroundedAtMs = null
        _revealed.value = true
    }

    fun hide() {
        backgroundedAtMs = null
        _revealed.value = false
    }

    /**
     * Wallpurr is about to open another activity itself: a picker, the live wallpaper chooser or
     * a system settings screen. Being stopped for it is not the user leaving the app, so
     * [HiddenAutoLock.OnLeave] waits up to [EXTERNAL_ACTIVITY_GRACE_MS] instead of hiding at once.
     */
    fun expectExternalActivity() {
        externalActivityAtMs = now()
    }

    fun onAppBackgrounded() {
        val expectedAt = externalActivityAtMs
        externalActivityAtMs = null
        if (!_revealed.value) return
        val external = expectedAt != null && now() - expectedAt <= EXTERNAL_ACTIVITY_WINDOW_MS
        when (policy) {
            HiddenAutoLock.OnLeave -> if (external) {
                backgroundedAtMs = now()
                backgroundedForExternalActivity = true
            } else {
                hide()
            }
            is HiddenAutoLock.AfterMinutes -> {
                backgroundedAtMs = now()
                backgroundedForExternalActivity = external
            }
            HiddenAutoLock.Manual -> Unit
        }
    }

    fun onAppForegrounded() {
        val leftAt = backgroundedAtMs ?: return
        backgroundedAtMs = null
        val limitMs = when (val current = policy) {
            HiddenAutoLock.OnLeave -> EXTERNAL_ACTIVITY_GRACE_MS
            is HiddenAutoLock.AfterMinutes -> {
                val policyMs = current.minutes * 60_000L
                if (backgroundedForExternalActivity) maxOf(policyMs, EXTERNAL_ACTIVITY_GRACE_MS) else policyMs
            }
            HiddenAutoLock.Manual -> return
        }
        backgroundedForExternalActivity = false
        if (now() - leftAt >= limitMs) hide()
    }

    private companion object {
        // How soon after expectExternalActivity() the stop must arrive to be attributed to it.
        const val EXTERNAL_ACTIVITY_WINDOW_MS = 5_000L
        const val EXTERNAL_ACTIVITY_GRACE_MS = 5 * 60_000L
    }
}
