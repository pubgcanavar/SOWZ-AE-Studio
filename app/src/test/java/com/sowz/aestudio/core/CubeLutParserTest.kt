package com.sowz.aestudio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutParserTest {
    @Test fun parsesIdentityCubeWithBlueChangingFastest() {
        val source = """
            TITLE "Identity 2"
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            1 1 1
        """.trimIndent().toByteArray()
        val lut = CubeLutParser.parse("identity.cube", source)
        assertEquals("Identity 2", lut.title)
        assertEquals(2, lut.size)
        assertEquals(8, lut.entryCount)
        assertEquals(0xFF0000FF.toInt(), lut.cube[0][0][1])
        assertEquals(0xFFFF00FF.toInt(), lut.cube[1][0][1])
        assertEquals(0xFFFFFFFF.toInt(), lut.cube[1][1][1])
    }

    @Test fun clampsOutputColoursToValidSdrRange() {
        val source = """
            LUT_3D_SIZE 2
            -1 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            2 2 2
        """.trimIndent().toByteArray()
        val lut = CubeLutParser.parse("wide.cube", source)
        assertEquals(0xFF000000.toInt(), lut.cube[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), lut.cube[1][1][1])
    }

    @Test fun rejectsTruncatedCube() {
        val source = """
            LUT_3D_SIZE 2
            0 0 0
            0 0 1
            0 1 0
        """.trimIndent().toByteArray()
        val error = try {
            CubeLutParser.parse("broken.cube", source)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(error?.message?.contains("expected 8 RGB rows") == true)
    }

    @Test fun rejectsShaperAndNonstandardDomainInsteadOfMisapplying() {
        val shaper = "LUT_1D_SIZE 16\n".toByteArray()
        val shaperError = try {
            CubeLutParser.parse("shaper.cube", shaper)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(shaperError?.message?.contains("1D shaper") == true)

        val domain = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.1 0 0
            DOMAIN_MAX 1 1 1
            0 0 0
            0 0 1
            0 1 0
            0 1 1
            1 0 0
            1 0 1
            1 1 0
            1 1 1
        """.trimIndent().toByteArray()
        val domainError = try {
            CubeLutParser.parse("domain.cube", domain)
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(domainError?.message?.contains("DOMAIN_MIN 0 0 0") == true)
    }
}
