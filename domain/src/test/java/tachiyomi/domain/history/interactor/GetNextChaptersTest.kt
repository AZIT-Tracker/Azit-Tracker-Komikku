package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga

// Azit -->
@Execution(ExecutionMode.CONCURRENT)
class GetNextChaptersTest {

    private fun chapter(number: Int, read: Boolean = false) = Chapter.create().copy(
        id = number.toLong(),
        mangaId = 1L,
        chapterNumber = number.toDouble(),
        read = read,
    )

    private val chapters = listOf(chapter(1, read = true), chapter(2, read = true), chapter(3), chapter(4), chapter(5))

    private fun getNextChapters(hidden: Set<Long>, showHidden: Boolean = false): GetNextChapters {
        val flags = Manga.CHAPTER_SORTING_NUMBER or if (showHidden) Manga.CHAPTER_SHOW_HIDDEN else 0L
        val manga = Manga.create().copy(id = 1L, source = 1L, chapterFlags = flags)
        val getManga = mockk<GetManga> { coEvery { await(1L) } returns manga }
        val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(1L, true) } returns chapters.shuffled() }
        return GetNextChapters(getChapters, mockk(), getManga, mockk(), getHiddenChapterIds = { hidden })
    }

    @Test
    fun `hidden chapters are never the next chapter`() = runTest {
        getNextChapters(hidden = setOf(3L)).await(1L).map { it.id } shouldBe listOf(4L, 5L)
    }

    @Test
    fun `without hidden chapters nothing changes`() = runTest {
        getNextChapters(hidden = emptySet()).await(1L).map { it.id } shouldBe listOf(3L, 4L, 5L)
    }

    @Test
    fun `a series that shows its hidden chapters offers them`() = runTest {
        getNextChapters(hidden = setOf(3L), showHidden = true).await(1L).map { it.id } shouldBe listOf(3L, 4L, 5L)
    }

    @Test
    fun `reading a hidden chapter, the next ones come after it, not from the start`() = runTest {
        val next = getNextChapters(hidden = setOf(4L)).await(1L, fromChapterId = 4L, onlyUnread = false)
        next.map { it.id } shouldBe listOf(4L, 5L)
    }
}
// Azit <--
