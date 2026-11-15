package eu.kanade.presentation.track.azit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.track.azit.Azit
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaType
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

/** Azit `release_status` values, in the order shown. */
private val RELEASE_STATUSES = listOf("releasing", "finished", "hiatus", "cancelled", "not_yet_released")

/**
 * "Create in Azit": the fields Komikku has for the manga, editable, then a new title is made in Azit
 * (`force_create`), added to the library and bound to the manga.
 */
@Composable
fun AzitCreateDialog(
    azit: Azit,
    mangaId: Long,
    onDismissRequest: () -> Unit,
    onCreated: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<AzitTitleQuery?>(null) }
    var types by remember { mutableStateOf<List<AzitMediaType>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(mangaId) {
        draft = withIOContext { azit.createDraft(mangaId) }
        types = withIOContext {
            try {
                azit.bookTypes()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Azit Tracker: no media types" }
                emptyList()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.azit_create_title)) },
        text = {
            val current = draft
            if (current == null) {
                Box(modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator() }
            } else {
                AzitDraftFields(draft = current, types = types, onChange = { draft = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && draft?.title?.isNotBlank() == true,
                onClick = {
                    val toCreate = draft ?: return@TextButton
                    creating = true
                    scope.launchIO {
                        try {
                            azit.createFromDraft(mangaId, toCreate)
                            withUIContext { onCreated() }
                        } catch (e: Exception) {
                            withUIContext {
                                creating = false
                                context.toast(e.message)
                            }
                        }
                    }
                },
            ) {
                Text(text = stringResource(KMR.strings.azit_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun AzitDraftFields(
    draft: AzitTitleQuery,
    types: List<AzitMediaType>,
    onChange: (AzitTitleQuery) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onChange(draft.copy(title = it)) },
            label = { Text(text = stringResource(KMR.strings.azit_field_title)) },
            isError = draft.title.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        ListField(
            initial = draft.altTitles,
            separator = "\n",
            label = stringResource(KMR.strings.azit_field_alt_titles),
            onChange = { onChange(draft.copy(altTitles = it)) },
        )
        Choice(
            label = stringResource(KMR.strings.azit_field_type),
            selected = types.firstOrNull { it.slug == draft.typeSlug }?.name
                ?: stringResource(KMR.strings.azit_field_type_auto, draft.kind),
            options = listOf<Pair<String?, String>>(null to stringResource(KMR.strings.azit_field_type_auto, draft.kind)) +
                types.map { it.slug to it.name },
            onSelect = { onChange(draft.copy(typeSlug = it)) },
        )
        OutlinedTextField(
            value = draft.coverUrl.orEmpty(),
            onValueChange = { onChange(draft.copy(coverUrl = it.ifBlank { null })) },
            label = { Text(text = stringResource(KMR.strings.azit_field_cover)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.description.orEmpty(),
            onValueChange = { onChange(draft.copy(description = it.ifBlank { null })) },
            label = { Text(text = stringResource(KMR.strings.azit_field_description)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        ListField(
            initial = draft.authors,
            separator = ",",
            label = stringResource(KMR.strings.azit_field_authors),
            onChange = { onChange(draft.copy(authors = it)) },
        )
        ListField(
            initial = draft.genres,
            separator = ",",
            label = stringResource(KMR.strings.azit_field_genres),
            onChange = { onChange(draft.copy(genres = it)) },
        )
        Choice(
            label = stringResource(KMR.strings.azit_field_release_status),
            selected = draft.releaseStatus ?: "-",
            options = listOf<Pair<String?, String>>(null to "-") + RELEASE_STATUSES.map { it to it },
            onSelect = { onChange(draft.copy(releaseStatus = it)) },
        )
        OutlinedTextField(
            value = draft.totalChapters?.toString().orEmpty(),
            onValueChange = { text -> onChange(draft.copy(totalChapters = text.trim().toIntOrNull()?.takeIf { it > 0 })) },
            label = { Text(text = stringResource(KMR.strings.azit_field_total_chapters)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A list edited as text ("a, b" or one per line): the text stays as typed, the list is parsed from it. */
@Composable
private fun ListField(
    initial: List<String>,
    separator: String,
    label: String,
    onChange: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(initial.joinToString(if (separator == ",") ", " else separator)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.split(separator).map(String::trim).filter(String::isNotEmpty))
        },
        label = { Text(text = label) },
        minLines = if (separator == "\n") 2 else 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "$label: $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(text = name) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
