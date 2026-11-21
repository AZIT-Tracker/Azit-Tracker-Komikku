package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterEdition
import tachiyomi.domain.chapter.model.ChapterEditionRow
import tachiyomi.domain.chapter.model.allVolumes
import tachiyomi.domain.chapter.model.toChanges
import tachiyomi.domain.chapter.model.typedAt
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.i18n.stringResource

// Azit -->
/**
 * Chapter edition (selection menu -> more): the selected chapters, each counted as another chapter number or as a
 * volume ("2" or "2-4"). Typing a chapter number fills the chapters after it (3 -> 4, 5, 6...). A blank number keeps
 * the chapter's own. Trackers get the last number given.
 */
@Composable
fun ChapterEditionDialog(
    chapters: List<Chapter>,
    editions: Map<Long, ChapterEdition>,
    onDismissRequest: () -> Unit,
    onConfirm: (editions: List<ChapterEdition>, cleared: List<Long>) -> Unit,
) {
    var rows by remember(chapters) {
        mutableStateOf(chapters.map { ChapterEditionRow.from(it.id, editions[it.id]) })
    }
    val allState = when {
        rows.all { it.isVolume } -> ToggleableState.On
        rows.none { it.isVolume } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(KMR.strings.action_chapter_edition)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(KMR.strings.chapter_edition_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(
                        state = allState,
                        onClick = { rows = rows.allVolumes(allState != ToggleableState.On) },
                    )
                    Text(
                        text = stringResource(KMR.strings.chapter_edition_all_volumes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.chapterId }) { index, row ->
                        val chapter = chapters[index]
                        ChapterEditionRowItem(
                            chapter = chapter,
                            row = row,
                            onVolumeChange = { volume ->
                                rows = rows.toMutableList().also { it[index] = row.copy(isVolume = volume) }
                            },
                            onTextChange = { text -> rows = rows.typedAt(index, text) },
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = rows.none { it.isInvalid },
                onClick = {
                    val (saved, cleared) = rows.toChanges()
                    onConfirm(saved, cleared)
                },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
    )
}

@Composable
private fun ChapterEditionRowItem(
    chapter: Chapter,
    row: ChapterEditionRow,
    onVolumeChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.isVolume, onCheckedChange = onVolumeChange)
                Text(
                    text = stringResource(KMR.strings.chapter_edition_volume),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                )
            }
        }
        OutlinedTextField(
            value = row.text,
            onValueChange = onTextChange,
            modifier = Modifier.width(104.dp),
            singleLine = true,
            isError = row.isInvalid,
            placeholder = {
                Text(
                    text = if (row.isVolume) {
                        stringResource(KMR.strings.chapter_edition_volume_hint)
                    } else {
                        ChapterEdition.formatNumber(chapter.chapterNumber).takeIf { chapter.isRecognizedNumber }.orEmpty()
                    },
                    maxLines = 1,
                )
            },
            keyboardOptions = KeyboardOptions(
                // A volume range needs "-": the phone pad has it.
                keyboardType = if (row.isVolume) KeyboardType.Phone else KeyboardType.Decimal,
            ),
        )
    }
}
// Azit <--
