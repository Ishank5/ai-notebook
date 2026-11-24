package com.humblesolutions.ainotetaker.recorder

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.humblesolutions.ainotetaker.BuildConfig
import com.humblesolutions.ainotetaker.model.RecorderDevice
import com.humblesolutions.ainotetaker.model.RecordingState
import com.humblesolutions.ainotetaker.recording.RecordingDriverFactory
import com.humblesolutions.ainotetaker.recording.RecordingSource
import com.humblesolutions.ainotetaker.repository.FunctionsRecordingUploadRepository
import com.humblesolutions.ainotetaker.repository.HttpChunkTransfer
import com.humblesolutions.ainotetaker.repository.RecordingStore
import com.humblesolutions.ainotetaker.usecase.PurgeUploadedRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.SyncRecordingsUseCase
import com.humblesolutions.ainotetaker.usecase.SyncStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * When recordings upload, once the recorder service is gone.
 *
 * WORKMANAGER, BECAUSE IT OUTLIVES EVERYTHING ELSE. It survives the app being
 * swiped away, the process being killed and the phone being rebooted, and it
 * waits for signal by itself — which is the whole of "record now, send later".
 *
 * TWO JOBS:
 * - [now]: once, as soon as there is a connection. After Stop, at launch, and
 *   when somebody taps Send now.
 * - [keepWatching]: hourly while anything is waiting. It is what finishes an
 *   interrupted recording nobody came back to, and what notices a recording
 *   the phone died in the middle of, after a reboot, before anybody opens the
 *   app. It stops itself when the queue is empty.
 */
object RecorderSync {

    private const val NOW = "recorder-sync"
    private const val WATCH = "recorder-watch"
    internal const val KEY_WATCH = "watch"

    /**
     * Upload as soon as there is a connection.
     *
     * [recordingId] names the recording this is about — one just stopped, or
     * given details — and gets it a run of its own. A single shared run would
     * not do: asking for it while one is already going is silently dropped,
     * and the run already going may have looked at this recording before it
     * stopped. Per recording, the request cannot be swallowed, and repeats
     * for the same recording still fold into one.
     *
     * [replace] is for Send now (and opening the app): a run
     * already waiting out a long backoff is thrown away and one starts at once.
     * never one that is running: REPLACE cancels it mid-upload, and opening or rotating the app did
     * that. A running pass reads the queue again before it ends, so it is left to finish.
     */
    fun now(context: Context, recordingId: String? = null, replace: Boolean = false) {
        val name = recordingId?.let { "$NOW-$it" } ?: NOW
        if (!replace) {
            enqueue(context, name, ExistingWorkPolicy.KEEP)
            return
        }
        // Asking WorkManager waits on its database, so off the main thread.
        RecorderRecovery.scope.launch {
            val running = runCatching {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            enqueue(context, name, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
        }
    }

    private fun enqueue(context: Context, name: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<RecordingSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }

    /**
     * The hourly check. No network constraint: recovery and the one-hour
     * auto-finish must happen offline too; the upload inside simply finds no
     * signal and hands over to [now], which waits for it.
     */
    fun keepWatching(context: Context) {
        val request = PeriodicWorkRequestBuilder<RecordingSyncWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf(KEY_WATCH to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCH, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    internal fun stopWatching(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WATCH)
    }
}

/** Which phone made a recording, for the admin looking at a strange one. */
internal object RecorderDevices {
    fun current(@Suppress("UNUSED_PARAMETER") context: Context) = RecorderDevice(
        platform = "android",
        appVersion = BuildConfig.VERSION_NAME,
        model = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80),
    )
}

/**
 * One pass over the queue: recover, tidy, upload.
 *
 * Recovery runs FIRST, and needs nobody signed in: a recording the phone died
 * in the middle of is repaired and marked for Continue the first time anything
 * of this app runs after the crash, whoever it belongs to.
 */
class RecordingSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val watching = inputData.getBoolean(RecorderSync.KEY_WATCH, false)
        return try {
            pass(watching)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A throw here would be a FAILED job, which WorkManager never runs
            // again. Nothing about a recording is ever worth giving up on.
            Log.e(TAG, "the upload run failed", e)
            if (watching) Result.success() else Result.retry()
        }
    }

    private suspend fun pass(watching: Boolean): Result {
        val store = RecordingSource.store(RecordingDriverFactory(applicationContext))
        val files = AndroidRecordingFiles(applicationContext)
        val runtime = RecordingSource.runtime
        val now = System.currentTimeMillis()

        // recovery, the exit reason and the notice, shared with the alarms and the boot receiver.
        RecorderRecovery.run(applicationContext, announce = true)
        PurgeUploadedRecordingsUseCase(store, files).execute(now)

        val owner = FirebaseAuth.getInstance().currentUser?.uid
        val outcome = owner?.let {
            SyncRecordingsUseCase(
                store,
                FunctionsRecordingUploadRepository(),
                HttpChunkTransfer(files),
                files,
                runtime,
            ).execute(it, RecorderDevices.current(applicationContext), now)
        }
        outcome?.let { Log.i(TAG, "sync: ${it.status}, ${it.uploaded} chunks, ${it.finalized} confirmed") }

        if (nothingWaiting(store, owner)) RecorderSync.stopWatching(applicationContext)

        return when {
            outcome?.status != SyncStatus.RETRY -> Result.success()
            // The hourly check does not retry on its own schedule; the one-off
            // job waits for signal and backs off properly.
            watching -> {
                RecorderSync.now(applicationContext)
                Result.success()
            }
            else -> Result.retry()
        }
    }

    private suspend fun nothingWaiting(store: RecordingStore, owner: String?): Boolean =
        store.active() == null &&
            store.inState(RecordingState.INTERRUPTED).isEmpty() &&
            (owner == null || store.toSync(owner).isEmpty())

    private companion object {
        const val TAG = "RecorderSync"
    }
}
