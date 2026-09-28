package com.memepatrol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StandardUnprojectorTest {

    @Test
    fun testPlaneIntersectionSimpleMath() {
        val ray = RaycastResult(
            origin = Vector3(0f, 1.0f, 0f), // 相机离地 1 米
            direction = Vector3(0f, -1f, 0f) // 正直向下射出
        )
        val floorPoint = Vector3(0f, 0f, 0f) // 地面 Y = 0

        val hit = StandardUnprojector.intersectPlane(ray, floorPoint)
        assertNotNull(hit)
        assertEquals(1.0f, hit!!.second, 1e-4f) // 距离严格为 1.0 米
        assertEquals(0f, hit.first.y, 1e-4f)
    }

    @Test
    fun test45DegreeDownwardRay() {
        val ray = RaycastResult(
            origin = Vector3(0f, 0.40f, 0f), // 相机离地刚好 40cm
            direction = Vector3(0f, -0.7071f, -0.7071f) // 45度角下视
        )
        val floorPoint = Vector3(0f, 0f, 0f)

        val hit = StandardUnprojector.intersectPlane(ray, floorPoint)
        assertNotNull(hit)
        // t = 0.40 / 0.7071 ≈ 0.565m
        assertEquals(0.5657f, hit!!.second, 1e-3f)
        assertEquals(0f, hit.first.y, 1e-4f)
    }

    @Test
    fun testHorizontalParallelRayRejected() {
        val ray = RaycastResult(
            origin = Vector3(0f, 1.0f, 0f),
            direction = Vector3(0f, 0f, -1f) // 水平直视前方，不与水平地面相交
        )
        val floorPoint = Vector3(0f, 0f, 0f)

        val hit = StandardUnprojector.intersectPlane(ray, floorPoint)
        assertNull(hit)
    }
}
