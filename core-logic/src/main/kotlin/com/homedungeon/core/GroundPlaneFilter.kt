package com.homedungeon.core

import kotlin.math.abs

object GroundPlaneFilter {

    /**
     * 判断平面法向量是否严格平行于绝对重力向量 (误差 < 3.5°)
     *
     * @param normalX, normalY, normalZ 平面法向量在世界坐标系中的分量
     */
    fun isStrictlyHorizontal(normalX: Float, normalY: Float, normalZ: Float): Boolean {
        // 重力垂直向上分量 normalY 必须接近 1.0 (正负 0.05 容差对应约 3.5 度倾角)
        return normalY >= 0.998f && abs(normalX) <= 0.06f && abs(normalZ) <= 0.06f
    }

    /**
     * 计算平面外接矩形水平有效面积
     */
    fun calculateExtentArea(extentX: Float, extentZ: Float): Float {
        return (extentX * extentZ).coerceAtLeast(0f)
    }
}
