package dev.chofito.wallpurr.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.ui.components.IconBubble
import dev.chofito.wallpurr.ui.components.PurrCard
import dev.chofito.wallpurr.ui.components.PowerSaveSelector
import dev.chofito.wallpurr.ui.settingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = settingsViewModel(),
) {
    val context = LocalContext.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pauseGif by viewModel.pauseGifHidden.collectAsStateWithLifecycle()
    val pauseVideo by viewModel.pauseVideoHidden.collectAsStateWithLifecycle()
    val playVideoOnLock by viewModel.playVideoOnLock.collectAsStateWithLifecycle()
    val changeOnHomeTap by viewModel.changeOnHomeTap.collectAsStateWithLifecycle()
    val powerSavePolicy by viewModel.powerSavePolicy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmReset by remember { mutableStateOf(false) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportJson(it, context) }
    }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.exportZip(it, context) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importConfig(it, context) }
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.wallpaper_interaction_title),
                style = MaterialTheme.typography.titleLarge,
            )
            PurrCard(onClick = { viewModel.setChangeOnHomeTap(!changeOnHomeTap) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.change_on_home_tap)) },
                    supportingContent = { Text(stringResource(R.string.change_on_home_tap_hint)) },
                    trailingContent = {
                        Switch(
                            checked = changeOnHomeTap,
                            onCheckedChange = { viewModel.setChangeOnHomeTap(it) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(
                stringResource(R.string.notification_section_title),
                style = MaterialTheme.typography.titleLarge,
            )
            PurrCard(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        },
                    )
                },
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.notification_settings_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.notification_settings_body))
                    },
                    leadingContent = { IconBubble(Icons.Outlined.NotificationsActive) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(stringResource(R.string.lock_animation_title), style = MaterialTheme.typography.titleLarge)
            PurrCard(onClick = { viewModel.setPlayVideoOnLock(!playVideoOnLock) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.play_video_on_lock)) },
                    supportingContent = { Text(stringResource(R.string.play_video_on_lock_hint)) },
                    trailingContent = {
                        Switch(
                            checked = playVideoOnLock,
                            onCheckedChange = { viewModel.setPlayVideoOnLock(it) },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(stringResource(R.string.media_pause_title), style = MaterialTheme.typography.titleLarge)
            PurrCard(onClick = { viewModel.setPauseGifHidden(!pauseGif) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pause_gif_hidden)) },
                    supportingContent = { Text(stringResource(R.string.pause_media_hidden_hint)) },
                    trailingContent = {
                        Switch(checked = pauseGif, onCheckedChange = { viewModel.setPauseGifHidden(it) })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            PurrCard(onClick = { viewModel.setPauseVideoHidden(!pauseVideo) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.pause_video_hidden)) },
                    supportingContent = { Text(stringResource(R.string.pause_media_hidden_hint)) },
                    trailingContent = {
                        Switch(checked = pauseVideo, onCheckedChange = { viewModel.setPauseVideoHidden(it) })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleLarge)
            PowerSaveSelector(
                policy = powerSavePolicy,
                onSelect = viewModel::setPowerSavePolicy,
            )
            Text(
                stringResource(R.string.battery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PurrCard(
                onClick = {
                    // Play does not allow the direct exemption request for this kind of app;
                    // open the system list so the user can choose Wallpurr manually.
                    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    runCatching { context.startActivity(list) }
                        .recoverCatching { context.startActivity(details) }
                },
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.battery_action)) },
                    leadingContent = { IconBubble(Icons.Outlined.BatteryAlert) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            PurrCard(onClick = { exportJson.launch("wallpurr-config.json") }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.export_json)) },
                    leadingContent = { IconBubble(Icons.Outlined.Download) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            PurrCard(onClick = { exportZip.launch("wallpurr-backup.zip") }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.export_zip)) },
                    leadingContent = { IconBubble(Icons.Outlined.Download) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            PurrCard(onClick = { importer.launch(arrayOf("application/json", "application/zip", "*/*")) }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.import_config)) },
                    leadingContent = {
                        IconBubble(
                            Icons.Outlined.Upload,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.secondary,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.titleLarge)
            PurrCard(onClick = { confirmReset = true }) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.developer_reset)) },
                    supportingContent = { Text(stringResource(R.string.developer_reset_hint)) },
                    leadingContent = {
                        IconBubble(
                            Icons.Outlined.DeleteForever,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.developer_reset_confirm_title)) },
            text = { Text(stringResource(R.string.developer_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.resetAllLocalData()
                    },
                ) {
                    Text(stringResource(R.string.developer_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
