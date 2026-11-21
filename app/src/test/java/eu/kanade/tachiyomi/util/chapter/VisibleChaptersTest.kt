package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

// Azit -->
@Execution(ExecutionMode.CONCURRENT)
class VisibleChaptersTest {

    private fun chapter(id: Long, number: Double, uploaded: Long = id) =
        Chapter.create().copy(id = id, mangaId = 1L, chapterNumber = number, dateUpload = uploaded)

    private fun manga(vararg flags: Long) = Manga.create().copy(id = 1L, chapterFlags = flags.fold(0L) { a, b -> a or b })

    // Two copies of chapter 2 (ids 2 and 3), chapter 2.5, and an unnumbered extra.
    private val chapters = listOf(chapter(1, 1.0), chapter(2, 2.0), chapter(3, 2.0), chapter(4, 2.5), chapter(5, -1.0))

    private fun List<Chapter>.ids() = map { it.id }

    @Test
    fun `duplicates keep the first uploaded copy, other numbers untouched`() {
        chapters.keepOneChapterEach(keepLast = false) { it }.ids() shouldBe listOf(1L, 2L, 4L, 5L)
    }

    @Test
    fun `duplicates can keep the last uploaded copy`() {
        chapters.keepOneChapterEach(keepLast = true) { it }.ids() shouldBe listOf(1L, 3L, 4L, 5L)
    }

    @Test
    fun `hidden chapters are left out`() {
        chapters.visibleChapters(manga(), setOf(1L, 4L)) { it }.ids() shouldBe listOf(2L, 3L, 5L)
    }

    @Test
    fun `a series that shows its hidden chapters keeps them`() {
        chapters.visibleChapters(manga(Manga.CHAPTER_SHOW_HIDDEN), setOf(1L, 4L)) { it }.ids() shouldBe chapters.ids()
    }

    @Test
    fun `a hidden copy never stands for its duplicates`() {
        // Hiding the first copy of chapter 2 shows the other one instead of losing chapter 2.
        chapters.visibleChapters(manga(Manga.CHAPTER_HIDE_DUPLICATES), setOf(2L)) { it }.ids() shouldBe listOf(1L, 3L, 4L, 5L)
    }

    @Test
    fun `without hidden chapters or duplicates filter nothing changes`() {
        chapters.visibleChapters(manga(), emptySet()) { it }.ids() shouldBe chapters.ids()
    }
}
// Azit <--
