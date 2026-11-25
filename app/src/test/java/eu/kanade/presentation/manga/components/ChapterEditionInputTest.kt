package eu.kanade.presentation.manga.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Azit -->
/** The Chapter edition boxes and their number keyboard. */
class ChapterEditionInputTest {

    @Test
    fun `digits, dot and dash are kept`() {
        "105-107".onlyNumberCharacters() shouldBe "105-107"
        "10.5".onlyNumberCharacters() shouldBe "10.5"
    }

    @Test
    fun `a comma becomes a dot`() {
        "10,5".onlyNumberCharacters() shouldBe "10.5"
        "2,5-4".onlyNumberCharacters() shouldBe "2.5-4"
    }

    @Test
    fun `anything else is dropped`() {
        "Ch. 12 a+b (3)".onlyNumberCharacters() shouldBe ".123"
        " 2 - 4 ".onlyNumberCharacters() shouldBe "2-4"
    }

    @Test
    fun `the keyboard has every digit, a dash and a dot each on its own key, erase and the action key`() {
        val keys = NUMBER_KEYBOARD_ROWS.flatten().filterNotNull()
        val typed = keys.filterIsInstance<NumberKey.Type>().map { it.character }

        typed.sorted() shouldBe (('0'..'9') + '.' + '-').sorted()
        keys.count { it == NumberKey.Erase } shouldBe 1
        keys.count { it == NumberKey.Action } shouldBe 1
        // "-" above "." down the right side, like the phone's number pad.
        NUMBER_KEYBOARD_ROWS.map { it.last() } shouldBe
            listOf(NumberKey.Erase, NumberKey.Action, NumberKey.Type('-'), NumberKey.Type('.'))
    }

    @Test
    fun `a key types at the cursor`() {
        TextFieldValue("105107", TextRange(3)).typed('-') shouldBe TextFieldValue("105-107", TextRange(4))
        TextFieldValue("", TextRange(0)).typed('1') shouldBe TextFieldValue("1", TextRange(1))
    }

    @Test
    fun `a key replaces the selection`() {
        TextFieldValue("91-93", TextRange(3, 5)).typed('4') shouldBe TextFieldValue("91-4", TextRange(4))
    }

    @Test
    fun `erase removes the character before the cursor, or the selection`() {
        TextFieldValue("105-107", TextRange(4)).erased() shouldBe TextFieldValue("105107", TextRange(3))
        TextFieldValue("105-107", TextRange(0, 4)).erased() shouldBe TextFieldValue("107", TextRange(0))
        TextFieldValue("12", TextRange(0)).erased() shouldBe TextFieldValue("12", TextRange(0))
    }
}
// Azit <--
