package eu.kanade.tachiyomi.util.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import kotlin.random.Random

// Azit -->
/** The library's unread badge of a series hiding its duplicates matches the chapters its list shows. */
@Execution(ExecutionMode.CONCURRENT)
class LibraryVisibleCountsTest {

    private fun chapter(
        id: Long,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        scanlator: String? = null,
        uploaded: Long = id,
    ) = Chapter.create().copy(
        id = id,
        mangaId = 1L,
        chapterNumber = number,
        read = read,
        bookmark = bookmark,
        scanlator = scanlator,
        dateUpload = uploaded,
    )

    /** A library series as the database counts it: every chapter, duplicates included. */
    private fun library(chapters: List<Chapter>, vararg flags: Long): LibraryManga {
        val chapterFlags = flags.fold(0L) { a, b -> a or b }
        return LibraryManga(
            manga = Manga.create().copy(id = 1L, chapterFlags = chapterFlags),
            categories = emptyList(),
            totalChapters = chapters.size.toLong(),
            readCount = chapters.count { it.read }.toLong(),
            bookmarkCount = chapters.count { it.bookmark }.toLong(),
            bookmarkReadCount = chapters.count { it.bookmark && it.read }.toLong(),
            chapterFlags = chapterFlags,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        )
    }

    private fun LibraryManga.counted(
        chapters: List<Chapter>,
        hidden: Set<Long> = emptySet(),
        excluded: Set<String> = emptySet(),
    ) = withVisibleCounts(chapters, hidden, excluded)

    @Test
    fun `two unread copies of one chapter count once (The Archmage Returns case)`() {
        val chapters = listOf(chapter(1, 1.0, scanlator = "A"), chapter(2, 1.0, scanlator = "B"))
        val counted = library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters)

        counted.unreadCount shouldBe 1
        counted.totalChapters shouldBe 1
    }

    @Test
    fun `the copy shown decides read or unread, first uploaded by default`() {
        // Chapter 2: the first copy is read, the later one is not.
        val chapters = listOf(chapter(1, 1.0, read = true), chapter(2, 2.0, read = true), chapter(3, 2.0))

        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters).unreadCount shouldBe 0
        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES, Manga.CHAPTER_DUPLICATES_KEEP_LAST)
            .counted(chapters).unreadCount shouldBe 1
    }

    @Test
    fun `chapters hidden one by one are never counted`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 2.0), chapter(3, 3.0))

        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters, hidden = setOf(3L)).unreadCount shouldBe 2
    }

    @Test
    fun `a hidden copy lets its duplicate count instead`() {
        // Chapter 1 has two copies; the first one is hidden: the other copy (unread) is the one shown and counted.
        val chapters = listOf(chapter(1, 1.0, read = true), chapter(2, 1.0))

        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters, hidden = setOf(1L)).unreadCount shouldBe 1
    }

    @Test
    fun `excluded scanlators are never counted`() {
        val chapters = listOf(chapter(1, 1.0, scanlator = "Bad"), chapter(2, 2.0, scanlator = "Good"), chapter(3, 3.0))

        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters, excluded = setOf("Bad")).unreadCount shouldBe 2
    }

    @Test
    fun `chapters without a number are all counted`() {
        val chapters = listOf(chapter(1, -1.0), chapter(2, -1.0), chapter(3, 1.0))

        library(chapters, Manga.CHAPTER_HIDE_DUPLICATES).counted(chapters).unreadCount shouldBe 3
    }

    @Test
    fun `the bookmark filters of the badge still apply to the chapters shown`() {
        // Chapter 1 twice (first copy bookmarked), chapter 2 bookmarked, chapter 3 not.
        val chapters = listOf(
            chapter(1, 1.0, bookmark = true),
            chapter(2, 1.0),
            chapter(3, 2.0, bookmark = true),
            chapter(4, 3.0),
        )
        val bookmarked = library(chapters, Manga.CHAPTER_HIDE_DUPLICATES, Manga.CHAPTER_SHOW_BOOKMARKED)
        val notBookmarked = library(chapters, Manga.CHAPTER_HIDE_DUPLICATES, Manga.CHAPTER_SHOW_NOT_BOOKMARKED)

        bookmarked.counted(chapters).unreadCount shouldBe 2
        notBookmarked.counted(chapters).unreadCount shouldBe 1
    }

    @Test
    fun `a series that doesn't hide duplicates keeps the library's counts`() {
        val chapters = listOf(chapter(1, 1.0), chapter(2, 1.0))
        val series = library(chapters)

        series.counted(chapters) shouldBe series
        series.counted(chapters).unreadCount shouldBe 2
    }

    @Test
    fun `the badge always equals the unread chapters the list shows`() {
        // Many random series: whatever the copies, read states, hidden chapters and keep option, the badge is the
        // number of unread chapters the series' own list shows.
        val random = Random(42)
        repeat(500) {
            val chapters = (1L..random.nextLong(1, 30)).map { id ->
                chapter(
                    id = id,
                    number = if (random.nextInt(10) == 0) -1.0 else random.nextInt(1, 10).toDouble(),
                    read = random.nextBoolean(),
                    uploaded = random.nextLong(1, 1000),
                )
            }
            val hidden = chapters.filter { random.nextInt(6) == 0 }.map { it.id }.toSet()
            val flags = Manga.CHAPTER_HIDE_DUPLICATES or (if (random.nextBoolean()) Manga.CHAPTER_DUPLICATES_KEEP_LAST else 0)
            val series = library(chapters, flags)

            val shownInList = chapters.visibleChapters(series.manga, hidden) { it }

            series.counted(chapters, hidden).unreadCount shouldBe shownInList.count { !it.read }.toLong()
            series.counted(chapters, hidden).totalChapters shouldBe shownInList.size.toLong()
        }
    }
}
// Azit <--
