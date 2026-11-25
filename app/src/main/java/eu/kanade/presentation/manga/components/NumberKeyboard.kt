package eu.kanade.presentation.manga.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

// Azit -->
/** A key of [NumberKeyboard]: a character it types, erase, or the action key (next chapter / done). */
internal sealed interface NumberKey {
    data class Type(val character: Char) : NumberKey
    data object Erase : NumberKey
    data object Action : NumberKey
}

/**
 * The keys of [NumberKeyboard], row by row (null: an empty spot), laid out like a phone's number pad: digits, then
 * erase, the action key, "-" (volume ranges, 2-4) and "." (10.5) down the right side.
 */
internal val NUMBER_KEYBOARD_ROWS: List<List<NumberKey?>> = listOf(
    listOf(NumberKey.Type('1'), NumberKey.Type('2'), NumberKey.Type('3'), NumberKey.Erase),
    listOf(NumberKey.Type('4'), NumberKey.Type('5'), NumberKey.Type('6'), NumberKey.Action),
    listOf(NumberKey.Type('7'), NumberKey.Type('8'), NumberKey.Type('9'), NumberKey.Type('-')),
    listOf(null, NumberKey.Type('0'), null, NumberKey.Type('.')),
)

/** [character] typed like a keyboard does: it replaces the selection, or goes in at the cursor. */
internal fun TextFieldValue.typed(character: Char): TextFieldValue {
    val start = selection.min
    val newText = text.replaceRange(start, selection.max, character.toString())
    return TextFieldValue(newText, TextRange(start + 1))
}

/** Erase like a keyboard does: the selection, or the character before the cursor. */
internal fun TextFieldValue.erased(): TextFieldValue {
    if (!selection.collapsed) {
        return TextFieldValue(text.removeRange(selection.min, selection.max), TextRange(selection.min))
    }
    val cursor = selection.start
    if (cursor == 0) return this
    return TextFieldValue(text.removeRange(cursor - 1, cursor), TextRange(cursor - 1))
}

/**
 * A number keyboard docked at the bottom of the screen, the way the phone's own keyboard is: it slides up while a
 * number box has the focus. Keys type where the cursor is, holding erase keeps erasing, and the action key says
 * "Next" (next chapter) or "Done" (closes it) like the phone's.
 */
@Composable
internal fun NumberKeyboard(
    visible: Boolean,
    last: Boolean,
    onKey: (NumberKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(keyboardBackground())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NUMBER_KEYBOARD_ROWS.forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.forEach { key -> KeyboardKey(key = key, last = last, onKey = onKey) }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyboardKey(key: NumberKey?, last: Boolean, onKey: (NumberKey) -> Unit) {
    val keyModifier = Modifier.weight(1f).height(56.dp)
    if (key == null) {
        Box(modifier = keyModifier)
        return
    }
    val view = LocalView.current
    val currentOnKey by rememberUpdatedState(onKey)
    var pressed by remember { mutableStateOf(false) }
    val press = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        currentOnKey(key)
    }
    val description = when (key) {
        is NumberKey.Type -> key.character.toString()
        NumberKey.Erase -> stringResource(KMR.strings.chapter_edition_key_backspace)
        NumberKey.Action -> actionLabel(last)
    }
    Box(
        modifier = keyModifier
            .clip(RoundedCornerShape(10.dp))
            .background(keyColor(pressed))
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick {
                    press()
                    true
                }
            }
            // A key types when pressed, like a keyboard; holding erase keeps erasing.
            .pointerInput(key) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        press()
                        if (key == NumberKey.Erase) {
                            coroutineScope {
                                val repeat = launch {
                                    delay(400)
                                    while (true) {
                                        press()
                                        delay(60)
                                    }
                                }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                        } else {
                            tryAwaitRelease()
                        }
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is NumberKey.Type -> Text(text = key.character.toString(), fontSize = 26.sp)
            NumberKey.Erase -> Icon(imageVector = Icons.AutoMirrored.Outlined.Backspace, contentDescription = null)
            NumberKey.Action -> Text(
                text = actionLabel(last),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Behind the keys: a step darker than the theme's surfaces in a dark theme (a light grey in a light one),
 * so the keys stand out on a plain, even background like the phone's keyboard.
 */
@Composable
private fun keyboardBackground(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.surface.luminance() < 0.5f) {
        lerp(colors.surfaceContainerLowest, Color.Black, 0.45f)
    } else {
        colors.surfaceContainerHighest
    }
}

/** A key: lighter than the background in a dark theme, white-ish in a light one; a little lighter / darker pressed. */
@Composable
private fun keyColor(pressed: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val key = if (dark) colors.surfaceContainerHighest else colors.surfaceContainerLowest
    return if (pressed) lerp(key, colors.onSurface, 0.12f) else key
}

@Composable
private fun actionLabel(last: Boolean): String =
    stringResource(if (last) KMR.strings.chapter_edition_key_done else KMR.strings.chapter_edition_key_next)
// Azit <--
