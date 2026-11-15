package eu.kanade.tachiyomi.data.track.azit

import android.app.Application
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.azit.AzitMapping.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntryFields
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaType
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.Dns
import okhttp3.OkHttpClient
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * The owner's self-hosted Azit Tracker (see AZIT.md at the root of this repository).
 *
 * Login is a server address (saved as the "username") and a device token (saved as the "password").
 * The remote id is Azit's `media_id`: once bound, every call names the title by it (no search).
 * Before that, Azit finds the title from the AniList id, the page address and the titles.
 */
class Azit(id: Long) : BaseTracker(id, "Azit Tracker"), DeletableTracker {

    companion object {
        const val READING = AzitMapping.READING
        const val COMPLETED = AzitMapping.COMPLETED
        const val ON_HOLD = AzitMapping.ON_HOLD
        const val DROPPED = AzitMapping.DROPPED
        const val PLAN_TO_READ = AzitMapping.PLAN_TO_READ
        const val REREADING = AzitMapping.REREADING

        /** Pause between two titles when binding the library in the background (rate friendly). */
        private const val BACKFILL_DELAY_MS = 750L
    }

    // A self-hosted server is often on the local network: DNS over HTTPS cannot resolve it.
    override val client: OkHttpClient =
        networkService.client.newBuilder()
            .dns(Dns.SYSTEM)
            .build()

    override val supportsReadingDates: Boolean = true

    private val context: Application by injectLazy()

    val api by lazy { AzitApi(client, ::getServer, ::getPassword, ::errorMessage) }

    private val getManga: GetManga by injectLazy()
    private val getTracks: GetTracks by injectLazy()
    private val getChaptersByMangaId: GetChaptersByMangaId by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val addTracks: AddTracks by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()
    private val deleteTrack: DeleteTrack by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()

