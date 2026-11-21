package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

// Azit -->
@Execution(ExecutionMode.CONCURRENT)
class HiddenChaptersTest {

    private val ids = listOf(1L, 2L, 3L, 4L)

    @Test
    fun `hidden chapters are left out, in the same order`() {
        ids.withoutHiddenChapters(setOf(2L, 4L)) { it } shouldBe listOf(1L, 3L)
    }

    @Test
    fun `nothing hidden keeps everything`() {
        ids.withoutHiddenChapters(emptySet()) { it } shouldBe ids
    }

    @Test
    fun `a series that shows its hidden chapters keeps them`() {
        ids.withoutHiddenChapters(setOf(2L), showHidden = true) { it } shouldBe ids
    }

    @Test
    fun `the chapter being opened stays even if hidden`() {
        ids.withoutHiddenChapters(setOf(2L, 3L), keepId = 3L) { it } shouldBe listOf(1L, 3L, 4L)
    }
}
// Azit <--
