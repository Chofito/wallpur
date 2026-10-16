package dev.chofito.wallpurr.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.ui.container

@Composable
fun hiddenContentRevealed(): Boolean {
    val gate = LocalContext.current.container().hiddenGate
    val revealed by gate.revealed.collectAsStateWithLifecycle()
    return revealed
}

/**
 * Screen title that doubles as the secret entry point: a long press asks for the device
 * credential (fingerprint, face, PIN, pattern or password) and then reveals hidden folders and
 * playlists. Nothing on screen hints at it, and a plain tap does nothing.
 */
@Composable
fun HiddenRevealTitle(text: String, modifier: Modifier = Modifier) {
    val unlock = rememberHiddenContentUnlock()
    val haptics = LocalHapticFeedback.current
    Text(
        text,
        modifier = modifier.pointerInput(unlock) {
            detectTapGestures(
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    unlock()
                },
            )
        },
    )
}

/** Shown only while hidden content is revealed; hides it again. */
@Composable
fun HiddenContentLockButton() {
    if (!hiddenContentRevealed()) return
    val gate = LocalContext.current.container().hiddenGate
    IconButton(onClick = gate::hide) {
        Icon(Icons.Outlined.LockOpen, contentDescription = stringResource(R.string.hidden_lock))
    }
}

@Composable
private fun rememberHiddenContentUnlock(): () -> Unit {
    val context = LocalContext.current
    val gate = context.container().hiddenGate
    val title = stringResource(R.string.hidden_reveal_title)
    val subtitle = stringResource(R.string.hidden_reveal_subtitle)
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) gate.reveal()
    }
    return remember(context, legacyLauncher, title, subtitle) {
        unlock@{
            if (gate.revealed.value) return@unlock
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            // Without a screen lock there is no credential to ask for; the gesture alone reveals.
            if (keyguard?.isDeviceSecure != true) {
                gate.reveal()
                return@unlock
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                authenticateWithPrompt(context, title, subtitle, gate::reveal)
            } else {
                @Suppress("DEPRECATION")
                keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
                    ?.let(legacyLauncher::launch)
                    ?: gate.reveal()
            }
        }
    }
}

private fun authenticateWithPrompt(
    context: Context,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
) {
    val builder = BiometricPrompt.Builder(context)
        .setTitle(title)
        .setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    builder.build().authenticate(
        CancellationSignal(),
        context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        },
    )
}
