package com.memepatrol.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object WallPoseDerivation {

    data class WallSurfacePose(
        val position: Vector3,    // 墙纸贴图中心三维坐标 (世界系)
        val normal: Vector3,      // 垂直墙面朝向玩家的法向量 (世界系)
        val yawAngleDegrees: Float // 绕世界 Y 轴旋转的偏航角
    )

    /**
     * 基于地面求交点与相机朝向，直接解析出完美的垂直墙面位姿
     *
     * @param floorY          已识别地面的世界 Y 坐标
     * @param camPos          当前相机世界坐标
     * @param camForwardRay   视线射线的世界单位方向向量
     * @param targetHeightAboveFloor 贴图中心离地高度 (默认 1.15m，接近人眼/齐胸高度)
     */
    fun deriveFromFloorIntersection(
        floorY: Float,
        camPos: Vector3,
        camForwardRay: Vector3,
        targetHeightAboveFloor: Float = 0.70f
    ): WallSurfacePose? {
        // 如果视线平行于地面或向上看 (ray.y >= 0)，无法与下方地面相交
        if (camForwardRay.y >= -0.05f) {
            return null
        }

        // 射线-平面求交: camPos.y + t * ray.y = floorY
        val t = (floorY - camPos.y) / camForwardRay.y
        if (t <= 0.2f || t > 15.0f) {
            // 距离过近或过远过滤
            return null
        }

        // 墙根交点 (地面接触点)
        val rootX = camPos.x + t * camForwardRay.x
        val rootZ = camPos.z + t * camForwardRay.z

        // 墙面法向量: 取视线在水平面投影的反方向 (从墙体指向房间内侧)
        val hLen = sqrt(camForwardRay.x * camForwardRay.x + camForwardRay.z * camForwardRay.z)
        if (hLen < 1e-4f) return null

        val normalX = -camForwardRay.x / hLen
        val normalZ = -camForwardRay.z / hLen
        val normal = Vector3(normalX, 0f, normalZ)

        // 墙贴图中心位置: 墙根正上方 targetHeightAboveFloor (默认降为 0.70m，贴图中心齐胸，底部贴近地面)
        val centerPos = Vector3(rootX, floorY + targetHeightAboveFloor, rootZ)

        // 偏航角 Yaw: 绕世界 Y 轴旋转。由于之前反了 180°，取 -camForwardRay 即可将贴图正面正对房间内
        val yawRad = atan2(-camForwardRay.x, -camForwardRay.z)
        val yawDeg = (yawRad * (180f / Math.PI.toFloat()))

        return WallSurfacePose(centerPos, normal, yawDeg)
    }
}
