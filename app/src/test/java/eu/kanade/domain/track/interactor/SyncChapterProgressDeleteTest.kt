package eu.kanade.domain.track.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

/** Chapters marked as read by the tracker sync are deleted only with both "delete after marked as read" options. */
class SyncChapterProgressDeleteTest {

    private val manga = Manga.create().copy(id = 1L)

    /** Chapters 1-5 in Komikku, 1-2 already read; the tracker says 4 chapters are read. */
    private val chapters = (1..5).map { number ->
        Chapter.create().copy(
            id = number.toLong(),
            mangaId = 1L,
            chapterNumber = number.toDouble(),
            sourceOrder = (5 - number).toLong(),
            read = number <= 2,
        )
    }

    private val remoteTrack = Track(
        id = 1, mangaId = 1, trackerId = 2, remoteId = 1, libraryId = null, title = "x", lastChapterRead = 4.0,
        totalChapters = 0, status = 1, score = 0.0, remoteUrl = "", startDate = 0, finishDate = 0, private = false,
    )

    private fun boolPref(value: Boolean): Preference<Boolean> = mockk { every { get() } returns value }

    /** Chapters passed to each deletion. */
    private val deleted = mutableListOf<List<Chapter>>()

    private fun run(removeAfterMarked: Boolean, removeAfterAutoMarked: Boolean) {
        val deleteDownload = mockk<DeleteDownload> {
            coEvery { awaitAll(any(), *anyVararg()) } answers {
                @Suppress("UNCHECKED_CAST")
                deleted += (args[1] as Array<Chapter>).toList()
            }
        }
        val preferences = mockk<DownloadPreferences> {
            every { removeAfterMarkedAsRead() } returns boolPref(removeAfterMarked)
            every { removeAfterAutoMarkedAsRead() } returns boolPref(removeAfterAutoMarked)
        }
        val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(1L, any()) } returns chapters }
        val getManga = mockk<GetManga> { coEvery { await(1L) } returns manga }
        val tracker = mockk<Tracker> { every { hasNotStartedReading(any()) } returns false }
        val sync = SyncChapterProgressWithTrack(
            updateChapter = mockk<UpdateChapter>(relaxed = true),
            insertTrack = mockk<InsertTrack>(relaxed = true),
            getChaptersByMangaId = getChapters,
            downloadPreferences = preferences,
            deleteDownload = deleteDownload,
            getManga = getManga,
        )

        runBlocking { sync.sync(1L, remoteTrack, tracker) } shouldBe 4
    }

    @Test
    fun `both options delete the chapters the sync marked as read`() {
        run(removeAfterMarked = true, removeAfterAutoMarked = true)

        // Only chapters 3 and 4: 1-2 were already read, 5 is past the tracker.
        deleted.single().map { it.id }.sorted() shouldBe listOf(3L, 4L)
        deleted.single().all { it.read } shouldBe true
    }

    @Test
    fun `without the automatic option nothing is deleted`() {
        run(removeAfterMarked = true, removeAfterAutoMarked = false)

        deleted shouldBe emptyList()
    }

    @Test
    fun `without the manual option nothing is deleted`() {
        run(removeAfterMarked = false, removeAfterAutoMarked = true)

        deleted shouldBe emptyList()
    }
}
