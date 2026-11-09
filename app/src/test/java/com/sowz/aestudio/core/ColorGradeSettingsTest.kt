package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ColorGradeSettingsTest {
    @Test fun defaultsProduceNoColorChange() {
        assertEquals(ColorGradeSettings(), ColorGradeSettings(0f, 0f, 0f))
    }

    @Test fun acceptsBoundaryValues() {
        assertEquals(
            ColorGradeSettings(180f, 100f, -100f),
            ColorGradeSettings(180f, 100f, -100f)
        )
    }

    @Test fun rejectsHueOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorGradeSettings(181f, 0f, 0f)
        }
    }

    @Test fun rejectsSaturationOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorGradeSettings(0f, -101f, 0f)
        }
    }

    @Test fun rejectsLightnessOutsideRange() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorGradeSettings(0f, 0f, 101f)
        }
    }

    @Test fun rejectsNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ColorGradeSettings(Float.NaN, 0f, 0f)
        }
    }
}
