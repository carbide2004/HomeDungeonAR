package com.homedungeon.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundPlaneFilterTest {

    @Test
    fun testStrictlyHorizontalFloor() {
        // 完美垂直向上的法向量
        assertTrue(GroundPlaneFilter.isStrictlyHorizontal(0f, 1.0f, 0f))
        // 极微小合理传感器噪点 (倾斜约 1.5°)
        assertTrue(GroundPlaneFilter.isStrictlyHorizontal(0.02f, 0.999f, -0.02f))
    }

    @Test
    fun testRejectsTiltedSlantedPlanes() {
        // 倾斜约 10° 的歪斜面
        assertFalse(GroundPlaneFilter.isStrictlyHorizontal(0.18f, 0.97f, 0f))
        // 垂直墙面
        assertFalse(GroundPlaneFilter.isStrictlyHorizontal(1.0f, 0f, 0f))
    }
}
