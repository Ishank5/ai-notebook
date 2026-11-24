package com.humblesolutions.ainotetaker

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase

actual fun testRecordingDriver(): SqlDriver = inMemoryDriver(RecordingDatabase.Schema)
