package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntryFields
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AzitApiTest {

    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    private fun api(code: Int = 200, body: String): AzitApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    bodies += request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return AzitApi(client, { SERVER }, { TOKEN })
    }

    private fun lastJson(): JsonObject = Json.parseToJsonElement(bodies.last()).jsonObject

    @Test
    fun `lookup sends the title fields with the device token`() = runBlocking<Unit> {
        val entry = api(body = ENTRY).lookup(
            AzitTitleQuery(title = "Solo Leveling", altTitles = listOf("나 혼자만 레벨업"), anilistId = 105398),
        )

        val request = requests.single()
        request.url.toString() shouldBe "$SERVER/api/tracking/lookup"
        request.method shouldBe "POST"
        request.header("Authorization") shouldBe "Bearer $TOKEN"
        val json = lastJson()
        json["title"]!!.jsonPrimitive.content shouldBe "Solo Leveling"
        json["alt_titles"]!!.jsonArray.size shouldBe 1
        json["anilist_id"]!!.jsonPrimitive.long shouldBe 105398
        json["kind"]!!.jsonPrimitive.content shouldBe "manga"
        bodies.last() shouldNotContain "\"url\""
        bodies.last() shouldNotContain "media_id"

        entry!!.mediaId shouldBe 42
        entry.progress.chapter shouldBe 12.0
        entry.status shouldBe "current"
        entry.total shouldBe 200.0
        entry.startedOn shouldBe "2024-03-01"
    }

    @Test
    fun `lookup returns null when the title is not in Azit`() = runBlocking<Unit> {
        api(body = "null").lookup(AzitTitleQuery(title = "Unknown")) shouldBe null
    }

    @Test
    fun `a chapter read sends the bound id, the chapter page and only forward`() = runBlocking<Unit> {
        val write = AzitProgressWrite.from(
            query = AzitTitleQuery(
                mediaId = 42,
                title = "Solo Leveling",
                url = "https://example.org/chapter/13",
                linkLabel = "Example",
            ),
            chapter = 13.0,
            status = AzitMapping.toAzitStatus(AzitMapping.READING),
        )

        api(body = ENTRY).progress(write)

        requests.single().url.encodedPath shouldBe "/api/tracking/progress"
        val json = lastJson()
        json["media_id"]!!.jsonPrimitive.long shouldBe 42
        json["chapter"]!!.jsonPrimitive.double shouldBe 13.0
        json["status"]!!.jsonPrimitive.content shouldBe "current"
        json["only_forward"]!!.jsonPrimitive.boolean shouldBe true
        json["force_create"]!!.jsonPrimitive.boolean shouldBe false
        json["url"]!!.jsonPrimitive.content shouldBe "https://example.org/chapter/13"
        json["link_label"]!!.jsonPrimitive.content shouldBe "Example"
        // Not an edit: score and dates must stay untouched in Azit, so they are not sent at all.
        json.containsKey("score") shouldBe false
        json.containsKey("started_on") shouldBe false
        json.containsKey("finished_on") shouldBe false
    }

    @Test
    fun `a manual edit sends score and dates, null included to clear them`() = runBlocking<Unit> {
        val write = AzitProgressWrite.from(
            query = AzitTitleQuery(mediaId = 42, title = "Solo Leveling"),
            chapter = 5.0,
            status = "paused",
            onlyForward = false,
            entryFields = AzitEntryFields(score = 85, startedOn = "2024-03-01", finishedOn = null),
        )

        api(body = ENTRY).progress(write)

        val json = lastJson()
        json["only_forward"]!!.jsonPrimitive.boolean shouldBe false
        json["chapter"]!!.jsonPrimitive.double shouldBe 5.0
        json["score"]!!.jsonPrimitive.int shouldBe 85
        json["started_on"]!!.jsonPrimitive.content shouldBe "2024-03-01"
        json["finished_on"] shouldBe JsonNull
    }

    @Test
    fun `create in Azit sends the app's data with force_create`() = runBlocking<Unit> {
        val write = AzitProgressWrite.from(
            query = AzitTitleQuery(
                title = "Some Manhwa",
                coverUrl = "https://example.org/cover.jpg",
                description = "A story",
                authors = listOf("Jane Doe"),
                genres = listOf("Action"),
                releaseStatus = "finished",
                totalChapters = 120,
                url = "https://example.org/manga/some",
                linkLabel = "Example",
            ),
            createIfMissing = true,
            forceCreate = true,
        )

        api(body = ENTRY).progress(write)

        val json = lastJson()
        json["force_create"]!!.jsonPrimitive.boolean shouldBe true
        json["create_if_missing"]!!.jsonPrimitive.boolean shouldBe true
        json["cover_url"]!!.jsonPrimitive.content shouldBe "https://example.org/cover.jpg"
        json["description"]!!.jsonPrimitive.content shouldBe "A story"
        json["authors"]!!.jsonArray.single().jsonPrimitive.content shouldBe "Jane Doe"
        json["genres"]!!.jsonArray.single().jsonPrimitive.content shouldBe "Action"
        json["release_status"]!!.jsonPrimitive.content shouldBe "finished"
        json["total_chapters"]!!.jsonPrimitive.int shouldBe 120
        json.containsKey("chapter") shouldBe false
    }

    @Test
    fun `refresh reads the bound entry and remove deletes it`() = runBlocking<Unit> {
        api(body = ENTRY).entry(42).mediaId shouldBe 42
        requests.last().method shouldBe "GET"
        requests.last().url.toString() shouldBe "$SERVER/api/tracking/entry/42"

        api(code = 204, body = "").removeEntry(42)
        requests.last().method shouldBe "DELETE"
        requests.last().url.toString() shouldBe "$SERVER/api/tracking/entry/42"
        requests.last().header("Authorization") shouldBe "Bearer $TOKEN"
    }

    @Test
    fun `media search asks for book types with the device token`() = runBlocking<Unit> {
        val page = api(body = """{"items": [{"id": 42, "title": "Solo Leveling"}], "total": 1}""")
            .searchMedia("solo lev", listOf(3, 4, 7))

        val request = requests.single()
        request.method shouldBe "GET"
        request.url.encodedPath shouldBe "/api/media"
        request.url.queryParameter("search") shouldBe "solo lev"
        request.url.queryParameter("media_type_ids") shouldBe "3,4,7"
        request.url.queryParameter("per_page") shouldBe "20"
        request.header("Authorization") shouldBe "Bearer $TOKEN"
        page.items.single().id shouldBe 42
    }

    @Test
    fun `a volume edit sends the volume`() = runBlocking<Unit> {
        api(body = ENTRY).progress(
            AzitProgressWrite.from(query = AzitTitleQuery(mediaId = 42, title = "x")).copy(volume = 3.0, onlyForward = false),
        )

        lastJson()["volume"]!!.jsonPrimitive.double shouldBe 3.0
        lastJson().containsKey("chapter") shouldBe false
    }

    @Test
    fun `a refused token becomes a readable error`() {
        val error = assertThrows<AzitException> {
            runBlocking { api(code = 401, body = """{"detail":"no"}""").lookup(AzitTitleQuery(title = "x")) }
        }
        error.message!! shouldContain "token"
        error.code shouldBe 401
    }

    @Test
    fun `checkLogin calls devices me`() = runBlocking<Unit> {
        api(body = """{"ok":true,"caller":"device:Phone"}""").checkLogin("http://other:8080", "azd_other")

        val request = requests.single()
        request.url.toString() shouldBe "http://other:8080/api/devices/me"
        request.header("Authorization") shouldBe "Bearer azd_other"
    }

    @Test
    fun `server addresses are normalized`() {
        AzitApi.normalizeServer(" 192.168.1.10:8080/ ") shouldBe "http://192.168.1.10:8080"
        AzitApi.normalizeServer("https://azit.example.org") shouldBe "https://azit.example.org"
    }

    companion object {
        private const val SERVER = "http://azit.test:8080"
        private const val TOKEN = "azd_test_token"
        const val ENTRY = """
            {"media_id": 42, "title": "Solo Leveling", "type": "manhwa", "created": false, "in_library": true,
             "status": "current", "progress": {"chapter": 12}, "main_unit": "chapter", "score": 85,
             "started_on": "2024-03-01", "finished_on": null, "total": 200, "cover_url": "/api/images/3/file",
             "shortcut_url": "https://example.org/chapter/12"}
        """
    }
}
