package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.AzitMapping.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMediaPage
import eu.kanade.tachiyomi.data.track.azit.dto.AzitSection
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class AzitMigrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `the summary counts linked, not found and failed titles`() {
        val summary = AzitMigrationSummary()
        summary.add(AzitLinker.Outcome.LINKED, "Solo Leveling")
        summary.add(AzitLinker.Outcome.NOT_FOUND, "Unknown Manhwa")
        summary.add(AzitLinker.Outcome.ALREADY_LINKED, "Already")
        summary.addFailure("Broken")

        summary.linked shouldBe 1
        summary.notFound shouldBe listOf("Unknown Manhwa")
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

        AzitMapping.bookTypes(sections).map { it.id } shouldBe listOf(3L, 4L) // no novel types: LNReader's
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
