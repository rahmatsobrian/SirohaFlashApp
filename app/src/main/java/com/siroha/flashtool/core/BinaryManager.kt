package com.siroha.flashtool.core

import android.content.Context
import java.io.File

/**
 * The qdl binaries from bin/<abi>/qdl were packaged as jniLibs/<abi>/libqdl.so
 * (see app/build.gradle.kts + the jniLibs/ folder). Android's package manager
 * extracts native libs to a directory that is guaranteed executable even under
 * Android 10+'s W^X restrictions on app-private storage, so this is the
 * reliable way to ship arbitrary ABI-specific executables in an app — the
 * same trick most EDL-flash apps on the Play Store use.
 */
object BinaryManager {

    /** Absolute, executable path to the qdl binary matching this device's ABI. */
    fun qdlPath(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir
        val candidate = File(dir, "libqdl.so")
        return if (candidate.exists()) candidate.absolutePath else null
    }

    /**
     * True if this ABI has the reverse-engineered "USB bridge" libraries
     * bundled (see [UsbBridgeServer]'s class doc for what they are and how
     * confirmed the mechanism is) — currently arm64-v8a only.
     */
    fun hasNoRootBridgeLibs(context: Context): Boolean {
        val dir = context.applicationInfo.nativeLibraryDir
        return File(dir, "libusb.so").exists() && File(dir, "libusb_bridge.so").exists()
    }

    /**
     * Stages everything the no-root QDL path needs into one directory the
     * app's own process can write to directly — no shell-UID boundary to
     * cross at all, since [FlashOperations.runQdlNoRootBridge] execs qdl as
     * a plain child of this same app process.
     *
     * The key step is copying jniLibs/libusb.so — the reverse-engineered,
     * bridge-patched build — into the staging dir under its OWN name,
     * "libusb.so", because that (confirmed via `readelf -d` on the bundled
     * arm64-v8a libqdl.so: `NEEDED libusb.so`) is the exact DT_NEEDED
     * soname our own qdl binary actually looks for — not "libusb-1.0.so"
     * as an earlier version of this comment assumed. No rename/alias step
     * is needed here at all, unlike the libxml2.so.16 case below, since
     * "libusb.so" is already a build-legal jniLibs filename and the file is
     * already named that. This copy only ever lives in [context]'s own
     * filesDir, on an LD_LIBRARY_PATH entry that's exclusive to this
     * no-root path.
     *
     * Returns the staging directory to put on LD_LIBRARY_PATH, or null if
     * this ABI doesn't have the bridge libraries bundled (see
     * [hasNoRootBridgeLibs]) or nativeLibraryDir is otherwise missing
     * something qdl needs.
     */
    fun stageNoRootLibs(context: Context): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bridgedLibusb = File(nativeDir, "libusb.so")
        val bridgeSo = File(nativeDir, "libusb_bridge.so")
        if (!bridgedLibusb.exists() || !bridgeSo.exists()) return null

        val stagingDir = File(context.filesDir, "qdl_nobridge")
        stagingDir.mkdirs()
        runCatching { bridgedLibusb.copyTo(File(stagingDir, "libusb.so"), overwrite = true) }
            .onFailure { return null }
        runCatching { bridgeSo.copyTo(File(stagingDir, "libusb_bridge.so"), overwrite = true) }
            .onFailure { return null }

        // qdl needs a "libxml2.so.16" alias regardless (same versioned-soname
        // problem — Android's packager rejects that filename directly, so the
        // build-legal "libxml2.so" gets copied here under the name qdl's
        // dynamic linker actually looks for).
        val bundledXml2 = File(nativeDir, "libxml2.so")
        if (bundledXml2.exists()) {
            runCatching { bundledXml2.copyTo(File(stagingDir, "libxml2.so.16"), overwrite = true) }
        }

        return stagingDir
    }

    /**
     * True if a raw qdl process's combined output looks like the
     * "CANNOT LINK EXECUTABLE ... not found: needed by main executable"
     * dynamic-linker failure — used to turn that cryptic native-loader
     * message into an actionable log line instead of leaving it as-is.
     */
    fun isMissingLibraryError(line: String): Boolean =
        line.contains("CANNOT LINK EXECUTABLE") && line.contains("not found")

    /**
     * True if a raw qdl process's output is the misleading
     * `unable to load programmer "<path>"` line this build of qdl
     * (arm64-v8a, v2.2-26-g8c42508-dirty) prints. Despite the wording, this
     * build reaches that message when it can't claim a 9008/EDL USB device
     * at all — NOT only when the loader path is actually missing/unreadable.
     * The other three ABIs bundled here (armeabi-v7a/x86/x86_64) are a
     * different qdl build with a different usage string; this heuristic is
     * specific to the arm64-v8a binary's behavior, so callers should still
     * verify the loader path themselves before assuming "no device" is the
     * whole story.
     */
    fun isLoadProgrammerError(line: String): Boolean =
        line.contains("unable to load programmer")

    /**
     * Copies a bypass-UBL asset directory (e.g. "bypass-ubl/Redmi4A-rolex") out
     * of the APK's assets into the app's own cache dir, as an absolute path
     * qdl's `ProcessBuilder` child process can open directly.
     */
    fun materializeAssetDir(context: Context, assetDir: String): File {
        val baseDir = context.externalCacheDir ?: context.cacheDir
        val target = File(baseDir, assetDir)
        target.mkdirs()
        val am = context.assets
        am.list(assetDir)?.forEach { name ->
            val outFile = File(target, name)
            // Blok if dihapus, langsung jalankan copyTo
            am.open("$assetDir/$name").use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }
}
