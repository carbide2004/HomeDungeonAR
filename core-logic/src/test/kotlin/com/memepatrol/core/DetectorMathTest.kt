package com.memepatrol.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorMathTest {

    @Test
    fun testDirectLookAtTarget() {
        val camPos = Vector3(0f, 0f, 0f)
        val camFwd = Vector3(0f, 0f, -1f)
        val target = Vector3(0f, 0f, -1f) // 1m directly ahead

        val (intensity, r, cosTheta) = DetectorMath.calculateIntensity(camPos, camFwd, target, k = 2.0f)

        assertEquals(1.0f, r, 1e-4f)
        assertEquals(1.0f, cosTheta, 1e-4f)
        // I = 1.0^2 * (1 / (1 + 1^2)) = 0.5
        assertEquals(0.5f, intensity, 1e-4f)
    }

    @Test
    fun testFacingAwayFromTarget() {
        val camPos = Vector3(0f, 0f, 0f)
        val camFwd = Vector3(0f, 0f, 1f) // looking backwards
        val target = Vector3(0f, 0f, -2f) // target is behind

        val (intensity, _, cosTheta) = DetectorMath.calculateIntensity(camPos, camFwd, target, k = 2.0f)

        assertEquals(0.0f, cosTheta, 1e-4f)
        assertEquals(0.0f, intensity, 1e-4f)
    }

    @Test
    fun testDistanceAttenuation() {
        val camPos = Vector3(0f, 0f, 0f)
        val camFwd = Vector3(0f, 0f, -1f)

        val targetNear = Vector3(0f, 0f, -0.5f)
        val targetFar = Vector3(0f, 0f, -3.0f)

        val (iNear, _, _) = DetectorMath.calculateIntensity(camPos, camFwd, targetNear, k = 2.0f)
        val (iFar, _, _) = DetectorMath.calculateIntensity(camPos, camFwd, targetFar, k = 2.0f)

        assertTrue(iNear > iFar)
    }

    @Test
    fun testDirectionalFocusK() {
        val camPos = Vector3(0f, 0f, 0f)
        val camFwd = Vector3(0f, 0f, -1f)
        val targetOffAngle = Vector3(1f, 0f, -1f) // 45 degrees to the right

        val (iK1, _, _) = DetectorMath.calculateIntensity(camPos, camFwd, targetOffAngle, k = 1.0f)
        val (iK4, _, _) = DetectorMath.calculateIntensity(camPos, camFwd, targetOffAngle, k = 4.0f)

        // k=4 should sharply suppress off-center targets
        assertTrue(iK4 < iK1)
    }
}
