package eu.kanade.presentation.more.settings.screen

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.domain.track.service.TrackingQueue
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK -->
/** One tracker of a waiting title: sent, or still waiting (and why its last send failed). */
private data class TrackerState(val name: String, val waitingChapter: Double?, val failedAt: Long?, val error: String?)

/** A title with at least one tracker still waiting. */
private data class WaitingTitle(val title: String, val chapter: Double, val trackers: List<TrackerState>)

private fun chapterText(chapter: Double): String =
    if (chapter == chapter.toLong().toDouble()) chapter.toLong().toString() else chapter.toString()

/**
 * Settings -> Tracking -> Waiting to be sent: the chapters read that a tracker has not accepted yet, the title's
 * trackers in a ⋮ menu (sent, or the error of the last try), and "Retry now". They are also sent on their own as soon
 * as a request to that tracker works (see [TrackingQueue]).
 */
@Composable
fun TrackingQueueList() {
    val store = remember { Injekt.get<DelayedTrackingStore>() }
    val getTracks = remember { Injekt.get<GetTracks>() }
    val getManga = remember { Injekt.get<GetManga>() }
    val trackerManager = remember { Injekt.get<TrackerManager>() }
    val changes by store.changes.collectAsState()
    var titles by remember { mutableStateOf<List<WaitingTitle>?>(null) }
    var retrying by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<WaitingTitle?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(changes) {
        titles = withContext(Dispatchers.IO) {
            val waiting = store.getItems().associateBy { it.trackId }
            waiting.values.mapNotNull { getTracks.awaitOne(it.trackId)?.mangaId }.distinct().mapNotNull { mangaId ->
                val manga = getManga.await(mangaId) ?: return@mapNotNull null
                val trackers = getTracks.await(mangaId).mapNotNull { track ->
                    val tracker = trackerManager.get(track.trackerId) ?: return@mapNotNull null
                    val item = waiting[track.id]
                    val failure = item?.let { store.failure(track.id) }
                    TrackerState(tracker.name, item?.lastChapterRead?.toDouble(), failure?.first, failure?.second)
                }
                val chapter = trackers.mapNotNull { it.waitingChapter }.maxOrNull() ?: return@mapNotNull null
                WaitingTitle(manga.title, chapter, trackers)
            }.sortedBy { it.title.lowercase() }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val list = titles
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when {
                    list == null -> stringResource(KMR.strings.tracking_queue_loading)
                    list.isEmpty() -> stringResource(KMR.strings.tracking_queue_all_sent)
                    else -> stringResource(KMR.strings.tracking_queue_waiting_count, list.size)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !retrying && !list.isNullOrEmpty(),
                onClick = {
                    retrying = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { TrackingQueue.flushAll() } }
                        retrying = false
                    }
                },
            ) {
                Text(stringResource(if (retrying) KMR.strings.tracking_queue_sending else KMR.strings.tracking_queue_retry))
            }
        }
        list?.forEach { title ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val failed = title.trackers.filter { it.waitingChapter != null }.joinToString { it.name }
                    Text(
                        stringResource(KMR.strings.tracking_queue_not_sent, chapterText(title.chapter), failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { details = title }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(KMR.strings.tracking_queue_status)) }
                }
            }
        }
    }

    details?.let { title ->
        AlertDialog(
            onDismissRequest = { details = null },
            confirmButton = {
                TextButton(onClick = { details = null }) { Text(stringResource(KMR.strings.tracking_queue_close)) }
            },
            title = { Text(title.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    title.trackers.forEach { tracker ->
                        Column {
                            if (tracker.waitingChapter == null) {
                                Text(stringResource(KMR.strings.tracking_queue_tracker_sent, tracker.name), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Text(
                                    stringResource(KMR.strings.tracking_queue_tracker_waiting, tracker.name, chapterText(tracker.waitingChapter)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                val failedWhen = tracker.failedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
                                val notTried = stringResource(KMR.strings.tracking_queue_not_tried)
                                Text(
                                    listOfNotNull(failedWhen, tracker.error?.takeIf { it.isNotBlank() }).joinToString(" · ")
                                        .ifBlank { notTried },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
// KMK <--
