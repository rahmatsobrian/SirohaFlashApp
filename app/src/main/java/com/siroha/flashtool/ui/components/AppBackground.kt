package com.siroha.flashtool.ui.components

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.siroha.flashtool.core.BackgroundStyle
import com.siroha.flashtool.core.WallpaperUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stock [VideoView] letterboxes/pillarboxes — it preserves the video's
 * native aspect ratio within whatever bounds it's given rather than filling
 * them, which is why the background video wasn't actually covering the
 * screen. This background is decorative (the same trade-off the blurred
 * static-image background already makes via `ContentScale.Crop`), so this
 * always claims the exact bounds it was given instead.
 */

/**
 * Full-bleed background layer for the "Custom wallpaper" Appearance mode:
 * draws a user-picked image behind [content], blurred by [blurRadius], with
 * a dark scrim so text stays legible regardless of how bright the image is.
 * When [style] is [BackgroundStyle.NONE] this is a zero-cost passthrough —
 * [content] is the only thing composed.
 *
 * Blur only actually softens the image on API 31+ ([Modifier.blur] is
 * backed by `RenderEffect`, which doesn't exist below that); on API 29-30
 * the image still shows behind the app, just sharp instead of soft.
 *
 * @param dim Scrim strength, 0-1.
 * @param videoEnabled When true and [style] is [BackgroundStyle.CUSTOM], plays [videoPath] as a
 *   looping video background instead of drawing [customImagePath] as a static image. Falls
 *   back to the static image if no video has been picked yet.
 * @param videoPath Path to a person-picked video file (Settings > Appearance > "Latar Belakang Video").
 * @param soundEnabled When true, the video plays its original audio (requesting transient audio
 *   focus for as long as it's audible) instead of the default muted loop. Off by default — see
 *   [com.siroha.flashtool.core.ThemePreferences.backgroundVideoSoundEnabled].
 */
