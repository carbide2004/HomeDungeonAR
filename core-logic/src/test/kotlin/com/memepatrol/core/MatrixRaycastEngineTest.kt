package com.memepatrol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixRaycastEngineTest {

    @Test
    fun testGridRaycastComputations() {
        val camPos = Vector3(0f, 1.4f, 0f)
        val camForward = Vector3(0f, -0.707f, -0.707f).normalized() // 向下45度
        val camRight = Vector3(1f, 0f, 0f)
        val camUp = Vector3(0f, 0.707f, -0.707f).normalized()

        val points = MatrixRaycastEngine.computeGridRaycasts(
            camPos = camPos,
            camForward = camForward,
            camRight = camRight,
            camUp = camUp,
            floorY = 0f,
            gridRows = 3,
            gridCols = 3
        )

        assertEquals(9, points.size)
        // 处于画面下半部分的点必然打在更近的地表
        val bottomCenter = points.firstOrNull { it.screenXNorm == 0.5f && it.screenYNorm > 0.6f }
        assertNotNull(bottomCenter)
        assertTrue(bottomCenter!!.distanceMeters > 0.2f)
    }
}
