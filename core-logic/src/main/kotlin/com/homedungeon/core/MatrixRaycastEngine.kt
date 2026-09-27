package com.homedungeon.core

import kotlin.math.sqrt

data class RayDistancePoint(
    val screenXNorm: Float, // 屏幕归一化坐标 [0, 1]
    val screenYNorm: Float,
    val hitWorldPos: Vector3?,
    val distanceMeters: Float
)

object MatrixRaycastEngine {

    /**
     * 生成屏幕 M x N 激光测距点阵，并计算每个点到地面的物理距离
     *
     * @param camPos 相机世界坐标
     * @param camForward 相机前向视线
     * @param camRight 屏幕右向向量
     * @param camUp 屏幕上向向量
     * @param floorY 地面世界高度
     * @param fovYRad 垂直视场角 (一般约 60度 = 1.047 rad)
     * @param aspect 屏幕宽高比 (例如 1080 / 2400)
     */
    fun computeGridRaycasts(
        camPos: Vector3,
        camForward: Vector3,
        camRight: Vector3,
        camUp: Vector3,
        floorY: Float,
        gridRows: Int = 5,
        gridCols: Int = 3,
        fovYRad: Float = 1.05f,
        aspect: Float = 0.45f
    ): List<RayDistancePoint> {
        val results = ArrayList<RayDistancePoint>()
        val tanHalfFovY = kotlin.math.tan(fovYRad * 0.5f)
        val tanHalfFovX = tanHalfFovY * aspect

        for (r in 0 until gridRows) {
            val vNorm = (r + 1).toFloat() / (gridRows + 1).toFloat() // Y 比例 (0~1)
            // NDC Y: 上为 +1, 下为 -1
            val ndcY = 1.0f - 2.0f * vNorm

            for (c in 0 until gridCols) {
                val uNorm = (c + 1).toFloat() / (gridCols + 1).toFloat() // X 比例 (0~1)
                // NDC X: 左为 -1, 右为 +1
                val ndcX = 2.0f * uNorm - 1.0f

                // 构建该像素对应的射线世界向量
                val rayDir = (camForward + (camRight * (ndcX * tanHalfFovX)) + (camUp * (ndcY * tanHalfFovY))).normalized()

                // 与地面求交
                val dy = floorY - camPos.y
                if (dy < 0f && rayDir.y < -0.05f) {
                    val t = dy / rayDir.y
                    if (t in 0.2f..10.0f) {
                        val hitPos = camPos + (rayDir * t)
                        results.add(RayDistancePoint(uNorm, vNorm, hitPos, t))
                        continue
                    }
                }

                results.add(RayDistancePoint(uNorm, vNorm, null, -1f))
            }
        }
        return results
    }
}
