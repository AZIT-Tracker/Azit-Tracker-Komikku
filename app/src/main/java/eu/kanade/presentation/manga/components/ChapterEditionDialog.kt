package eu.kanade.presentation.manga.components

import android.text.InputType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
@OptIn(ExperimentalLayoutApi::class)
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
    val allState = when {
        rows.all { it.isVolume } -> ToggleableState.On
        rows.none { it.isVolume } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    // The window takes the keyboard into account: the dialog shrinks above it and its list scrolls to the last chapter.
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(text = stringResource(KMR.strings.action_chapter_edition)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Typing: the help makes room for the chapters.
                if (!WindowInsets.isImeVisible) {
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
                // One scrolling column (not a lazy list): the keyboard's "Next" moves to the next chapter's box and
                // scrolls to it, all the way to the last one, without closing the window.
                Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { index, row ->
                        ChapterEditionRowItem(
                            chapter = chapters[index],
                            row = row,
                            last = index == rows.lastIndex,
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
    last: Boolean,
    onVolumeChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
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
        NumberKeypad {
            OutlinedTextField(
                value = row.text,
                // Only what a chapter number or a volume range can hold: digits, "." (10.5) and "-" (2-4).
                onValueChange = { onTextChange(it.onlyNumberCharacters()) },
                modifier = Modifier.width(if (row.isVolume) 136.dp else 104.dp),
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
                // The number pad; NumberKeypad makes it a signed one so it also has "-" (volume ranges).
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = if (last) ImeAction.Done else ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.clearFocus() },
                ),
            )
        }
    }
}

/** Keeps only digits, "." and "-"; a "," (the decimal key of some keyboards) becomes ".". */
internal fun String.onlyNumberCharacters(): String = replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }

/**
 * Asks the keyboard for a signed decimal number pad: digits, "." and "-" only. Compose's KeyboardType.Decimal leaves
 * out "-" (Samsung's pad shows digits, "." and "," only), so the input type the keyboard receives gets the signed flag.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NumberKeypad(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val signedRequest = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            }
            nextHandler.startInputMethod(signedRequest)
        },
        content = content,
    )
}
// Azit <--
