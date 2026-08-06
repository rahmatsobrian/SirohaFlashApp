package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Reimplements the operations flash.sh exposed through its Termux menu,
 * against whichever [ShellExecutor] (root or Shizuku) is currently active.
 * Every op streams progress into [LogRepository] so the Logs screen and any
 * exported .log file show exactly what ran and what came back.
 */
class FlashOperations(
    private val context: Context,
    private val executor: ShellExecutor,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "FlashOps"
    }

    /** Menu 3 in flash.sh: "Cek Status UBL" — reads the unlock-bootloader flag. */
    suspend fun checkUblStatus(): ShellResult {
        log.info(TAG, "Checking bootloader unlock status...")
        val result = executor.exec("getprop ro.boot.flash.locked; getprop ro.boot.verifiedbootstate")
        if (result.isSuccess) {
            log.success(TAG, "UBL status: ${result.stdout.joinToString(" | ")}")
        } else {
            log.error(TAG, "Failed to read UBL status: ${result.stderr.joinToString()}")
        }
        return result
    }

    /**
     * Menu 1 in flash.sh: "QDL Flash (EDL 9008)". Runs the bundled qdl binary
     * against a firehose loader + rawprogram/patch XML set the user selects.
     * Streams qdl's own progress output live.
     */
    fun runQdl(
        loaderPath: String,
        rawprogramPaths: List<String>,
        patchPaths: List<String>
    ): Flow<String> = flow {
        val qdl = BinaryManager.qdlPath(context)
        if (qdl == null) {
            log.error(TAG, "qdl binary not found for this device's ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()})")
            emit("[error] qdl binary missing for this ABI")
            return@flow
        }
        val args = buildList {
            add(qdl)
            add("--debug")
            add(loaderPath)
            rawprogramPaths.forEach { add(it) }
            patchPaths.forEach { add(it) }
        }.joinToString(" ") { "'$it'" }

        log.info(TAG, "Starting QDL flash: $args")
        executor.execStreaming(args).collect { line ->
            log.info("qdl", line)
            emit(line)
        }
        log.success(TAG, "QDL flash stream finished (verify exit status in log)")
    }

    /**
     * Menu 7 in flash.sh: "Bypass UBL Redmi 4A (rolex)". Materializes the
     * bundled firehose/patch assets and drives qdl exactly like the original
     * bash implementation did.
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

    /** Menu 6 in flash.sh: "FRP Remove". Left as a thin, explicit wrapper. */
    suspend fun removeFrp(): ShellResult {
        log.warn(TAG, "FRP removal requested — only run this on a device you own or are authorized to service.")
        // flash.sh's original approach wipes the frp/config partition via dd from
        // fastboot mode; that requires a bundled fastboot binary, which this repo
        // did not include (only qdl/EDL binaries were provided). This surface is
        // wired up end-to-end EXCEPT for the actual dd call — see README "Known
        // gaps" for what to add once you supply fastboot binaries per ABI.
        log.error(TAG, "No fastboot binary bundled — see README 'Known gaps' before enabling this.")
        return ShellResult(-1, emptyList(), listOf("fastboot binary not bundled"))
    }
}
