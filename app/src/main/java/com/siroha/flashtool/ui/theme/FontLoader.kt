package com.siroha.flashtool.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import com.siroha.flashtool.core.FontFamilyPreset
import java.io.File

/**
 * Resolves [FontFamilyPreset] to an actual [FontFamily], loading a
 * person-picked .ttf/.otf file for [FontFamilyPreset.CUSTOM]. Adapted from
 * FolkPatch's `FontConfig` — trimmed to just the parts
 * [com.siroha.flashtool.core.ThemePreferences] doesn't already cover
 * (persistence lives in DataStore there, not SharedPreferences).
 */
object FontLoader {
    private const val TAG = "FontLoader"

    // Avoids re-reading the font file from disk (Typeface.createFromFile) on every recomposition.
    private var cachedPath: String? = null
    private var cachedFamily: FontFamily? = null

    /** Copies a picked font [uri] into internal storage and returns its absolute path, or null on failure. */
    fun persistPickedFont(context: Context, uri: Uri): String? = runCatching {
        context.filesDir.listFiles { f -> f.name.startsWith("custom_font_") }?.forEach { it.delete() }
        val target = File(context.filesDir, "custom_font_${System.currentTimeMillis()}.ttf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    fun resolve(context: Context, preset: FontFamilyPreset, customPath: String?): FontFamily = when (preset) {
        FontFamilyPreset.DEFAULT -> FontFamily.Default
        FontFamilyPreset.SERIF -> FontFamily.Serif
        FontFamilyPreset.MONOSPACE -> FontFamily.Monospace
        FontFamilyPreset.CUSTOM -> loadCustom(context, customPath)
    }

    private fun loadCustom(context: Context, path: String?): FontFamily {
        if (path == null) return FontFamily.Default
        if (path == cachedPath && cachedFamily != null) return cachedFamily!!
        val file = File(path)
        if (!file.exists()) return FontFamily.Default
        return try {
            val family = FontFamily(Typeface.createFromFile(file))
            cachedPath = path
            cachedFamily = family
            family
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom font", e)
            FontFamily.Default
        }
    }
}
