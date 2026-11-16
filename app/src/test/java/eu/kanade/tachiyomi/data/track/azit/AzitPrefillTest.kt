package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.TrackStatus
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.ZoneId

class AzitPrefillTest {

    private val paris = ZoneId.of("Europe/Paris")
    private fun day(date: String) = AzitMapping.fromAzitDate(date, paris)

    /** The AniList card of the report: Reading / 124 / 8.2 / started 18 May 2023. */
    private val anilist = AzitPrefillSource(
        isAnilist = true,
        status = TrackStatus.READING,
        chapter = 124.0,
        score10 = 8.2,
        startDate = day("2023-05-18"),
        finishDate = 0,
    )

    private val mangaUpdates = AzitPrefillSource(
        isAnilist = false,
        status = TrackStatus.PAUSED,
        chapter = 100.0,
        score10 = 7.0,
        startDate = day("2022-01-01"),
        finishDate = day("2022-06-01"),
    )

    @Test
    fun `the AniList track fills the form`() {
        val entry = AzitPrefill.entry(listOf(anilist), AzitLocalReading())

        entry.status shouldBe AzitMapping.READING
        entry.chapter shouldBe 124.0
        entry.score shouldBe 82.0
        AzitMapping.toAzitDate(entry.startDate, paris) shouldBe "2023-05-18"
        entry.finishDate shouldBe 0
    }

    @Test
    fun `AniList wins over the other trackers, which fill what AniList lacks`() {
        // MangaUpdates is listed first, AniList still wins.
        val entry = AzitPrefill.entry(listOf(mangaUpdates, anilist), AzitLocalReading())

        entry.status shouldBe AzitMapping.READING
        entry.chapter shouldBe 124.0
        entry.score shouldBe 82.0
        AzitMapping.toAzitDate(entry.startDate, paris) shouldBe "2023-05-18"
        // AniList has no finish date: the next tracker's is used.
        AzitMapping.toAzitDate(entry.finishDate, paris) shouldBe "2022-06-01"
    }

    @Test
    fun `without trackers, Komikku's reading and history are used`() {
        val reading = AzitPrefill.entry(
            emptyList(),
            AzitLocalReading(lastReadChapter = 30.0, firstReadAt = day("2024-02-10"), lastReadAt = day("2024-03-01")),
        )
        reading.status shouldBe AzitMapping.READING
        reading.chapter shouldBe 30.0
        reading.score shouldBe 0.0
        AzitMapping.toAzitDate(reading.startDate, paris) shouldBe "2024-02-10"
        // Not completed: no finish date.
        reading.finishDate shouldBe 0

        val completed = AzitPrefill.entry(
            emptyList(),
            AzitLocalReading(
                lastReadChapter = 50.0,
                allRead = true,
                firstReadAt = day("2024-02-10"),
                lastReadAt = day("2024-03-01"),
            ),
        )
        completed.status shouldBe AzitMapping.COMPLETED
        AzitMapping.toAzitDate(completed.finishDate, paris) shouldBe "2024-03-01"
    }

    @Test
    fun `a tracker without dates falls back on the history`() {
        val entry = AzitPrefill.entry(
            listOf(anilist.copy(status = TrackStatus.COMPLETED, startDate = 0)),
            AzitLocalReading(lastReadChapter = 124.0, firstReadAt = day("2023-01-02"), lastReadAt = day("2023-09-09")),
        )

        AzitMapping.toAzitDate(entry.startDate, paris) shouldBe "2023-01-02"
        AzitMapping.toAzitDate(entry.finishDate, paris) shouldBe "2023-09-09"
    }

    @Test
    fun `nothing read means planning, with no dates`() {
        val entry = AzitPrefill.entry(emptyList(), AzitLocalReading(firstReadAt = day("2024-02-10")))

        entry.status shouldBe AzitMapping.PLAN_TO_READ
        entry.chapter shouldBe 0.0
        entry.startDate shouldBe 0
    }

    @Test
    fun `the create call sends the entry with force_create`() {
        val draft = AzitCreateDraft(
            query = AzitTitleQuery(title = "Solo Leveling", typeSlug = "manhwa"),
            entry = AzitPrefill.entry(listOf(anilist), AzitLocalReading()),
        )

        val body = draft.toWrite(paris).toJson(
            Json {
                encodeDefaults = true
                explicitNulls = false
            },
        )

        body["force_create"]!!.jsonPrimitive.boolean shouldBe true
        body["title"]!!.jsonPrimitive.content shouldBe "Solo Leveling"
        body["type_slug"]!!.jsonPrimitive.content shouldBe "manhwa"
        body["status"]!!.jsonPrimitive.content shouldBe "current"
        body["chapter"]!!.jsonPrimitive.double shouldBe 124.0
        body["score"]!!.jsonPrimitive.int shouldBe 82
        body["started_on"]!!.jsonPrimitive.content shouldBe "2023-05-18"
        body["finished_on"] shouldBe JsonNull
    }
}
