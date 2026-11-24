package com.humblesolutions.ainotetaker

import app.cash.sqldelight.db.SqlDriver

/**
 * An in-memory `recordings.db` with the schema ALREADY APPLIED — the same
 * contract as [testDriver], for the same reason: who creates the schema
 * differs between the JDBC and native drivers.
 */
expect fun testRecordingDriver(): SqlDriver
