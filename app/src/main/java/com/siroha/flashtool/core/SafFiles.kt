package com.siroha.flashtool.core

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object SafFiles {
    private const val TAG = "SafFiles"

    // ------------------------------------------------------------------
    // All files access
    // ------------------------------------------------------------------

    /** True when the app may read shared storage by plain path (All files access, or pre-Android 11). */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /** Opens the system "All files access" screen for this app (falls back to the generic list on odd OEM ROMs). */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
        val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        for (intent in listOf(perApp, generic)) {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    // ------------------------------------------------------------------
    // Real path of a picked document
    // ------------------------------------------------------------------

    /**
     * The real filesystem path behind a picked document, e.g.
     * /storage/emulated/0/Download/1DM/Compressed/boot.img, or null when nothing can be worked out
     * (cloud storage, streaming-only providers). Several strategies are tried in turn, because
     * every file manager / OEM picker exposes a different kind of Uri:
     *  1. the document id of the standard providers (internal storage, SD card, Downloads
     *     "raw:" / "msf:" / numeric ids, MediaStore),
     *  2. the provider's `_data` column,
     *  3. the symlink behind an opened file descriptor (/proc/self/fd/N),
     *  4. a MediaStore lookup by file name + size (only when the answer is unambiguous).
     *
     * This only says WHERE the file is. Whether the app may actually READ it depends on the
     * All files access permission, so callers use [prepareFile] (verifies with a real open and
     * falls back to a cache copy) instead of trusting the path blindly.
     */
    fun resolveRealPath(context: Context, uri: Uri): String? =
        runCatching { resolveByDocumentId(context, uri) }.getOrNull()?.let(::normalizePath)
            ?: runCatching { resolveByFileDescriptor(context, uri) }.getOrNull()
            ?: runCatching { resolveByMediaStoreLookup(context, uri) }.getOrNull()

    private fun resolveByDocumentId(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (!DocumentsContract.isDocumentUri(context, uri)) return queryDataColumn(context, uri)
        val docId = DocumentsContract.getDocumentId(uri)
        return when (uri.authority) {
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
                docId.startsWith("msf:") -> mediaStorePath(context, docId.removePrefix("msf:").toLongOrNull())
                else -> {
                    val id = docId.toLongOrNull()
                    (if (id != null) mediaStorePath(context, id) else null)
                        ?: queryDataColumn(context, uri)
                        ?: id?.let {
                            listOf("content://downloads/public_downloads", "content://downloads/all_downloads")
                                .firstNotNullOfOrNull { base -> queryDataColumn(context, ContentUris.withAppendedId(Uri.parse(base), it)) }
                        }
                }
            }
            "com.android.providers.media.documents" -> {
                val kind = docId.substringBefore(':')
                val id = docId.substringAfter(':').toLongOrNull() ?: return null
                val base = when (kind) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Files.getContentUri("external")
                }
                queryDataColumn(context, ContentUris.withAppendedId(base, id))
                    ?: mediaStorePath(context, id)
            }
            else -> queryDataColumn(context, uri)
        }
    }

    /** `_data` of a MediaStore.Files row (what Downloads "msf:123" ids point at). */
    private fun mediaStorePath(context: Context, id: Long?): String? {
        if (id == null) return null
        return queryDataColumn(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
    }

    private fun queryDataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * Opens the document and asks the kernel where the descriptor points. Works for most local
     * providers even when they expose no document id or `_data` column.
     */
    private fun resolveByFileDescriptor(context: Context, uri: Uri): String? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use {
            val link = Os.readlink("/proc/self/fd/${it.fd}")
            normalizePath(link)?.takeIf { p -> p.startsWith("/storage/") || p.startsWith("/sdcard/") }
        }
    }

    /** Last resort: a MediaStore row with the same name AND size; used only when exactly one path matches. */
    private fun resolveByMediaStoreLookup(context: Context, uri: Uri): String? {
        val name = displayName(context, uri, "").takeIf { it.isNotEmpty() } ?: return null
        val size = querySize(context, uri) ?: return null
        val paths = mutableSetOf<String>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf("_data"),
            "_display_name = ? AND _size = ?",
            arrayOf(name, size.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { paths += it }
        }
        return paths.singleOrNull()?.let(::normalizePath)
    }

    /** Maps the FUSE / pass-through spellings of a storage path back to the usual /storage/... form. */
    private fun normalizePath(raw: String?): String? {
        var p = raw?.trim() ?: return null
        if (!p.startsWith("/")) return null
        Regex("^/mnt/(?:pass_through|user)/\\d+/(.*)$").find(p)?.let { m ->
            val rest = m.groupValues[1]
            p = "/storage/" + if (rest.startsWith("primary/")) "emulated/0/" + rest.removePrefix("primary/") else rest
        }
        Regex("^/data/media/(\\d+)/(.*)$").find(p)?.let { m -> p = "/storage/emulated/${m.groupValues[1]}/${m.groupValues[2]}" }
        return p
    }

    /**
     * The real folder behind a picked tree (OpenDocumentTree) Uri, e.g. /storage/emulated/0/Download/ROM,
     * or null when it can't be worked out (non-local providers).
     */
    fun resolveTreePath(context: Context, treeUri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        when (treeUri.authority) {
            "com.android.externalstorage.documents" -> {
                val volume = docId.substringBefore(':')
                val relative = docId.substringAfter(':', "")
                if (volume.equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory().path + "/" + relative
                } else {
                    "/storage/$volume/$relative"
                }
            }
            "com.android.providers.downloads.documents" -> docId.takeIf { it.startsWith("raw:") }?.removePrefix("raw:")
            else -> null
        }
    }.getOrNull()?.let(::normalizePath)?.trimEnd('/')

    /** A folder the app can list right now (needs All files access for most shared-storage folders), else null. */
    fun readableDir(path: String?): File? =
        path?.let { File(it) }?.takeIf { it.isDirectory && runCatching { it.listFiles() != null }.getOrDefault(false) }

    /** True only when a file can really be created in [dir] right now (checked by creating and deleting a probe file). */
    fun canReallyWrite(dir: File): Boolean = runCatching {
        val probe = File(dir, ".siroha_write_probe")
        probe.outputStream().use { it.write(0) }
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * True only when the file can really be opened and read right now. `File.canRead()` is not
     * enough: on scoped storage it can say yes while open() still fails with EACCES.
     */
    fun canReallyRead(file: File): Boolean = runCatching {
        file.isFile && FileInputStream(file).use { it.read(); true }
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Getting a readable file for the flashing code
    // ------------------------------------------------------------------

    /**
     * The one entry point every flash/boot/sideload screen uses to turn a picked document into a
     * path the clients can read:
     *  1. With All files access the picked file is used IN PLACE (no multi-GB copy, no extra free
     *     space needed). The path is verified by really opening it.
     *  2. If the real path can't be found or opened, the file is copied into the app's cache
     *     ([copyToCache]) as the backup, exactly like before.
     */
    suspend fun prepareFile(
        context: Context,
        uri: Uri,
        suggestedName: String,
        onProgress: ((copiedBytes: Long) -> Unit)? = null
    ): String {
        val direct = withContext(Dispatchers.IO) {
            resolveRealPath(context, uri)?.takeIf { canReallyRead(File(it)) }
        }
        if (direct != null) {
            Log.i(TAG, "Using $direct directly (All files access)")
            return direct
        }
        Log.i(TAG, "No direct access to $uri - copying $suggestedName to cache instead")
        return copyToCache(context, uri, suggestedName, onProgress)
    }

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
