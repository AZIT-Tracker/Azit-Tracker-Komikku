package eu.kanade.domain.track.service

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.azit.Azit
import exh.log.xLogD
import exh.log.xLogW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

// KMK -->
/**
 * The progress waiting to be sent ([DelayedTrackingStore]: one entry per manga and tracker), sent tracker by tracker.
 *
 * As soon as a tracker accepts something (a chapter read, a change in the tracking sheet, a sync), what was kept for
 * that tracker while it could not be reached goes out right away ([onSuccess]); one tracker being down never holds
 * back the others. [DelayedTrackingUpdateJob] is the safety net when nothing is sent for a while.
 */
object TrackingQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    private val store: DelayedTrackingStore by lazy { Injekt.get() }
    private val getTracks: GetTracks by lazy { Injekt.get() }
    private val insertTrack: InsertTrack by lazy { Injekt.get() }
    private val trackerManager: TrackerManager by lazy { Injekt.get() }

    /** [trackerId] just answered: send what waits for it, in the background (once at a time per tracker). */
    fun onSuccess(trackerId: Long) {
        if (store.getItems().isEmpty()) return
        scope.launch { flush(trackerId) }
    }

    /** Send everything waiting, tracker by tracker (the "Retry now" button). */
    suspend fun flushAll() {
        waitingTrackers().forEach { flush(it) }
    }

    /** The trackers that have something waiting. */
    suspend fun waitingTrackers(): Set<Long> =
        store.getItems().mapNotNull { getTracks.awaitOne(it.trackId)?.trackerId }.toSet()

    /**
     * Send everything waiting for [trackerId]; stops at its first failure (still unreachable: the rest stays).
     * Returns true when nothing is left for it.
     */
    suspend fun flush(trackerId: Long): Boolean {
        val lock = locks.getOrPut(trackerId) { Mutex() }
        if (lock.isLocked) return false // already being sent
        return lock.withLock {
            val service = trackerManager.get(trackerId) ?: return@withLock true
            if (!service.isLoggedIn) return@withLock false
            if (service is Azit) return@withLock flushAzit(service) // our own tracker: everything in one request
            for (item in store.getItems()) {
                val track = getTracks.awaitOne(item.trackId)
                if (track == null) {
                    store.remove(item.trackId) // the manga is no longer tracked
                    continue
                }
                if (track.trackerId != trackerId) continue
                try {
                    val remote = service.refresh(track.toDbTrack()).toDomainTrack(idRequired = true)!!
                    val chapter = item.lastChapterRead.toDouble()
                    if (chapter > remote.lastChapterRead) { // progress only goes forward
                        val updated = remote.copy(lastChapterRead = chapter)
                        service.update(updated.toDbTrack(), true)
                        insertTrack.await(updated)
                    }
                    store.remove(item.trackId)
                    xLogD("Sent waiting progress: track ${item.trackId}, chapter $chapter")
                } catch (e: Exception) {
                    xLogW("Waiting progress for tracker $trackerId still not sent: ${e.message}")
                    store.recordFailure(item.trackId, e.message ?: e::class.simpleName)
                    return@withLock false
                }
            }
            true
        }
    }

    /** Everything waiting for Azit, in one request; each title answered on its own. */
    private suspend fun flushAzit(azit: Azit): Boolean {
        val waiting = store.getItems().mapNotNull { item ->
            val track = getTracks.awaitOne(item.trackId)
            if (track == null) {
                store.remove(item.trackId)
                null
            } else if (track.trackerId != azit.id) {
                null
            } else {
                item to track.copy(lastChapterRead = item.lastChapterRead.toDouble())
            }
        }
        if (waiting.isEmpty()) return true
        val results = try {
            azit.updateMany(waiting.map { (_, track) -> track.toDbTrack() })
        } catch (e: Exception) {
            xLogW("Waiting progress for Azit still not sent: ${e.message}")
            waiting.forEach { (item, _) -> store.recordFailure(item.trackId, e.message ?: e::class.simpleName) }
            return false
        }
        var allSent = true
        waiting.zip(results).forEach { (pair, result) ->
            val (item, _) = pair
            result.onSuccess { updated ->
                updated.toDomainTrack(idRequired = true)?.let { insertTrack.await(it) }
                store.remove(item.trackId)
            }.onFailure { error ->
                allSent = false
                store.recordFailure(item.trackId, error.message ?: error::class.simpleName)
            }
        }
        xLogD("Sent waiting progress to Azit: ${results.count { it.isSuccess }} of ${results.size}")
        return allSent
    }
}
// KMK <--
