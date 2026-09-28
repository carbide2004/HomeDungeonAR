package com.memepatrol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WallPoseDerivationTest {

    @Test
    fun testDeriveWallFromLookingDownAtFloor() {
        val floorY = 0f
        val camPos = Vector3(0f, 1.5f, 3.0f) // 相机在离地 1.5m 处
        // 视线向前 (-Z) 并向下 (-Y)
        val camRay = Vector3(0f, -0.5f, -1.0f).normalized()

        val pose = WallPoseDerivation.deriveFromFloorIntersection(
            floorY = floorY,
            camPos = camPos,
            camForwardRay = camRay,
            targetHeightAboveFloor = 1.2f
        )

        assertNotNull(pose)
        assertEquals(1.2f, pose!!.position.y, 1e-3f)
        assertEquals(0f, pose.position.x, 1e-3f)
        // 墙根位于 Z = 0
        assertEquals(0f, pose.position.z, 1e-2f)
        // 法向量指向房间内部 (+Z)
        assertEquals(1.0f, pose.normal.z, 1e-3f)
        assertEquals(0.0f, pose.normal.y, 1e-3f)
    }

    @Test
    fun testLookingUpDoesNotHitFloor() {
        val floorY = 0f
        val camPos = Vector3(0f, 1.5f, 3.0f)
        val camRay = Vector3(0f, 0.5f, -1.0f).normalized() // 向上看

        val pose = WallPoseDerivation.deriveFromFloorIntersection(
            floorY = floorY,
            camPos = camPos,
            camForwardRay = camRay
        )

        assertNull(pose)
    }
}
