package com.siroha.flashtool.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Helpers behind [BackgroundStyle.CUSTOM] in Settings > Appearance.
 */
object WallpaperUtils {

    /**
     * Copies a photo-picker [uri] into internal storage (downscaled to a
     * sane max dimension) and returns the saved file's absolute path, or
     * null on failure.
     *
     * The filename includes a timestamp on purpose: [AppBackground] keys
     * its image-loading `remember`/`LaunchedEffect` off this path string, so
     * reusing a fixed filename here meant picking a *different* image still
     * produced the *same* path — Compose saw no change and kept showing the
     * old bitmap until something else (like switching background style)
     * forced a recomposition. A fresh filename every pick guarantees the
     * path always changes, so the new image shows immediately.
     */
    fun persistPickedImage(context: Context, uri: Uri): String? = runCatching {
        context.filesDir.listFiles { f -> f.name.startsWith("appearance_background_") }
            ?.forEach { it.delete() }
        val target = File(context.filesDir, "appearance_background_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val original = BitmapFactory.decodeStream(input) ?: return null
            val maxDim = 1440
            val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original,
                    (original.width * scale).toInt().coerceAtLeast(1),
                    (original.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else original
            FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        } ?: return null
        target.absolutePath
    }.getOrNull()

    fun readCustomImage(path: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(path)
    }.getOrNull()

    /**
     * Copies a picked video file into internal storage and returns its
     * absolute path, or null on failure. Unlike [persistPickedImage], there's
     * no downscaling step — video re-encoding is a much heavier operation
     * than a bitmap resize, and out of scope for what's meant to be a
     * lightweight looping background clip. Same fresh-filename-per-pick
     * reasoning as [persistPickedImage] applies here too.
     */
    fun persistPickedVideo(context: Context, uri: Uri): String? = runCatching {
        context.filesDir.listFiles { f -> f.name.startsWith("appearance_background_video_") }
            ?.forEach { it.delete() }
        val target = File(context.filesDir, "appearance_background_video_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        } ?: return null
        target.absolutePath
    }.getOrNull()
}
