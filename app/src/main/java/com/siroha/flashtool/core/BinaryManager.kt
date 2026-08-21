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

    /** Directory the compat alias below is staged into. Outside the app's own
     *  SELinux-isolated storage on purpose — see [wrapWithQdlLibraryPath]. */
    private const val QDL_COMPAT_DIR = "/data/local/tmp/.siroha_qdl_compat"

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
     * The fix ships the dependency as a build-legal "libxml2.so" next to
     * libqdl.so (jniLibs/<abi>/libxml2.so) and stages an alias named
     * "libxml2.so.16" at runtime. That staging is done by
     * [wrapWithQdlLibraryPath], NOT here in the app process — see its doc
     * comment for why an earlier version of this function (which symlinked
     * the alias into the app's own filesDir) still failed under Shizuku.
     *
     * qdl's OTHER non-system dependency, libusb-1.0.so, doesn't have this
     * versioned-soname problem — its DT_NEEDED entry is literally
     * "libusb-1.0.so", which is already a build-legal jniLibs filename. It
     * only needs to be *present* at jniLibs/<abi>/libusb-1.0.so and
     * reachable via LD_LIBRARY_PATH; no alias/copy step required. See
     * [wrapWithQdlLibraryPath], which puts nativeLibraryDir itself on
     * LD_LIBRARY_PATH for exactly this case.
     */
    fun qdlLdLibraryPath(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bundledXml2 = File(nativeDir, "libxml2.so")
        return if (bundledXml2.exists()) nativeDir else null
    }

    /**
     * Wraps a qdl command with a shell prelude that stages the
     * "libxml2.so.16" alias and prefixes LD_LIBRARY_PATH — run through
     * whichever [ShellExecutor] (root or Shizuku) is about to execute qdl
     * itself, rather than created ahead of time from the app's own process.
     *
     * That distinction is the actual fix for "the libxml2 error still
     * happens": the app's own process can freely write into its private
     * filesDir, but a non-root Shizuku shell runs as a different UID under
     * a different SELinux domain and is NOT allowed to read another app's
     * SELinux-isolated app_data_file storage — so an alias staged there was
     * invisible to the very shell trying to use it (root mode happened to
     * work, since su bypasses that isolation, which is why this looked
     * "fixed" under root testing but "still occurs" under Shizuku). qdl's
     * own nativeLibraryDir doesn't have that problem: the same shell about
     * to exec libqdl.so from there can, by definition, also read
     * libxml2.so sitting right next to it. So the copy happens as part of
     * the same command the executor runs, staged into /data/local/tmp —
     * world-readable, and already used elsewhere in this app
     * (ShizukuShellExecutor's streaming log files) as the shared scratch
     * space visible to both root and Shizuku's shell.
     *
     * LD_LIBRARY_PATH also includes nativeLibraryDir directly (in addition
     * to the compat dir) — qdl's DT_RUNPATH is hard-coded to a Termux path
     * from however it was originally built ("/data/data/com.termux/...")
     * which doesn't exist in this app, so nothing resolves from
     * nativeLibraryDir automatically; it has to be added explicitly. This
     * is what lets any correctly-named dependency dropped into
     * jniLibs/<abi>/ — e.g. libusb-1.0.so, once bundled — resolve without
     * needing its own copy-to-/data/local/tmp special case the way the
     * versioned libxml2.so.16 alias needs.
     */
    fun wrapWithQdlLibraryPath(context: Context, qdlCommand: String): String {
        val nativeDir = qdlLdLibraryPath(context) ?: return qdlCommand
        val bundledXml2 = "$nativeDir/libxml2.so"
        val alias = "$QDL_COMPAT_DIR/libxml2.so.16"
        val stage = "mkdir -p '$QDL_COMPAT_DIR' && cp -f '$bundledXml2' '$alias' 2>/dev/null"
        return "$stage; LD_LIBRARY_PATH=\"$QDL_COMPAT_DIR:$nativeDir:\$LD_LIBRARY_PATH\" $qdlCommand"
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
