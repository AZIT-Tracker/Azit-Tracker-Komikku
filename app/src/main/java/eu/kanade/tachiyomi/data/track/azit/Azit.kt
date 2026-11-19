package eu.kanade.tachiyomi.data.track.azit

import android.app.Application
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.azit.AzitMapping.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitDuplicates
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntryFields
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaType
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import exh.util.mangaType
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
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
    private val getHistory: GetHistory by injectLazy()
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
        // KMK -->
        if (didReadChapter) return track.copyEntry(api.progress(chapterWrite(track)))
        // KMK <--
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
                createIfMissing = false,
            )
        } else {
            AzitProgressWrite.from(
                query = boundQuery(track),
                chapter = track.last_chapter_read,
                status = AzitMapping.toAzitStatus(track.status),
                createIfMissing = false,
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

    // KMK -->
    /** A chapter read: progress only moves forward, the status follows, and the chapter page becomes the shortcut. */
    private suspend fun chapterWrite(track: Track): AzitProgressWrite {
        if (track.status != COMPLETED) {
            if (track.total_chapters > 0 && track.last_chapter_read.toLong() >= track.total_chapters) {
                track.status = COMPLETED
                if (track.finished_reading_date == 0L) track.finished_reading_date = System.currentTimeMillis()
            } else if (track.status != REREADING) {
                track.status = READING
            }
        }
        val (url, label) = readingPage(track)
        return AzitProgressWrite.from(
            query = boundQuery(track).copy(url = url, linkLabel = label),
            chapter = track.last_chapter_read.takeIf { it > 0 },
            status = AzitMapping.toAzitStatus(track.status),
            createIfMissing = false,
        )
    }

    /**
     * Chapters read while Azit could not be reached, all in one request ([TrackingQueue]): one result per track, in
     * the same order (the updated track, or why Azit refused it).
     */
    suspend fun updateMany(tracks: List<Track>): List<Result<Track>> {
        val bound = tracks.filter { it.remote_id > 0 }
        val answers = api.progressBatch(bound.map { chapterWrite(it) }).iterator()
        return tracks.map { track ->
            if (track.remote_id <= 0) runCatching { bind(track) } else answers.next().map { track.copyEntry(it) }
        }
    }
    // KMK <--

    /**
     * Linking only: the Azit state is read into the track, nothing is written to Azit (see [AzitLinker]).
     * Komikku's usual push of local progress after binding is skipped for Azit too (in [AddTracks]).
     *
     * The one exception is the "Create in Azit" result, which the user picks to create a new title.
     */
    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        if (track.remote_id == AzitMapping.CREATE_REMOTE_ID) {
            // The fields edited in the "Create in Azit" dialog, else what Komikku knows as is.
            val draft = pendingDrafts.remove(track.manga_id) ?: createDraft(track.manga_id)
            // KMK -->
            val write = draft.toWrite()
            val coverId = uploadCover(track.manga_id, write.coverUrl)
            return track.copyEntry(api.progress(if (coverId != null) write.copy(coverImageId = coverId) else write))
            // KMK <--
        }
        val entry = linker.find(track.remote_id) { fullQuery(track) }
            ?: throw AzitException(context.stringResource(KMR.strings.azit_error_not_in_azit), code = 404)
        return track.copyEntry(entry)
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

    private val pendingDrafts = ConcurrentHashMap<Long, AzitCreateDraft>()

    private var cachedBookTypes: List<AzitMediaType>? = null

    /** Azit's book types (manga, manhwa, light novel...), for the search and the "Create in Azit" dialog. */
    suspend fun bookTypes(): List<AzitMediaType> {
        cachedBookTypes?.let { return it }
        return AzitMapping.bookTypes(api.sections()).also { cachedBookTypes = it }
    }

    /**
     * What Komikku knows, to be edited before creating the title in Azit: the manga's details, and the entry
     * from the other trackers of the manga (AniList first), else from Komikku's chapters and reading history.
     */
    suspend fun createDraft(mangaId: Long): AzitCreateDraft {
        val track = Track.create(id).apply {
            manga_id = mangaId
            title = getManga.await(mangaId)?.title.orEmpty()
        }
        val query = fullQuery(track)
        return AzitCreateDraft(query = query.copy(typeSlug = guessTypeSlug(mangaId)), entry = entryPrefill(mangaId))
    }

    /** Azit type of the manga (manhwa, webtoon, light novel...), when Azit has that type. */
    private suspend fun guessTypeSlug(mangaId: Long): String? {
        val manga = getManga.await(mangaId) ?: return null
        val sourceName = sourceManager.get(manga.source)?.name
        val available = try {
            bookTypes().map { it.slug }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: no media types, type left automatic" }
            return null
        }
        return AzitMapping.guessTypeSlug(manga.genre, sourceName, manga.mangaType(sourceName), available)
    }

    private suspend fun entryPrefill(mangaId: Long): AzitEntryPrefill {
        val trackerManager = Injekt.get<TrackerManager>()
        val sources = getTracks.await(mangaId)
            .filter { it.trackerId != id }
            .mapNotNull { other ->
                val tracker = trackerManager.get(other.trackerId) ?: return@mapNotNull null
                AzitPrefillSource(
                    isAnilist = other.trackerId == TrackerManager.ANILIST,
                    status = TrackStatus.parseTrackerStatus(trackerManager, other.trackerId, other.status),
                    chapter = other.lastChapterRead,
                    score10 = runCatching { tracker.get10PointScore(other) }.getOrDefault(0.0),
                    startDate = other.startDate,
                    finishDate = other.finishDate,
                )
            }
        val chapters = getChaptersByMangaId.await(mangaId)
        val readDates = getHistory.await(mangaId).mapNotNull { it.readAt?.time?.takeIf { time -> time > 0 } }
        val local = AzitLocalReading(
            lastReadChapter = chapters.filter { it.read }.maxOfOrNull { it.chapterNumber }?.takeIf { it > 0 } ?: 0.0,
            allRead = chapters.isNotEmpty() && chapters.all { it.read },
            firstReadAt = readDates.minOrNull() ?: 0,
            lastReadAt = readDates.maxOrNull() ?: 0,
        )
        return AzitPrefill.entry(sources, local)
    }

    /** Creates the title in Azit from the edited [draft] (details and entry) and binds the manga to it. */
    suspend fun createFromDraft(mangaId: Long, draft: AzitCreateDraft) {
        pendingDrafts[mangaId] = draft
        val track = TrackSearch.create(id).apply {
            remote_id = AzitMapping.CREATE_REMOTE_ID
            manga_id = mangaId
            title = draft.query.title
            tracking_url = "${getServer()}/#create"
        }
        addTracks.bind(this, track, mangaId)
    }

    // KMK -->
    /**
     * Before "Create in Azit": titles Azit already has for this manga (same AniList id or page: [AzitDuplicates.linked],
     * same name: [AzitDuplicates.sameName]). Nothing found (or Azit unreachable): an empty answer.
     */
    suspend fun duplicatesFor(mangaId: Long, draft: AzitCreateDraft): AzitDuplicates {
        val track = Track.create(id).apply {
            manga_id = mangaId
            title = draft.query.title
        }
        val known = fullQuery(track)
        return try {
            api.duplicates(draft.query.copy(url = draft.query.url ?: known.url, anilistId = draft.query.anilistId ?: known.anilistId))
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: duplicate check failed" }
            AzitDuplicates()
        }
    }

    /** "Use this one": the manga is bound to a title Azit already has, instead of creating a new one. */
    suspend fun bindExisting(mangaId: Long, entry: AzitEntry) {
        val track = TrackSearch.create(id).apply {
            remote_id = entry.mediaId
            manga_id = mangaId
            title = entry.title
            tracking_url = "${getServer()}/media/${entry.mediaId}"
            cover_url = AzitMapping.absoluteUrl(entry.coverUrl, getServer()).orEmpty()
        }
        addTracks.bind(this, track, mangaId)
    }
    // KMK <--

    // ---- Migration from AniList ----

    /**
     * "Migrate from AniList" for one manga: links it to its Azit title (found by AniList id and titles) and
     * nothing else. No status, chapter, score or date is written to Azit or AniList, and a title Azit doesn't
     * have is not created. AniList is unlinked in Komikku afterwards if asked, only when the link worked.
     */
    suspend fun migrateFromAnilist(anilistTrack: DomainTrack, unbindAnilist: Boolean): AzitLinker.Outcome {
        val outcome = linkManga(anilistTrack.mangaId)
        if (outcome == AzitLinker.Outcome.LINKED && unbindAnilist) {
            deleteTrack.await(anilistTrack.mangaId, anilistTrack.trackerId)
        }
        return outcome
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
        423 -> context.stringResource(KMR.strings.azit_error_paused)
        else -> context.stringResource(KMR.strings.azit_error_http, code)
    }

    // ---- Automatic binding ----

    private val linker by lazy { AzitLinker(api) }

    /**
     * Links a manga to its Azit title (read only, see [AzitLinker]) and saves the Azit state as its local track.
     * Komikku's chapters and AniList are left as they are.
     */
    suspend fun linkManga(mangaId: Long): AzitLinker.Outcome {
        val manga = getManga.await(mangaId)
        val track = Track.create(id).apply {
            manga_id = mangaId
            title = manga?.title.orEmpty()
        }
        return linker.link(
            alreadyLinked = getTracks.await(mangaId).any { it.trackerId == id },
            query = { fullQuery(track) },
            save = { entry ->
                track.copyEntry(entry)
                track.toDomainTrack(idRequired = false)?.let { insertTrack.await(it) }
            },
        )
    }

    /**
     * Links a manga to Azit without opening the tracking sheet. Called when a chapter of a library manga is
     * read ([eu.kanade.domain.track.interactor.TrackChapter]) and when the manga gets an AniList track
     * ([AddTracks], `requireFavorite = false`). A title Azit doesn't have is skipped.
     */
    suspend fun autoBind(mangaId: Long, requireFavorite: Boolean = true) {
        if (!isLoggedIn || !trackPreferences.azitAutoBind().get()) return
        try {
            if (requireFavorite && getManga.await(mangaId)?.favorite != true) return
            linkManga(mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: could not link manga $mangaId" }
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

    /** A linked title: named by its `media_id`, the title is only there because the API requires one. */
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

    // KMK -->
    /**
     * The cover a new title gets: the file Komikku shows (its custom cover, the cached one, or downloaded with the
     * source's own headers), uploaded to Azit. Sites often refuse their pictures elsewhere, so a link alone shows
     * broken. Null when there is none or it fails: Azit then tries the link itself.
     */
    private suspend fun uploadCover(mangaId: Long, coverUrl: String?): Long? {
        if (coverUrl.isNullOrBlank()) return null
        return try {
            val manga = getManga.await(mangaId)
            val coverCache = Injekt.get<CoverCache>()
            val cached = manga?.let { coverCache.getCustomCoverFile(it.id).takeIf { file -> file.exists() } }
                ?: coverCache.getCoverFile(coverUrl)?.takeIf { it.exists() }
            val bytes = cached?.readBytes() ?: run {
                val source = manga?.let { sourceManager.get(it.source) as? HttpSource }
                val request = GET(coverUrl, source?.headers ?: okhttp3.Headers.headersOf())
                (source?.client ?: client).newCall(request).awaitSuccess().use { it.body.bytes() }
            }
            if (bytes.isEmpty()) return null
            api.uploadCover(bytes, imageMimeType(bytes))
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Azit Tracker: could not upload the cover" }
            null
        }
    }

    private fun imageMimeType(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
        bytes.size > 11 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }
    // KMK <--

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
