package com.humblesolutions.ainotetaker.recorder

import android.content.Context
import android.util.Base64
import com.humblesolutions.ainotetaker.repository.RecordingFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The recorder's folder: `noBackupFilesDir/recorder`.
 *
 * NO-BACKUP, with the queue that describes it (`recordings.db` is opened with
 * `useNoBackupDirectory`). Auto Backup would restore rows without audio, or
 * audio without rows, onto a new phone; neither can upload.
 *
 * NOT the cache directory: Android clears caches when storage runs low, which
 * is precisely when a recording is most likely to be sitting unsent.
 */
class AndroidRecordingFiles(context: Context) : RecordingFiles {

    val root: File = File(context.noBackupFilesDir, "recorder").apply { mkdirs() }

    /** Where a queue path points today. */
    fun resolve(path: String): File = File(root, path)

    override fun freeBytes(): Long = root.usableSpace

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        // a read that fails is null, never a throw into the shared code; `exists` tells the rest.
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    // Added for AI Notebook.
    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { resolve(path).exists() }

    // Added for AI Notebook.
    override suspend fun size(path: String): Long? = withContext(Dispatchers.IO) {
        resolve(path).takeIf { it.isFile }?.length()
    }

    override suspend fun seal(path: String, length: Long): String? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.isFile) return@withContext null
        try {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() > length) raf.setLength(length)
                raf.fd.sync()
            }
            md5Of(file)
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { resolve(path).delete() }
    }

    // set aside in `rejected/`, never deleted.
    override suspend fun reject(path: String): Boolean = withContext(Dispatchers.IO) {
        val from = resolve(path)
        if (!from.exists()) return@withContext true
        val to = File(root, "$REJECTED/$path")
        try {
            to.parentFile?.mkdirs()
            if (to.exists()) to.delete()
            if (from.renameTo(to)) return@withContext true
            from.copyTo(to, overwrite = true)
            from.delete()
            true
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun deleteFolder(folder: String) {
        withContext(Dispatchers.IO) { resolve(folder).deleteRecursively() }
    }

    companion object {

        /** where audio that can never be sent is kept rather than deleted. */
        const val REJECTED = "rejected"

        /** Base64 MD5, the form Storage checks `Content-MD5` against. */
        fun md5Of(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        }
    }
}
