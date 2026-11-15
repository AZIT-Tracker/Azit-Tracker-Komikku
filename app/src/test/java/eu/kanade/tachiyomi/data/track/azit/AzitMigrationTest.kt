package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.azit.AzitMapping.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaPage
import eu.kanade.tachiyomi.data.track.azit.dto.AzitSection
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.time.ZoneId
import tachiyomi.domain.track.model.Track as DomainTrack

class AzitMigrationTest {

    private val paris = ZoneId.of("Europe/Paris")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun anilistTrack(
        status: Long = Anilist.READING,
        chapters: Double = 0.0,
        score: Double = 0.0,
        start: Long = 0,
        finish: Long = 0,
    ) = DomainTrack(
        id = 1,
        mangaId = 10,
        trackerId = TrackerManager.ANILIST,
        remoteId = 105398,
        libraryId = null,
        title = "Solo Leveling",
        lastChapterRead = chapters,
        totalChapters = 200,
        status = status,
        score = score,
        remoteUrl = "https://anilist.co/manga/105398",
        startDate = start,
        finishDate = finish,
        private = false,
    )

    @Test
    fun `every AniList status has an Azit status`() {
        mapOf(
            Anilist.READING to "current",
            Anilist.COMPLETED to "completed",
            Anilist.ON_HOLD to "paused",
            Anilist.DROPPED to "dropped",
            Anilist.PLAN_TO_READ to "planning",
            Anilist.REREADING to "repeating",
        ).forEach { (anilist, azit) ->
            AzitMapping.toAzitStatus(AzitMapping.fromAnilistStatus(anilist)) shouldBe azit
        }
    }

    @Test
    fun `the AniList track is copied onto the bound title`() {
        val start = AzitMapping.fromAzitDate("2023-01-15", paris)
        val finish = AzitMapping.fromAzitDate("2024-02-01", paris)

        val write = AzitMapping.migrationWrite(
            mediaId = 42,
            title = "Solo Leveling",
            anilist = anilistTrack(Anilist.COMPLETED, chapters = 200.0, score = 85.0, start = start, finish = finish),
            zone = paris,
        )
        val body = write.toJson(json)

        body["media_id"]!!.jsonPrimitive.long shouldBe 42
        body["chapter"]!!.jsonPrimitive.double shouldBe 200.0
        body["status"]!!.jsonPrimitive.content shouldBe "completed"
        // AniList keeps 0-100 on the track, like Azit.
        body["score"]!!.jsonPrimitive.int shouldBe 85
        body["started_on"]!!.jsonPrimitive.content shouldBe "2023-01-15"
        body["finished_on"]!!.jsonPrimitive.content shouldBe "2024-02-01"
        body["only_forward"]!!.jsonPrimitive.boolean shouldBe false
        body["force_create"]!!.jsonPrimitive.boolean shouldBe false
    }

    @Test
    fun `an unscored, undated AniList track clears nothing by accident`() {
        val body = AzitMapping.migrationWrite(42, "Solo Leveling", anilistTrack(Anilist.PLAN_TO_READ), paris)
            .toJson(json)

        body["status"]!!.jsonPrimitive.content shouldBe "planning"
        body["score"] shouldBe JsonNull
        body["started_on"] shouldBe JsonNull
        body["finished_on"] shouldBe JsonNull
    }

    @Test
    fun `the summary counts migrated, created and failed titles`() {
        val summary = AzitMigrationSummary()
        summary.add(Azit.MigrationOutcome.MIGRATED)
        summary.add(Azit.MigrationOutcome.CREATED)
        summary.add(Azit.MigrationOutcome.SKIPPED)
        summary.addFailure("Broken")

        summary.migrated shouldBe 2
        summary.created shouldBe 1
        summary.skipped shouldBe 1
        summary.failed shouldBe listOf("Broken")
    }

    @Test
    fun `book types come from the sections, in a stable order`() {
        val sections = json.decodeFromString<List<AzitSection>>(
            """
            [{"id": 1, "slug": "books", "name": "Books", "media_types": [
                {"id": 7, "slug": "light_novel", "name": "Light novel"},
                {"id": 3, "slug": "manga", "name": "Manga"},
                {"id": 4, "slug": "manhwa", "name": "Manhwa"}]},
             {"id": 2, "slug": "watch", "name": "Watch", "media_types": [{"id": 9, "slug": "anime", "name": "Anime"}]}]
            """,
        )

        AzitMapping.bookTypes(sections).map { it.id } shouldBe listOf(3L, 4L, 7L)
        AzitMapping.kindForType("light_novel") shouldBe "novel"
        AzitMapping.kindForType("manhwa") shouldBe "manga"
        AzitMapping.kindForType("comic") shouldBe "comic"
        AzitMapping.kindForType(null, "novel") shouldBe "novel"
    }

    @Test
    fun `a catalogue title becomes a search result with its cover`() {
        val page = json.decodeFromString<AzitMediaPage>(
            """
            {"items": [{"id": 42, "title": "Solo Leveling", "media_type_slug": "manhwa", "media_type_name": "Manhwa",
              "format": "Manhwa", "release_status": "finished", "start_year": 2018,
              "cover_url": "/api/images/3/file", "has_cover": true, "unit_totals": {"chapter": 200},
              "entry": {"status": "current", "score": 85, "progress": {"chapter": 12}}},
             {"id": 43, "title": "No cover", "cover_url": "/static/placeholder.svg", "has_cover": false,
              "unit_totals": {}, "entry": null}],
             "total": 2}
            """,
        )

        val result = TrackSearch.create(100L).copyFrom(page.items[0], "http://azit.test:8080")
        result.remote_id shouldBe 42
        result.cover_url shouldBe "http://azit.test:8080/api/images/3/file"
        result.publishing_type shouldBe "Manhwa"
        result.start_date shouldBe "2018"
        result.total_chapters shouldBe 200
        result.last_chapter_read shouldBe 12.0
        result.status shouldBe AzitMapping.READING
        result.score shouldBe 85.0
        result.tracking_url shouldBe "http://azit.test:8080/media/42"

        val placeholder = TrackSearch.create(100L).copyFrom(page.items[1], "http://azit.test:8080")
        placeholder.cover_url shouldBe ""
        placeholder.status shouldBe AzitMapping.PLAN_TO_READ
    }

    @Test
    fun `numbers are shown without a useless decimal`() {
        AzitMapping.formatNumber(3.0) shouldBe "3"
        AzitMapping.formatNumber(3.5) shouldBe "3.5"
    }
}
