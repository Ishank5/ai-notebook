package com.humblesolutions.ainotetaker.repository

/**
 * What the server has made of recordings this phone has sent.
 *
 * READ-ONLY, AND ONLY ABOUT THE PERSON'S OWN RECORDINGS. the
 * CRM API answers `POST /api/recordings/statuses` for the caller's own
 * recordings and leaves anybody else's out, as TAS's Firestore rule did.
 */
interface RecordingStatusRepository {
    /**
     * The server's `status` for each id it could read. An id it could not
     * read — no signal, not there any more — is left out, never guessed at.
     */
    suspend fun serverStatuses(ids: List<String>): Map<String, String>
}
