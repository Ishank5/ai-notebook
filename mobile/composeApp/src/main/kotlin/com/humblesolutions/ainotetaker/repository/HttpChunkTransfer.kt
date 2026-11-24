package com.humblesolutions.ainotetaker.repository

import android.util.Log
import com.humblesolutions.ainotetaker.model.ChunkUploadTarget
import com.humblesolutions.ainotetaker.model.TransferResult
import com.humblesolutions.ainotetaker.recorder.AndroidRecordingFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * One chunk, PUT to its signed URL.
 *
 * PLAIN HTTPS. The link already carries the permission, so there is no SDK
 * between the file and Storage — and every header the signature covers is
 * sent exactly as the server listed it, or Storage refuses the lot.
 *
 * REFUSED MEANS THE FILE, AND NOTHING ELSE. Only Storage saying the bytes do
 * not match their MD5, or are too big, counts as [TransferResult.REJECTED] —
 * the answer that eventually drops a chunk. A signature Storage does not
 * accept, a missing permission, an expired link: those are the server's
 * problem, fixed on the server, and the audio must still be here when it is.
 */
class HttpChunkTransfer(private val files: AndroidRecordingFiles) : ChunkTransfer {

    override suspend fun upload(target: ChunkUploadTarget, path: String): TransferResult =
        withContext(Dispatchers.IO) {
            val file = files.resolve(path)
            if (!file.isFile) return@withContext TransferResult.MISSING

            val connection = try {
                URL(target.url).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                return@withContext TransferResult.RETRY
            }
            try {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setFixedLengthStreamingMode(file.length())
                target.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                file.inputStream().use { input ->
                    connection.outputStream.use { output -> input.copyTo(output) }
                }

                val code = connection.responseCode
                if (code in 200..299) return@withContext TransferResult.UPLOADED

                val body = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                Log.w(TAG, "Storage answered $code for chunk ${target.seq}: ${body.take(300)}")
                if (code == 400 && FILE_FAULTS.any { body.contains("<Code>$it</Code>") }) {
                    TransferResult.REJECTED
                } else if (code == 403 && body.contains("<Code>SignatureDoesNotMatch</Code>")) {
                    // the link is signed for the row's size and MD5; a file that no longer matches
                    // them is refused like this, forever. The sync measures it again and repairs it.
                    TransferResult.MISMATCH
                } else {
                    // an answer, so not "no signal": this recording waits, the others still go.
                    TransferResult.FAILED
                }
            } catch (e: IOException) {
                TransferResult.RETRY
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TAG = "RecorderUpload"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000

        /** Storage's codes for "these bytes are not what was promised". */
        val FILE_FAULTS = listOf("BadDigest", "InvalidDigest", "EntityTooLarge", "EntityTooSmall")
    }
}
