package eu.kanade.tachiyomi.data.track.azit.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The fields Azit Tracker uses to find a title: the bound `media_id` first (no search), then the AniList id,
 * the page address, then any title or alternative title. The app's data (cover, description, authors...) is
 * only used when the title has to be created.
 */
@Serializable
data class AzitTitleQuery(
    @SerialName("media_id")
    val mediaId: Long? = null,
    val title: String,
    @SerialName("alt_titles")
    val altTitles: List<String> = emptyList(),
    @SerialName("anilist_id")
    val anilistId: Long? = null,
    /** The page being read: it becomes the title's shortcut in Azit (one link kept per site). */
    val url: String? = null,
    @SerialName("link_label")
    val linkLabel: String? = null,
    val kind: String = KIND_MANGA,
    @SerialName("type_slug")
    val typeSlug: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    @SerialName("release_status")
    val releaseStatus: String? = null,
    @SerialName("total_chapters")
    val totalChapters: Int? = null,
) {
    companion object {
        const val KIND_MANGA = "manga"
        const val KIND_NOVEL = "novel"
    }
}

/**
 * Personal fields of an entry for a manual edit. Each one is always sent, `null` included, because Azit only
 * changes the fields present in the request and clears one sent as `null`.
 */
data class AzitEntryFields(
    /** 0-100. */
    val score: Int?,
    /** `YYYY-MM-DD`. */
    val startedOn: String?,
    val finishedOn: String?,
)

/** `POST /api/tracking/progress`: find (or create) the title and update its entry. */
@Serializable
data class AzitProgressWrite(
    @SerialName("media_id")
    val mediaId: Long? = null,
    val title: String,
    @SerialName("alt_titles")
    val altTitles: List<String> = emptyList(),
    @SerialName("anilist_id")
    val anilistId: Long? = null,
    val url: String? = null,
    @SerialName("link_label")
    val linkLabel: String? = null,
    val kind: String = AzitTitleQuery.KIND_MANGA,
    @SerialName("type_slug")
    val typeSlug: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    @SerialName("release_status")
    val releaseStatus: String? = null,
    @SerialName("total_chapters")
    val totalChapters: Int? = null,
    val chapter: Double? = null,
    val volume: Double? = null,
    @SerialName("create_if_missing")
    val createIfMissing: Boolean = true,
    @SerialName("only_forward")
    val onlyForward: Boolean = true,
    @SerialName("force_create")
    val forceCreate: Boolean = false,
    val status: String? = null,
    /** Sent separately by [toJson], with explicit nulls. */
    @Transient
    val entryFields: AzitEntryFields? = null,
) {
    /** The request body: null fields are left out, except the [entryFields] (a null there clears the field). */
    fun toJson(json: Json): JsonObject {
        val body = json.encodeToJsonElement(serializer(), this).jsonObject
        val fields = entryFields ?: return body
        return JsonObject(
            body + mapOf(
                "score" to JsonPrimitive(fields.score),
                "started_on" to JsonPrimitive(fields.startedOn),
                "finished_on" to JsonPrimitive(fields.finishedOn),
            ),
        )
    }

    companion object {
        fun from(
            query: AzitTitleQuery,
            chapter: Double? = null,
            status: String? = null,
            createIfMissing: Boolean = true,
            onlyForward: Boolean = true,
            forceCreate: Boolean = false,
            entryFields: AzitEntryFields? = null,
        ) = AzitProgressWrite(
            mediaId = query.mediaId,
            title = query.title,
            altTitles = query.altTitles,
            anilistId = query.anilistId,
            url = query.url,
            linkLabel = query.linkLabel,
            kind = query.kind,
            typeSlug = query.typeSlug,
            coverUrl = query.coverUrl,
            description = query.description,
            authors = query.authors,
            genres = query.genres,
            releaseStatus = query.releaseStatus,
            totalChapters = query.totalChapters,
            chapter = chapter,
            createIfMissing = createIfMissing,
            onlyForward = onlyForward,
            forceCreate = forceCreate,
            status = status,
            entryFields = entryFields,
        )
    }
}

/** A library entry as returned by `/lookup`, `/progress` and `/entry/{media_id}`. */
@Serializable
data class AzitEntry(
    @SerialName("media_id")
    val mediaId: Long,
    val title: String,
    val type: String? = null,
    val created: Boolean = false,
    @SerialName("in_library")
    val inLibrary: Boolean = false,
    val status: String? = null,
    val progress: AzitProgress = AzitProgress(),
    @SerialName("main_unit")
    val mainUnit: String? = null,
    /** 0-100. */
    val score: Int? = null,
    @SerialName("started_on")
    val startedOn: String? = null,
    @SerialName("finished_on")
    val finishedOn: String? = null,
    /** Total of the main unit (chapters), when known. */
    val total: Double? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("shortcut_url")
    val shortcutUrl: String? = null,
)

// KMK -->
/**
 * `POST /api/tracking/duplicates`, before "Create in Azit": [linked] already has this AniList id or page (creating
 * is refused: the manga is bound to it instead); [sameName] has one of the manga's names ("is it this one?").
 */
@Serializable
data class AzitDuplicates(
    val linked: AzitEntry? = null,
    @SerialName("same_name")
    val sameName: List<AzitEntry> = emptyList(),
) {
    val any: Boolean get() = linked != null || sameName.isNotEmpty()
}
// KMK <--

@Serializable
data class AzitProgress(
    val chapter: Double? = null,
    val volume: Double? = null,
)

/** `GET /api/devices/me`. */
@Serializable
data class AzitMe(
    val ok: Boolean = false,
    val caller: String? = null,
)

/** `GET /api/sections`: the tabs of Azit, with their media types. */
@Serializable
data class AzitSection(
    val id: Long,
    val slug: String,
    val name: String,
    @SerialName("media_types")
    val mediaTypes: List<AzitMediaType> = emptyList(),
)

@Serializable
data class AzitMediaType(
    val id: Long,
    val slug: String,
    val name: String,
)

/** `GET /api/media`: a page of titles. */
@Serializable
data class AzitMediaPage(
    val items: List<AzitMediaSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class AzitMediaSummary(
    val id: Long,
    val title: String,
    @SerialName("media_type_slug")
    val mediaTypeSlug: String? = null,
    @SerialName("media_type_name")
    val mediaTypeName: String? = null,
    val format: String? = null,
    @SerialName("release_status")
    val releaseStatus: String? = null,
    @SerialName("start_year")
    val startYear: Int? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    /** False when [coverUrl] is only the type's placeholder. */
    @SerialName("has_cover")
    val hasCover: Boolean = true,
    @SerialName("unit_totals")
    val unitTotals: Map<String, Int?> = emptyMap(),
    val entry: AzitEntrySummary? = null,
)

@Serializable
data class AzitEntrySummary(
    val status: String? = null,
    val score: Int? = null,
    val progress: Map<String, Double> = emptyMap(),
)
