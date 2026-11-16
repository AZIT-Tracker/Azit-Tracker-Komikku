package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery

/**
 * Linking a manga to its Azit title. It **never writes to Azit**: the only requests are
 * `POST /api/tracking/lookup` (a read-only search) or `GET /api/tracking/entry/{media_id}`.
 * The Azit state (status, chapters, score, dates) is read into the local track, never sent.
 * Creating a title only happens through "Create in Azit", outside of this class.
 */
class AzitLinker(private val api: AzitApi) {

    enum class Outcome { LINKED, NOT_FOUND, ALREADY_LINKED }

    /** The Azit title: by its id when it is known (a search result), else found by AniList id and titles. */
    suspend fun find(remoteId: Long, query: suspend () -> AzitTitleQuery): AzitEntry? =
        if (remoteId > 0) api.entry(remoteId) else api.lookup(query())

    /**
     * Links one manga: nothing when it is already linked, nothing (and no creation) when Azit doesn't have the
     * title, else [save] stores the Azit state locally.
     */
    suspend fun link(
        alreadyLinked: Boolean,
        query: suspend () -> AzitTitleQuery,
        save: suspend (AzitEntry) -> Unit,
    ): Outcome {
        if (alreadyLinked) return Outcome.ALREADY_LINKED
        val entry = find(0, query) ?: return Outcome.NOT_FOUND
        save(entry)
        return Outcome.LINKED
    }
}
