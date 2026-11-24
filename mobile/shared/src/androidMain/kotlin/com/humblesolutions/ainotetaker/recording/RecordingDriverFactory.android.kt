package com.humblesolutions.ainotetaker.recording

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase

actual class RecordingDriverFactory(private val context: Context) {

    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(
        schema = RecordingDatabase.Schema,
        context = context.applicationContext,
        name = "recordings.db",
        callback = object : AndroidSqliteDriver.Callback(RecordingDatabase.Schema) {
            /*
             * DURABLE ON A POWER CUT, NOT ONLY ON A CRASH.
             *
             * Since Android 9 a database that does not choose is opened in WAL
             * mode with `synchronous=NORMAL`, where a commit reaches the disk
             * only at the next checkpoint — and this database's writes are so
             * small that a checkpoint can be a whole meeting away. A forced
             * restart or a kernel panic would then roll the queue back to
             * before the recording existed, leaving its audio on disk with
             * nothing that knows it is there.
             *
             * Rollback journal with FULL sync makes every commit durable when
             * it returns. It costs a flush per write; the recorder writes a
             * handful of rows a minute.
             */
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.disableWriteAheadLogging()
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA synchronous = FULL")
            }
        },
        // Out of Auto Backup, with the audio it describes: a queue restored
        // onto a new phone without its files is a list of meetings that can
        // never upload.
        useNoBackupDirectory = true,
    )
}
