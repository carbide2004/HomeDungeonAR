package com.homedungeon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GroundPhysicsEngineTest {

    @Test
    fun testGroundProjectionNormalLookingDown() {
        val camPos = Vector3(0f, 1.4f, 0f)
        // 向前 (-Z) 且向下 45 度看
        val ray = Vector3(0f, -1f, -1f).normalized()

        val groundHit = GroundPhysicsEngine.projectReticleToAbsoluteFloor(
            camPos, ray, standingEyeHeight = 1.4f
        )

        assertNotNull(groundHit)
        // 目标地面高度必须严格为 0
        assertEquals(0f, groundHit!!.y, 1e-4f)
        assertEquals(0f, groundHit.x, 1e-4f)
        // 45 度投影距离 Z 应该为 -1.4m
        assertEquals(-1.4f, groundHit.z, 1e-3f)
    }

    @Test
    fun testLookingUpRejected() {
        val camPos = Vector3(0f, 1.4f, 0f)
        val ray = Vector3(0f, 0.2f, -1f).normalized() // 向上看

        val groundHit = GroundPhysicsEngine.projectReticleToAbsoluteFloor(camPos, ray)
        assertNull(groundHit)
    }
}
