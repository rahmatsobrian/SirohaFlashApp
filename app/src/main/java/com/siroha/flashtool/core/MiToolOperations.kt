package com.siroha.flashtool.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Reimplements the two pieces of the original MiTool (miflashf.py,
 * mifcetool.py) that are actually possible to rebuild natively without a
 * private Xiaomi API — see README/About for why "Unlock Bootloader" (Mi
 * Unlock) and "Mi Assistant" are NOT here: both depend on Xiaomi's
 * proprietary account-based servers / an undocumented external binary that
 * was never open-sourced even in the original project, not just something
 * left unfinished.
 */
class MiToolOperations(
    private val context: Context,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "MiTool"
    }

    data class RomImage(val partitionName: String, val fileUri: Uri, val sizeBytes: Long)

    /**
     * Mirrors what miflashf.py + Xiaomi's own flash_all.sh actually do:
     * every `<name>.img` in the ROM's images/ folder gets fastboot-flashed
     * to the partition literally named `<name>` — that convention is how
     * Xiaomi's official scripts work across every device/chipset, so this
     * doesn't need a hardcoded per-device partition list.
     */
    fun scanFastbootRom(treeUri: Uri): List<RomImage> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val imagesDir = root.listFiles().firstOrNull { it.isDirectory && it.name == "images" } ?: root
        return imagesDir.listFiles()
            .filter { it.isFile && it.name?.endsWith(".img", ignoreCase = true) == true }
            .map { file ->
                RomImage(
                    partitionName = file.name!!.removeSuffix(".img").removeSuffix(".IMG"),
                    fileUri = file.uri,
                    sizeBytes = file.length()
                )
            }
            .sortedBy { it.partitionName }
    }

    /**
     * Flashes every selected image to its same-named partition via the
     * app's native fastboot client, then optionally locks the bootloader —
     * the practical equivalent of running Xiaomi's flash_all.sh vs
     * flash_all_lock.sh.
     */
    suspend fun flashFastbootRom(
        images: List<RomImage>,
        lockAfter: Boolean,
        fastboot: FastbootOperations
    ): Boolean = withContext(Dispatchers.IO) {
        log.info(TAG, "Flashing ${images.size} image(s) from the picked ROM folder...")
        var allOk = true
        for (image in images) {
            val localPath = SafFiles.copyToCache(context, image.fileUri, "miflash_${image.partitionName}.img")
            val ok = fastboot.flashPartition(image.partitionName, File(localPath))
            if (!ok) allOk = false
        }
        if (lockAfter) {
            log.warn(TAG, "Locking bootloader (flashing lock)...")
            fastboot.rawCommand("flashing lock")
        }
        if (allOk) log.success(TAG, "Fastboot ROM flash finished.") else log.error(TAG, "One or more partitions failed to flash — check the log above.")
        allOk
    }

    /**
     * Downloads [url] and extracts one named entry from it — the practical
     * equivalent of mifcetool.py's firmware_content_extractor call. Unlike
     * that external library (which used HTTP range requests to fetch only
     * the needed bytes from a remote zip's central directory), this
     * downloads the whole file first: simpler and still correct, but slower
     * and more bandwidth-heavy for multi-gigabyte ROM zips. Selective range-
     * based extraction is a reasonable future improvement, not implemented
     * here.
     */
    suspend fun downloadAndExtractFromZip(url: String, entryName: String): File? = withContext(Dispatchers.IO) {
        val downloadFile = File(context.cacheDir, "mitool_download.zip")
        try {
            log.info(TAG, "Downloading $url ...")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode !in 200..299) {
                log.error(TAG, "Download failed: HTTP ${connection.responseCode}")
                return@withContext null
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastLoggedPercent = -1
            connection.inputStream.use { input ->
                downloadFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastLoggedPercent && percent % 10 == 0) {
                                log.info(TAG, "  download: $percent%")
                                lastLoggedPercent = percent
                            }
                        }
                    }
                }
            }
            log.success(TAG, "Downloaded ${downloaded} bytes, extracting '$entryName'...")

            val outputDir = File(context.filesDir, "mitool_extracted").apply { mkdirs() }
            val outputFile = File(outputDir, entryName.substringAfterLast('/'))
            ZipInputStream(downloadFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName || entry.name.endsWith("/$entryName")) {
                        outputFile.outputStream().use { out -> zip.copyTo(out) }
                        log.success(TAG, "Extracted to ${outputFile.absolutePath}")
                        return@withContext outputFile
                    }
                    entry = zip.nextEntry
                }
            }
            log.error(TAG, "Entry '$entryName' not found in the downloaded zip.")
            null
        } catch (t: Throwable) {
            log.error(TAG, "Extract failed: ${t.message}")
            null
        } finally {
            downloadFile.delete()
        }
    }
}
