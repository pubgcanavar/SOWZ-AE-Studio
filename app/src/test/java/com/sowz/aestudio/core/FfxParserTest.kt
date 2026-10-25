package com.sowz.aestudio.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FfxParserTest {
    private fun sampleFfx(): ByteArray {
        val headPayload = ByteArray(66)
        ByteBuffer.wrap(headPayload, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(97)
        return ffxFile(chunk("head", headPayload))
    }

    private fun ffxWithBrightnessContrast(animated: Boolean = false): ByteArray {
        val headPayload = ByteArray(66)
        ByteBuffer.wrap(headPayload, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(97)
        val effectPath = list("tdsp", concat(
            textChunk("tdmn", "ADBE Effect Parade"),
            textChunk("tdmn", "ADBE Brightness & Contrast 2")
        ))
        // The sentinel contains only one tdmn entry and is not an effect.
        val sentinel = list("tdsp", textChunk("tdmn", "ADBE End of path sentinel"))
        val brightness = list("tdbs", concat(
            textChunk("tdsn", "Brightness"),
            doubleChunk(25.0),
            if (animated) list("list", concat(chunk("lhd3", ByteArray(52)), chunk("ldat", ByteArray(48)))) else ByteArray(0)
        ))
        val contrast = list("tdbs", concat(textChunk("tdsn", "Contrast"), doubleChunk(40.0)))
        val effectParameters = list("tdgp", concat(
            textChunk("tdsn", "Brightness & Contrast"),
            brightness,
            contrast
        ))
        val effectBlock = list("sspc", concat(textChunk("fnam", "Brightness & Contrast"), effectParameters))
        return ffxFile(concat(chunk("head", headPayload), list("besc", concat(effectPath, sentinel, effectBlock))))
    }

    private fun ffxFile(contents: ByteArray): ByteArray {
        val result = ByteArray(12 + contents.size)
        "RIFX".toByteArray(Charsets.US_ASCII).copyInto(result, 0)
        ByteBuffer.wrap(result, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(result.size - 8)
        "FaFX".toByteArray(Charsets.US_ASCII).copyInto(result, 8)
        contents.copyInto(result, 12)
        return result
    }

    private fun chunk(id: String, payload: ByteArray): ByteArray {
        val result = ByteArray(8 + payload.size + (payload.size and 1))
        id.toByteArray(Charsets.US_ASCII).copyInto(result, 0)
        ByteBuffer.wrap(result, 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
        payload.copyInto(result, 8)
        return result
    }

    private fun list(form: String, children: ByteArray): ByteArray =
        chunk("LIST", concat(form.toByteArray(Charsets.US_ASCII), children))

    private fun textChunk(id: String, value: String): ByteArray =
        chunk(id, value.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0))

    private fun doubleChunk(value: Double): ByteArray =
        chunk("cdat", ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(value).array())

    private fun concat(vararg values: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { values.forEach { write(it) } }.toByteArray()

    @Test
    fun recognizesExpectedContainer() {
        val bytes = sampleFfx()
        assertTrue(FfxParser.isLikelyFfx(bytes))
        val report = FfxParser.inspect("sample.ffx", bytes)
        assertTrue(report.parsed)
        assertEquals("FFX preset (effect inspector)", report.format)
        assertTrue(report.metadata.any { it.contains("Raw preset format-version marker") && it.contains("97") })
        assertTrue(report.warnings.any { it.contains("does not render") })
    }

    @Test
    fun decodesEffectNamesAndStaticBrightnessContrastValues() {
        val effects = FfxParser.parseEffects(ffxWithBrightnessContrast())
        assertEquals(1, effects.size)
        assertEquals("ADBE Brightness & Contrast 2", effects[0].matchName)
        assertEquals("Brightness & Contrast", effects[0].displayName)
        assertEquals(25.0, effects[0].parameters.first { it.name == "Brightness" }.value!!, 0.0001)
        assertEquals(40.0, effects[0].parameters.first { it.name == "Contrast" }.value!!, 0.0001)
        assertFalse(effects[0].parameters.any { it.isAnimated })
    }

    @Test
    fun marksKeyframedParametersInsteadOfPretendingTheyAreStatic() {
        val effects = FfxParser.parseEffects(ffxWithBrightnessContrast(animated = true))
        assertTrue(effects[0].parameters.first { it.name == "Brightness" }.isAnimated)
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
