package com.homedungeon.ar.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * 探测震动驱动器：根据探测强度 I ∈ [0, 1] 动态调制物理震动节奏与振幅
 */
class DetectorHapticDriver(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var lastPulseTime = 0L

    /**
     * 在每一帧渲染时更新当前物理探测强度
     * @param intensity [0.0, 1.0]
     */
    fun update(intensity: Float) {
        if (!vibrator.hasVibrator() || intensity < 0.03f) {
            return
        }

        val now = SystemClock.uptimeMillis()

        // 脉冲周期映射：从微弱时的 650ms 减小至高频时的 90ms
        val pulseInterval = (650 - (intensity * 560)).toLong().coerceIn(90, 650)

        if (now - lastPulseTime >= pulseInterval) {
            lastPulseTime = now

            // 脉冲时长：弱强度短促 (25ms)，高强度饱满 (65ms)
            val duration = (25 + (intensity * 40)).toLong().coerceIn(25, 65)

            // 振幅：动态提升 (40 ~ 255)
            val amplitude = (40 + (intensity * 215)).roundToInt().coerceIn(1, 255)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (vibrator.hasAmplitudeControl()) {
                        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun triggerOneShotTap() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, 180))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35)
            }
        } catch (_: Exception) {
        }
    }
}
