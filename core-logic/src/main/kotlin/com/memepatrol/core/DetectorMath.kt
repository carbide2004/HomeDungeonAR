package com.memepatrol.core

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class Vector3(val x: Float, val y: Float, val z: Float) {
    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vector3 {
        val len = length()
        return if (len > 1e-6f) Vector3(x / len, y / len, z / len) else Vector3(0f, 0f, 0f)
    }

    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)
}

object DetectorMath {

    /**
     * 计算探测震动强度 I
     *
     * @param cameraPosition 相机在世界坐标系中的位置
     * @param cameraForward  相机在世界坐标系中的前向视线单位向量 (通常为相机旋转变换后的 -Z)
     * @param targetPosition 异常锚点在世界坐标系中的位置
     * @param k              指向性收束指数 (k 越大，越要求对准目标)
     * @return 强度 [0.0, 1.0] 浮点值，以及中间物理量 (距离 r, 余弦对齐度 dot)
     */
    fun calculateIntensity(
        cameraPosition: Vector3,
        cameraForward: Vector3,
        targetPosition: Vector3,
        k: Float = 2.0f
    ): Triple<Float, Float, Float> {
        val diff = targetPosition - cameraPosition
        val r = diff.length()
        if (r < 1e-4f) {
            return Triple(1.0f, 0.0f, 1.0f)
        }

        val dHat = diff.normalized()
        val fHat = cameraForward.normalized()

        val cosTheta = max(0f, fHat.dot(dHat))
        val directionalComponent = cosTheta.pow(k)
        val distanceComponent = 1.0f / (1.0f + r * r)

        val intensity = (directionalComponent * distanceComponent).coerceIn(0f, 1f)
        return Triple(intensity, r, cosTheta)
    }
}
