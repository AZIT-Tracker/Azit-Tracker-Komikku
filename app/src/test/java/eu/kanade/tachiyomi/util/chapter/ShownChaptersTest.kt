package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

// Azit -->
/** Downloads follow the series' list: the badge counts what it shows, and a read chapter takes its hidden copies. */
@Execution(ExecutionMode.CONCURRENT)
class ShownChaptersTest {

    private fun chapter(id: Long, number: Double, mangaId: Long = 1L, uploaded: Long = id) = Chapter.create().copy(
        id = id,
        mangaId = mangaId,
        chapterNumber = number,
        dateUpload = uploaded,
    )

    private fun series(vararg flags: Long) = Manga.create().copy(id = 1L, chapterFlags = flags.fold(0L) { a, b -> a or b })

    @Test
    fun `a hidden duplicate isn't shown, so its download isn't counted`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 1.0), chapter(3, 2.0))

        chapters.shownChapterIds(series(Manga.CHAPTER_HIDE_DUPLICATES), emptySet()) shouldBe setOf(1L, 3L)
        chapters.shownChapterIds(series(), emptySet()) shouldBe setOf(1L, 2L, 3L)
    }

    @Test
    fun `a chapter hidden one by one isn't shown unless the series shows hidden chapters`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 2.0))

        chapters.shownChapterIds(series(), setOf(2L)) shouldBe setOf(1L)
        chapters.shownChapterIds(series(Manga.CHAPTER_SHOW_HIDDEN), setOf(2L)) shouldBe setOf(1L, 2L)
    }

    @Test
    fun `reading a chapter takes the copies its list hides`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 1.0), chapter(3, 1.0), chapter(4, 2.0))
        val shown = chapters.shownChapterIds(series(Manga.CHAPTER_HIDE_DUPLICATES), emptySet())

        chapters.hiddenCopiesOf(chapters[0], shown).map { it.id } shouldContainExactlyInAnyOrder listOf(2L, 3L)
    }

    @Test
    fun `copies the list shows stay, as do other chapters and other series`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 1.0), chapter(3, 2.0), chapter(4, 1.0, mangaId = 2L))
        val shown = chapters.shownChapterIds(series(), emptySet())

        chapters.hiddenCopiesOf(chapters[0], shown).shouldBeEmpty()
    }

    @Test
    fun `a copy hidden one by one goes with the chapter read`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 1.0))
        val shown = chapters.shownChapterIds(series(), setOf(2L))

        chapters.hiddenCopiesOf(chapters[0], shown).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a chapter without a number has no copies`() {
        val chapters = listOf(chapter(1, -1.0), chapter(2, -1.0))

        chapters.hiddenCopiesOf(chapters[0], emptySet()).shouldBeEmpty()
    }
}
// Azit <--
