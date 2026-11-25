package eu.kanade.domain.chapter.model

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterEdition
import tachiyomi.domain.chapter.model.coveredNumbers
import tachiyomi.domain.chapter.model.missingBetween
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.manga.model.Manga

// Azit -->
/** Sorting "by chapter number" uses the number given in Chapter edition. */
class ChapterEditionSortTest {

    private fun item(id: Long, number: Double, edition: ChapterEdition? = null) = ChapterList.Item(
        chapter = Chapter.create().copy(id = id, chapterNumber = number, sourceOrder = id),
        downloadState = Download.State.NOT_DOWNLOADED,
        downloadProgress = 0,
        edition = edition,
        sourceName = null,
        showScanlator = false,
    )

    private val items = listOf(
        // The source numbers them 100, 101, 102; edited to 3, 1, 2.
        item(1, 100.0, ChapterEdition(1, false, 3.0)),
        item(2, 101.0, ChapterEdition(2, false, 1.0)),
        item(3, 102.0, ChapterEdition(3, false, 2.0)),
        // Not edited: keeps its own number.
        item(4, 2.5),
    )

    private fun manga(direction: Long) = Manga.create().copy(
        chapterFlags = Manga.CHAPTER_SORTING_NUMBER or direction,
    )

    @Test
    fun `by chapter number ascending follows the edited numbers`() {
        items.sortedWith(items.itemSort(manga(Manga.CHAPTER_SORT_ASC))).map { it.id } shouldBe listOf(2L, 3L, 4L, 1L)
    }

    @Test
    fun `by chapter number descending follows the edited numbers`() {
        items.sortedWith(items.itemSort(manga(Manga.CHAPTER_SORT_DESC))).map { it.id } shouldBe listOf(1L, 4L, 3L, 2L)
    }

    /**
     * Like Golgo 13: every volume is "Ch. 0" at the source; volumes 5-8 are left as they are, volumes 9-11 are
     * edited to 39-43, 44-49 and 50-53.
     */
    private val volumes = listOf(
        item(5, 0.0),
        item(6, 0.0),
        item(7, 0.0),
        item(8, 0.0),
        item(9, 0.0, ChapterEdition(9, true, 39.0, 43.0)),
        item(10, 0.0, ChapterEdition(10, true, 44.0, 49.0)),
        item(11, 0.0, ChapterEdition(11, true, 50.0, 53.0)),
    )

    private fun sorted(direction: Long) = volumes.sortedWith(volumes.itemSort(manga(direction)))

    private fun missingRows(list: List<ChapterList>) = list.mapIndexedNotNull { index, row ->
        (row as? ChapterList.MissingCount)?.let { index to it.count }
    }

    @Test
    fun `descending puts the highest edited volume on top`() {
        sorted(Manga.CHAPTER_SORT_DESC).map { it.id }.take(3) shouldBe listOf(11L, 10L, 9L)
    }

    @Test
    fun `edited volumes that follow each other leave nothing missing`() {
        missingBetween(39.0, 43.0, 44.0, 49.0) shouldBe 0
        missingBetween(44.0, 49.0, 39.0, 43.0) shouldBe 0
        missingBetween(50.0, 53.0, 39.0, 43.0) shouldBe 6
    }

    @Test
    fun `descending list only shows the chapters missing before 39`() {
        val list = sorted(Manga.CHAPTER_SORT_DESC).withMissingCounts(sortDescending = true)

        // 11, 10, 9, [38 missing], 8, 7, 6, 5: nothing above the top volume, nothing between the edited ones.
        missingRows(list) shouldBe listOf(3 to 38)
    }

    @Test
    fun `ascending list only shows the chapters missing before 39`() {
        val list = sorted(Manga.CHAPTER_SORT_ASC).withMissingCounts(sortDescending = false)

        // 5, 6, 7, 8, [38 missing], 9, 10, 11.
        missingRows(list) shouldBe listOf(4 to 38)
    }

    @Test
    fun `the highest chapter never gets the before-the-first row`() {
        // The top of a descending list is the highest chapter: no "110 missing" above volume 31 (111-113).
        val list = listOf(
            item(31, 0.0, ChapterEdition(31, true, 111.0, 113.0)),
            item(9, 0.0, ChapterEdition(9, true, 39.0, 43.0)),
        ).withMissingCounts(sortDescending = false)

        missingRows(list) shouldBe listOf(1 to 67)
    }

    @Test
    fun `the header counts what the edited volumes cover`() {
        val covered = volumes.flatMap { it.edition.coveredNumbers(it.chapter.chapterNumber) }

        // 0 (volumes 5-8) and 39-53: chapters 1-38 are missing, nothing else.
        covered.missingChaptersCount() shouldBe 38
    }

    @Test
    fun `chapters without an edition keep the usual count`() {
        val list = listOf(item(1, 3.0), item(2, 1.0)).withMissingCounts(sortDescending = true)

        missingRows(list) shouldBe listOf(1 to 1)
    }
}
// Azit <--
