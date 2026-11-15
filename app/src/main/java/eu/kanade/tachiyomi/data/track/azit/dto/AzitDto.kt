package eu.kanade.tachiyomi.data.track.azit.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The fields Azit Tracker uses to find a title in the library:
 * AniList id first, then the page address on the source, then any title or alternative title.
 */
@Serializable
data class AzitTitleQuery(
    val title: String,
    @SerialName("alt_titles")
    val altTitles: List<String> = emptyList(),
    @SerialName("anilist_id")
    val anilistId: Long? = null,
    val url: String? = null,
    val kind: String = KIND_MANGA,
    @SerialName("type_slug")
    val typeSlug: String? = null,
) {
    companion object {
        const val KIND_MANGA = "manga"
        const val KIND_NOVEL = "novel"
    }
}

/** "I read chapter 12": `POST /api/tracking/progress`. */
@Serializable
data class AzitProgressWrite(
    val title: String,
    @SerialName("alt_titles")
    val altTitles: List<String> = emptyList(),
    @SerialName("anilist_id")
    val anilistId: Long? = null,
    val url: String? = null,
    val kind: String = AzitTitleQuery.KIND_MANGA,
    @SerialName("type_slug")
    val typeSlug: String? = null,
    val chapter: Double? = null,
    val volume: Double? = null,
    @SerialName("create_if_missing")
    val createIfMissing: Boolean = true,
    @SerialName("only_forward")
    val onlyForward: Boolean = true,
    val status: String? = null,
) {
    companion object {
        fun from(
            query: AzitTitleQuery,
            chapter: Double?,
            status: String?,
            createIfMissing: Boolean,
        ) = AzitProgressWrite(
            title = query.title,
            altTitles = query.altTitles,
            anilistId = query.anilistId,
            url = query.url,
            kind = query.kind,
            typeSlug = query.typeSlug,
            chapter = chapter,
            createIfMissing = createIfMissing,
            status = status,
        )
    }
}

/** A library entry as returned by `/api/tracking/lookup` and `/api/tracking/progress`. */
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
    /** 0-100, read only from Komikku. */
    val score: Int? = null,
)

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
