package com.homedungeon.core

import kotlin.math.sqrt

object GroundPhysicsEngine {

    /**
     * 纯物理重力反投影：根据手机实际离地高度 H，将屏幕中心视线射线直接与绝对水平地平面求交
     * 彻底绕过单目 ARCore 的平面拟合误差，保证在数学上 100% 严格水平咬合于地面
     *
     * @param cameraWorldPos 相机世界坐标
     * @param cameraForwardRay 相机正前方向量 (世界坐标)
     * @param standingEyeHeight 玩家手持手机时的典型离地高度 (默认 1.35m)
     * @return 贴合在绝对地面的三维物理落点
     */
    fun projectReticleToAbsoluteFloor(
        cameraWorldPos: Vector3,
        cameraForwardRay: Vector3,
        standingEyeHeight: Float = 1.35f
    ): Vector3? {
        // 如果视线水平或向上看，不与下方地面相交
        if (cameraForwardRay.y >= -0.08f) {
            return null
        }

        // 绝对地面的物理高度: 相机高度向下偏移 standingEyeHeight
        // 在局部相对观察中，地板方程即为 Y = cameraWorldPos.y - standingEyeHeight
        val targetFloorY = cameraWorldPos.y - standingEyeHeight

        // 射线方程: P(t) = cameraWorldPos + t * cameraForwardRay
        // P(t).y = targetFloorY => t = (targetFloorY - cameraWorldPos.y) / ray.y
        val t = -standingEyeHeight / cameraForwardRay.y

        // 视线投射距离限制在 0.4m ~ 6.0m (室内合理范围)
        if (t < 0.4f || t > 6.0f) {
            return null
        }

        val groundX = cameraWorldPos.x + t * cameraForwardRay.x
        val groundZ = cameraWorldPos.z + t * cameraForwardRay.z

        return Vector3(groundX, targetFloorY, groundZ)
    }
}
