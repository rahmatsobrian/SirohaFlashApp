package com.siroha.flashtool.core

import android.content.Context
import android.net.Uri
import java.io.File

object SafFiles {
    /**
     * SAF content:// Uris aren't readable by a separate root/Shizuku shell
     * process, so we copy the picked file into our own external cache dir first
     * and hand that absolute path to qdl/fastboot instead.
     */
    fun copyToCache(context: Context, uri: Uri, suggestedName: String): String {
        // PERBAIKAN: Gunakan externalCacheDir agar folder bisa ditembus oleh Shizuku.
        // Jika externalCacheDir null (jarang terjadi), fallback ke cacheDir.
        val safeCacheDir = context.externalCacheDir ?: context.cacheDir
        val outDir = File(safeCacheDir, "qdl_inputs").apply { mkdirs() }
        val outFile = File(outDir, suggestedName)
        
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $uri" }
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        
        // Files under cacheDir are only readable by this app's UID; make them
        // world-readable so a root/Shizuku shell process (different UID) can
        // still open them for the qdl invocation.
        outFile.setReadable(true, false)
        return outFile.absolutePath
    }
}
