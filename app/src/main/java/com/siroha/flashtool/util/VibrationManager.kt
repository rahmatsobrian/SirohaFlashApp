package com.siroha.flashtool.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Short haptic "tick" on tap, for Settings > Appearance > "Vibration".
 * Stateless: callers pass in the current
 * [ThemePreferences][com.siroha.flashtool.core.ThemePreferences] values
 * (already collected via `collectAsState`) instead of this object keeping
 * its own duplicate copy of the setting.
 *
 * Both amplitude AND duration scale with [intensity]. Amplitude alone
 * ([VibrationEffect.createOneShot]'s second argument) is silently ignored
 * on a lot of hardware — notably many MediaTek-chipset phones, which this
 * app's target devices include — where the motor driver only supports a
 * fixed default strength regardless of what amplitude value is requested.
 * Scaling the pulse's duration too means low vs. high intensity stays
 * perceptibly different even on that hardware, instead of every value
 * feeling identical.
 */
object VibrationManager {

    fun vibrate(context: Context, enabled: Boolean, intensity: Float) {
        if (!enabled) return

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (vibrator.hasVibrator()) {
                val clamped = intensity.coerceIn(0f, 1f)
                // Map 0.0-1.0 to 1-255 for hardware that does honor amplitude.
                val amplitude = (clamped * 255).toInt().coerceIn(1, 255)
                // 12ms-45ms — scales with intensity so the *duration* also
                // carries the difference, not just amplitude (see class doc).
                val duration = (12 + clamped * 33).toLong()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val hasAmplitudeControl = vibrator.hasAmplitudeControl()
                    try {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(
                                duration,
                                if (hasAmplitudeControl) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
                            )
                        )
                    } catch (e: Exception) {
                        // Fallback if amplitude control isn't supported on this device
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(duration)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            // Never crash the app over a missing/denied vibrator
            Log.e("VibrationManager", "Failed to vibrate", e)
        }
    }
}
