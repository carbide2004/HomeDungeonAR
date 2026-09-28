package com.memepatrol.core

import kotlin.math.abs
import kotlin.math.sqrt

data class RaycastResult(
    val origin: Vector3,
    val direction: Vector3
)

object StandardUnprojector {

    /**
     * 利用 (P * V)^(-1) 将屏幕 NDC 坐标准确反投影到世界空间，彻底消除任何旋转/FOV/屏幕宽高比偏置
     *
     * @param u 屏幕 NDC X [-1, 1]
     * @param v 屏幕 NDC Y [-1, 1]
     * @param invProjViewMatrix 已经过求逆的 (P * V)^(-1) 4x4 矩阵 (OpenGL 列主序数组)
     */
    fun unprojectNdCToWorldRay(
        u: Float,
        v: Float,
        invProjViewMatrix: FloatArray
    ): RaycastResult {
        // 近平面点 qNear = (u, v, -1, 1)
        val nearX = invProjViewMatrix[0] * u + invProjViewMatrix[4] * v + invProjViewMatrix[8] * -1f + invProjViewMatrix[12] * 1f
        val nearY = invProjViewMatrix[1] * u + invProjViewMatrix[5] * v + invProjViewMatrix[9] * -1f + invProjViewMatrix[13] * 1f
        val nearZ = invProjViewMatrix[2] * u + invProjViewMatrix[6] * v + invProjViewMatrix[10] * -1f + invProjViewMatrix[14] * 1f
        val nearW = invProjViewMatrix[3] * u + invProjViewMatrix[7] * v + invProjViewMatrix[11] * -1f + invProjViewMatrix[15] * 1f

        // 远平面点 qFar = (u, v, 1, 1)
        val farX = invProjViewMatrix[0] * u + invProjViewMatrix[4] * v + invProjViewMatrix[8] * 1f + invProjViewMatrix[12] * 1f
        val farY = invProjViewMatrix[1] * u + invProjViewMatrix[5] * v + invProjViewMatrix[9] * 1f + invProjViewMatrix[13] * 1f
        val farZ = invProjViewMatrix[2] * u + invProjViewMatrix[6] * v + invProjViewMatrix[10] * 1f + invProjViewMatrix[14] * 1f
        val farW = invProjViewMatrix[3] * u + invProjViewMatrix[7] * v + invProjViewMatrix[11] * 1f + invProjViewMatrix[15] * 1f

        val pNear = Vector3(nearX / nearW, nearY / nearW, nearZ / nearW)
        val pFar = Vector3(farX / farW, farY / farW, farZ / farW)

        val dir = (pFar - pNear).normalized()
        return RaycastResult(origin = pNear, direction = dir)
    }

    /**
     * 严谨点法式平面求交
     * 
     * @param ray 空间射线 (起点 C, 单位方向 v)
     * @param planePoint 平面上已知一点 (例如 floorAnchor 在当帧的实时世界坐标)
     * @param planeNormal 平面法向量 (例如水平地面为 0, 1, 0)
     */
    fun intersectPlane(
        ray: RaycastResult,
        planePoint: Vector3,
        planeNormal: Vector3 = Vector3(0f, 1f, 0f)
    ): Pair<Vector3, Float>? {
        val denom = planeNormal.dot(ray.direction)
        // 避免平行 (夹角接近 90 度时 denom 接近 0)
        if (abs(denom) < 0.03f) {
            return null
        }

        val t = planeNormal.dot(planePoint - ray.origin) / denom
        // 仅当距离 > 0 且在合理室内探测范围内 (0.1m ~ 12m)
        if (t <= 0.05f || t > 12.0f) {
            return null
        }

        val hitPos = ray.origin + (ray.direction * t)
        return Pair(hitPos, t)
    }
}