@Composable
fun AppBackground(
    style: BackgroundStyle,
    customImagePath: String?,
    blurRadius: Dp,
    dim: Float = 0.28f,
    videoEnabled: Boolean = false,
    videoPath: String? = null,
    soundEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (style == BackgroundStyle.NONE) {
        content()
        return
    }

    val context = LocalContext.current

    var bitmap by remember(style, customImagePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(style, customImagePath) {
        bitmap = withContext(Dispatchers.IO) {
            when (style) {
                BackgroundStyle.CUSTOM -> customImagePath?.let { WallpaperUtils.readCustomImage(it) }
                BackgroundStyle.NONE -> null
            }
        }
    }

    val showVideo = style == BackgroundStyle.CUSTOM && videoEnabled && videoPath != null

    Box(modifier = modifier.fillMaxSize()) {
        if (showVideo) {
            // VideoView (not ExoPlayer) deliberately — avoids pulling in a
            // new Gradle dependency for what's just a looping background
            // clip. Blur isn't applied here the way it is to the static
            // image: Modifier.blur only affects Compose drawing, not the
            // separate Android View surface VideoView renders into, and
            // re-blurring a live video frame-by-frame would need a much
            // heavier renderer than this feature needs.
            val lifecycleOwner = LocalLifecycleOwner.current
            val currentSoundEnabled by rememberUpdatedState(soundEnabled)
// Menggunakan VideoView standar yang dibungkus FrameLayout
val videoView = remember { VideoView(context) }
val videoContainer = remember {
    android.widget.FrameLayout(context).apply {
        addView(videoView, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.Gravity.CENTER
        ))
    }
}
val layoutListenerRef = remember { mutableStateOf<android.view.View.OnLayoutChangeListener?>(null) }
            // VideoView itself has no volume control — only the underlying
            // MediaPlayer does, and it's only available once playback has
            // prepared (see setOnPreparedListener below).
            var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
            // Whether audio focus is currently held, so it's requested at
            // most once while playing (repeated requests are wasteful and
            // can themselves cause brief audible glitches) and always
            // abandoned on the way out.
            var hasAudioFocus by remember { mutableStateOf(false) }
            val audioManager = remember { context.getSystemService(AudioManager::class.java) }
            val focusRequest = remember {
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    // Duck (lower volume) rather than fully stop other apps'
                    // audio — this is decorative background sound, not the
                    // thing the person opened the app to listen to, so it
                    // shouldn't fight a call or another app for the floor.
                    .setOnAudioFocusChangeListener { change ->
                        when (change) {
                            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                                runCatching { mediaPlayer?.setVolume(0f, 0f) }
                            AudioManager.AUDIOFOCUS_GAIN ->
                                if (currentSoundEnabled) runCatching { mediaPlayer?.setVolume(1f, 1f) }
                        }
                    }
                    .build()
            }

            fun applySound(enabled: Boolean) {
                if (enabled) {
                    if (!hasAudioFocus && audioManager != null) {
                        hasAudioFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    }
                    runCatching { mediaPlayer?.setVolume(if (hasAudioFocus) 1f else 0f, if (hasAudioFocus) 1f else 0f) }
                } else {
                    runCatching { mediaPlayer?.setVolume(0f, 0f) }
                    if (hasAudioFocus) {
                        audioManager?.abandonAudioFocusRequest(focusRequest)
                        hasAudioFocus = false
                    }
                }
            }

            // Mute + pause while the app is backgrounded (VideoView has no
            // built-in lifecycle awareness) so a video that was set to play
            // with sound doesn't keep making noise, or draining battery,
            // once the person has switched away — then resume on return.
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            runCatching { videoView.pause() }
                            if (hasAudioFocus) {
                                audioManager?.abandonAudioFocusRequest(focusRequest)
                                hasAudioFocus = false
                            }
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            runCatching { videoView.start() }
                            applySound(currentSoundEnabled)
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    runCatching { videoView.stopPlayback() }
                    mediaPlayer = null
                    if (hasAudioFocus) {
                        audioManager?.abandonAudioFocusRequest(focusRequest)
                        hasAudioFocus = false
                    }
                }
            }

            LaunchedEffect(soundEnabled) { applySound(soundEnabled) }

AndroidView(
    modifier = Modifier.fillMaxSize(),
    factory = {
        videoView.apply {
            setVideoURI(Uri.parse(videoPath))
            setOnErrorListener { _, _, _ -> true }
            setOnPreparedListener { player ->
                mediaPlayer = player
                player.isLooping = true
                applySound(currentSoundEnabled)

                // 1. Ambil dimensi video DI LUAR fungsi adjustCrop (saat player sedang aman)
                val vWidth = player.videoWidth.toFloat()
                val vHeight = player.videoHeight.toFloat()

                val adjustCrop = {
                    val cWidth = videoContainer.width.toFloat()
                    val cHeight = videoContainer.height.toFloat()

                    if (vWidth > 0 && vHeight > 0 && cWidth > 0 && cHeight > 0) {
                        val videoRatio = vWidth / vHeight
                        val containerRatio = cWidth / cHeight

                        val lp = layoutParams as android.widget.FrameLayout.LayoutParams
                        if (videoRatio > containerRatio) {
                            lp.width = (cHeight * videoRatio).toInt()
                            lp.height = cHeight.toInt()
                        } else {
                            lp.width = cWidth.toInt()
                            lp.height = (cWidth / videoRatio).toInt()
                        }
                        layoutParams = lp
                    }
                }

                adjustCrop()

                layoutListenerRef.value?.let { videoContainer.removeOnLayoutChangeListener(it) }
                val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> adjustCrop() }
                videoContainer.addOnLayoutChangeListener(listener)
                layoutListenerRef.value = listener

                player.start()
            }
        }
        videoContainer 
    },
    update = { container ->
        if (videoView.tag != videoPath) {
            videoView.tag = videoPath
            videoView.setOnErrorListener { _, _, _ -> true }
            videoView.setVideoURI(Uri.parse(videoPath))
            videoView.setOnPreparedListener { player ->
                mediaPlayer = player
                player.isLooping = true
                applySound(currentSoundEnabled)

                // 2. Lakukan hal yang sama di blok update
                val vWidth = player.videoWidth.toFloat()
                val vHeight = player.videoHeight.toFloat()

                val adjustCrop = {
                    val cWidth = container.width.toFloat()
                    val cHeight = container.height.toFloat()

                    if (vWidth > 0 && vHeight > 0 && cWidth > 0 && cHeight > 0) {
                        val videoRatio = vWidth / vHeight
                        val containerRatio = cWidth / cHeight

                        val lp = videoView.layoutParams as android.widget.FrameLayout.LayoutParams
                        if (videoRatio > containerRatio) {
                            lp.width = (cHeight * videoRatio).toInt()
                            lp.height = cHeight.toInt()
                        } else {
                            lp.width = cWidth.toInt()
                            lp.height = (cWidth / videoRatio).toInt()
                        }
                        videoView.layoutParams = lp
                    }
                }

                adjustCrop()

                layoutListenerRef.value?.let { container.removeOnLayoutChangeListener(it) }
                val listener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> adjustCrop() }
                container.addOnLayoutChangeListener(listener)
                layoutListenerRef.value = listener

                player.start()
            }
        }
    }
)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
            )
        } else {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(blurRadius)
                )
                // Flat dark scrim, independent of light/dark theme — keeps
                // foreground text readable over any photo without needing to
                // sample the image's brightness.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
                )
            }
        }
        content()
    }
}
