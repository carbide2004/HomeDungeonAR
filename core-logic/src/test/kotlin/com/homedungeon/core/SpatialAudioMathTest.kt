package com.homedungeon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialAudioMathTest {

    private val origin = Vector3(0f, 0f, 0f)
    private val forward = Vector3(0f, 0f, -1f) // looking into -Z
    private val right = Vector3(1f, 0f, 0f)    // +X is right

    @Test
    fun testSoundDirectlyInFront() {
        val source = Vector3(0f, 0f, -1f) // 1m directly ahead
        val res = SpatialAudioMath.calculateSpatialGain(origin, forward, right, source)

        assertEquals(1.0f, res.distance, 1e-4f)
        assertEquals(0.0f, res.azimuthDegrees, 1e-2f)
        // Balanced stereo in front
        assertEquals(res.leftVolume, res.rightVolume, 1e-3f)
    }

    @Test
    fun testSoundDirectlyToTheRight() {
        val source = Vector3(2f, 0f, 0f) // 2m to the right
        val res = SpatialAudioMath.calculateSpatialGain(origin, forward, right, source)

        assertEquals(2.0f, res.distance, 1e-4f)
        assertEquals(90.0f, res.azimuthDegrees, 1e-2f)
        // Right ear should be distinctly louder than left ear
        assertTrue(res.rightVolume > res.leftVolume)
        assertEquals(0.0f, res.leftVolume, 1e-3f)
    }

    @Test
    fun testSoundDirectlyToTheLeft() {
        val source = Vector3(-2f, 0f, 0f) // 2m to the left
        val res = SpatialAudioMath.calculateSpatialGain(origin, forward, right, source)

        assertEquals(2.0f, res.distance, 1e-4f)
        assertEquals(-90.0f, res.azimuthDegrees, 1e-2f)
        // Left ear should be distinctly louder than right ear
        assertTrue(res.leftVolume > res.rightVolume)
        assertEquals(0.0f, res.rightVolume, 1e-3f)
    }

    @Test
    fun testSoundBehindListener() {
        val frontSource = Vector3(0f, 0f, -2f)
        val rearSource = Vector3(0f, 0f, 2f) // behind listener

        val frontRes = SpatialAudioMath.calculateSpatialGain(origin, forward, right, frontSource)
        val rearRes = SpatialAudioMath.calculateSpatialGain(origin, forward, right, rearSource)

        assertEquals(frontRes.distance, rearRes.distance, 1e-4f)
        // Rear sound should be attenuated by rear head-shadow factor
        assertTrue(rearRes.leftVolume < frontRes.leftVolume)
        assertTrue(rearRes.rightVolume < frontRes.rightVolume)
    }
}
