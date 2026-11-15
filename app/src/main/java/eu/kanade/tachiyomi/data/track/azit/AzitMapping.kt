package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.source.model.SManga
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Conversions between Komikku's data and Azit Tracker's API (no Android or network: unit tested). */
object AzitMapping {

    const val READING = 1L
    const val COMPLETED = 2L
    const val ON_HOLD = 3L
    const val DROPPED = 4L
    const val PLAN_TO_READ = 5L
    const val REREADING = 6L

    /** Remote id of the "Create in Azit" search result: binding it always creates a new title. */
    const val CREATE_REMOTE_ID = -1L

    /** Komikku status → Azit `EntryStatus`. */
    fun toAzitStatus(status: Long): String? = when (status) {
        READING -> "current"
        COMPLETED -> "completed"
        ON_HOLD -> "paused"
        DROPPED -> "dropped"
        PLAN_TO_READ -> "planning"
        REREADING -> "repeating"
        else -> null
    }

    /** Azit `EntryStatus` → Komikku status. */
    fun fromAzitStatus(status: String?): Long? = when (status) {
        "current" -> READING
        "completed" -> COMPLETED
        "paused" -> ON_HOLD
        "dropped" -> DROPPED
        "planning" -> PLAN_TO_READ
        "repeating" -> REREADING
        else -> null
    }

    /**
     * Scores are kept on the track as 0-100 (like AniList) and shown as 0.0-10.0 (AniList's POINT_10_DECIMAL).
     * The score list index is the 0-100 value.
     */
    val scoreList: List<String> = (0..100).map { (it / 10f).toString() }

    fun toAzitScore(score: Double): Int? = score.takeIf { it > 0 }?.roundToInt()?.coerceIn(0, 100)

    fun displayScore(score: Double): String = if (score <= 0) "-" else (score / 10).toString()

    /** Epoch millis (local midnight, as Komikku's date pickers give it) → `YYYY-MM-DD`; 0 → null. */
    fun toAzitDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (epochMillis <= 0) return null
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
    }

    /** `YYYY-MM-DD` (or a partial `YYYY-MM` / `YYYY`) → epoch millis at local midnight; null → 0. */
    fun fromAzitDate(date: String?, zone: ZoneId = ZoneId.systemDefault()): Long {
        if (date.isNullOrBlank()) return 0
        val parts = date.trim().split("-").map { it.toIntOrNull() ?: return 0 }
        return try {
            LocalDate.of(parts[0], parts.getOrElse(1) { 1 }, parts.getOrElse(2) { 1 })
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            0
        }
    }

    /** `SManga.status` → Azit `release_status`. */
    fun toReleaseStatus(status: Long): String? = when (status.toInt()) {
        SManga.ONGOING -> "releasing"
        SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> "finished"
        SManga.ON_HIATUS -> "hiatus"
        SManga.CANCELLED -> "cancelled"
        else -> null
    }

    /** "novel" for light / web novels (from the genres or the source name), "manga" otherwise. */
    fun kindFor(genres: List<String>?, sourceName: String?): String {
        val novel = genres.orEmpty().any { it.contains("novel", ignoreCase = true) } ||
            sourceName?.contains("novel", ignoreCase = true) == true
        return if (novel) AzitTitleQuery.KIND_NOVEL else AzitTitleQuery.KIND_MANGA
    }

    /** "Jane Doe, John Roe" + "John Roe" → [Jane Doe, John Roe]. */
    fun authors(author: String?, artist: String?): List<String> =
        listOfNotNull(author, artist)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)

    /** Copies an Azit entry onto a Komikku track. */
    fun Track.copyFrom(entry: AzitEntry, server: String, zone: ZoneId = ZoneId.systemDefault()): Track = apply {
        remote_id = entry.mediaId
        title = entry.title
        last_chapter_read = entry.progress.chapter ?: 0.0
        total_chapters = entry.total?.toLong() ?: 0
        status = fromAzitStatus(entry.status) ?: PLAN_TO_READ
        score = entry.score?.toDouble() ?: 0.0
        started_reading_date = fromAzitDate(entry.startedOn, zone)
        finished_reading_date = fromAzitDate(entry.finishedOn, zone)
        tracking_url = "$server/media/${entry.mediaId}"
    }
}
