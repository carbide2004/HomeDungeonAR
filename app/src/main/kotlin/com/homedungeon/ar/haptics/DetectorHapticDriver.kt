package com.homedungeon.ar.haptics

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * 探测震动驱动器：根据探测强度 I ∈ [0, 1] 动态调制物理震动节奏与振幅
 */
class DetectorHapticDriver(context: Context) {

    companion object {
        private const val TAG = "DetectorHapticDriver"
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // 关键：必须指定 USAGE_MEDIA / USAGE_GAME，避免因系统设置关闭了“触感反馈 (haptic_feedback_enabled=0)”而静默吞掉震动
    private val vibrationAttributes: VibrationAttributes? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_MEDIA)
            .build()
    } else null

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setUsage(AudioAttributes.USAGE_GAME)
        .build()

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

            // 脉冲时长：弱强度短促 (35ms)，高强度饱满 (80ms)
            val duration = (35 + (intensity * 45)).toLong().coerceIn(35, 80)
            val amplitude = (50 + (intensity * 205)).roundToInt().coerceIn(1, 255)

            executeVibrate(duration, amplitude)
        }
    }

    fun triggerOneShotTap() {
        executeVibrate(duration = 50, amplitude = 220)
    }

    fun triggerTestVibration(): String {
        return try {
            if (!vibrator.hasVibrator()) {
                "硬件不支持震动"
            } else {
                executeVibrate(duration = 150, amplitude = 255)
                "已触发 150ms 强震动"
            }
        } catch (e: Exception) {
            "震动失败: ${e.message}"
        }
    }

    private fun executeVibrate(duration: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(duration, amplitude)
                } else {
                    VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && vibrationAttributes != null) {
                    vibrator.vibrate(effect, vibrationAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, audioAttributes)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration, audioAttributes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrate invocation failed: ${e.message}")
        }
    }
}
