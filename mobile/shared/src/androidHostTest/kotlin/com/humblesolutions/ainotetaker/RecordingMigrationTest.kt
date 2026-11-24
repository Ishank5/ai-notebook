package com.humblesolutions.ainotetaker

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.recording.SqlRecordingStore
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * a queue made by the version before plans (schema 2) keeps every
 * recording through `2.sqm`, and gains the plan columns empty.
 *
 * On the JVM only: it needs a database WITHOUT the current schema applied, which
 * is a one-liner with JDBC and not with the native driver. The SQL is the same.
 */
class RecordingMigrationTest {

    @Test
    fun `a queue from the version before plans keeps its recordings`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The recording table as schema 2 (Recording.sq plus 1.sqm) left it.
        driver.execute(
            null,
            """
            CREATE TABLE recording (
                id TEXT NOT NULL PRIMARY KEY, owner_uid TEXT NOT NULL, state TEXT NOT NULL,
                started_at INTEGER NOT NULL, ended_at INTEGER, end_reason TEXT, title TEXT, meeting_type TEXT,
                location TEXT, attendees TEXT, heartbeat_at INTEGER, uploaded_at INTEGER, purged_at INTEGER,
                last_error TEXT, company_choice TEXT, company_id TEXT, company_name TEXT
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE TABLE chunk (recording_id TEXT NOT NULL, seq INTEGER NOT NULL, path TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, duration_ms INTEGER NOT NULL DEFAULT 0, md5 TEXT, state TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, rejections INTEGER NOT NULL DEFAULT 0, last_attempt_at INTEGER, PRIMARY KEY (recording_id, seq))", 0)
        driver.execute(null, "CREATE TABLE gap (recording_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER, reason TEXT NOT NULL)", 0)
        driver.execute(
            null,
            "INSERT INTO recording(id, owner_uid, state, started_at, title) VALUES ('r-old', 'u1', 'stopped', 1, 'Planning call')",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO chunk(recording_id, seq, path, state) VALUES ('r-old', 0, 'r-old/00000.aac', 'closed')",
            0,
        )

        RecordingDatabase.Schema.migrate(driver, 2, RecordingDatabase.Schema.version)

        assertEquals(6L, RecordingDatabase.Schema.version)
        val store = SqlRecordingStore(RecordingDatabase(driver), Dispatchers.Unconfined)
        val old = store.byId("r-old")!!
        assertEquals("Planning call", old.title)
        assertEquals(RecordingState.STOPPED, old.state)
        assertNull(old.planMs)
        assertNull(old.stopAtMs)
        // and 3.sqm's found time and exit info, empty.
        assertNull(old.foundUptimeMs)
        assertNull(old.exitInfo)
        // and 4.sqm's owner name, empty.
        assertNull(old.ownerName)
        // and 5.sqm's unreadable count, at nothing.
        val chunk = store.chunks("r-old").single()
        assertEquals(0, chunk.unreadable)
        assertNull(chunk.unreadableSince)
        store.noteUnreadable("r-old", 0, 99L)
        store.noteUnreadable("r-old", 0, 120L)
        assertEquals(2, store.chunks("r-old").single().unreadable)
        assertEquals(99L, store.chunks("r-old").single().unreadableSince, "since the first")

        // And the new columns work.
        store.create("r-new", "u1", 2, planMs = 7_200_000L, ownerName = "Rohan Gill")
        assertEquals("Rohan Gill", store.byId("r-new")!!.ownerName)
        store.setStopAt("r-new", 10_800_000L)
        assertEquals(7_200_000L, store.byId("r-new")!!.planMs)
        assertEquals(10_800_000L, store.byId("r-new")!!.stopAtMs)
        store.markFound("r-new", 42L)
        store.setExitInfo("r-new", "reason=SIGNALED")
        assertEquals(42L, store.byId("r-new")!!.foundUptimeMs)
        assertEquals("reason=SIGNALED", store.byId("r-new")!!.exitInfo)
    }
}
