package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaSummary
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaType
import eu.kanade.tachiyomi.data.track.azit.dto.AzitSection
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SManga
import exh.util.MangaType
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

    /**
     * Always "manga": Komikku never sends novels to Azit (they belong to LNReader, which only sends novels), so the
     * two apps never change each other's titles. The server refuses them too (`X-Azit-App: komikku`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun kindFor(genres: List<String>?, sourceName: String?): String = AzitTitleQuery.KIND_MANGA

    /** "Jane Doe, John Roe" + "John Roe" → [Jane Doe, John Roe]. */
    fun authors(author: String?, artist: String?): List<String> =
        listOfNotNull(author, artist)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)

    /** 12.0 -> "12", 12.5 -> "12.5". */
    fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /** Azit media types a reader app deals with (searched, and offered when creating a title). */
    val BOOK_TYPE_SLUGS = listOf("manga", "manhwa", "manhua", "webtoon", "comic") // never novels: LNReader's

    /** The `kind` Azit expects for a media type. */
    fun kindForType(typeSlug: String?, fallback: String = AzitTitleQuery.KIND_MANGA): String = when (typeSlug) {
        "novel", "light_novel", "web_novel" -> AzitTitleQuery.KIND_NOVEL
        "comic" -> "comic"
        null -> fallback
        else -> AzitTitleQuery.KIND_MANGA
    }

    /** Komikku's reading type (from genres and source, see `exh.util.mangaType`) → Azit type slug. */
    fun typeSlugFor(type: MangaType): String = when (type) {
        MangaType.TYPE_MANGA -> "manga"
        MangaType.TYPE_MANHWA -> "manhwa"
        MangaType.TYPE_MANHUA -> "manhua"
        MangaType.TYPE_WEBTOON -> "webtoon"
        MangaType.TYPE_COMIC -> "comic"
    }

    /**
     * The Azit type of a manga for "Create in Azit": Komikku's own type guess (never a novel: those are LNReader's).
     * Null when Azit has no such type (the server then picks one).
     */
    fun guessTypeSlug(
        genres: List<String>?,
        sourceName: String?,
        comicType: MangaType,
        availableSlugs: Collection<String>,
    ): String? {
        return typeSlugFor(comicType).takeIf { it in availableSlugs && it in BOOK_TYPE_SLUGS }
    }

    /** Book type ids of Azit, in the order of [BOOK_TYPE_SLUGS]. */
    fun bookTypes(sections: List<AzitSection>): List<AzitMediaType> =
        sections.flatMap { it.mediaTypes }
            .filter { it.slug in BOOK_TYPE_SLUGS }
            .distinctBy { it.id }
            .sortedBy { BOOK_TYPE_SLUGS.indexOf(it.slug) }

    /** Azit gives its own images as paths (`/api/images/...`). */
    fun absoluteUrl(url: String?, server: String): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("/") -> "$server$url"
        else -> url
    }

    /** A title of Azit's catalogue as a search result (its summary is set by the caller). */
    fun TrackSearch.copyFrom(media: AzitMediaSummary, server: String): TrackSearch = apply {
        remote_id = media.id
        title = media.title
        cover_url = media.coverUrl.takeIf { media.hasCover }?.let { absoluteUrl(it, server) }.orEmpty()
        publishing_type = listOfNotNull(media.mediaTypeName, media.format?.takeIf { it != media.mediaTypeName })
            .joinToString(" · ")
        publishing_status = media.releaseStatus.orEmpty()
        start_date = media.startYear?.toString().orEmpty()
        total_chapters = media.unitTotals["chapter"]?.toLong() ?: 0
        last_chapter_read = media.entry?.progress?.get("chapter") ?: 0.0
        status = fromAzitStatus(media.entry?.status) ?: PLAN_TO_READ
        score = media.entry?.score?.toDouble() ?: 0.0
        tracking_url = "$server/media/${media.id}"
    }

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
