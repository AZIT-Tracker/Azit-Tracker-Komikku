package eu.kanade.domain.track.interactor

import eu.kanade.domain.chapter.interactor.GetChapterEditions
import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.service.TrackingQueue
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterEdition
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

class SyncChapterProgressWithTrack(
    private val updateChapter: UpdateChapter,
    private val insertTrack: InsertTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    // KMK -->
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val deleteDownload: DeleteDownload = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    // KMK <--
    // Azit -->
    /** The chapters counted as something else (Chapter edition): trackers get their last number. */
    private val getChapterEditions: suspend (Long) -> Map<Long, ChapterEdition> = { mangaId ->
        Injekt.get<GetChapterEditions>().await(mangaId)
    },
    // Azit <--
) {
    /**
     * Sync chapter progress with the [EnhancedTracker]
     */
    suspend fun await(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ): Int? {
        if (tracker !is EnhancedTracker) {
            return null
        }
        // KMK -->
        return sync(mangaId, remoteTrack, tracker)
    }

    /**
     * Sync chapter progress with the all trackers.
     */
    suspend fun sync(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ): Int? {
        // KMK <--
        // Current chapters in database, sort by source's order because database's order is a mess
        val dbChapters = getChaptersByMangaId.await(mangaId)
            // KMK -->
            .sortedByDescending { it.sourceOrder }
            // KMK <--
            .filter { it.isRecognizedNumber }
        // Azit -->: an edited chapter counts as the last number given to it
        val editions = getChapterEditions(mangaId)
        val numberOf = { chapter: Chapter -> editions[chapter.id]?.trackNumber ?: chapter.chapterNumber }
        // Azit <--

        val sortedChapters = dbChapters
            .sortedBy {
                /* Azit --> */ numberOf(it) /* Azit <-- */
            }

        // KMK -->
        var lastCheckChapter: Double
        var checkingChapter = 0.0

        /**
         * Chapters to update to follow tracker: only continuous incremental chapters
         * any abnormal chapter number will stop it from updating read status further.
         * Some mangas has name such as Volume 2 Chapter 1 which will corrupt the order
         * if we sort by chapterNumber.
         */
        val chaptersToMarkRead = dbChapters
            .takeWhile { chapter ->
                lastCheckChapter = checkingChapter
                // Azit -->
                checkingChapter = numberOf(chapter)
                checkingChapter >= lastCheckChapter && checkingChapter <= remoteTrack.lastChapterRead
                // Azit <--
            }
            .filter { chapter -> !chapter.read }
        val chapterUpdates = chaptersToMarkRead
            // KMK <--
            .map { it.copy(read = true).toChapterUpdate() }

        // only take into account continuous reading
        val localLastRead = sortedChapters.takeWhile { it.read }.lastOrNull()
            ?.let {
                /* Azit --> */ numberOf(it) /* Azit <-- */
            } ?: 0F
        // Tracker will update to latest read chapter
        val lastRead = max(remoteTrack.lastChapterRead, localLastRead.toDouble())
        val updatedTrack = remoteTrack.copy(lastChapterRead = lastRead)

        try {
            // Update Tracker to localLastRead if needed
            if (lastRead > remoteTrack.lastChapterRead) {
                tracker.update(updatedTrack.toDbTrack())
                // update Track in database
                insertTrack.await(updatedTrack)
            }
            // KMK -->
            TrackingQueue.onSuccess(tracker.id) // it answered (the refresh before this): send what it missed
            // KMK <--
            // KMK -->
            // Always update local chapters following Tracker even past chapters
            if (chapterUpdates.isNotEmpty() &&
                !tracker.hasNotStartedReading(remoteTrack.status)
            ) {
                updateChapter.awaitAll(chapterUpdates)
                deleteAutoMarkedRead(mangaId, chaptersToMarkRead)
                return lastRead.toInt()
            }
            // KMK <--
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
        // KMK -->
        return null
        // KMK <--
    }

    // KMK -->
    /**
     * Chapters the tracker sync just marked as read are deleted like manually marked ones, when both
     * "after manually marked as read" and "after automatically marked as read" are on. Excluded categories and
     * bookmarked chapters are respected by the download manager, as for a manual deletion.
     */
    private suspend fun deleteAutoMarkedRead(mangaId: Long, chapters: List<Chapter>) {
        if (chapters.isEmpty()) return
        if (!downloadPreferences.removeAfterMarkedAsRead().get()) return
        if (!downloadPreferences.removeAfterAutoMarkedAsRead().get()) return
        try {
            val manga = getManga.await(mangaId) ?: return
            deleteDownload.awaitAll(manga, *chapters.map { it.copy(read = true) }.toTypedArray())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not delete chapters marked as read by the tracker sync" }
        }
    }
    // KMK <--
}
