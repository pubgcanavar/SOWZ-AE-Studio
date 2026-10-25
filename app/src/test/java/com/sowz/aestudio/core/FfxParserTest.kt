package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FfxParserTest {
    private fun sampleFfx(): ByteArray {
        val bytes = ByteArray(86)
        "RIFX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(78)
        "FaFX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        "head".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        ByteBuffer.wrap(bytes, 16, 4).order(ByteOrder.BIG_ENDIAN).putInt(66)
        ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.BIG_ENDIAN).putInt(97)
        return bytes
    }

    @Test
    fun recognizesExpectedContainer() {
        val bytes = sampleFfx()
        assertTrue(FfxParser.isLikelyFfx(bytes))
        val report = FfxParser.inspect("sample.ffx", bytes)
        assertTrue(report.parsed)
        assertEquals("FFX preset (read-only inspector)", report.format)
        assertTrue(report.metadata.any { it.contains("Raw preset format-version marker") && it.contains("97") })
        assertTrue(report.warnings.any { it.contains("does not render") })
    }

    @Test
    fun rejectsWrongContainerType() {
        val bytes = sampleFfx()
        "Egg!".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        assertFalse(FfxParser.isLikelyFfx(bytes))
        try {
            FfxParser.inspect("wrong.ffx", bytes)
            fail("Expected a signature error")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("signature"))
        }
    }

    @Test
    fun reportsDeterministicSha256() {
        val bytes = sampleFfx()
        assertEquals(FfxParser.sha256(bytes), FfxParser.sha256(bytes.copyOf()))
        assertEquals(64, FfxParser.sha256(bytes).length)
    }
}
