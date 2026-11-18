package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class DelayedTrackingStore(context: Context) {

    /**
     * Preference file where queued tracking updates are stored.
     */
    private val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)

    // KMK -->
    /** Why each waiting update could not be sent: "<time ms>|<message>", per track. */
    private val failures = context.getSharedPreferences("tracking_queue_failures", Context.MODE_PRIVATE)

    /** Bumped on every change, so the waiting list on screen follows. */
    val changes = MutableStateFlow(0)

    fun recordFailure(trackId: Long, message: String?) {
        failures.edit { putString(trackId.toString(), "${System.currentTimeMillis()}|${message.orEmpty().take(300)}") }
        changes.value++
    }

    /** (when, why) of the last failed send of this track, if it waits. */
    fun failure(trackId: Long): Pair<Long, String>? {
        val raw = failures.getString(trackId.toString(), null) ?: return null
        return raw.substringBefore('|').toLongOrNull()?.let { it to raw.substringAfter('|') }
    }
    // KMK <--

    fun add(trackId: Long, lastChapterRead: Double) {
        val previousLastChapterRead = preferences.getFloat(trackId.toString(), 0f)
        if (lastChapterRead > previousLastChapterRead) {
            logcat(LogPriority.DEBUG) { "Queuing track item: $trackId, last chapter read: $lastChapterRead" }
            preferences.edit {
                putFloat(trackId.toString(), lastChapterRead.toFloat())
            }
            // KMK -->
            changes.value++
            // KMK <--
        }
    }

    fun remove(trackId: Long) {
        preferences.edit {
            remove(trackId.toString())
        }
        // KMK -->
        failures.edit { remove(trackId.toString()) }
        changes.value++
        // KMK <--
    }

    fun getItems(): List<DelayedTrackingItem> {
        return preferences.all.mapNotNull {
            DelayedTrackingItem(
                trackId = it.key.toLong(),
                lastChapterRead = it.value.toString().toFloat(),
            )
        }
    }

    data class DelayedTrackingItem(
        val trackId: Long,
        val lastChapterRead: Float,
    )
}
