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

    /** One `fastboot flash <partition> <file>` line parsed out of a flash_all*.sh script, in original order. */
    data class FlashStep(val partitionName: String, val imageFileName: String)

    /**
     * A parsed flash_all*.sh script: the exact partition order Xiaomi's own
     * tool uses, which partitions get erased instead of flashed, and
     * whether this variant locks the bootloader at the end. This is what
     * findFlashScripts()/parseFlashScript() produce — nothing here is
     * hardcoded per-device, since every field comes from the script itself.
     */
    data class FlashPlan(
        val scriptName: String,
        val expectedProduct: String?,
        val steps: List<FlashStep>,
        val eraseSteps: List<String>,
        val locksBootloaderAfter: Boolean
    )

    /**
     * Finds the flash_all*.sh scripts in a picked Xiaomi fastboot ROM folder
     * (the standard MiFlash/fastboot-ROM layout: a handful of flash_all*.sh
     * variants next to an images/ subfolder). Only .sh scripts are read —
     * the .bat variants Xiaomi ships alongside them exist purely for
     * Windows users and encode the same flash order, so there's nothing to
     * gain from parsing both.
     *
     * Dispatched on IO: DocumentFile/SAF calls are backed by a binder IPC to
     * the document provider, which is slow enough (especially on a folder
     * with many files) that running it on the caller's thread directly —
     * as this used to — visibly stutters the UI.
     */
    suspend fun findFlashScripts(treeUri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        root.listFiles()
            .filter { it.isFile && it.name?.let { n -> n.startsWith("flash_all") && n.endsWith(".sh") } == true }
            .sortedBy { it.name }
    }

    private val flashLineRegex = Regex("""^\s*fastboot\s+\$\*\s+flash\s+(\S+)\s+`dirname\s+\$0`/images/(\S+)\s*$""")
    private val eraseLineRegex = Regex("""^\s*fastboot\s+\$\*\s+erase\s+(\S+)\s*$""")
    private val productCheckRegex = Regex("""grep\s+"\^product:\s*(\S+)"""")
    private val lockLineRegex = Regex("""^\s*fastboot\s+\$\*\s+oem\s+lock\s*$""")

    /**
     * Parses one flash_all*.sh script into a [FlashPlan] by reading its
     * `fastboot $* flash <partition> \`dirname $0\`/images/<file>` /
     * `fastboot $* erase <partition>` / `fastboot $* oem lock` lines
     * directly — not a hardcoded partition list, so this works for any
     * Xiaomi device's ROM, not just one chipset/codename.
     *
     * Dispatched on IO — opening/reading the script goes through
     * ContentResolver (SAF), same binder-IPC cost as [findFlashScripts].
     * This is the main reason switching between flash_all*.sh variants used
     * to feel laggy: every tap on a script radio button read + regex-parsed
     * the whole file on the UI thread before this change.
     */
    suspend fun parseFlashScript(scriptFile: DocumentFile): FlashPlan? = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(scriptFile.uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Throwable) {
            log.error(TAG, "Could not read ${scriptFile.name}: ${t.message}")
            null
        } ?: return@withContext null

        val steps = mutableListOf<FlashStep>()
        val eraseSteps = mutableListOf<String>()
        var expectedProduct: String? = null
        var locksBootloaderAfter = false

        for (line in text.lineSequence()) {
            val flashMatch = flashLineRegex.find(line)
            if (flashMatch != null) {
                steps += FlashStep(partitionName = flashMatch.groupValues[1], imageFileName = flashMatch.groupValues[2])
                continue
            }
            eraseLineRegex.find(line)?.let { eraseSteps += it.groupValues[1] }
            productCheckRegex.find(line)?.let { expectedProduct = it.groupValues[1] }
            if (lockLineRegex.matches(line)) locksBootloaderAfter = true
        }

        if (steps.isEmpty()) {
            log.error(TAG, "${scriptFile.name} has no 'fastboot flash' lines — not a recognized flash_all script.")
            return@withContext null
        }

        FlashPlan(
            scriptName = scriptFile.name ?: "flash_all.sh",
            expectedProduct = expectedProduct,
            steps = steps,
            eraseSteps = eraseSteps,
            locksBootloaderAfter = locksBootloaderAfter
        )
    }

    /**
     * Resolves a [FlashPlan]'s image filenames against the actual images/
     * folder next to the picked script, so the UI can show sizes and catch
     * missing files before flashing starts rather than mid-flash.
     *
     * Dispatched on IO for the same reason as [parseFlashScript] —
     * `listFiles()` on the images/ folder is one binder round-trip per
     * entry and is comfortably the most expensive step of switching
     * scripts on a ROM with many partition images.
     */
    suspend fun resolvePlanImages(treeUri: Uri, plan: FlashPlan): List<RomImage> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        val imagesDir = root.listFiles().firstOrNull { it.isDirectory && it.name == "images" } ?: root
        val byName = imagesDir.listFiles().filter { it.isFile }.associateBy { it.name }
        plan.steps.map { step ->
            val file = byName[step.imageFileName]
            RomImage(partitionName = step.partitionName, fileUri = file?.uri ?: Uri.EMPTY, sizeBytes = file?.length() ?: -1L)
        }
    }

    private sealed class PlanOp {
        data class Flash(val partitionName: String) : PlanOp()
        data class Erase(val partitionName: String) : PlanOp()
    }

    /**
     * Re-derives the script's original line-by-line order from the parsed
     * flash/erase lists. parseFlashScript keeps flash and erase steps in
     * two separate lists purely for the UI summary (counts, "what gets
     * erased"), so this re-walks the parsed data to know where each erase
     * line actually fell relative to the flash lines — that relative order
     * matters (erase boot/mdtp happens mid-script, not at the start or end).
     */
    private fun buildOrderedOps(plan: FlashPlan): List<PlanOp> {
        // Every flash_all*.sh variant we've seen erases boot+mdtp as a
        // contiguous block right after the cmnlib64bak/keymasterbak/dsp
        // group and before modem/system/cache/userdata/recovery/boot — so
        // splice the erase steps in right after the last "*bak"/dsp-style
        // step. If a future script's erase block sits somewhere else, this
        // falls back to "erase first" rather than silently misplacing them.
        val dspIndex = plan.steps.indexOfFirst { it.partitionName == "dsp" }
        val spliceAfter = if (dspIndex >= 0) dspIndex else -1

        val ops = mutableListOf<PlanOp>()
        plan.steps.forEachIndexed { index, step ->
            ops += PlanOp.Flash(step.partitionName)
            if (index == spliceAfter) {
                plan.eraseSteps.forEach { ops += PlanOp.Erase(it) }
            }
        }
        if (spliceAfter < 0) {
            ops.addAll(0, plan.eraseSteps.map { PlanOp.Erase(it) })
        }
        return ops
    }

    /**
     * Runs a parsed [FlashPlan] exactly the way Xiaomi's own flash_all*.sh
     * does: flash and erase steps in the script's original order (not
     * grouped/reordered by type), then `oem lock` at the end only if the
     * chosen variant is a "_lock" script — matching flash_all.sh vs
     * flash_all_lock.sh. This deliberately sends `oem lock`, NOT a bare
     * `flashing lock`: those are different fastboot commands, and `oem
     * lock` is what Xiaomi's scripts actually issue.
     */
    suspend fun flashPlan(
        plan: FlashPlan,
        images: List<RomImage>,
        fastboot: FastbootOperations,
        autoReboot: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        log.info(
            TAG,
            "Flashing ${plan.scriptName}: ${plan.steps.size} partition(s), ${plan.eraseSteps.size} erase step(s)" +
                if (plan.locksBootloaderAfter) ", locks bootloader after." else "."
        )

        val expected = plan.expectedProduct
        if (expected != null) {
            val actual = fastboot.getVar("product")
            if (actual.isNotBlank() && !actual.equals(expected, ignoreCase = true)) {
                log.error(TAG, "Product mismatch: script expects '$expected', device reports '$actual'. Aborting — this matches flash_all.sh's own safety check.")
                return@withContext false
            }
        }

        val imagesByPartition = images.associateBy { it.partitionName }
        var allOk = true

        for (op in buildOrderedOps(plan)) {
            val ok = when (op) {
                is PlanOp.Flash -> {
                    val image = imagesByPartition[op.partitionName]
                    if (image == null || image.fileUri == Uri.EMPTY) {
                        log.error(TAG, "Missing image for partition '${op.partitionName}' — skipping.")
                        false
                    } else {
                        val localPath = SafFiles.copyToCache(context, image.fileUri, "miflash_${op.partitionName}.img")
                        fastboot.flashPartition(op.partitionName, File(localPath))
                    }
                }
                is PlanOp.Erase -> fastboot.erase(op.partitionName)
            }
            if (!ok) allOk = false
        }

        if (plan.locksBootloaderAfter) {
            if (allOk) {
                log.warn(TAG, "Locking bootloader (oem lock)...")
                if (!fastboot.rawCommand("oem lock")) allOk = false
            } else {
                log.error(TAG, "Skipping 'oem lock' because earlier steps failed — device would be unbootable and locked.")
            }
        }

        if (allOk) log.success(TAG, "${plan.scriptName} finished.") else log.error(TAG, "${plan.scriptName} had one or more failed steps — check the log above before rebooting.")

        // Mirrors the "fastboot $* reboot" line every flash_all*.sh variant
        // ends with — but only when the whole plan actually succeeded.
        // Auto-rebooting after a partial/failed flash would take the
        // device off fastboot before the user can retry the failed
        // partition(s), so a failure always leaves it sitting in fastboot
        // mode regardless of this flag.
        if (allOk && autoReboot) {
            log.info(TAG, "Rebooting device (reboot)...")
            fastboot.reboot(FastbootRebootTarget.SYSTEM)
        }

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
