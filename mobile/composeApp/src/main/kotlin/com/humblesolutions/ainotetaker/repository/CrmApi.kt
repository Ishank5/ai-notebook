package com.humblesolutions.ainotetaker.repository

import com.google.firebase.auth.FirebaseAuth
import com.humblesolutions.ainotetaker.BuildConfig
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI Notebook HTTP API (the ntApi Cloud Function), as the phone calls it.
 *
 * Firebase is used for sign-in only. Everything TAS asked
 * Firestore and Cloud Functions for (who someone is, upload links, closing a
 * recording, its status) is a plain HTTPS call here, carrying the Firebase ID
 * token as a bearer token. The API checks the token and the person's row in
 * the CRM's own users table on every call.
 *
 * PLAIN HttpURLConnection, like [HttpChunkTransfer]: four small JSON calls do
 * not need a client library.
 */
internal object CrmApi {

    private const val CONNECT_TIMEOUT_MS = 15_000
    /** The callables' own default was 70 seconds; a finalize lists the bucket. */
    private const val READ_TIMEOUT_MS = 60_000

    /** What the server said: its HTTP status and its JSON body (empty when it had none). */
    class Answer(val status: Int, val body: JSONObject) {
        val ok: Boolean get() = status in 200..299

        /** The API's reason code: `inactive`, `no_profile`, `recorder.err.*` … */
        val code: String get() = body.optString("code")
    }

    /**
     * One call. Throws [IOException] only when no answer came back at all: no
     * signal, a timeout, a token that could not be had offline.
     *
     * A 401 IS ASKED AGAIN ONCE, with a token refreshed on the spot. A token
     * expires every hour, and one cached across a night in a pocket is the
     * usual reason the API says "sign in again" to somebody who is signed in.
     */
    suspend fun call(method: String, path: String, body: JSONObject? = null): Answer {
        val raw = callText(method, path, body?.toString())
        return Answer(raw.status, runCatching { JSONObject(raw.text) }.getOrDefault(JSONObject()))
    }

    /** the status and the body as text, for the meetings calls, whose JSON is read in `shared`. */
    class RawAnswer(val status: Int, val text: String)

    /** As [call], with the body sent and returned as text. The same 401 rule: asked again once with a fresh token. */
    suspend fun callText(method: String, path: String, body: String? = null): RawAnswer =
        withContext(Dispatchers.IO) {
            val first = send(method, path, body, token(forceRefresh = false))
            if (first.status != 401) first else send(method, path, body, token(forceRefresh = true))
        }

    private suspend fun token(forceRefresh: Boolean): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            // Offline with an expired token: no answer is possible.
            throw IOException("No ID token: ${e.message}", e)
        }
    }

    private fun send(method: String, path: String, body: String?, token: String?): RawAnswer {
        val connection = URL(BuildConfig.API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return RawAnswer(status, text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A refused recording call as the recorder's key, mirroring how TAS read a
     * callable's error: the server's own `recorder.err.*` key when
     * it gave one; otherwise signed out, inactive, or a server fault.
     *
     * never "no signal". This is only called with an answer in
     * hand, and TAS read every other 4xx (a 404, a 409, a 429) as no signal,
     * which stopped the whole upload run behind one recording. No answer at all
     * is a thrown IOException, read as no signal by the caller.
     */
    fun recorderErrorOf(answer: Answer): String {
        listOf(answer.code, answer.body.optString("error"))
            .firstOrNull { it.startsWith("recorder.err.") }
            ?.let { return it }
        return when {
            answer.status == 401 -> RecorderErrors.SIGNED_OUT
            answer.status == 403 && answer.code in setOf("inactive", "no_profile") -> RecorderErrors.INACTIVE
            else -> RecorderErrors.SERVER
        }
    }
}

/** A Kotlin null as JSON null, which the API reads as "not given", as the callables did. */
internal fun JSONObject.putOrNull(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)
