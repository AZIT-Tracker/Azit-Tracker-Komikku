package eu.kanade.domain.track.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.track.service.TrackingQueue
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterEdition
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

// Azit -->
/** Trackers see an edited chapter (Chapter edition) as the last number given to it. */
class SyncChapterEditionTest {

    private val manga = Manga.create().copy(id = 1L)

    private fun chapters(readUpTo: Int) = (1..5).map { number ->
        Chapter.create().copy(
            id = number.toLong(),
            mangaId = 1L,
            chapterNumber = number.toDouble(),
            sourceOrder = (5 - number).toLong(),
            read = number <= readUpTo,
        )
    }

    private fun track(lastChapterRead: Double) = Track(
        id = 1, mangaId = 1, trackerId = 2, remoteId = 1, libraryId = null, title = "x",
        lastChapterRead = lastChapterRead, totalChapters = 0, status = 1, score = 0.0, remoteUrl = "", startDate = 0,
        finishDate = 0, private = false,
    )

    private val sent = slot<DbTrack>()
    private val updated = mutableListOf<ChapterUpdate>()

    private val tracker = mockk<Tracker> {
        every { hasNotStartedReading(any()) } returns false
        every { id } returns 2L
        coEvery { update(capture(sent), any()) } answers { firstArg() }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(TrackingQueue)
        every { TrackingQueue.onSuccess(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(TrackingQueue)
    }

    private fun sync(chapters: List<Chapter>, editions: Map<Long, ChapterEdition>, remote: Track): Int? {
        val falsePref = mockk<Preference<Boolean>> { every { get() } returns false }
        val sync = SyncChapterProgressWithTrack(
            updateChapter = mockk<UpdateChapter> {
                coEvery { awaitAll(any()) } answers {
                    @Suppress("UNCHECKED_CAST")
                    updated += firstArg<List<ChapterUpdate>>()
                }
            },
            insertTrack = mockk<InsertTrack>(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId> { coEvery { await(1L, any()) } returns chapters },
            downloadPreferences = mockk<DownloadPreferences> {
                every { removeAfterMarkedAsRead() } returns falsePref
                every { removeAfterAutoMarkedAsRead() } returns falsePref
            },
            deleteDownload = mockk<DeleteDownload>(relaxed = true),
            getManga = mockk<GetManga> { coEvery { await(1L) } returns manga },
            getChapterEditions = { editions },
        )
        return runBlocking { sync.sync(1L, remote, tracker) }
    }

    @Test
    fun `a read volume sends the last number of its range`() {
        // Chapters 1-3 read, chapter 3 is the volume holding chapters 3-10 (4 and 5 follow as 11, 12): the tracker
        // (at 2) goes to 10.
        val editions = mapOf(
            3L to ChapterEdition(3, true, 3.0, 10.0),
            4L to ChapterEdition(4, false, 11.0),
            5L to ChapterEdition(5, false, 12.0),
        )
        sync(chapters(readUpTo = 3), editions, track(2.0))

        sent.captured.last_chapter_read shouldBe 10.0
    }

    @Test
    fun `a chapter given another number sends that number`() {
        // Chapters 1-5 count as 40-44, chapter 1 is read: the tracker (at 0) goes to 40.
        val editions = (1L..5L).associateWith { ChapterEdition(it, false, it + 39.0) }
        sync(chapters(readUpTo = 1), editions, track(0.0))

        sent.captured.last_chapter_read shouldBe 40.0
    }

    @Test
    fun `without editions the chapter's own number is sent`() {
        sync(chapters(readUpTo = 3), emptyMap(), track(2.0))

        sent.captured.last_chapter_read shouldBe 3.0
    }

    @Test
    fun `the tracker's progress marks chapters read by their edited number`() {
        // Chapters 1-5 renumbered 11-15; the tracker is at 13: chapters 1-3 become read, 4-5 stay unread.
        val editions = (1L..5L).associateWith { ChapterEdition(it, false, it + 10.0) }

        sync(chapters(readUpTo = 0), editions, track(13.0)) shouldBe 13

        updated.map { it.id }.sorted() shouldBe listOf(1L, 2L, 3L)
    }

    private fun chaptersFromZero() = (0..3).map { number ->
        Chapter.create().copy(
            id = number.toLong() + 10,
            mangaId = 1L,
            chapterNumber = number.toDouble(),
            sourceOrder = (3 - number).toLong(),
            read = false,
        )
    }

    @Test
    fun `chapter 0 stays unread while the tracker is at 0`() {
        sync(chaptersFromZero(), emptyMap(), track(0.0))

        updated shouldBe emptyList()
    }

    @Test
    fun `chapter 0 is marked read once the tracker reaches chapter 1`() {
        sync(chaptersFromZero(), emptyMap(), track(1.0))

        updated.map { it.id }.sorted() shouldBe listOf(10L, 11L)
    }
}
// Azit <--
