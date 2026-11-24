package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.model.ChunkUploadTarget
import com.humblesolutions.ainotetaker.model.EndReason
import com.humblesolutions.ainotetaker.model.FinalizeResult
import com.humblesolutions.ainotetaker.model.LocalRecording
import com.humblesolutions.ainotetaker.model.RecorderDevice
import com.humblesolutions.ainotetaker.model.RecordingChunk
import com.humblesolutions.ainotetaker.model.RecordingGap
import com.humblesolutions.ainotetaker.model.UploadTargets
import com.humblesolutions.ainotetaker.usecase.RecorderErrors
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The recorder's two server calls, `POST /api/recordings/upload-urls` and
 * `POST /api/recordings/finalize`, in the CRM API
 *.
 *
 * these were the `recordingUploadUrls` and `finalizeRecording`
 * Cloud Functions. The payloads and answers are the callables' own, field for
 * field, so the shared sync reads them unchanged; timestamps go as plain JSON
 * numbers now that no callable protocol wraps them.
 */
class ApiRecordingUploadRepository : RecordingUploadRepository {

    override suspend fun uploadTargets(
        recording: LocalRecording,
        chunks: List<RecordingChunk>,
        device: RecorderDevice,
    ): UploadTargets = answer({ UploadTargets(errorKey = it) }) {
        val reply = CrmApi.call(
            "POST",
            "/api/recordings/upload-urls",
            JSONObject()
                // The server refuses a recording that is not the caller's —
                // see `assertOwner` in receive.js.
                .put("ownerUid", recording.ownerUid)
                .put("recordingId", recording.id)
                .put(
                    "chunks",
                    JSONArray(
                        chunks.map {
                            JSONObject()
                                .put("seq", it.seq)
                                .put("bytes", it.bytes.toInt())
                                .putOrNull("md5", it.md5)
                        },
                    ),
                )
                .put("startedAt", recording.startedAt)
                // the company picked before recording (null when it was not asked).
                .putCompany(recording)
                .put("platform", device.platform)
                .put("appVersion", device.appVersion)
                .put("deviceModel", device.model),
        )
        if (!reply.ok) return@answer UploadTargets(errorKey = CrmApi.recorderErrorOf(reply))

        val urls = reply.body.optJSONArray("urls") ?: JSONArray()
        // chunks the server gave no link for, over its size limit.
        val refused = reply.body.optJSONArray("refused") ?: JSONArray()
        UploadTargets(
            refusedSeqs = (0 until refused.length()).mapNotNull { i ->
                refused.optJSONObject(i)?.takeIf { it.has("seq") }?.optInt("seq")
            },
            targets = (0 until urls.length()).mapNotNull { i ->
                val target = urls.optJSONObject(i) ?: return@mapNotNull null
                if (!target.has("seq")) return@mapNotNull null
                val seq = target.optInt("seq")
                val url = target.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val headers = buildMap {
                    target.optJSONObject("headers")?.let { h ->
                        for (name in h.keys()) (h.opt(name) as? String)?.let { put(name, it) }
                    }
                }
                ChunkUploadTarget(seq, url, headers)
            },
        )
    }

    override suspend fun finalize(
        recording: LocalRecording,
        seqs: List<Int>,
        durationMs: Long,
        gaps: List<RecordingGap>,
    ): FinalizeResult = answer({ FinalizeResult(errorKey = it) }) {
        val reply = CrmApi.call(
            "POST",
            "/api/recordings/finalize",
            JSONObject()
                .put("ownerUid", recording.ownerUid)
                .put("recordingId", recording.id)
                .put("seqs", JSONArray(seqs))
                .put("durationMs", durationMs.toInt())
                .put("endReason", (recording.endReason ?: EndReason.STOPPED).id)
                .put(
                    "gaps",
                    JSONArray(
                        gaps.map {
                            JSONObject()
                                .put("startMs", it.startMs.toInt())
                                .putOrNull("endMs", it.endMs?.toInt())
                                .put("reason", it.reason.id)
                        },
                    ),
                )
                .put("startedAt", recording.startedAt)
                .putOrNull("endedAt", recording.endedAt)
                .putOrNull("title", recording.title)
                .putOrNull("meetingType", recording.meetingType)
                .putOrNull("location", recording.location)
                .putOrNull("attendees", recording.attendees)
                // and the company, which can be changed after the meeting like the details.
                .putCompany(recording),
        )
        if (!reply.ok) return@answer FinalizeResult(errorKey = CrmApi.recorderErrorOf(reply))

        val missing = reply.body.optJSONArray("missing") ?: JSONArray()
        FinalizeResult(
            ok = reply.body.optBoolean("ok", false),
            missing = (0 until missing.length()).mapNotNull { i ->
                (missing.opt(i) as? Number)?.toInt()
            },
        )
    }

    /**
     * The server's answer, or the reason there was none, as a result.
     *
     * A refusal the server gave is read by [CrmApi.recorderErrorOf]. Anything
     * that brought no answer at all (no signal, a timeout) is "try later", as
     * it was with the callables.
     */
    private inline fun <T> answer(failed: (String) -> T, call: () -> T): T = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(RecorderErrors.NETWORK)
    }
}

/**
 * the company a recording is with, as the API reads it (`companyOf` in the server's
 * `recording/recordings.js`). A recording with none sends nulls, and the server leaves what it has.
 */
private fun JSONObject.putCompany(recording: LocalRecording): JSONObject {
    val company = recording.company
    return putOrNull("companyChoice", company?.choice?.id)
        .putOrNull("companyId", company?.id)
        .putOrNull("companyName", company?.name)
}

/**
 * the recorder (`recorder/RecorderService.kt` and
 * `recorder/RecorderSync.kt`) is kept as close to TAS as it can be (the
 * service only carries the company picked before Record), and it builds
 * this repository by its old name. The name now means the API version above.
 */
typealias FunctionsRecordingUploadRepository = ApiRecordingUploadRepository
