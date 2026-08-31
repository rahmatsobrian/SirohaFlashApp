package com.siroha.flashtool.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SafFiles {
    /**
     * SAF content:// Uris aren't a plain filesystem path qdl's `ProcessBuilder`
     * child process can open directly, so we copy the picked file into our own
     * external cache dir first and hand that absolute path to qdl instead.
     *
     * Dispatched on IO and suspend, not a plain blocking call: this used to
     * be called directly from inside an ActivityResult picker callback (the
     * main thread) for files up to several GB, which is a guaranteed ANR —
     * the exact "screen goes black for a few seconds" symptom reported for
     * large ADB sideload ZIPs. Every caller must now wrap this in a
     * coroutine (e.g. `scope.launch { ... }`); it can no longer be called
     * directly from a non-suspend callback.
     */
    suspend fun copyToCache(
        context: Context,
        uri: Uri,
        suggestedName: String,
        onProgress: ((copiedBytes: Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val safeCacheDir = context.externalCacheDir ?: context.cacheDir
        val outDir = File(safeCacheDir, "qdl_inputs").apply { mkdirs() }
        val outFile = File(outDir, suggestedName)

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $uri" }
            outFile.outputStream().use { output ->
                if (onProgress == null) {
                    input.copyTo(output)
                } else {
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                }
            }
        }

        outFile.absolutePath
    }

    /**
     * Tries to open [uri] as a real, seekable file descriptor directly —
     * i.e. without copying it anywhere first. Local storage providers
     * (internal storage, SD cards, most file-manager apps) generally hand
     * back a real underlying fd here that supports arbitrary seeking; some
     * providers (certain cloud-backed ones) only support sequential
     * streaming and this returns null for those, so callers should always
     * have a [copyToCache] fallback ready.
     *
     * This exists specifically for ADB sideload: minadbd requests blocks
     * out of file order (see [AdbUsbClient.sideload]'s doc), which needs
     * real random access, and a multi-GB ROM ZIP is exactly the case where
     * skipping an extra several-GB copy (both the time it takes AND the
     * device storage it needs free) matters most.
     */
    suspend fun openSeekableFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? =
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        }

    /** Best-effort display name for [uri] (falls back to [fallback] if the provider won't say). */
    fun displayName(context: Context, uri: Uri, fallback: String): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull() ?: fallback
    }
}
