package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.Azit.Companion.copyFrom
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
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

        entry!!.mediaId shouldBe 42
        entry.progress.chapter shouldBe 12.0
        entry.status shouldBe "current"
    }

    @Test
    fun `lookup returns null when the title is not in Azit`() = runBlocking<Unit> {
        api(body = "null").lookup(AzitTitleQuery(title = "Unknown")) shouldBe null
    }

    @Test
    fun `progress sends chapter, status and the create and forward flags`() = runBlocking<Unit> {
        val write = AzitProgressWrite.from(
            query = AzitTitleQuery(title = "Solo Leveling", url = "https://example.org/manga/solo"),
            chapter = 13.0,
            status = Azit.toAzitStatus(Azit.READING),
            createIfMissing = false,
        )

        val entry = api(body = ENTRY).progress(write)

        requests.single().url.encodedPath shouldBe "/api/tracking/progress"
        val json = lastJson()
        json["chapter"]!!.jsonPrimitive.double shouldBe 13.0
        json["status"]!!.jsonPrimitive.content shouldBe "current"
        json["create_if_missing"]!!.jsonPrimitive.boolean shouldBe false
        json["only_forward"]!!.jsonPrimitive.boolean shouldBe true
        json["url"]!!.jsonPrimitive.content shouldBe "https://example.org/manga/solo"
        entry.mediaId shouldBe 42
    }

    @Test
    fun `a refused token becomes a readable error`() {
        val error = assertThrows<AzitException> {
            runBlocking { api(code = 401, body = """{"detail":"no"}""").lookup(AzitTitleQuery(title = "x")) }
        }
        error.message!! shouldContain "token"
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

    @Test
    fun `statuses map both ways`() {
        listOf(Azit.READING, Azit.COMPLETED, Azit.ON_HOLD, Azit.DROPPED, Azit.PLAN_TO_READ, Azit.REREADING)
            .forEach { status -> Azit.fromAzitStatus(Azit.toAzitStatus(status)) shouldBe status }
        Azit.toAzitStatus(Azit.ON_HOLD) shouldBe "paused"
        Azit.fromAzitStatus(null) shouldBe null
    }

    @Test
    fun `an entry is copied onto a track`() {
        val entry = Json { ignoreUnknownKeys = true }
            .decodeFromString<eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry>(ENTRY)

        val track = TrackSearch.create(100L).copyFrom(entry, SERVER)

        track.remote_id shouldBe 42
        track.title shouldBe "Solo Leveling"
        track.last_chapter_read shouldBe 12.0
        track.status shouldBe Azit.READING
        track.tracking_url shouldBe "$SERVER/media/42"
    }

    companion object {
        private const val SERVER = "http://azit.test:8080"
        private const val TOKEN = "azd_test_token"
        private const val ENTRY = """
            {"media_id": 42, "title": "Solo Leveling", "type": "manhwa", "created": false, "in_library": true,
             "status": "current", "progress": {"chapter": 12}, "main_unit": "chapter", "score": 85}
        """
    }
}
