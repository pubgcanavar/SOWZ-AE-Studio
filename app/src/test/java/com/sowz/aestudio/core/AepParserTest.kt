package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AepParserTest {
    @Test
    fun rejectsUnknownFormat() {
        try {
            AepParser.parse("bad.bin", byteArrayOf(1, 2, 3, 4))
            fail("Expected unsupported-file exception")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Unsupported"))
        }
    }

    @Test
    fun parsesAepxXmlAndRecognizesItems() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><AfterEffectsProject><Composition name="Main Comp" width="1080" height="1920"><Layer name="Text 01"/><Layer name="Footage 02"/></Composition></AfterEffectsProject>"""
        val result = AepParser.parse("sample.aepx", xml.toByteArray(Charsets.UTF_8))
        assertTrue(result.parsed)
        assertEquals("AEPX (XML project)", result.format)
        assertTrue(result.items.any { it.type == "Composition" && it.name == "Main Comp" })
        assertTrue(result.items.count { it.type == "Layer" } >= 2)
    }

    @Test
    fun parsesRifxContainerSignature() {
        val bytes = ByteArray(12)
        "RIFX".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(4)
        "Egg!".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
        val report = AepParser.parse("sample.aep", bytes)
        assertTrue(report.parsed)
        assertTrue(report.format.contains("RIFX"))
        assertTrue(report.warnings.isNotEmpty())
    }

    @Test
    fun rejectsRifxWithoutEggForm() {
        val bad = byteArrayOf(82, 73, 70, 88, 0, 0, 0, 4, 0, 0, 0, 0)
        try {
            AepParser.parse("broken.aep", bad)
            fail("Expected missing form signature")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Egg!"))
        }
    }
}
