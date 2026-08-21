package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Reimplements the QDL/EDL operations flash.sh exposed through its Termux
 * menu, against whichever [ShellExecutor] (root or Shizuku) is currently
 * active. Every op streams progress into [LogRepository] so the Logs screen
 * and any exported .log file show exactly what ran and what came back.
 *
 * Fastboot-based operations (menu_fastboot, menu_gsi, menu_ab, and the
 * fastboot half of menu_frp) live in [FastbootOperations] instead, since
 * they go over a completely different transport (raw USB, not a shell).
 */
class FlashOperations(
    private val context: Context,
    private val executor: ShellExecutor,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "FlashOps"
    }

    /**
     * Menu 2 in flash.sh: "QDL Flash (EDL 9008)". Runs the bundled qdl binary
     * against a firehose loader + rawprogram/patch XML set the user selects.
     *
     * @param selectedLabels If non-null, only <program> entries in the
     *   rawprogram XML whose label is in this set are flashed — this is what
     *   powers the partition checklist in the QDL Flash screen. Pass null to
     *   flash every partition in the file, matching flash.sh's default.
     * @param storage "emmc" or "ufs", matching flash.sh's menu 1/2 choice.
     * @param includeFolder Optional firmware folder passed as qdl's
     *   `--include`, for images referenced by filename only in the XML.
     */
    fun runQdl(
        loaderPath: String,
        rawprogramPaths: List<String>,
        patchPaths: List<String>,
        selectedLabels: Set<String>? = null,
        storage: String = "emmc",
        includeFolder: String? = null
    ): Flow<String> = flow {
        val qdl = BinaryManager.qdlPath(context)
        if (qdl == null) {
            log.error(TAG, "qdl binary not found for this device's ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()})")
            emit("[error] qdl binary missing for this ABI")
            return@flow
        }

        val effectiveRawprogram = if (selectedLabels != null) {
            rawprogramPaths.mapIndexed { i, path ->
                val src = File(path)
                val all = RawProgramXml.parsePartitions(src).map { it.label }.toSet()
                if (selectedLabels == all) {
                    path // nothing deselected — flash the original file as-is
                } else {
                    val filtered = File(context.cacheDir, "qdl_inputs/rawprogram_filtered_$i.xml")
                    filtered.parentFile?.mkdirs()
                    RawProgramXml.writeFiltered(src, selectedLabels, filtered)
                    log.info(TAG, "Filtered rawprogram to ${selectedLabels.size}/${all.size} selected partitions")
                    filtered.absolutePath
                }
            }
        } else {
            rawprogramPaths
        }

        val args = buildList {
            add(qdl)
            add("--debug")
            add("--storage")
            add(storage)
            if (includeFolder != null) {
                add("--include")
                add(includeFolder)
            }
            add(loaderPath)
            effectiveRawprogram.forEach { add(it) }
            patchPaths.forEach { add(it) }
        }.joinToString(" ") { "'$it'" }

        val command = withQdlLdLibraryPath(args)
        log.info(TAG, "Starting QDL flash: $args")
        executor.execStreaming(command).collect { line ->
            log.info("qdl", line)
            logMissingLibraryHint(line)
            emit(line)
        }
        log.success(TAG, "QDL flash stream finished (verify exit status in log)")
    }

    /** Menu 7 (manual command) in flash.sh's QDL menu: raw qdl arguments, unmodified. */
    fun runQdlManual(rawArgs: String): Flow<String> = flow {
        val qdl = BinaryManager.qdlPath(context)
        if (qdl == null) {
            log.error(TAG, "qdl binary not found for this device's ABI")
            emit("[error] qdl binary missing for this ABI")
            return@flow
        }
        val command = withQdlLdLibraryPath("'$qdl' $rawArgs")
        log.info(TAG, "Running manual QDL command: $command")
        executor.execStreaming(command).collect { line ->
            log.info("qdl", line)
            logMissingLibraryHint(line)
            emit(line)
        }
    }

    /**
     * Wraps a qdl invocation with a shell prelude that stages any bundled
     * compat libraries (currently: libxml2, see
     * [BinaryManager.wrapWithQdlLibraryPath]) under the exact versioned
     * name qdl's dynamic linker looks for, run through the SAME executor
     * (root or Shizuku) that's about to run qdl itself. A no-op (returns
     * [qdlCommand] unchanged) if nothing was bundled for this ABI.
     */
    private fun withQdlLdLibraryPath(qdlCommand: String): String =
        BinaryManager.wrapWithQdlLibraryPath(context, qdlCommand)

    /**
     * qdl failing to even start because a shared library it needs (e.g.
     * libxml2.so.16) isn't present shows up as a raw, easy-to-miss native
     * linker line ("CANNOT LINK EXECUTABLE ...: library ... not found").
     * Surface it as an explicit, actionable log entry instead of leaving it
     * to blend in with normal qdl output.
     */
    private fun logMissingLibraryHint(line: String) {
        if (BinaryManager.isMissingLibraryError(line)) {
            log.error(
                TAG,
                "qdl failed to start: a shared library it depends on is missing on this device/ABI. " +
                    "Bundle it as jniLibs/<abi>/libxml2.so (see BinaryManager.qdlLdLibraryPath) so it can be resolved at runtime."
            )
        }
        if (BinaryManager.isLoadProgrammerError(line)) {
            log.error(
                TAG,
                "qdl reported \"unable to load programmer\" — on this device's ABI build of qdl, this message " +
                    "almost always means no 9008/EDL device was claimed over USB (misleading wording, not a " +
                    "missing/corrupt loader file). Check: (1) device is actually in EDL/9008 mode and shows up " +
                    "in the EDL device check, (2) the app has USB host permission for it, (3) only if the device " +
                    "IS connected and this still happens, then re-check the loader path/permissions."
            )
        }
    }

    /**
     * Menu 9 in flash.sh: "Bypass UBL Redmi 4A (rolex)". Materializes the
     * bundled firehose/patch assets and drives qdl exactly like the original
     * bash implementation did — always the full 3-partition set, no checklist.
     */
    fun runBypassUblRedmi4A(): Flow<String> = flow {
        log.info(TAG, "Preparing Redmi 4A (rolex) bypass-UBL assets...")
        val dir = BinaryManager.materializeAssetDir(context, "bypass-ubl/Redmi4A-rolex")
        val loader = dir.resolve("rahmatsobrian.mbn")
        val rawprogram = dir.resolve("rawprogram0.xml")
        val patch = dir.resolve("patch0.xml")

        if (!loader.exists() || !rawprogram.exists() || !patch.exists()) {
            log.error(TAG, "Missing bypass-UBL assets in ${dir.absolutePath}")
            emit("[error] bypass-UBL assets missing")
            return@flow
        }

        log.warn(TAG, "This flow is device-specific to Redmi 4A (rolex) only — do NOT run it on any other model.")
        runQdl(
            loaderPath = loader.absolutePath,
            rawprogramPaths = listOf(rawprogram.absolutePath),
            patchPaths = listOf(patch.absolutePath)
        ).collect { emit(it) }
    }

    /** Menu 3/4 in flash.sh's QDL menu: EDL/ADB device presence checks. */
    suspend fun checkEdlDevice(): List<String> {
        val devices = UsbDeviceHelper.listDevices(context).filter { UsbDeviceHelper.isEdlDevice(it) }
        return if (devices.isEmpty()) {
            log.warn(TAG, "No EDL (9008) device detected.")
            emptyList()
        } else {
            devices.forEach { log.success(TAG, "EDL device: ${it.deviceName} (${it.vendorId}:${it.productId})") }
            devices.map { it.deviceName }
        }
    }
}
