package dev.chofito.wallpurr.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chofito.wallpurr.R
import dev.chofito.wallpurr.ui.components.NameDialog

@Composable
fun ShareImportDialogs(viewModel: ShareImportViewModel) {
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val importedIds by viewModel.importedIds.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(importedIds) {
        if (importedIds == null) {
            naming = false
            newName = ""
        }
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.share_importing_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
        )
    }

    val ids = importedIds
    if (!importing && ids != null && naming) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            value = newName,
            onValueChange = { newName = it },
            onConfirm = { viewModel.createAndAdd(newName) },
            onDismiss = { naming = false },
            confirmLabel = stringResource(R.string.create),
            cancelLabel = stringResource(R.string.cancel),
        )
    } else if (!importing && ids != null) {
        AlertDialog(
            onDismissRequest = { viewModel.keepInLibrary() },
            title = { Text(stringResource(R.string.share_imported_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(stringResource(R.string.share_imported_body, ids.size))
                    playlists.forEach { playlist ->
                        TextButton(
                            onClick = { viewModel.addToExisting(playlist.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { naming = true }) {
                    Text(stringResource(R.string.new_playlist))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.keepInLibrary() }) {
                    Text(stringResource(R.string.share_library_only))
                }
            },
        )
    }
}
