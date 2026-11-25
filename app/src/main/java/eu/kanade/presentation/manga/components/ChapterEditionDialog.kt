package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.state.ToggleableState
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
        mutableStateOf(
            chapters.map { chapter ->
                ChapterEditionRow.from(chapter.id, editions[chapter.id], chapter.chapterNumber.takeIf { chapter.isRecognizedNumber })
            },
        )
    }
    // The row the keypad types into (null: no keypad), and each row's box to move the focus to it.
    var selected by remember(chapters) { mutableStateOf<Int?>(null) }
    val focusRequesters = remember(chapters) { chapters.map { FocusRequester() } }
    val focusManager = LocalFocusManager.current
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
                // Typing: the help makes room for the chapters and the keypad.
                if (selected == null) {
                    Text(
                        text = stringResource(KMR.strings.chapter_edition_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
                    )
                }
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
                // One scrolling column (not a lazy list): the keypad's "Next" moves to the next chapter's box and
                // scrolls to it, all the way to the last one, without closing the window.
                Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, row ->
                        ChapterEditionRowItem(
                            chapter = chapters[index],
                            row = row,
                            focusRequester = focusRequesters[index],
                            onSelect = { selected = index },
                            onVolumeChange = { volume ->
                                rows = rows.toMutableList().also { it[index] = row.copy(isVolume = volume) }
                            },
                            onTextChange = { text -> rows = rows.typedAt(index, text) },
                        )
                    }
                }
                selected?.let { index ->
                    val last = index == rows.lastIndex
                    NumberKeypad(
                        last = last,
                        onKey = { key -> rows = rows.typedAt(index, rows[index].text + key) },
                        onBackspace = { rows = rows.typedAt(index, rows[index].text.dropLast(1)) },
                        onNext = {
                            if (last) {
                                focusManager.clearFocus()
                                selected = null
                            } else {
                                focusRequesters[index + 1].requestFocus()
                            }
                        },
                    )
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
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
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
        // Read only: the phone's keyboard stays closed, the window's own keypad (digits, "." and "-") types here.
        OutlinedTextField(
            value = row.text,
            onValueChange = { onTextChange(it.onlyNumberCharacters()) },
            readOnly = true,
            modifier = Modifier
                .width(if (row.isVolume) 136.dp else 104.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onSelect() },
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
        )
    }
}

/** Keeps only digits, "." / "," and "-". */
internal fun String.onlyNumberCharacters(): String = filter { it.isDigit() || it == '.' || it == ',' || it == '-' }

/** The keys of [NumberKeypad], row by row: only what a chapter number (10.5) or a volume range (2-4) holds. */
internal val NUMBER_KEYPAD_ROWS = listOf(
    listOf("1", "2", "3", BACKSPACE_KEY),
    listOf("4", "5", "6", "-"),
    listOf("7", "8", "9", "."),
    listOf("0", NEXT_KEY),
)
internal const val BACKSPACE_KEY = "backspace"
internal const val NEXT_KEY = "next"

/**
 * The window's own number keypad: digits, "." and "-" each on a key, erase, and next chapter (done on the last one).
 * Same keys on every phone (Samsung's number pad has no "-", and a "," next to ".").
 */
@Composable
private fun NumberKeypad(
    last: Boolean,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NUMBER_KEYPAD_ROWS.forEach { keys ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keys.forEach { key ->
                    // "0" and next share the last row: next takes the room of three keys.
                    val weight = if (key == NEXT_KEY) 3f else 1f
                    FilledTonalButton(
                        onClick = {
                            when (key) {
                                BACKSPACE_KEY -> onBackspace()
                                NEXT_KEY -> onNext()
                                else -> onKey(key)
                            }
                        },
                        modifier = Modifier.weight(weight).height(44.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        when (key) {
                            BACKSPACE_KEY -> Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                                contentDescription = stringResource(KMR.strings.chapter_edition_key_backspace),
                            )
                            NEXT_KEY -> Icon(
                                imageVector = if (last) Icons.Outlined.Check else Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = stringResource(
                                    if (last) KMR.strings.chapter_edition_key_done else KMR.strings.chapter_edition_key_next,
                                ),
                            )
                            else -> Text(text = key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
// Azit <--
