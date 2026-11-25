package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 *
 * The number boxes use the window's own number keyboard ([NumberKeyboard], docked at the bottom like the phone's),
 * so "-" and "." each have a key whatever keyboard the phone uses.
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
    // The box being typed in (null: keyboard closed), each box's cursor, and each box's focus.
    var focused by remember(chapters) { mutableStateOf<Int?>(null) }
    var selections by remember(chapters) { mutableStateOf(emptyMap<Int, TextRange>()) }
    val focusRequesters = remember(chapters) { chapters.map { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    val allState = when {
        rows.all { it.isVolume } -> ToggleableState.On
        rows.none { it.isVolume } -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    fun closeKeyboard() {
        focusManager.clearFocus()
        focused = null
    }

    fun valueOf(index: Int): TextFieldValue {
        val text = rows[index].text
        val selection = selections[index] ?: TextRange(text.length)
        return TextFieldValue(text, TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length)))
    }

    fun edit(index: Int, value: TextFieldValue) {
        val text = value.text.onlyNumberCharacters()
        rows = rows.typedAt(index, text)
        selections = selections + (index to TextRange(value.selection.start.coerceAtMost(text.length)))
    }

    // Back and a tap outside close the keyboard first, like the phone's, then the window.
    val dismiss = { if (focused != null) closeKeyboard() else onDismissRequest() }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        dismiss()
                    }
                    .windowInsetsPadding(
                        if (focused == null) {
                            WindowInsets.safeDrawing
                        } else {
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        },
                    )
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                    // A tap on the window itself stays there.
                    modifier = Modifier.widthIn(min = 280.dp, max = 560.dp).pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text(
                            text = stringResource(KMR.strings.action_chapter_edition),
                            style = MaterialTheme.typography.headlineSmall,
                            color = AlertDialogDefaults.titleContentColor,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Typing: the help makes room for the chapters.
                            if (focused == null) {
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
                            // One scrolling column (not a lazy list): "Next" moves to the next chapter's box and
                            // scrolls to it, all the way to the last one, without closing the window.
                            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                                rows.forEachIndexed { index, row ->
                                    ChapterEditionRowItem(
                                        chapter = chapters[index],
                                        row = row,
                                        value = valueOf(index),
                                        focusRequester = focusRequesters[index],
                                        onFocus = { focused = index },
                                        onVolumeChange = { volume ->
                                            rows = rows.toMutableList().also { it[index] = row.copy(isVolume = volume) }
                                        },
                                        onValueChange = { value ->
                                            if (value.text == row.text) {
                                                selections = selections + (index to value.selection)
                                            } else {
                                                edit(index, value)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Row(modifier = Modifier.align(Alignment.End)) {
                            TextButton(onClick = onDismissRequest) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                            TextButton(
                                enabled = rows.none { it.isInvalid },
                                onClick = {
                                    val (saved, cleared) = rows.toChanges()
                                    onConfirm(saved, cleared)
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_save))
                            }
                        }
                    }
                }
            }
            NumberKeyboard(
                visible = focused != null,
                last = focused == rows.lastIndex,
                onKey = { key ->
                    val index = focused ?: return@NumberKeyboard
                    when (key) {
                        is NumberKey.Type -> edit(index, valueOf(index).typed(key.character))
                        NumberKey.Erase -> edit(index, valueOf(index).erased())
                        NumberKey.Action -> if (index == rows.lastIndex) {
                            closeKeyboard()
                        } else {
                            focusRequesters[index + 1].requestFocus()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChapterEditionRowItem(
    chapter: Chapter,
    row: ChapterEditionRow,
    value: TextFieldValue,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onVolumeChange: (Boolean) -> Unit,
    onValueChange: (TextFieldValue) -> Unit,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).bringIntoViewRequester(bringIntoView),
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
        WithoutPhoneKeyboard {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .width(if (row.isVolume) 136.dp else 104.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            onFocus()
                            // Once the keyboard has slid up, the box shows above it.
                            scope.launch {
                                delay(KEYBOARD_SLIDE_MILLIS)
                                bringIntoView.bringIntoView()
                            }
                        }
                    },
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
}

private const val KEYBOARD_SLIDE_MILLIS = 350L

/** Keeps only digits, "." and "-"; a "," (pasted, or from a hardware keyboard) becomes ".". */
internal fun String.onlyNumberCharacters(): String = replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }

/** The number boxes keep the phone's keyboard closed: the window's [NumberKeyboard] types in them. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WithoutPhoneKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { _, _ -> awaitCancellation() },
        content = content,
    )
}
// Azit <--
