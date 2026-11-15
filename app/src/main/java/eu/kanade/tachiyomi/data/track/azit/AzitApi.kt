package eu.kanade.tachiyomi.data.track.azit

import eu.kanade.tachiyomi.data.track.azit.dto.AzitEntry
import eu.kanade.tachiyomi.data.track.azit.dto.AzitMe
import eu.kanade.tachiyomi.data.track.azit.dto.AzitProgressWrite
import eu.kanade.tachiyomi.data.track.azit.dto.AzitTitleQuery
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Client for the tracking API of a self-hosted Azit Tracker.
 *
 * Every call is authenticated with a device token (`Authorization: Bearer azd_...`) created in
 * Azit at Settings → Devices & scrobbler.
 */
class AzitApi(
    private val client: OkHttpClient,
    private val server: () -> String,
    private val token: () -> String,
    /** Turns an HTTP error code into the message shown to the user. */
    private val errorMessage: (Int) -> String = ::defaultErrorMessage,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** Checks an address and a token before saving them ("Test connection"). */
    suspend fun checkLogin(server: String, token: String): AzitMe = withIOContext {
        val response = execute { client.newCall(GET("$server$ME", headers(token))).awaitSuccess() }
        val me = json.decodeFromString<AzitMe>(response)
        if (!me.ok) throw AzitException("Azit Tracker refused this token.")
        me
    }

    /** The library entry for this title, or null when it is not in Azit. */
    suspend fun lookup(query: AzitTitleQuery): AzitEntry? = withIOContext {
        val body = json.encodeToString(AzitTitleQuery.serializer(), query).toRequestBody(JSON_MEDIA_TYPE)
        val response = execute {
            client.newCall(POST("${server()}$LOOKUP", headers(token()), body)).awaitSuccess()
        }
        json.decodeFromString<AzitEntry?>(response)
    }

    /** Finds (or creates) the title and updates its entry (progress, status, score, dates). */
    suspend fun progress(write: AzitProgressWrite): AzitEntry = withIOContext {
        val body = write.toJson(json).toString().toRequestBody(JSON_MEDIA_TYPE)
        val response = execute {
            client.newCall(POST("${server()}$PROGRESS", headers(token()), body)).awaitSuccess()
        }
        json.decodeFromString<AzitEntry>(response)
    }

    /** The current state of a bound title. */
    suspend fun entry(mediaId: Long): AzitEntry = withIOContext {
        val response = execute { client.newCall(GET("${server()}$ENTRY/$mediaId", headers(token()))).awaitSuccess() }
        json.decodeFromString<AzitEntry>(response)
    }

    /** Removes the title from the library (it stays in Azit's catalogue). */
    suspend fun removeEntry(mediaId: Long) {
        withIOContext {
            val request = Request.Builder()
                .url("${server()}$ENTRY/$mediaId")
                .headers(headers(token()))
                .delete()
                .build()
            execute { client.newCall(request).awaitSuccess() }
        }
    }

    private fun headers(token: String): Headers = Headers.Builder()
        .add("Authorization", "Bearer $token")
        .add("Accept", "application/json")
        .build()

    private suspend fun execute(call: suspend () -> okhttp3.Response): String {
        val response = try {
            call()
        } catch (e: HttpException) {
            throw AzitException(errorMessage(e.code), e, e.code)
        }
        return response.use { it.body.string() }
    }

    companion object {
        const val ME = "/api/devices/me"
        const val LOOKUP = "/api/tracking/lookup"
        const val PROGRESS = "/api/tracking/progress"
        const val ENTRY = "/api/tracking/entry"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** `azit.local:8080/` → `http://azit.local:8080`. */
        fun normalizeServer(address: String): String {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }

        fun defaultErrorMessage(code: Int): String = when (code) {
            401, 403 -> "Azit Tracker: invalid or revoked device token"
            404 -> "Azit Tracker: title not found (deleted in Azit, or adding missing titles is turned off)"
            else -> "Azit Tracker: HTTP error $code"
        }
    }
}

class AzitException(message: String, cause: Throwable? = null, val code: Int? = null) : Exception(message, cause)