    /** Background work that outlives a screen (binding the library after login). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    // Like AniList's POINT_10_DECIMAL: shown 0.0-10.0, kept on the track (and in Azit) as 0-100.
    override fun getScoreList(): ImmutableList<String> = AzitMapping.scoreList.toImmutableList()

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun get10PointScore(track: DomainTrack): Double = track.score / 10.0

    override fun displayScore(track: DomainTrack): String = AzitMapping.displayScore(track.score)

    override fun hasNotStartedReading(status: Long): Boolean = status == PLAN_TO_READ

    /**
     * A chapter read (`didReadChapter`): progress only moves forward, and the chapter page becomes the
     * title's shortcut in Azit. Otherwise it is an edit from the tracking sheet: everything is sent as is.
     */
    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (track.remote_id <= 0) return bind(track)
        if (track.status != COMPLETED && didReadChapter) {
            if (track.total_chapters > 0 && track.last_chapter_read.toLong() >= track.total_chapters) {
                track.status = COMPLETED
                if (track.finished_reading_date == 0L) track.finished_reading_date = System.currentTimeMillis()
            } else if (track.status != REREADING) {
                track.status = READING
            }
        }
        val write = if (didReadChapter) {
            val (url, label) = readingPage(track)
            AzitProgressWrite.from(
                query = boundQuery(track).copy(url = url, linkLabel = label),
                chapter = track.last_chapter_read.takeIf { it > 0 },
                status = AzitMapping.toAzitStatus(track.status),
            )
        } else {
            AzitProgressWrite.from(
                query = boundQuery(track),
                chapter = track.last_chapter_read,
                status = AzitMapping.toAzitStatus(track.status),
                onlyForward = false,
                entryFields = AzitEntryFields(
                    score = AzitMapping.toAzitScore(track.score),
                    startedOn = AzitMapping.toAzitDate(track.started_reading_date),
                    finishedOn = AzitMapping.toAzitDate(track.finished_reading_date),
                ),
            )
        }
        return track.copyEntry(api.progress(write))
    }

    /**
     * - the "Create in Azit" result: a new title made from Komikku's data, even if one matches;
     * - a search result: that title (added to the library if needed);
     * - otherwise (automatic binding): Azit finds the title, or creates it when "add missing titles" is on.
     */
    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val write = when {
            track.remote_id == AzitMapping.CREATE_REMOTE_ID ->
                AzitProgressWrite.from(
                    // The fields edited in the "Create in Azit" dialog, else Komikku's data as is.
                    query = pendingDrafts.remove(track.manga_id) ?: fullQuery(track),
                    createIfMissing = true,
                    forceCreate = true,
                )
            track.remote_id > 0 -> AzitProgressWrite.from(query = boundQuery(track))
            else -> AzitProgressWrite.from(query = fullQuery(track), createIfMissing = createIfMissing())
        }
        return track.copyEntry(api.progress(write))
    }

    /** Azit's catalogue (books only), with covers; "Create in Azit" is always the last result. */
    override suspend fun search(query: String): List<TrackSearch> {
        val server = getServer()
        val results = try {
            api.searchMedia(query, bookTypes().map { it.id }).items.map { media ->
                TrackSearch.create(id).copyFrom(media, server).apply {
                    summary = media.entry?.status
                        ?.let { context.stringResource(KMR.strings.azit_search_in_list, it) }
                        ?: context.stringResource(KMR.strings.azit_search_not_in_list)
                }
            }
        } catch (e: AzitException) {
            if (e.code == 401 || e.code == 403) throw e
            // Older servers: exact title match only.
            logcat(LogPriority.WARN, e) { "Azit Tracker: media search failed, using lookup" }
            listOfNotNull(
                api.lookup(AzitTitleQuery(title = query))?.let { entry ->
                    TrackSearch.create(id).apply {
                        copyEntry(entry)
                        cover_url = AzitMapping.absoluteUrl(entry.coverUrl, server).orEmpty()
                        publishing_type = entry.type.orEmpty()
                        summary = context.stringResource(KMR.strings.azit_search_not_in_list)
                    }
                },
            )
        }
        // Always offered, even when a title matches: it may be the wrong one.
        return results + TrackSearch.create(id).apply {
            remote_id = AzitMapping.CREATE_REMOTE_ID
            title = context.stringResource(KMR.strings.azit_create_in_azit, query)
            tracking_url = "$server/#create"
            status = PLAN_TO_READ
            summary = context.stringResource(KMR.strings.azit_create_in_azit_summary)
        }
    }

    override suspend fun refresh(track: Track): Track {
        if (track.remote_id > 0) return track.copyEntry(api.entry(track.remote_id))
        val entry = api.lookup(fullQuery(track)) ?: return track
        return track.copyEntry(entry)
    }

    /** "Remove from Azit": the entry leaves the library, the title stays in Azit's catalogue. */
    override suspend fun delete(track: DomainTrack) {
        if (track.remoteId > 0) api.removeEntry(track.remoteId)
    }

    override suspend fun login(username: String, password: String) {
        val server = AzitApi.normalizeServer(username)
        val token = password.trim()
        api.checkLogin(server, token)
        saveCredentials(server, token)
        scope.launch { bindLibraryFromAnilist() }
    }

    fun getServer(): String = getUsername()

    // ---- Data Komikku's track has no field for (cover, volumes) ----

    /** The cover Azit has for a bound title ("" when none), kept from the last answer of the server. */
    fun coverPreference(mediaId: Long): Preference<String> = preferenceStore.getString("azit_cover_$mediaId", "")

    /** Volumes read of a bound title, kept from the last answer of the server. */
    fun volumePreference(mediaId: Long): Preference<String> = preferenceStore.getString("azit_volume_$mediaId", "")

    private fun Track.copyEntry(entry: AzitEntry): Track {
        copyFrom(entry, getServer())
        coverPreference(entry.mediaId).set(AzitMapping.absoluteUrl(entry.coverUrl, getServer()).orEmpty())
        volumePreference(entry.mediaId).set(entry.progress.volume?.let(AzitMapping::formatNumber).orEmpty())
        return this
    }

    /** Sets the volumes read (from the track card). */
    suspend fun setRemoteVolume(track: DomainTrack, volume: Double) {
        val entry = api.progress(
            AzitProgressWrite.from(query = AzitTitleQuery(mediaId = track.remoteId, title = track.title))
                .copy(volume = volume, onlyForward = false),
        )
        val dbTrack = track.toDbTrack().copyEntry(entry)
        dbTrack.toDomainTrack(idRequired = false)?.let { insertTrack.await(it) }
    }

    // ---- "Create in Azit" with edited fields ----

    private val pendingDrafts = ConcurrentHashMap<Long, AzitTitleQuery>()

    private var cachedBookTypes: List<AzitMediaType>? = null

    /** Azit's book types (manga, manhwa, light novel...), for the search and the "Create in Azit" dialog. */
    suspend fun bookTypes(): List<AzitMediaType> {
        cachedBookTypes?.let { return it }
        return AzitMapping.bookTypes(api.sections()).also { cachedBookTypes = it }
    }

    /** What Komikku knows about the manga, to be edited before creating the title in Azit. */
    suspend fun createDraft(mangaId: Long): AzitTitleQuery {
        val track = Track.create(id).apply {
            manga_id = mangaId
            title = getManga.await(mangaId)?.title.orEmpty()
        }
        return fullQuery(track)
    }

    /** Creates the title in Azit from the edited [draft] and binds the manga to it. */
    suspend fun createFromDraft(mangaId: Long, draft: AzitTitleQuery) {
        pendingDrafts[mangaId] = draft.copy(kind = AzitMapping.kindForType(draft.typeSlug, draft.kind))
        val track = TrackSearch.create(id).apply {
            remote_id = AzitMapping.CREATE_REMOTE_ID
            manga_id = mangaId
            title = draft.title
            tracking_url = "${getServer()}/#create"
        }
        addTracks.bind(this, track, mangaId)
    }

    // ---- Migration from AniList ----

    enum class MigrationOutcome { MIGRATED, CREATED, SKIPPED }

    /**
     * Binds the manga of [anilistTrack] to Azit (by its AniList id; Azit imports the title from AniList, or makes
     * it from Komikku's data, when it is missing) and copies the AniList status, chapters, score and dates.
     * The local Azit track is only saved once both calls worked, so a stopped migration resumes cleanly.
     */
    suspend fun migrateFromAnilist(anilistTrack: DomainTrack, unbindAnilist: Boolean): MigrationOutcome {
        if (getTracks.await(anilistTrack.mangaId).any { it.trackerId == id }) return MigrationOutcome.SKIPPED
        val track = Track.create(id).apply {
            manga_id = anilistTrack.mangaId
            title = anilistTrack.title
        }
        val bound = api.progress(AzitProgressWrite.from(query = fullQuery(track), createIfMissing = true))
        val entry = api.progress(AzitMapping.migrationWrite(bound.mediaId, bound.title, anilistTrack))
        track.copyEntry(entry)
        track.toDomainTrack(idRequired = false)?.let { insertTrack.await(it) }
        if (unbindAnilist) deleteTrack.await(anilistTrack.mangaId, anilistTrack.trackerId)
        return if (bound.created) MigrationOutcome.CREATED else MigrationOutcome.MIGRATED
    }

    /** Library manga tracked on AniList and not yet on Azit. */
    suspend fun anilistTracksToMigrate(): List<DomainTrack> {
        return getTracks.await()
            .groupBy { it.mangaId }
            .values
            .filter { tracks -> tracks.none { it.trackerId == id } }
            .mapNotNull { tracks -> tracks.firstOrNull { it.trackerId == TrackerManager.ANILIST } }
            .filter { getManga.await(it.mangaId)?.favorite == true }
    }

    private fun errorMessage(code: Int): String = when (code) {
        401, 403 -> context.stringResource(KMR.strings.azit_error_token)
        404 -> context.stringResource(KMR.strings.azit_error_not_found)
        else -> context.stringResource(KMR.strings.azit_error_http, code)
    }

    private fun createIfMissing(): Boolean = trackPreferences.azitCreateIfMissing().get()

    // ---- Automatic binding ----

    /**
     * Binds a manga to Azit without opening the tracking sheet. Called when a chapter of a library manga is
     * read ([eu.kanade.domain.track.interactor.TrackChapter]) and when the manga gets an AniList track
     * ([AddTracks], `requireFavorite = false`). The AniList id makes the match exact.
     */
    suspend fun autoBind(mangaId: Long, requireFavorite: Boolean = true) {
        if (!isLoggedIn || !trackPreferences.azitAutoBind().get()) return
        try {
            if (getTracks.await(mangaId).any { it.trackerId == id }) return
            val manga = getManga.await(mangaId) ?: return
            if (requireFavorite && !manga.favorite) return
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

    /** After login: library manga already tracked on AniList get bound to Azit too, one at a time. */
    private suspend fun bindLibraryFromAnilist() {
        if (!trackPreferences.azitAutoBind().get()) return
        try {
            val mangaIds = getTracks.await()
                .groupBy { it.mangaId }
                .filterValues { tracks ->
                    tracks.any { it.trackerId == TrackerManager.ANILIST } && tracks.none { it.trackerId == id }
                }
                .keys
            for (mangaId in mangaIds) {
                if (!isLoggedIn) return
                if (getManga.await(mangaId)?.favorite != true) continue
                autoBind(mangaId, requireFavorite = true)
                delay(BACKFILL_DELAY_MS)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: could not bind the library" }
        }
    }

    // ---- What is sent ----

    /** A bound title: named by its `media_id`, the title is only there because the API requires one. */
    private fun boundQuery(track: Track) = AzitTitleQuery(mediaId = track.remote_id, title = track.title)

    /** Everything Komikku knows about the manga: to find the title in Azit, or to create it. */
    private suspend fun fullQuery(track: Track): AzitTitleQuery {
        val manga = track.manga_id.takeIf { it > 0 }?.let { getManga.await(it) }
            ?: return AzitTitleQuery(title = track.title)
        val source = sourceManager.get(manga.source)
        val titles = listOf(manga.title, manga.ogTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val anilistId = getTracks.await(manga.id)
            .firstOrNull { it.trackerId == TrackerManager.ANILIST }
            ?.remoteId
            ?.takeIf { it > 0 }
        val releaseStatus = AzitMapping.toReleaseStatus(manga.status)
        val totalChapters = if (releaseStatus == "finished") {
            getChaptersByMangaId.await(manga.id).maxOfOrNull { it.chapterNumber }?.toInt()?.takeIf { it > 0 }
        } else {
            null
        }
        return AzitTitleQuery(
            title = titles.firstOrNull() ?: track.title,
            altTitles = titles.drop(1),
            anilistId = anilistId,
            url = mangaUrl(manga),
            linkLabel = source?.name,
            kind = AzitMapping.kindFor(manga.genre, source?.name),
            coverUrl = manga.thumbnailUrl?.takeIf { it.startsWith("http") },
            description = manga.description?.take(20_000),
            authors = AzitMapping.authors(manga.author, manga.artist),
            genres = manga.genre.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(50),
            releaseStatus = releaseStatus,
            totalChapters = totalChapters,
        )
    }

    /** The page of the chapter just read (the title's shortcut in Azit) and the source's name. */
    private suspend fun readingPage(track: Track): Pair<String?, String?> {
        return try {
            val manga = getManga.await(track.manga_id) ?: return null to null
            val source = sourceManager.get(manga.source) as? HttpSource ?: return null to null
            val chapters = getChaptersByMangaId.await(manga.id).filter { it.chapterNumber == track.last_chapter_read }
            val chapter = chapters.firstOrNull { it.read } ?: chapters.firstOrNull()
            val url = chapter?.let { runCatching { source.getChapterUrl(it.toSChapter()) }.getOrNull() }
                ?: mangaUrl(manga)
            url to source.name
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: no reading page for ${track.title}" }
            null to null
        }
    }

    private fun mangaUrl(manga: Manga): String? {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return null
        return runCatching { source.getMangaUrl(manga.toSManga()) }.getOrNull()
    }
}
