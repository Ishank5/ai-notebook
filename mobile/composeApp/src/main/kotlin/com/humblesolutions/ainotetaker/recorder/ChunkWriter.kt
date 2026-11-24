package com.humblesolutions.ainotetaker.recorder

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * One chunk file, being written.
 *
 * UNBUFFERED, AND FORCED TO DISK EVERY FEW SECONDS. `FileOutputStream` hands
 * each frame straight to the kernel, so a crash of the app loses nothing that
 * was written; [sync] pushes the kernel's copy to flash, so a flat battery
 * loses at most the seconds since the last one. Recovery cuts whatever torn
 * frame is left at the end.
 *
 * The MD5 is kept as the bytes go by, so closing a chunk does not read the
 * file back.
 */
internal class ChunkWriter(val seq: Int, val path: String, file: File) {

    /** Truncating, not appending: a leftover file under this name is not this chunk. */
    private val out: FileOutputStream = run {
        val folder = file.parentFile
        val newFolder = folder != null && !folder.exists() && folder.mkdirs()
        FileOutputStream(file, false).also {
            /*
             * THE NAME, NOT ONLY THE BYTES. Syncing a file makes its contents
             * durable, but on ext4 the entry that names it lives in the
             * folder, and a power cut can leave synced audio with no name to
             * find it by. One flush of the folder per chunk — and of the
             * recorder's folder when a recording's own is new.
             */
            syncFolder(folder)
            if (newFolder) syncFolder(folder?.parentFile)
        }
    }

    private fun syncFolder(folder: File?) {
        folder ?: return
        try {
            val fd = Os.open(folder.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (e: ErrnoException) {
            // Best effort: not every filesystem lets a folder be synced.
        }
    }
    private val digest = MessageDigest.getInstance("MD5")

    var bytes = 0L
        private set
    var frames = 0L
        private set

    data class Closed(val bytes: Long, val frames: Long, val md5: String)

    /** One ADTS frame: header and payload in a single write. */
    fun write(header: ByteArray, payload: ByteArray) {
        val frame = header + payload
        out.write(frame)
        digest.update(frame)
        bytes += frame.size
        frames++
    }

    fun sync() {
        out.fd.sync()
    }

    fun close(): Closed {
        out.fd.sync()
        out.close()
        return Closed(bytes, frames, Base64.encodeToString(digest.digest(), Base64.NO_WRAP))
    }

    /** Lets go of the file without a verdict; recovery will seal what reached the disk. */
    fun abandon() {
        runCatching { out.close() }
    }
}
