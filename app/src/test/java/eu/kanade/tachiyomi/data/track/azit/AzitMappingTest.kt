package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.AzitMapping.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SManga
import exh.util.MangaType
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AzitMappingTest {

    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `statuses map both ways`() {
        listOf(
            AzitMapping.READING,
            AzitMapping.COMPLETED,
            AzitMapping.ON_HOLD,
            AzitMapping.DROPPED,
            AzitMapping.PLAN_TO_READ,
            AzitMapping.REREADING,
        ).forEach { status -> AzitMapping.fromAzitStatus(AzitMapping.toAzitStatus(status)) shouldBe status }
        AzitMapping.toAzitStatus(AzitMapping.ON_HOLD) shouldBe "paused"
        AzitMapping.toAzitStatus(AzitMapping.REREADING) shouldBe "repeating"
        AzitMapping.fromAzitStatus(null) shouldBe null
    }

    @Test
    fun `scores are shown on 10 and sent on 100`() {
        AzitMapping.scoreList.size shouldBe 101
        AzitMapping.scoreList[85] shouldBe "8.5"
        AzitMapping.scoreList.last() shouldBe "10.0"
        // The list index is the 0-100 value kept on the track.
        AzitMapping.toAzitScore(85.0) shouldBe 85
        AzitMapping.toAzitScore(0.0) shouldBe null
        AzitMapping.displayScore(85.0) shouldBe "8.5"
        AzitMapping.displayScore(0.0) shouldBe "-"
    }

    @Test
    fun `dates round trip as local days`() {
        val millis = AzitMapping.fromAzitDate("2024-03-01", paris)
        AzitMapping.toAzitDate(millis, paris) shouldBe "2024-03-01"
        AzitMapping.toAzitDate(0, paris) shouldBe null
        AzitMapping.fromAzitDate(null, paris) shouldBe 0
        AzitMapping.toAzitDate(AzitMapping.fromAzitDate("2024-03", paris), paris) shouldBe "2024-03-01"
        AzitMapping.fromAzitDate("not a date", paris) shouldBe 0
    }

    @Test
    fun `release statuses come from the source status`() {
        AzitMapping.toReleaseStatus(SManga.ONGOING.toLong()) shouldBe "releasing"
        AzitMapping.toReleaseStatus(SManga.COMPLETED.toLong()) shouldBe "finished"
        AzitMapping.toReleaseStatus(SManga.PUBLISHING_FINISHED.toLong()) shouldBe "finished"
        AzitMapping.toReleaseStatus(SManga.ON_HIATUS.toLong()) shouldBe "hiatus"
        AzitMapping.toReleaseStatus(SManga.CANCELLED.toLong()) shouldBe "cancelled"
        AzitMapping.toReleaseStatus(SManga.UNKNOWN.toLong()) shouldBe null
    }

    @Test
    fun `kind and authors`() {
        AzitMapping.kindFor(listOf("Action", "Light Novel"), "Some Source") shouldBe "novel"
        AzitMapping.kindFor(listOf("Action"), "NovelBin") shouldBe "novel"
        AzitMapping.kindFor(listOf("Action"), "MangaDex") shouldBe "manga"
        AzitMapping.kindFor(null, null) shouldBe "manga"
        AzitMapping.authors("Jane Doe, John Roe", "John Roe") shouldBe listOf("Jane Doe", "John Roe")
        AzitMapping.authors(null, " ") shouldBe emptyList()
    }

    @Test
    fun `the type is guessed from genres, source and Komikku's reading type`() {
        val all = AzitMapping.BOOK_TYPE_SLUGS
        AzitMapping.guessTypeSlug(listOf("Action", "Light Novel"), "Some Source", MangaType.TYPE_MANGA, all) shouldBe
            "light_novel"
        AzitMapping.guessTypeSlug(listOf("Web Novel"), null, MangaType.TYPE_MANGA, all) shouldBe "web_novel"
        AzitMapping.guessTypeSlug(listOf("Action"), "NovelBin", MangaType.TYPE_MANGA, all) shouldBe "novel"
        AzitMapping.guessTypeSlug(listOf("Action"), "Toonily", MangaType.TYPE_MANHWA, all) shouldBe "manhwa"
        AzitMapping.guessTypeSlug(listOf("Long Strip"), null, MangaType.TYPE_WEBTOON, all) shouldBe "webtoon"
        AzitMapping.guessTypeSlug(emptyList(), null, MangaType.TYPE_MANHUA, all) shouldBe "manhua"
        AzitMapping.guessTypeSlug(emptyList(), null, MangaType.TYPE_MANGA, all) shouldBe "manga"
    }

    @Test
    fun `a type Azit doesn't have is left automatic`() {
        val onlyManga = listOf("manga", "novel")
        AzitMapping.guessTypeSlug(emptyList(), null, MangaType.TYPE_WEBTOON, onlyManga) shouldBe null
        // A light novel falls back on "novel" when that is all Azit has.
        AzitMapping.guessTypeSlug(listOf("Light Novel"), null, MangaType.TYPE_MANGA, onlyManga) shouldBe "novel"
    }

    @Test
    fun `an entry is copied onto a track`() {
        val entry = Json { ignoreUnknownKeys = true }.decodeFromString<AzitEntry>(AzitApiTest.ENTRY)

        val track = TrackSearch.create(100L).copyFrom(entry, "http://azit.test:8080", paris)

        track.remote_id shouldBe 42
        track.title shouldBe "Solo Leveling"
        track.last_chapter_read shouldBe 12.0
        track.total_chapters shouldBe 200
        track.status shouldBe AzitMapping.READING
        track.score shouldBe 85.0
        AzitMapping.toAzitDate(track.started_reading_date, paris) shouldBe "2024-03-01"
        track.finished_reading_date shouldBe 0
        track.tracking_url shouldBe "http://azit.test:8080/media/42"
    }
}
