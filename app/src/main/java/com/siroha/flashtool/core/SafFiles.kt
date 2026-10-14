package com.siroha.flashtool.core

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SafFiles {
    /**
     * The real filesystem path behind a picked document, e.g.
     * /storage/emulated/0/Download/1dm/compressed/aboot.img, or null when the provider
     * doesn't expose one (cloud storage, some download-manager ids). Works for the
     * internal-storage and SD-card pickers, "raw:" Downloads entries and MediaStore items.
     * Whether the app may actually READ that path depends on the All files access permission,
     * so callers keep the Uri as a fallback.
     */
    fun resolveRealPath(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path
        if (!DocumentsContract.isDocumentUri(context, uri)) return@runCatching queryDataColumn(context, uri)
        val docId = DocumentsContract.getDocumentId(uri)
        when (uri.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = docId.substringBefore(':')
                val relative = docId.substringAfter(':', "")
                if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().path + "/" + relative
                } else {
                    "/storage/$volume/$relative"
                }
            }
            "com.android.providers.downloads.documents" -> when {
                docId.startsWith("raw:") -> docId.removePrefix("raw:")
                else -> queryDataColumn(context, uri)
            }
            "com.android.providers.media.documents" -> {
                val kind = docId.substringBefore(':')
                val id = docId.substringAfter(':').toLongOrNull() ?: return@runCatching null
                val base = when (kind) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                queryDataColumn(context, ContentUris.withAppendedId(base, id))
            }
            else -> queryDataColumn(context, uri)
        }
    }.getOrNull()?.takeIf { it.startsWith("/") }

    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

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

        // Big images take a while to copy: show it on the progress overlay as the first slice of
        // the step (the USB transfer that follows gets the rest). Small files aren't worth a flicker.
        val size = querySize(context, uri)
        val track = size != null && size >= 8L shl 20
        val previousStage = if (track) OperationStatus.stage.value else null
        if (track) {
            OperationStatus.setStage("Copying $suggestedName to cache")
            OperationStatus.setPhase(0f, 0.15f)
        }
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open $uri" }
                outFile.outputStream().use { output ->
                    if (onProgress == null && !track) {
                        input.copyTo(output)
                    } else {
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress?.invoke(copied)
                            if (track) OperationStatus.reportProgress(copied, size!!)
                        }
                    }
                }
            }
        } finally {
            if (track) {
                OperationStatus.setPhase(0.15f, 1f)
                OperationStatus.setStage(previousStage)
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

    /** Size in bytes the provider reports for [uri], or null when it won't say. */
    private fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
            } else null
        }
    }.getOrNull()

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
