package eu.kanade.presentation.track.azit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.track.azit.Azit
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * What the Azit Tracker card shows besides the usual status / chapters / score / dates:
 * the cover Azit has, the volumes read, and a link to the title in Azit.
 */
@Composable
fun AzitTrackExtras(
    azit: Azit,
    track: Track,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val cover by remember(track.remoteId) { azit.coverPreference(track.remoteId) }.collectAsState()
    val volume by remember(track.remoteId) { azit.volumePreference(track.remoteId) }.collectAsState()
    var editVolume by remember { mutableStateOf(false) }
    val open = { if (track.remoteUrl.isNotBlank()) uriHandler.openUri(track.remoteUrl) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cover.isNotBlank()) {
            MangaCover.Book(
                data = cover,
                modifier = Modifier.height(96.dp),
                onClick = open,
            )
        }
        Column {
            TextButton(onClick = { editVolume = true }) {
                Icon(imageVector = Icons.Outlined.CollectionsBookmark, contentDescription = null)
                Text(
                    text = if (volume.isBlank()) {
                        stringResource(KMR.strings.azit_volumes)
                    } else {
                        stringResource(KMR.strings.azit_volumes_value, volume)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            TextButton(onClick = open) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Text(
                    text = stringResource(KMR.strings.azit_open_in_azit),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    if (editVolume) {
        var text by remember { mutableStateOf(volume) }
        val value = text.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }
        AlertDialog(
            onDismissRequest = { editVolume = false },
            title = { Text(text = stringResource(KMR.strings.azit_volumes)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value != null,
                    onClick = {
                        editVolume = false
                        val newVolume = value ?: return@TextButton
                        scope.launchIO {
                            try {
                                azit.setRemoteVolume(track, newVolume)
                            } catch (e: Exception) {
                                withUIContext { context.toast(e.message) }
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { editVolume = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
