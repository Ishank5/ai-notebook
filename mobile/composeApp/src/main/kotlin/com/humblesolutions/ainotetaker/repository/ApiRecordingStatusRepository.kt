package com.humblesolutions.ainotetaker.repository

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the server has made of this person's recordings: their status, nothing
 * more, from `POST /api/recordings/statuses`.
 *
 * TAS read each `recordings/{id}` document from Firestore. The
 * API answers for many ids at once and, as the rule did, only for the caller's
 * own recordings: anybody else's id is simply left out of the answer.
 *
 * NEVER GUESSES. An id the server did not answer for, or a call that did not
 * get through, is left out, and its row goes on saying "Sent".
 */
class ApiRecordingStatusRepository : RecordingStatusRepository {

    override suspend fun serverStatuses(ids: List<String>): Map<String, String> {
        val statuses = mutableMapOf<String, String>()
        for (batch in ids.distinct().chunked(MAX_IDS)) {
            try {
                val reply = CrmApi.call(
                    "POST",
                    "/api/recordings/statuses",
                    JSONObject().put("ids", JSONArray(batch)),
                )
                if (!reply.ok) continue
                val answered = reply.body.optJSONObject("statuses") ?: continue
                for (id in batch) {
                    (answered.opt(id) as? String)?.let { statuses[id] = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No signal: these stay as they are on the phone.
            }
        }
        return statuses
    }

    private companion object {
        /** The API takes at most this many ids per call. */
        const val MAX_IDS = 100
    }
}
