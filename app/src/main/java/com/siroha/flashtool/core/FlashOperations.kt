package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Reimplements the QDL/EDL operations flash.sh exposed through its Termux
 * menu — entirely without root or Shizuku. qdl runs as a completely
 * ordinary, unprivileged child process of this app (`ProcessBuilder`, no
 * shell layer at all), linked at runtime against a reverse-engineered
 * `libusb.so` build (see [BinaryManager.stageNoRootLibs]) that knows how to
 * ask this app's own [UsbBridgeServer] for a USB file descriptor instead of
 * opening `/dev/bus/usb/...` directly — that's the part that normally
 * requires root or Shizuku. Every op streams progress into [LogRepository]
 * so the Logs screen and any exported .log file show exactly what ran and
 * what came back.
 *
 * Fastboot-based operations (menu_fastboot, menu_gsi, menu_ab, and the
 * fastboot half of menu_frp) live in [FastbootOperations] instead, since
 * they go over a completely different transport (raw USB, not a shell) and
 * never needed root/Shizuku in the first place.
 */
class FlashOperations(
    private val context: Context,
    private val log: LogRepository
) {
    companion object {
        private const val TAG = "FlashOps"
    }

    /**
     * Menu 2 in flash.sh: "QDL Flash (EDL 9008)". Runs the bundled qdl binary
     * against a firehose loader + rawprogram/patch XML set the user selects,
     * over a completely unprivileged USB connection — see [UsbBridgeServer]
     * for how qdl talks to the EDL device without root or Shizuku.
     *
     * @param selectedLabels If non-null, only <program> entries in the
     *   rawprogram XML whose label is in this set are flashed — this is what
     *   powers the partition checklist in the QDL Flash screen. Pass null to
     *   flash every partition in the file, matching flash.sh's default.
     * @param storage "emmc" or "ufs", matching flash.sh's menu 1/2 choice.
     * @param includeFolder Optional firmware folder passed as qdl's
     *   `--include`, for images referenced by filename only in the XML.
     * @param dryRun Passes qdl's `--dry-run`: parses the loader/XML and
     *   simulates the whole flash sequence without touching the target's
     *   storage — useful for validating a loader/rawprogram/patch set before
     *   committing to a real flash. Also skips finding/opening a real EDL
     *   device or starting the bridge server at all, while still exercising
     *   the *linking* half of this path for real (qdl launches against the
     *   actual staged `libusb.so` build).
     * @param allowMissing Passes qdl's `--allow-missing`: skips (instead of
     *   failing on) any `<program>`/`<patch>` entry whose referenced file
     *   isn't found under [includeFolder], rather than aborting the run.
     * @param finalizeProvisioning Passes qdl's `--finalize-provisioning`.
     *   qdl itself warns this is IRREVERSIBLE ("WARNING: irreversible
     *   provisioning will start in 5s") — only pass true when the user has
     *   explicitly opted in, never as a default.
     */
    fun runQdlNoRootBridge(
        loaderPath: String,
        rawprogramPaths: List<String>,
        patchPaths: List<String>,
        selectedLabels: Set<String>? = null,
        storage: String = "emmc",
        includeFolder: String? = null,
        allowMissing: Boolean = false,
        dryRun: Boolean = false,
        finalizeProvisioning: Boolean = false,
        debugLog: Boolean = false,
    ): Flow<String> = flow {
        val qdl = BinaryManager.qdlPath(context)
        if (qdl == null) {
            log.error(TAG, "qdl binary not found for this device's ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()})")
            emit("[error] qdl binary missing for this ABI")
            return@flow
        }
        log.debug(TAG, "No-root bridge: resolved qdl=$qdl for ABI ${android.os.Build.SUPPORTED_ABIS.firstOrNull()} (supported: ${android.os.Build.SUPPORTED_ABIS.joinToString()})")
        val stagingDir = BinaryManager.stageNoRootLibs(context)
        if (stagingDir == null) {
            log.error(TAG, "No-root USB bridge libraries aren't bundled for this ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()}) — only arm64-v8a is currently supported.")
            emit("[error] No-root USB bridge isn't available on this device's ABI")
            return@flow
        }
        val stagedFiles = stagingDir.listFiles().orEmpty()
        log.debug(TAG, "Staged ${stagedFiles.size} lib(s) into ${stagingDir.absolutePath}: " +
            stagedFiles.joinToString { "${it.name} (${it.length()}B)" })

        val effectiveRawprogram = if (selectedLabels != null) {
            rawprogramPaths.mapIndexed { i, path ->
                val src = File(path)
                val all = RawProgramXml.parsePartitions(src).map { it.label }.toSet()
                if (selectedLabels == all) {
                    path
                } else {
                    val safeCacheDir = context.externalCacheDir ?: context.cacheDir
                    val filtered = File(safeCacheDir, "qdl_inputs/rawprogram_filtered_nobridge_$i.xml")
                    filtered.parentFile?.mkdirs()
                    RawProgramXml.writeFiltered(src, selectedLabels, filtered)
                    log.info(TAG, "Filtered rawprogram to ${selectedLabels.size}/${all.size} selected partitions")
                    filtered.absolutePath
                }
            }
        } else {
            rawprogramPaths
        }

        val argv = buildList {
            add(qdl)
            if (dryRun) add("--dry-run")
            if (allowMissing) add("--allow-missing")
            if (finalizeProvisioning) add("--finalize-provisioning")
            if (debugLog) add("--debug")
            add("--storage")
            add(storage)
            if (includeFolder != null) {
                add("--include")
                add(includeFolder)
            }
            add(loaderPath)
            effectiveRawprogram.forEach { add(it) }
            patchPaths.forEach { add(it) }
        }

        var bridge: UsbBridgeServer? = null
        val startedAt = System.currentTimeMillis()
        log.debug(TAG, "No-root bridge argv: ${argv.joinToString(" ")}")
        if (finalizeProvisioning) {
            log.warn(TAG, "finalize-provisioning is enabled: qdl treats this as IRREVERSIBLE once it starts.")
        }
        try {
            if (!dryRun) {
                val device = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isEdlDevice(it) }
                if (device == null) {
                    log.warn(TAG, "No EDL (9008) device detected.")
                    emit("[error] No EDL (9008) device detected — connect the device in EDL mode first.")
                    return@flow
                }
                log.info(TAG, "Requesting USB permission for ${device.deviceName} (EDL 9008)...")
                log.debug(TAG, "EDL device: name=${device.deviceName} vendorId=${device.vendorId} productId=${device.productId}")
                if (!UsbDeviceHelper.requestPermission(context, device)) {
                    emit("[error] USB permission denied for the EDL device.")
                    return@flow
                }
                coroutineScope {
                    val server = UsbBridgeServer(context, log)
                    bridge = server
                    val bridgeStartedAt = System.currentTimeMillis()
                    if (!server.start(this, device)) {
                        emit("[error] Could not start the USB bridge (see log for the exact reason: device open failure or the abstract socket was already taken).")
                        return@coroutineScope
                    }
                    log.debug(TAG, "USB bridge started in ${System.currentTimeMillis() - bridgeStartedAt}ms")
                    emit("USB bridge is up — starting qdl unprivileged (no root, no Shizuku)...")
                    runQdlProcessNoRoot(argv, stagingDir, debugLog).collect { emit(it) }
                }
            } else {
                emit("[DRY RUN] Skipping EDL device + USB bridge — running qdl --dry-run unprivileged to confirm it launches against the staged libusb.so build.")
                runQdlProcessNoRoot(argv, stagingDir, debugLog).collect { emit(it) }
            }
        } finally {
            bridge?.stop()
        }
        log.success(TAG, "No-root QDL run finished in ${System.currentTimeMillis() - startedAt}ms (verify exit status in log)")
    }.flowOn(Dispatchers.IO)

    /** Shared "actually spawn qdl as a plain subprocess" step for [runQdlNoRootBridge], both the real and dry-run branches.
     *  LD_LIBRARY_PATH lists [ldLibraryPathDir] (the staged libusb.so / libusb_bridge.so / libxml2.so.16) first, then
     *  nativeLibraryDir as a fallback so any dependency that wasn't (or couldn't be) copied into staging — e.g. libm.so,
     *  libc.so, or a future dep — still resolves instead of failing with "not found". */
    /** Lines matching this (case-insensitive) are the ones surfaced to the UI — same filter [runQdl]'s
     *  shell-side `awk` applies, reimplemented in Kotlin here since this path has no shell pipeline to pipe through. */
    private val qdlNoiseFilter = Regex("flashed|error|warn|fail|waiting|bootable|applied", RegexOption.IGNORE_CASE)

    private fun runQdlProcessNoRoot(argv: List<String>, ldLibraryPathDir: File, debugLog: Boolean = false): Flow<String> = flow {
        log.info(TAG, "Starting QDL flash (no-root bridge): ${argv.joinToString(" ")}")
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val ldLibraryPath = "${ldLibraryPathDir.absolutePath}:$nativeDir"
        log.debug(TAG, "LD_LIBRARY_PATH=$ldLibraryPath")
        // Mirrors runQdl's `tee qdl_debug.log`: full raw output goes to this file when
        // debugLog is on, while only the filtered/deduped lines below reach the UI —
        // keeps the on-screen log readable without losing the raw trace for bug reports.
        val debugWriter = if (debugLog) {
            val safeCacheDir = context.externalCacheDir ?: context.cacheDir
            val debugFile = File(safeCacheDir, "qdl_debug.log")
            log.info(TAG, "Debug log is enabled. Full raw log will be saved to: ${debugFile.absolutePath}")
            runCatching { debugFile.bufferedWriter() }.getOrNull()
        } else null
        val processStartedAt = System.currentTimeMillis()
        val process: Process = try {
            ProcessBuilder(argv)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath }
                .start()
        } catch (e: Exception) {
            log.error(TAG, "Failed to start qdl unprivileged: ${e.javaClass.simpleName}: ${e.message}")
            emit("[error] Failed to start qdl: ${e.message}")
            return@flow
        }

        // process.waitFor()/reader.readLine() below are plain blocking Java
        // I/O, not suspend functions — cancelling this Flow's collecting
        // coroutine (e.g. the person retries, backs out mid-flash, or a
        // previous attempt's job gets replaced) does NOT interrupt them.
        // Without this hook, an orphaned qdl process keeps running in the
        // background, the outer runQdlNoRootBridge()'s `finally { bridge
        // ?.stop() }` never gets a chance to run (it's blocked on this same
        // call stack), and the *next* flash attempt's
        // LocalServerSocket(SOCKET_NAME) bind fails with "Address already
        // in use" — the exact "stuck, needs an app restart" symptom this
        // fixes. invokeOnCompletion fires immediately on cancellation from
        // the cancelling thread, regardless of what this coroutine's body
        // is currently blocked on; destroyForcibly() closes the process's
        // stdout pipe, which is what actually unblocks readLine() below.
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null && process.isAlive) {
                log.warn(TAG, "qdl (no-root) flow cancelled — force-killing orphaned process" + (pidOrNull(process)?.let { " (pid=$it)" } ?: ""))
                process.destroyForcibly()
            }
        }

        val pid: Long? = pidOrNull(process)
        log.debug(TAG, "qdl process started" + (pid?.let { " (pid=$it)" } ?: ""))
        var lineCount = 0
        var lastEmitted: String? = null
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lineCount++
                    log.info("qdl", line)
                    logMissingLibraryHint(line)
                    debugWriter?.let { w -> runCatching { w.write(line); w.newLine(); w.flush() } }
                    // Same dedup-and-filter behaviour as runQdl's awk pipeline: only
                    // forward lines that look meaningful, and never repeat the exact
                    // same line twice in a row (qdl's progress lines repeat a lot).
                    if (qdlNoiseFilter.containsMatchIn(line) && line != lastEmitted) {
                        lastEmitted = line
                        emit(line)
                    }
                }
            }
            val exitCode = process.waitFor()
            val elapsedMs = System.currentTimeMillis() - processStartedAt
            log.debug(TAG, "qdl process exited: code=$exitCode lines=$lineCount elapsed=${elapsedMs}ms")
            if (exitCode != 0) {
                log.error(TAG, "qdl (no-root) exited with code $exitCode after ${elapsedMs}ms")
                emit("[error] qdl exited with code $exitCode")
            } else {
                log.success(TAG, "qdl (no-root) exited cleanly (code 0) after ${elapsedMs}ms")
            }
        } finally {
            // Defense-in-depth alongside the invokeOnCompletion hook above:
            // covers a plain exception thrown mid-read too, not just
            // cancellation.
            if (process.isAlive) process.destroyForcibly()
            runCatching { debugWriter?.close() }
        }
    }

    /**
     * Reflection instead of Process.pid() — that API wasn't resolving
     * against this project's toolchain (see chat history), even with an
     * explicit `Process` type. Android's ProcessBuilder.start() returns a
     * java.lang.UNIXProcess/ProcessImpl instance with a private "pid"
     * field, which reflection can read regardless of that API's resolution
     * issue. Best-effort only — getOrNull() so a failure here (wrong field
     * name on some OEM/AOSP variant) never breaks the actual qdl run, just
     * drops the pid from log lines.
     */
    private fun pidOrNull(process: Process): Long? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getLong(process)
    }.getOrNull()

    /**
     * qdl failing to even start because a shared library it needs (e.g.
     * libxml2.so.16) isn't present shows up as a raw, easy-to-miss native
     * linker line ("CANNOT LINK EXECUTABLE ...: library ... not found").
     * Surface it as an explicit, actionable log entry instead of leaving it
     * to blend in with normal qdl output.
     */
    private fun logMissingLibraryHint(line: String) {
        if (BinaryManager.isMissingLibraryError(line)) {
            log.debug(TAG, "Raw linker error line: $line")
            log.error(
                TAG,
                "qdl failed to start: a shared library it depends on is missing on this device/ABI. " +
                    "Bundle it as jniLibs/<abi>/libxml2.so (see BinaryManager.stageNoRootLibs) so it can be resolved at runtime."
            )
        }
        if (BinaryManager.isLoadProgrammerError(line)) {
            log.error(
                TAG,
                "qdl reported \"unable to load programmer\". This usually means one of two things: " +
                    "(1) qdl couldn't read the loader file due to a permission issue, OR " +
                    "(2) if this isn't a dry run, the connection to the EDL (9008) device dropped or failed to initialize."
            )
        }
    }

    /**
     * Menu 9 in flash.sh: "Bypass UBL Redmi 4A (rolex)". Materializes the
     * bundled firehose/patch assets and drives qdl exactly like the original
     * bash implementation did — always the full 3-partition set, no
     * checklist — over the same no-root USB bridge as [runQdlNoRootBridge].
     */
    fun runBypassUblRedmi4ANoRootBridge(allowMissing: Boolean, dryRun: Boolean = false): Flow<String> = flow {
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
        log.debug(TAG, "bypass-UBL assets: ${loader.name} (${loader.length()}B), ${rawprogram.name} (${rawprogram.length()}B), ${patch.name} (${patch.length()}B) in ${dir.absolutePath}")

        log.warn(TAG, "This flow is device-specific to Redmi 4A (rolex) only — do NOT run it on any other model.")
        runQdlNoRootBridge(
            loaderPath = loader.absolutePath,
            rawprogramPaths = listOf(rawprogram.absolutePath),
            patchPaths = listOf(patch.absolutePath),
            includeFolder = dir.absolutePath,
            allowMissing = allowMissing,
            dryRun = dryRun
        ).collect { emit(it) }
    }

    /**
     * Test-only counterpart to [runBypassUblRedmi4ANoRootBridge] for when
     * there's no EDL-capable device on hand to test against for real. This
     * never touches USB or the qdl binary at all: it just re-checks the
     * bundled assets exist (so a broken/missing asset bundle still shows
     * up), then emits a scripted sequence of realistic-looking output lines
     * with similar pacing to a real run — enough to verify the screen's own
     * line-by-line collection, error-line highlighting, and success/failure
     * snackbar logic actually work, independent of any hardware.
     *
     * @param simulateFailure Emits a failure partway through instead of
     *   completing, so the failure-path UI (red output line, "failed"
     *   snackbar) can be exercised too, not just the happy path.
     */
    fun runBypassUblRedmi4ASimulated(simulateFailure: Boolean = false): Flow<String> = flow {
        log.info(TAG, "[DRY RUN] Preparing Redmi 4A (rolex) bypass-UBL assets...")
        val dir = BinaryManager.materializeAssetDir(context, "bypass-ubl/Redmi4A-rolex")
        val loader = dir.resolve("rahmatsobrian.mbn")
        val rawprogram = dir.resolve("rawprogram0.xml")
        val patch = dir.resolve("patch0.xml")

        if (!loader.exists() || !rawprogram.exists() || !patch.exists()) {
            log.error(TAG, "[DRY RUN] Missing bypass-UBL assets in ${dir.absolutePath}")
            emit("[error] bypass-UBL assets missing")
            return@flow
        }

        emit("[DRY RUN] Simulated only — no real device or qdl binary is used below.")
        delay(300)
        emit("Waiting for EDL (9008) device...")
        delay(500)
        emit("Firehose loader uploaded: ${loader.name}")
        delay(500)
        emit("Firehose handshake OK")
        delay(500)
        emit("Flashing partitions from ${rawprogram.name}...")
        delay(600)
        emit("Flashed partition: fastboot")
        delay(400)
        emit("Flashed partition: partition")
        delay(400)
        if (simulateFailure) {
            log.error(TAG, "[DRY RUN] Simulated a failure outcome on request.")
            emit("[error] Simulated failure: ${patch.name} verification failed (dry run)")
            return@flow
        }
        emit("Applying ${patch.name}...")
        delay(400)
        emit("Patch applied")
        delay(300)
        emit("Bootable flag set")
        log.success(TAG, "[DRY RUN] Simulation finished — no real device was touched.")
    }

}
