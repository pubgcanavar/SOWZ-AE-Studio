package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraShakeSettingsTest {
    @Test
    fun sampleIsDeterministicAtTheSameTimestamp() {
        val settings = CameraShakeSettings(12f, 9f, 2.5f, 42)
        assertEquals(settings.sampleAt(1200L), settings.sampleAt(1200L))
    }

    @Test
    fun sampleStaysWithinConfiguredLimits() {
        val settings = CameraShakeSettings(16f, 12f, 4f, 7)
        repeat(200) { frame ->
            val sample = settings.sampleAt(frame * 40L)
            assertTrue(kotlin.math.abs(sample.xPixels) <= 16.001f)
            assertTrue(kotlin.math.abs(sample.yPixels) <= 16.001f)
            assertTrue(kotlin.math.abs(sample.rotationDegrees) <= 4.001f)
        }
    }
}
