package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.chapter.interactor.GetChapterEditions
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.service.TrackingQueue
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.source.model.SManga
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.service.finishedSeriesTotal
import tachiyomi.domain.track.service.totalWithFinished
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    /**
     * Fetches updated tracking data from all logged in trackers.
     * Then update chapter progress to all trackers.
     * This does not update local chapters' read status.
     */
    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            // KMK -->
            // Azit Tracker links a library manga by itself on the first chapter read.
            trackerManager.azit.autoBind(mangaId)
            // KMK <--
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext
            // Azit -->: the last chapter of a finished series completes the entry, even where the tracker has no
            // chapter count (it gets the series' own)
            val finishedTotal = finishedTotal(mangaId, chapterNumber)
            // Azit <--

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    chapterNumber <= track.lastChapterRead /* SY --> */ ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)/* SY <-- */
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val updatedTrack = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastChapterRead = chapterNumber)
                                // Azit -->
                                .let { it.copy(totalChapters = totalWithFinished(it.totalChapters, finishedTotal)) }
                            // Azit <--
                            service.update(updatedTrack.toDbTrack(), true)
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                            // KMK -->
                            TrackingQueue.onSuccess(track.trackerId) // it answers again: send what it missed
                            // KMK <--
                        } catch (e: Exception) {
                            // Azit -->: kept here so the progress sent later still completes the entry
                            if (finishedTotal != null && track.totalChapters <= 0) {
                                runCatching { insertTrack.await(track.copy(totalChapters = finishedTotal)) }
                            }
                            // Azit <--
                            delayedTrackingStore.add(track.id, chapterNumber)
                            // KMK -->
                            delayedTrackingStore.recordFailure(track.id, e.message ?: e::class.simpleName)
                            // KMK <--
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }

    // Azit -->
    /** The chapter count that completes the entry when [chapterNumber] ends a finished series, else null. */
    private suspend fun finishedTotal(mangaId: Long, chapterNumber: Double): Long? {
        return runCatching {
            val manga = Injekt.get<GetManga>().await(mangaId) ?: return null
            val finished = manga.status == SManga.COMPLETED.toLong() ||
                manga.status == SManga.PUBLISHING_FINISHED.toLong()
            if (!finished) return null
            val chapters = Injekt.get<GetChaptersByMangaId>().await(mangaId)
                .let { Injekt.get<GetChapterEditions>().applyTo(mangaId, it) }
            finishedSeriesTotal(true, chapters.map { it.chapterNumber }, chapterNumber)
        }.getOrNull()
    }
    // Azit <--
}
