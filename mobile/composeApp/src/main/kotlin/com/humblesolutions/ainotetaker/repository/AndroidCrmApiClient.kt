package com.humblesolutions.ainotetaker.repository

import com.humblesolutions.ainotetaker.meeting.ApiMeetingRepository

/**
 * The meetings calls' HTTPS, through [CrmApi] and its Firebase ID token. What the calls mean, and
 * their JSON, is in `shared` ([ApiMeetingRepository]).
 */
object AndroidCrmApiClient : CrmApiClient {
    override suspend fun send(method: String, path: String, body: String?): CrmResponse {
        val answer = CrmApi.callText(method, path, body)
        return CrmResponse(answer.status, answer.text)
    }
}

/** The meetings repository, one per process: it holds nothing but the transport. */
object Meetings {
    val repository: MeetingRepository by lazy { ApiMeetingRepository(AndroidCrmApiClient) }
}
