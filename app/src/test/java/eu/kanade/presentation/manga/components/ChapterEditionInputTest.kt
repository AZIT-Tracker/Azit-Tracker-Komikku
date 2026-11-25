package eu.kanade.presentation.manga.components

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Azit -->
/** The Chapter edition boxes only take what a chapter number or a volume range holds. */
class ChapterEditionInputTest {

    @Test
    fun `digits, dot, comma and dash are kept`() {
        "105-107".onlyNumberCharacters() shouldBe "105-107"
        "10.5".onlyNumberCharacters() shouldBe "10.5"
        "10,5".onlyNumberCharacters() shouldBe "10,5"
    }

    @Test
    fun `anything else is dropped`() {
        "Ch. 12 a+b (3)".onlyNumberCharacters() shouldBe ".123"
        " 2 - 4 ".onlyNumberCharacters() shouldBe "2-4"
    }
}
// Azit <--
