package com.humblesolutions.ainotetaker

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase

actual fun testRecordingDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { RecordingDatabase.Schema.create(it) }
