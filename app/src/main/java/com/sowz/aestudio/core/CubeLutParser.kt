package com.sowz.aestudio.core

import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * Reader for the common .cube 3D LUT format.
 *
 * It supports one 3D cube with the standard RGB domain [0,1]. It deliberately rejects 1D shaper
 * tables and non-default domains rather than silently applying an incorrect colour transform.
 */
data class CubeLutData(
    val fileName: String,
    val title: String,
    val size: Int,
    /** Indexed as [red][green][blue], with each value an opaque ARGB colour. */
    val cube: Array<Array<IntArray>>,
    val entryCount: Int
)

object CubeLutParser {
    private const val MAX_FILE_BYTES = 12 * 1024 * 1024
    private const val MIN_SIZE = 2
    private const val MAX_SIZE = 64

    fun parse(fileName: String, bytes: ByteArray): CubeLutData {
        require(bytes.isNotEmpty()) { "The selected LUT file is empty." }
        require(bytes.size <= MAX_FILE_BYTES) { "The .cube file exceeds the 12 MB safety limit." }

        val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        var title = fileName.substringAfterLast('/').substringBeforeLast('.').take(120)
        var size: Int? = null
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var sawData = false
        val rows = ArrayList<FloatArray>()

        text.lineSequence().forEachIndexed { index, original ->
            val line = original.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            val directive = parts.first().uppercase()

            when (directive) {
                "TITLE" -> {
                    require(!sawData) { "TITLE must appear before LUT data (line ${index + 1})." }
                    title = line.substringAfterFirstWord().trim().trim('"').take(160).ifBlank { title }
                }
                "LUT_3D_SIZE" -> {
                    require(!sawData) { "LUT_3D_SIZE must appear before LUT data (line ${index + 1})." }
                    require(parts.size == 2) { "Invalid LUT_3D_SIZE directive on line ${index + 1}." }
                    val n = parts[1].toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE value on line ${index + 1}.")
                    require(n in MIN_SIZE..MAX_SIZE) {
                        "This editor supports .cube sizes from $MIN_SIZE to $MAX_SIZE; got $n."
                    }
                    require(size == null) { "The file contains multiple LUT_3D_SIZE directives." }
                    size = n
                }
                "LUT_1D_SIZE" -> throw IllegalArgumentException(
                    "This file contains a 1D shaper table. Only standalone 3D .cube LUTs are supported right now."
                )
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    require(!sawData) { "$directive must appear before LUT data (line ${index + 1})." }
                    require(parts.size == 4) { "Invalid $directive directive on line ${index + 1}." }
                    val values = parts.drop(1).mapIndexed { component, value ->
                        value.toFloatOrNull()?.takeIf { it.isFinite() }
                            ?: throw IllegalArgumentException("Invalid $directive component ${component + 1} on line ${index + 1}.")
                    }.toFloatArray()
                    if (directive == "DOMAIN_MIN") domainMin = values else domainMax = values
                }
                "LUT_3D_INPUT_RANGE" -> throw IllegalArgumentException(
                    "LUT_3D_INPUT_RANGE is not supported by this importer yet."
                )
                else -> {
                    // Unknown non-numeric words are rejected. This prevents treating a malformed
                    // directive as an RGB row and producing a misleadingly accepted preset.
                    val n = size ?: throw IllegalArgumentException(
                        "LUT_3D_SIZE must be declared before RGB values (line ${index + 1})."
                    )
                    require(parts.size == 3) { "Expected three RGB values on line ${index + 1}." }
                    val values = parts.map { token ->
                        token.toFloatOrNull()?.takeIf { it.isFinite() }
                            ?: throw IllegalArgumentException("Invalid RGB value on line ${index + 1}.")
                    }
                    require(values.all { it in -4f..4f }) {
                        "RGB value out of the supported range on line ${index + 1}."
                    }
                    require(rows.size < n * n * n) { "The file contains more than ${n * n * n} RGB rows." }
                    rows.add(floatArrayOf(values[0], values[1], values[2]))
                    sawData = true
                }
            }
        }

        val n = size ?: throw IllegalArgumentException("No LUT_3D_SIZE directive was found.")
        require(domainMin.contentEquals(floatArrayOf(0f, 0f, 0f)) &&
            domainMax.contentEquals(floatArrayOf(1f, 1f, 1f))) {
            "This importer currently supports only DOMAIN_MIN 0 0 0 and DOMAIN_MAX 1 1 1."
        }
        val expected = n * n * n
        require(rows.size == expected) {
            "Incomplete .cube LUT: expected $expected RGB rows but found ${rows.size}."
        }

        val cube = Array(n) { Array(n) { IntArray(n) } }
        rows.forEachIndexed { index, rgb ->
            // .cube data is ordered with blue changing fastest, then green, then red.
            val redIndex = index / (n * n)
            val greenIndex = (index / n) % n
            val blueIndex = index % n
            val red = (rgb[0].coerceIn(0f, 1f) * 255f).roundToInt()
            val green = (rgb[1].coerceIn(0f, 1f) * 255f).roundToInt()
            val blue = (rgb[2].coerceIn(0f, 1f) * 255f).roundToInt()
            cube[redIndex][greenIndex][blueIndex] =
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }

        return CubeLutData(fileName, title, n, cube, rows.size)
    }

    private fun String.substringAfterFirstWord(): String {
        val firstSpace = indexOfFirst { it.isWhitespace() }
        return if (firstSpace < 0) "" else substring(firstSpace + 1)
    }
}
