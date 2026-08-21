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
     * qdl (like most qcserial/libxml2-based flashing tools) is dynamically
     * linked against libxml2, and its DT_NEEDED entry names the exact
     * soname it was built against — typically "libxml2.so.16" — not the
     * generic "libxml2.so". Android's packager only accepts native-library
     * files whose name ends in ".so" into nativeLibraryDir (a file literally
     * named "libxml2.so.16" gets rejected/stripped at build time), so a
     * versioned dependency like this can never be bundled directly next to
     * libqdl.so under the name the linker is actually looking for. Without
     * it, launching qdl fails immediately with:
     *   CANNOT LINK EXECUTABLE ".../libqdl.so": library "libxml2.so.16"
     *   not found: needed by main executable
     *
     * The fix: bundle the dependency as a build-legal "libxml2.so" (e.g.
     * jniLibs/<abi>/libxml2.so — see jniLibs/ next to libqdl.so), then at
     * runtime create a symlink under the app's own writable storage named
     * "libxml2.so.16" pointing at it, and point the dynamic linker at that
     * directory via LD_LIBRARY_PATH when qdl is launched (see
     * [FlashOperations]). Returns null if no libxml2.so was bundled for
     * this ABI — callers should still try running qdl in that case (it may
     * not need it, or the device may already carry a compatible system
     * libxml2.so.16), but should treat a "library ... not found" failure as
     * expected rather than surprising.
     */
    fun qdlLdLibraryPath(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bundledXml2 = File(nativeDir, "libxml2.so")
        if (!bundledXml2.exists()) return null

        val compatDir = File(context.filesDir, "qdl-compat-libs").apply { mkdirs() }
        val alias = File(compatDir, "libxml2.so.16")
        if (!alias.exists()) {
            runCatching {
                android.system.Os.symlink(bundledXml2.absolutePath, alias.absolutePath)
            }.recoverCatching {
                // Some storage/filesystem configurations disallow symlinks —
                // fall back to a plain copy under the alias name, which the
                // linker treats identically to a symlink for its purposes.
                bundledXml2.copyTo(alias, overwrite = true)
            }
        }
        return if (alias.exists()) "${compatDir.absolutePath}:$nativeDir" else null
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
     * Copies a bypass-UBL asset directory (e.g. "bypass-ubl/Redmi4A-rolex") out
     * of the APK's assets into the app's private files dir, where a root/Shizuku
     * shell command can actually read it from an absolute path.
     */
    fun materializeAssetDir(context: Context, assetDir: String): File {
        val target = File(context.filesDir, assetDir)
        target.mkdirs()
        val am = context.assets
        am.list(assetDir)?.forEach { name ->
            val outFile = File(target, name)
            if (!outFile.exists() || outFile.length() == 0L) {
                am.open("$assetDir/$name").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return target
    }
}
