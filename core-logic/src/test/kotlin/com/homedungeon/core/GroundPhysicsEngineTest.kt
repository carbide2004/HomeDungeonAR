package com.homedungeon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroundPhysicsEngineTest {

    @Test
    fun testGroundIntersection() {
        val camPos = Vector3(0f, 1.4f, 0f) // 相机在离地 1.4m 处
        val camRay = Vector3(0f, -1f, -1f).normalized() // 向前下倾斜 45 度

        val hit = GroundPhysicsEngine.projectReticleToFloorY(camPos, camRay, floorY = 0f)
        assertNotNull(hit)
        assertEquals(0f, hit!!.y, 1e-4f)
        assertEquals(0f, hit.x, 1e-4f)
        assertEquals(-1.4f, hit.z, 1e-2f)
    }

    @Test
    fun testLookingUpRejected() {
        val camPos = Vector3(0f, 1.4f, 0f)
        val camRay = Vector3(0f, 0.2f, -1f).normalized() // 向上仰望

        val hit = GroundPhysicsEngine.projectReticleToFloorY(camPos, camRay, floorY = 0f)
        assertNull(hit)
    }
}
