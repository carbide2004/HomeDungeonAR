package com.memepatrol.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class SpatialAudioResult(
    val leftVolume: Float,
    val rightVolume: Float,
    val distance: Float,
    val azimuthDegrees: Float
)

object SpatialAudioMath {

    /**
     * 计算单声道声源在物理空间中映射到双耳耳机的立体声音量分配
     *
     * @param listenerPos     听者/相机世界坐标
     * @param listenerForward 听者/相机视线正前向单位向量 (世界坐标)
     * @param listenerRight   听者/相机右手侧正向单位向量 (世界坐标)
     * @param sourcePos       声源世界物理坐标
     * @param alpha           距离衰减系数 (默认 0.8)
     * @return 左右耳输出音量、物理距离与水平方位角 (-180° ~ +180°)
     */
    fun calculateSpatialGain(
        listenerPos: Vector3,
        listenerForward: Vector3,
        listenerRight: Vector3,
        sourcePos: Vector3,
        alpha: Float = 0.8f
    ): SpatialAudioResult {
        val diff = sourcePos - listenerPos
        val distance = diff.length()

        if (distance < 1e-4f) {
            return SpatialAudioResult(
                leftVolume = 1.0f,
                rightVolume = 1.0f,
                distance = 0.0f,
                azimuthDegrees = 0.0f
            )
        }

        // 1. 距离反比例平滑衰减
        val distanceGain = (1.0f / (1.0f + alpha * distance)).coerceIn(0.0f, 1.0f)

        // 2. 将相对位移分解到相机的水平视听平面 (前后分量与左右分量)
        val fHat = listenerForward.normalized()
        val rHat = listenerRight.normalized()

        val frontProj = diff.dot(fHat)
        val rightProj = diff.dot(rHat)

        // 水平方位角: 0° 为正前方, +90° 为正右方, -90° 为正左方, ±180° 为正后方
        val azimuthRad = atan2(rightProj, frontProj)
        val azimuthDeg = (azimuthRad * (180.0f / PI.toFloat()))

        // 3. 等功率立体声平移 (Equal-Power Panning)
        // pan ∈ [0, 1]: 0.0 为极左, 0.5 为正中, 1.0 为极右
        val pan = ((sin(azimuthRad) + 1.0f) * 0.5f).coerceIn(0.0f, 1.0f)
        val panAngle = (pan * (PI.toFloat() * 0.5f))

        val leftGain = cos(panAngle)
        val rightGain = sin(panAngle)

        // 4. 后脑声学遮蔽衰减 (当声源在后脑勺时, 稍微降低高频/总能量模拟脑影)
        val rearFactor = if (frontProj < 0f) 0.78f else 1.0f

        val finalLeft = (distanceGain * leftGain * rearFactor).coerceIn(0.0f, 1.0f)
        val finalRight = (distanceGain * rightGain * rearFactor).coerceIn(0.0f, 1.0f)

        return SpatialAudioResult(
            leftVolume = finalLeft,
            rightVolume = finalRight,
            distance = distance,
            azimuthDegrees = azimuthDeg
        )
    }
}
