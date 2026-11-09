package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test

class NativeEffectMapperTest {
    @Test
    fun mapsMultipleStaticEffectsFromFfx() {
        val mapped = NativeEffectMapper.fromFfx(
            listOf(
                FfxEffect("ADBE Brightness & Contrast 2", "Brightness & Contrast", listOf(
                    FfxParameter("Brightness", 25.0),
                    FfxParameter("Contrast", 40.0)
                )),
                FfxEffect("ADBE Gaussian Blur 2", "Gaussian Blur", listOf(
                    FfxParameter("Blurriness", 12.0)
                )),
                FfxEffect("ADBE HUE SATURATION", "Hue/Saturation", listOf(
                    FfxParameter("Master Hue", 20.0),
                    FfxParameter("Master Saturation", 45.0),
                    FfxParameter("Master Lightness", -5.0)
                ))
            )
        )
        assertEquals(0.25f, mapped.brightness!!, 0.0001f)
        assertEquals(0.40f, mapped.contrast!!, 0.0001f)
        assertEquals(12f, mapped.blurSigma!!, 0.0001f)
        assertEquals(20f, mapped.hueDegrees!!, 0.0001f)
        assertEquals(45f, mapped.saturation!!, 0.0001f)
        assertEquals(-5f, mapped.lightness!!, 0.0001f)
        assertEquals(3, mapped.appliedEffects.size)
    }

    @Test
    fun animatedAndUnknownValuesRemainUnapplied() {
        val mapped = NativeEffectMapper.fromFfx(
            listOf(
                FfxEffect("ADBE Gaussian Blur 2", "Gaussian Blur", listOf(
                    FfxParameter("Blurriness", 22.0, isAnimated = true)
                )),
                FfxEffect("com.vendor.unknown", "Vendor Effect", emptyList())
            )
        )
        assertNull(mapped.blurSigma)
        assertTrue(mapped.appliedEffects.isEmpty())
        assertTrue(mapped.skippedEffects.isNotEmpty())
    }
}
