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
