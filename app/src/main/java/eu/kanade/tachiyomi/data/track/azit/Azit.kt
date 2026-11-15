package eu.kanade.tachiyomi.data.track.azit

import android.app.Application
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import logcat.LogPriority
import okhttp3.Dns
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * The owner's self-hosted Azit Tracker (see AZIT.md at the root of this repository).
 *
 * Login is a server address (saved as the "username") and a device token (saved as the "password").
 * The remote id is Azit's `media_id`. Azit finds the title itself from the AniList id, the page
 * address on the source and the titles, so every call sends all of them.
 */
class Azit(id: Long) : BaseTracker(id, "Azit Tracker") {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L
        const val REREADING = 6L

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

        /** Copies an Azit entry onto a Komikku track. */
        fun Track.copyFrom(entry: AzitEntry, server: String): Track = apply {
            remote_id = entry.mediaId
            title = entry.title
            last_chapter_read = entry.progress.chapter ?: 0.0
            status = fromAzitStatus(entry.status) ?: PLAN_TO_READ
            score = 0.0
            tracking_url = "$server/media/${entry.mediaId}"
        }
    }

    // A self-hosted server is often on the local network: DNS over HTTPS cannot resolve it.
    override val client: OkHttpClient =
        networkService.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()

    private val context: Application by injectLazy()

    val api by lazy { AzitApi(client, ::getServer, ::getPassword, ::errorMessage) }

    private val getManga: GetManga by injectLazy()
    private val getTracks: GetTracks by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val addTracks: AddTracks by injectLazy()

    override fun getLogo(): Int = R.drawable.brand_azit

    override fun getStatusList(): List<Long> = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ, REREADING)

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        PLAN_TO_READ -> MR.strings.plan_to_read
        REREADING -> MR.strings.repeating
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = REREADING

    override fun getCompletionStatus(): Long = COMPLETED

    // Scores are given in Azit itself (its own score format and detailed ratings).
    override fun getScoreList(): ImmutableList<String> = persistentListOf()

    override fun displayScore(track: DomainTrack): String = ""

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.status != COMPLETED && didReadChapter) {
            if (track.total_chapters > 0 && track.last_chapter_read.toLong() == track.total_chapters) {
                track.status = COMPLETED
            } else if (track.status != REREADING) {
                track.status = READING
            }
        }
        val entry = api.progress(
            AzitProgressWrite.from(
                query = titleQuery(track),
                chapter = track.last_chapter_read.takeIf { it > 0 },
                status = toAzitStatus(track.status),
                createIfMissing = createIfMissing(),
            ),
        )
        return track.copyFrom(entry, getServer())
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        // No chapter: Azit only finds (or creates) the title. Komikku then sends the read chapters.
        val entry = api.progress(
            AzitProgressWrite.from(
                query = titleQuery(track),
                chapter = null,
                status = null,
                createIfMissing = createIfMissing(),
            ),
        )
        return track.copyFrom(entry, getServer())
    }

    override suspend fun search(query: String): List<TrackSearch> {
        val entry = api.lookup(AzitTitleQuery(title = query))
        val result = TrackSearch.create(id)
        if (entry != null) {
            result.copyFrom(entry, getServer())
            result.publishing_type = entry.type.orEmpty()
            result.summary = entry.status
                ?.let { context.stringResource(KMR.strings.azit_search_in_list, it) }
                ?: context.stringResource(KMR.strings.azit_search_not_in_list)
        } else {
            // Not in Azit yet: binding it creates it there (when "create missing titles" is on).
            result.remote_id = 0
            result.title = query
            result.tracking_url = getServer()
            result.status = PLAN_TO_READ
            result.summary = context.stringResource(KMR.strings.azit_search_will_be_created)
        }
        return listOf(result)
    }

    override suspend fun refresh(track: Track): Track {
        val entry = api.lookup(titleQuery(track)) ?: return track
        return track.copyFrom(entry, getServer())
    }

    override suspend fun login(username: String, password: String) {
        val server = AzitApi.normalizeServer(username)
        val token = password.trim()
        api.checkLogin(server, token)
        saveCredentials(server, token)
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ

    fun getServer(): String = getUsername()

    private fun errorMessage(code: Int): String = when (code) {
        401, 403 -> context.stringResource(KMR.strings.azit_error_token)
        404 -> context.stringResource(KMR.strings.azit_error_not_found)
        else -> context.stringResource(KMR.strings.azit_error_http, code)
    }

    private fun createIfMissing(): Boolean = trackPreferences.azitCreateIfMissing().get()

    /**
     * Binds a library manga to Azit the first time one of its chapters is read, so the reading is
     * tracked without opening the tracking sheet. Called from [eu.kanade.domain.track.interactor.TrackChapter].
     */
    suspend fun autoBind(mangaId: Long) {
        if (!isLoggedIn || !trackPreferences.azitAutoBind().get()) return
        try {
            if (getTracks.await(mangaId).any { it.trackerId == id }) return
            val manga = getManga.await(mangaId)?.takeIf { it.favorite } ?: return
            val track = TrackSearch.create(id).apply {
                manga_id = mangaId
                title = manga.title
                tracking_url = getServer()
            }
            addTracks.bind(this, track, mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: could not bind manga $mangaId" }
        }
    }

    /** Everything Azit can use to find the title: the bound title, the manga's titles, AniList id and page. */
    private suspend fun titleQuery(track: Track): AzitTitleQuery {
        val manga = track.manga_id.takeIf { it > 0 }?.let { getManga.await(it) }
        val titles = listOfNotNull(track.title, manga?.title, manga?.ogTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val anilistId = manga?.let { m ->
            getTracks.await(m.id).firstOrNull { it.trackerId == TrackerManager.ANILIST }?.remoteId
        }
        return AzitTitleQuery(
            title = titles.firstOrNull() ?: track.title,
            altTitles = titles.drop(1),
            anilistId = anilistId?.takeIf { it > 0 },
            url = manga?.let { mangaUrl(it) },
            kind = AzitTitleQuery.KIND_MANGA,
        )
    }

    private fun mangaUrl(manga: Manga): String? {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        return runCatching { source.getMangaUrl(manga.toSManga()) }.getOrNull()
    }
}
