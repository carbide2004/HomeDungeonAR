package com.memepatrol.ar.haptics

import android.content.Context
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 探测触觉驱动器：利用系统级 View.performHapticFeedback 触觉管线
 * 彻底避开国内厂商对 Vibrator.vibrate 的 VendorEffect 权限壁垒
 */
class DetectorHapticDriver(private val context: Context) {

    private var lastPulseTime = 0L

    /**
     * 在每一帧渲染时更新当前物理探测强度
     * @param intensity [0.0, 1.0]
     * @param view 用于触发触觉反馈的宿主视图 (如 activity.window.decorView 或 binding.root)
     */
    fun update(intensity: Float, view: View?) {
        if (view == null || intensity < 0.04f) {
            return
        }

        val now = SystemClock.uptimeMillis()
        // 脉冲周期：从 650ms 逐渐缩短到 120ms
        val pulseInterval = (650 - (intensity * 530)).toLong().coerceIn(120, 650)

        if (now - lastPulseTime >= pulseInterval) {
            lastPulseTime = now

            val feedbackConstant = when {
                intensity > 0.65f -> HapticFeedbackConstants.REJECT // 连续强烈顿挫/警报
                intensity > 0.35f -> HapticFeedbackConstants.CONFIRM // 明显实感确认震动
                else -> HapticFeedbackConstants.LONG_PRESS // 长按扎实触感
            }

            performHaptic(view, feedbackConstant)
        }
    }

    fun triggerOneShotTap(view: View?) {
        view?.let { performHaptic(it, HapticFeedbackConstants.CONFIRM) }
    }

    fun triggerTestVibration(view: View?): String {
        return if (view == null) {
            "View 为空"
        } else {
            val success = performHaptic(view, HapticFeedbackConstants.CONFIRM)
            "触感触发结果: $success"
        }
    }

    private fun performHaptic(view: View, constant: Int): Boolean {
        return view.performHapticFeedback(
            constant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }
}
