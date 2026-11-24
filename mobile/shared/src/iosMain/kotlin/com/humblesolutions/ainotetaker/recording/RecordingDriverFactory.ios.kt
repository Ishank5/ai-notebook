package com.humblesolutions.ainotetaker.recording

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.humblesolutions.ainotetaker.recording.db.RecordingDatabase
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.SynchronousFlag
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

actual class RecordingDriverFactory {

    actual fun createDriver(): SqlDriver = NativeSqliteDriver(
        schema = RecordingDatabase.Schema,
        name = "recordings.db",
        onConfiguration = { config ->
            config.copy(
                extendedConfig = DatabaseConfiguration.Extended(
                    basePath = recorderFolder(),
                    // Every commit on disk when it returns — see the Android
                    // factory for what a lost commit would cost.
                    synchronousFlag = SynchronousFlag.FULL,
                ),
            )
        },
    )

    companion object {

        /**
         * `Application Support/Recorder`, created on first use and kept out of
         * iCloud backup — the queue and the audio together, so neither is ever
         * restored without the other.
         *
         * NOT Caches, which iOS empties under storage pressure: that is
         * precisely when a recording is most likely to be sitting unsent.
         *
         * WRITABLE WITH THE PHONE LOCKED. Files made inside inherit the
         * folder's protection class, and "until first unlock" is what lets a
         * meeting be recorded — and its queue updated — in a locked pocket.
         * Set explicitly rather than assumed, so a stricter default added to
         * the app one day cannot silently stop recordings at the lock screen.
         *
         * Public so the Swift recorder writes its chunks into the same folder.
         */
        @OptIn(ExperimentalForeignApi::class)
        fun recorderFolder(): String {
            val base = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory, NSUserDomainMask, true,
            ).firstOrNull() as? String ?: error("no Application Support directory")
            val dir = "$base/Recorder"
            val files = NSFileManager.defaultManager
            if (!files.fileExistsAtPath(dir)) {
                files.createDirectoryAtPath(
                    dir,
                    withIntermediateDirectories = true,
                    attributes = mapOf<Any?, Any?>(
                        NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication,
                    ),
                    error = null,
                )
                NSURL.fileURLWithPath(dir).setResourceValue(
                    NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null,
                )
            }
            return dir
        }
    }
}
