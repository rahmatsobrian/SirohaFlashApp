package com.siroha.flashtool.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.siroha.flashtool.ui.theme.Button
import com.siroha.flashtool.ui.theme.TextButton
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen crop tool for a picked wallpaper image, shown after
 * "Potong gambar?" is confirmed. The crop frame always matches the dialog's
 * own bounds (edge-to-edge, no letterboxing) since the output is a
 * full-bleed app background — there's no separate movable crop rectangle to
 * position the way a generic photo-crop tool would have, just the photo
 * itself, pinch-zoomed and dragged until the part the person wants is what
 * fills the screen.
 *
 * On confirm, renders exactly what's on screen — the visible crop window —
 * into a same-size [Bitmap] via [Canvas], so the result matches the preview
 * pixel-for-pixel rather than requiring a separate coordinate-math pass.
 *
 * @param onCropped Called with the cropped bitmap when the person taps
 *   "Potong". Caller is responsible for persisting it (see
 *   [com.siroha.flashtool.core.WallpaperUtils.persistCroppedBitmap]).
 * @param onDismiss Called when the person cancels without cropping.
 */
@Composable
fun ImageCropDialog(
    source: Bitmap,
    onCropped: (Bitmap) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var frameSize by remember { mutableStateOf(IntSize.Zero) }

        // Smallest scale that still fully covers the crop frame on both
        // axes — same "no gaps at the edges" invariant ContentScale.Crop
        // enforces, recomputed any time the dialog's own size changes (e.g.
        // rotation) so a stale minimum doesn't leave the image undersized.
        val minScale = remember(frameSize, source) {
            if (frameSize.width == 0 || frameSize.height == 0) 1f
            else max(
                frameSize.width.toFloat() / source.width,
                frameSize.height.toFloat() / source.height
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { frameSize = it }
                    .pointerInput(source) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(minScale, minScale * 5f)
                            scale = newScale
                            offset = clampOffset(offset + pan, frameSize, source, newScale)
                        }
                    }
            ) {
                val bitmapState = remember(source) { source.asImageBitmap() }
                Image(
                    bitmap = bitmapState,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }

            Text(
                "Seret dan cubit untuk mengatur posisi",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Batal", color = Color.White)
                }
                Button(
                    onClick = {
                        val cropped = renderCrop(source, frameSize, scale, offset)
                        onCropped(cropped)
                    }
                ) {
                    Text("Potong")
                }
            }
        }
    }
}

/**
 * Keeps the image from being panned past its own edges — without this, a
 * large pan gesture (or zooming back out after zooming in) could leave a
 * black gap at the crop frame's border instead of image content.
 */
private fun clampOffset(raw: Offset, frameSize: IntSize, source: Bitmap, scale: Float): Offset {
    if (frameSize.width == 0 || frameSize.height == 0) return Offset.Zero
    val scaledW = source.width * scale
    val scaledH = source.height * scale
    val maxX = max(0f, (scaledW - frameSize.width) / 2f)
    val maxY = max(0f, (scaledH - frameSize.height) / 2f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/**
 * Renders the currently-visible crop window (frame-sized, at the person's
 * chosen pan/zoom) into a new same-size bitmap. Mirrors the same
 * scale+translate math [ImageCropDialog] applies via `graphicsLayer` for
 * on-screen preview, just replayed on a [Canvas] instead of the GPU
 * compositor, so the two always agree on what "the crop" looked like.
 */
private fun renderCrop(source: Bitmap, frameSize: IntSize, scale: Float, offset: Offset): Bitmap {
    val w = frameSize.width.coerceAtLeast(1)
    val h = frameSize.height.coerceAtLeast(1)
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        // graphicsLayer's translationX/Y is applied in the *scaled* image's
        // own coordinate space and offsets from center; postTranslate here
        // needs the same center-relative offset restated as absolute
        // top-left canvas coordinates, hence the extra `(frameSize -
        // scaledSize) / 2` recentering term alongside the pan offset.
        val scaledW = source.width * scale
        val scaledH = source.height * scale
        postTranslate(
            (w - scaledW) / 2f + offset.x,
            (h - scaledH) / 2f + offset.y
        )
    }
    canvas.drawBitmap(source, matrix, null)
    return result
}
