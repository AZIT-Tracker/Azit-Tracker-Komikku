package eu.kanade.presentation.track.azit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.data.track.azit.Azit
import eu.kanade.tachiyomi.data.track.azit.AzitCreateDraft
import eu.kanade.tachiyomi.data.track.azit.AzitEntryPrefill
import eu.kanade.tachiyomi.data.track.azit.AzitMapping
import eu.kanade.tachiyomi.data.track.azit.dto.AzitDuplicates
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaType
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
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
    var draft by remember { mutableStateOf<AzitCreateDraft?>(null) }
    var types by remember { mutableStateOf<List<AzitMediaType>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    // KMK -->
    var duplicates by remember { mutableStateOf<AzitDuplicates?>(null) }

    fun create(toCreate: AzitCreateDraft) {
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
    }

    fun useExisting(entry: AzitEntry) {
        creating = true
        duplicates = null
        scope.launchIO {
            try {
                azit.bindExisting(mangaId, entry)
                withUIContext { onCreated() }
            } catch (e: Exception) {
                withUIContext {
                    creating = false
                    context.toast(e.message)
                }
            }
        }
    }

    duplicates?.let { found ->
        AzitDuplicatesDialog(
            found = found,
            server = azit.getServer(),
            onUse = ::useExisting,
            onCreateAnyway = if (found.linked == null) {
                {
                    duplicates = null
                    draft?.let(::create)
                }
            } else {
                null
            },
            onDismissRequest = { duplicates = null },
        )
    }
    // KMK <--

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
                AzitDraftFields(
                    full = current,
                    types = types,
                    statusName = { status -> azit.getStatus(status)?.let { context.stringResource(it) }.orEmpty() },
                    statuses = azit.getStatusList(),
                    onFullChange = { draft = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && draft?.query?.title?.isNotBlank() == true,
                onClick = {
                    val toCreate = draft ?: return@TextButton
                    // KMK -->
                    // Azit may already have it (same page or AniList id: blocked; same name: asked first).
                    creating = true
                    scope.launchIO {
                        val found = azit.duplicatesFor(mangaId, toCreate)
                        withUIContext {
                            creating = false
                            if (found.any) duplicates = found else create(toCreate)
                        }
                    }
                    // KMK <--
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
    full: AzitCreateDraft,
    types: List<AzitMediaType>,
    statuses: List<Long>,
    statusName: (Long) -> String,
    onFullChange: (AzitCreateDraft) -> Unit,
) {
    val draft = full.query
    val onChange: (AzitTitleQuery) -> Unit = { onFullChange(full.copy(query = it)) }
    val entry = full.entry
    val onEntryChange: (AzitEntryPrefill) -> Unit = { onFullChange(full.copy(entry = it)) }
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
        if (!draft.coverUrl.isNullOrBlank()) {
            MangaCover.Book(
                data = draft.coverUrl,
                modifier = Modifier
                    .height(160.dp)
                    .align(Alignment.CenterHorizontally),
            )
        }
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

        // The library entry: prefilled from the other trackers (AniList first), else Komikku's reading.
        Text(
            text = stringResource(KMR.strings.azit_create_entry_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Choice(
            label = stringResource(KMR.strings.azit_field_reading_status),
            selected = statusName(entry.status),
            options = statuses.map { it.toString() to statusName(it) },
            onSelect = { value -> value?.toLongOrNull()?.let { onEntryChange(entry.copy(status = it)) } },
        )
        ParsedField(
            initial = entry.chapter.takeIf { it > 0 }?.let(AzitMapping::formatNumber).orEmpty(),
            label = stringResource(KMR.strings.azit_field_chapters_read),
            keyboardType = KeyboardType.Decimal,
            parse = { it.replace(',', '.').toDoubleOrNull()?.takeIf { value -> value >= 0 } },
            onValue = { onEntryChange(entry.copy(chapter = it ?: 0.0)) },
        )
        ParsedField(
            initial = entry.score.takeIf { it > 0 }?.let { AzitMapping.formatNumber(it / 10) }.orEmpty(),
            label = stringResource(KMR.strings.azit_field_score),
            keyboardType = KeyboardType.Decimal,
            parse = { it.replace(',', '.').toDoubleOrNull()?.takeIf { value -> value in 0.0..10.0 } },
            onValue = { onEntryChange(entry.copy(score = (it ?: 0.0) * 10)) },
        )
        ParsedField(
            initial = AzitMapping.toAzitDate(entry.startDate).orEmpty(),
            label = stringResource(KMR.strings.azit_field_started_on),
            keyboardType = KeyboardType.Number,
            parse = { AzitMapping.fromAzitDate(it).takeIf { millis -> millis > 0 } },
            onValue = { onEntryChange(entry.copy(startDate = it ?: 0)) },
        )
        ParsedField(
            initial = AzitMapping.toAzitDate(entry.finishDate).orEmpty(),
            label = stringResource(KMR.strings.azit_field_finished_on),
            keyboardType = KeyboardType.Number,
            parse = { AzitMapping.fromAzitDate(it).takeIf { millis -> millis > 0 } },
            onValue = { onEntryChange(entry.copy(finishDate = it ?: 0)) },
        )
    }
}

/**
 * A value typed as text: the text stays as typed; a blank field gives null, a field that doesn't parse is shown
 * in error and keeps the last valid value.
 */
@Composable
private fun <T : Any> ParsedField(
    initial: String,
    label: String,
    keyboardType: KeyboardType,
    parse: (String) -> T?,
    onValue: (T?) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val invalid = text.isNotBlank() && parse(text.trim()) == null
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (it.isBlank()) {
                onValue(null)
            } else {
                parse(it.trim())?.let(onValue)
            }
        },
        label = { Text(text = label) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
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

// KMK -->
/**
 * Azit already has this manga: with the same page or AniList id ([AzitDuplicates.linked]) it is not created again,
 * the manga can only be bound to it; with the same name, "is it one of these?" before creating anyway.
 */
@Composable
private fun AzitDuplicatesDialog(
    found: AzitDuplicates,
    server: String,
    onUse: (AzitEntry) -> Unit,
    onCreateAnyway: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    val linked = found.linked
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(
                    if (linked != null) KMR.strings.azit_duplicate_linked_title else KMR.strings.azit_duplicate_name_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        if (linked != null) KMR.strings.azit_duplicate_linked_text else KMR.strings.azit_duplicate_name_text,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOfNotNull(linked).ifEmpty { found.sameName }.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUse(entry) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MangaCover.Book(
                            data = AzitMapping.absoluteUrl(entry.coverUrl, server),
                            modifier = Modifier.width(48.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = entry.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (entry.inLibrary) {
                                    stringResource(KMR.strings.azit_search_in_list, entry.status.orEmpty())
                                } else {
                                    stringResource(KMR.strings.azit_search_not_in_list)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = { onUse(entry) }) {
                            Text(text = stringResource(KMR.strings.azit_duplicate_use))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onCreateAnyway != null) {
                TextButton(onClick = onCreateAnyway) {
                    Text(text = stringResource(KMR.strings.azit_duplicate_create_anyway))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
// KMK <--
