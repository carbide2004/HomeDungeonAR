package com.homedungeon.core

import kotlin.math.sqrt

object GroundPhysicsEngine {

    /**
     * 严谨物理反投影：
     * 地球重力方向严格垂直向下 (0, -1, 0)。
     * 地平面的方程为：Y = floorY (常数)。
     *
     * @param cameraWorldPos 相机世界坐标 (X, Y, Z)
     * @param cameraForwardRay 相机正前方向量 (单位向量)
     * @param floorY 物理地面的绝对高度 (例如 0f，或者锁定的 groundY)
     */
    fun projectReticleToFloorY(
        cameraWorldPos: Vector3,
        cameraForwardRay: Vector3,
        floorY: Float
    ): Vector3? {
        // 如果相机已经位于地面以下，或者视线水平/向上仰望 (ray.y >= -0.05)，则射线永远不会与下方地面相交
        val dy = floorY - cameraWorldPos.y
        if (dy >= 0f || cameraForwardRay.y >= -0.05f) {
            return null
        }

        // 射线求交: cameraWorldPos.y + t * ray.y = floorY
        val t = dy / cameraForwardRay.y

        // 合理距离限制 (0.3m 到 8.0m)
        if (t < 0.3f || t > 8.0f) {
            return null
        }

        val groundX = cameraWorldPos.x + t * cameraForwardRay.x
        val groundZ = cameraWorldPos.z + t * cameraForwardRay.z

        return Vector3(groundX, floorY, groundZ)
    }
}
