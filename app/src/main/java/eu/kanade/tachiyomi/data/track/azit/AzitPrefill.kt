package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntryFields
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * What "Create in Azit" sends: the title (from the manga) and the entry (from the other trackers, else from
 * Komikku's own reading history). Everything is shown in the form and can be edited before creating.
 */
data class AzitCreateDraft(
    val query: AzitTitleQuery,
    val entry: AzitEntryPrefill,
) {
    /** The create call: a new title (`force_create`) with its library entry, written as the user left it. */
    fun toWrite(zone: ZoneId = ZoneId.systemDefault()): AzitProgressWrite = AzitProgressWrite.from(
        query = query.copy(kind = AzitMapping.kindForType(query.typeSlug, query.kind)),
        chapter = entry.chapter.takeIf { it > 0 },
        status = AzitMapping.toAzitStatus(entry.status),
        createIfMissing = true,
        onlyForward = false,
        forceCreate = true,
        entryFields = AzitEntryFields(
            score = AzitMapping.toAzitScore(entry.score),
            startedOn = AzitMapping.toAzitDate(entry.startDate, zone),
            finishedOn = AzitMapping.toAzitDate(entry.finishDate, zone),
        ),
    )
}

/** The library entry of a new title. */
data class AzitEntryPrefill(
    /** Komikku status of the Azit tracker ([AzitMapping.READING]...). */
    val status: Long = AzitMapping.PLAN_TO_READ,
    val chapter: Double = 0.0,
    /** 0-100, 0 when unscored. */
    val score: Double = 0.0,
    /** Epoch millis, 0 when unknown. */
    val startDate: Long = 0,
    val finishDate: Long = 0,
)

/** One tracker bound to the manga, already normalised by the caller. */
data class AzitPrefillSource(
    val isAnilist: Boolean,
    val status: TrackStatus?,
    val chapter: Double,
    /** 0-10 (`Tracker.get10PointScore`), 0 when unscored. */
    val score10: Double,
    val startDate: Long,
    val finishDate: Long,
)

/** Komikku's own reading of the manga, used when no tracker knows. */
data class AzitLocalReading(
    /** Highest chapter number marked read, 0 when none. */
    val lastReadChapter: Double = 0.0,
    /** All the manga's chapters are read. */
    val allRead: Boolean = false,
    /** Epoch millis of the first and the last chapter read (history), 0 when none. */
    val firstReadAt: Long = 0,
    val lastReadAt: Long = 0,
)

object AzitPrefill {

    /** Generic tracker status → Komikku status of the Azit tracker. */
    fun fromTrackStatus(status: TrackStatus?): Long? = when (status) {
        TrackStatus.READING -> AzitMapping.READING
        TrackStatus.REPEATING -> AzitMapping.REREADING
        TrackStatus.PLAN_TO_READ -> AzitMapping.PLAN_TO_READ
        TrackStatus.PAUSED -> AzitMapping.ON_HOLD
        TrackStatus.COMPLETED -> AzitMapping.COMPLETED
        TrackStatus.DROPPED -> AzitMapping.DROPPED
        TrackStatus.OTHER, null -> null
    }

    /**
     * Each field comes from the first tracker that has it, AniList first. Without any tracker, the chapters and
     * status come from what is read in Komikku. Without dates from a tracker, the history gives them: start = first
     * chapter read, finish = last chapter read, only when the manga is completed.
     */
    fun entry(sources: List<AzitPrefillSource>, local: AzitLocalReading): AzitEntryPrefill {
        val ordered = sources.sortedByDescending { it.isAnilist }
        val chapter = ordered.firstOrNull { it.chapter > 0 }?.chapter ?: local.lastReadChapter
        val status = ordered.firstNotNullOfOrNull { fromTrackStatus(it.status) }
            ?: when {
                chapter <= 0 -> AzitMapping.PLAN_TO_READ
                local.allRead -> AzitMapping.COMPLETED
                else -> AzitMapping.READING
            }
        val score = ordered.firstOrNull { it.score10 > 0 }?.let { (it.score10 * 10).roundToInt().toDouble() } ?: 0.0
        val startDate = ordered.firstOrNull { it.startDate > 0 }?.startDate
            ?: local.firstReadAt.takeIf { chapter > 0 }
            ?: 0
        val finishDate = ordered.firstOrNull { it.finishDate > 0 }?.finishDate
            ?: local.lastReadAt.takeIf { status == AzitMapping.COMPLETED }
            ?: 0
        return AzitEntryPrefill(
            status = status,
            chapter = chapter,
            score = score.coerceIn(0.0, 100.0),
            startDate = startDate,
            finishDate = finishDate,
        )
    }
}
