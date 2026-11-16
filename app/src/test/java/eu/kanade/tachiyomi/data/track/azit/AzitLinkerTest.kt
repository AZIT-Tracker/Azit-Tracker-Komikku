package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

/**
 * Linking (automatic, migration, choosing a search result) must never write to Azit:
 * only `POST /api/tracking/lookup` (read only) and `GET /api/tracking/entry/{id}` are allowed.
 */
class AzitLinkerTest {

    /** Method + path of every request sent. */
    private val requests = mutableListOf<String>()

    private fun linker(lookupAnswer: String = AzitApiTest.ENTRY): AzitLinker {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += "${request.method} ${request.url.encodedPath}"
                    val body = if (request.url.encodedPath.endsWith("/lookup")) lookupAnswer else AzitApiTest.ENTRY
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("test")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        return AzitLinker(AzitApi(client, { "http://azit.test:8080" }, { "azd_test" }))
    }

    private val query = AzitTitleQuery(title = "Solo Leveling", anilistId = 105398)

    private fun assertReadOnly() {
        requests.forEach { request ->
            val allowed = request == "POST /api/tracking/lookup" || request.startsWith("GET /api/tracking/entry/")
            allowed shouldBe true
        }
    }

    @Test
    fun `linking a found title only looks it up and keeps the Azit state`() = runBlocking<Unit> {
        var saved: AzitEntry? = null

        val outcome = linker().link(alreadyLinked = false, query = { query }, save = { saved = it })

        outcome shouldBe AzitLinker.Outcome.LINKED
        requests shouldBe listOf("POST /api/tracking/lookup")
        assertReadOnly()
        // The local track gets Azit's state, it doesn't send its own.
        saved!!.mediaId shouldBe 42
        saved!!.status shouldBe "current"
        saved!!.progress.chapter shouldBe 12.0
    }

    @Test
    fun `a title Azit doesn't have is skipped, never created`() = runBlocking<Unit> {
        var saved = false

        val outcome = linker(lookupAnswer = "null").link(alreadyLinked = false, query = { query }, save = { saved = true })

        outcome shouldBe AzitLinker.Outcome.NOT_FOUND
        saved shouldBe false
        requests shouldBe listOf("POST /api/tracking/lookup")
        assertReadOnly()
    }

    @Test
    fun `an already linked manga sends nothing at all`() = runBlocking<Unit> {
        val outcome = linker().link(alreadyLinked = true, query = { query }, save = {})

        outcome shouldBe AzitLinker.Outcome.ALREADY_LINKED
        requests shouldBe emptyList()
    }

    @Test
    fun `choosing a search result reads that entry`() = runBlocking<Unit> {
        val entry = linker().find(remoteId = 42) { query }

        entry!!.mediaId shouldBe 42
        requests shouldBe listOf("GET /api/tracking/entry/42")
        assertReadOnly()
    }
}
