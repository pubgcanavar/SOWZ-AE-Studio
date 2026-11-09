package com.sowz.aestudio.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

/**
 * Bounded read-only decoder for public FFX RIFX/FaFX containers.
 *
 * It can identify effect match names and read static cdat values from tdbs
 * parameter nodes. It does not execute expressions, decode arbitrary plug-in
 * blobs, or claim that an After Effects effect can be reproduced on Android.
 */
data class FfxParameter(
    val name: String,
    val value: Double?,
    val value2: Double? = null,
    val value3: Double? = null,
    val value4: Double? = null,
    val isAnimated: Boolean = false
)

data class FfxEffect(
    val matchName: String,
    val displayName: String,
    val parameters: List<FfxParameter>
)

object FfxParser {
    private const val MAX_CHUNKS = 100_000
    private const val MAX_DEPTH = 32
    private const val MAX_FILE_BYTES = 32 * 1024 * 1024

    private data class ChunkNode(
        val id: String,
        val form: String? = null,
        val content: ByteArray = ByteArray(0),
        val children: List<ChunkNode> = emptyList()
    )
    private data class ParseState(var visited: Int = 0)

    fun isLikelyFfx(bytes: ByteArray): Boolean =
        bytes.size >= 86 &&
            ascii(bytes, 0, 4) == "RIFX" &&
            ascii(bytes, 8, 4) == "FaFX" &&
            ascii(bytes, 12, 4) == "head"

    /**
     * Decodes effect identity and static parameter values where known.
     * Unknown and third-party effects remain data only; this function never
     * evaluates expressions or attempts to run a plug-in.
     */
    fun parseEffects(bytes: ByteArray): List<FfxEffect> {
        validateHeader(bytes)
        val root = parseTree(bytes)
        val besc = root.getOrNull(1)?.takeIf { it.id == "LIST" && it.form == "besc" }
            ?: root.firstOrNull { it.id == "LIST" && it.form == "besc" }
            ?: return emptyList()

        val tdspNodes = besc.children.filter { it.id == "LIST" && it.form == "tdsp" }
        val effectNames = tdspNodes.mapNotNull { node ->
            val names = flatten(node).filter { it.id == "tdmn" }.map { decodeString(it.content) }
            names.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
        val parameterBlocks = besc.children.filter { it.id == "LIST" && it.form == "sspc" }
        val count = minOf(effectNames.size, parameterBlocks.size)

        return (0 until count).map { index ->
            val block = parameterBlocks[index]
            val all = flatten(block)
            val displayName = all.firstOrNull { it.id == "fnam" }
                ?.let { decodeString(it.content) }
                ?.takeIf { it.isNotBlank() }
                ?: all.firstOrNull { it.id == "tdsn" }
                    ?.let { decodeString(it.content) }
                    ?.takeIf { it.isNotBlank() }
                ?: effectNames[index]

            val parameters = all.filter { it.id == "LIST" && it.form == "tdbs" }.mapNotNull { node ->
                val name = node.children.firstOrNull { it.id == "tdsn" }
                    ?.let { decodeString(it.content) }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val cdat = node.children.firstOrNull { it.id == "cdat" }?.content
                val values = if (cdat == null) emptyList() else (0 until minOf(4, cdat.size / 8))
                    .mapNotNull { slot -> readFiniteDouble(cdat, slot * 8) }
                val animated = node.children.any { it.id == "LIST" && it.form == "list" } ||
                    flatten(node).any { it.id == "LIST" && it.form == "list" }
                FfxParameter(
                    name = name,
                    value = values.getOrNull(0),
                    value2 = values.getOrNull(1),
                    value3 = values.getOrNull(2),
                    value4 = values.getOrNull(3),
                    isAnimated = animated
                )
            }.distinctBy { it.name.lowercase(Locale.ROOT) }

            FfxEffect(effectNames[index], displayName, parameters)
        }
    }

    private fun validateHeader(bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES) { "The FFX file exceeds the 32 MB safety limit." }
        require(bytes.size >= 86) { "This FFX file is too small to be a normal After Effects preset." }
        require(ascii(bytes, 0, 4) == "RIFX" && ascii(bytes, 8, 4) == "FaFX" && ascii(bytes, 12, 4) == "head") {
            "FFX signature mismatch. Expected RIFX / FaFX / head. The file may be damaged or may not be an After Effects preset."
        }
    }

    private fun parseTree(bytes: ByteArray): List<ChunkNode> {
        val declared = u32(bytes, 4)
        val end = if (declared in 4..Int.MAX_VALUE.toLong()) minOf(bytes.size.toLong(), declared + 8L).toInt() else bytes.size
        return parseChunks(bytes, 12, end, 0, ParseState())
    }

    private fun parseChunks(
        bytes: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        state: ParseState
    ): List<ChunkNode> {
        if (depth > MAX_DEPTH || state.visited >= MAX_CHUNKS) return emptyList()
        val nodes = mutableListOf<ChunkNode>()
        var offset = start
        while (offset.toLong() + 8L <= end.toLong() && state.visited < MAX_CHUNKS) {
            val id = ascii(bytes, offset, 4)
            if (id.length != 4 || id.any { it.code !in 32..126 }) break
            val sizeLong = u32(bytes, offset + 4)
            if (sizeLong > Int.MAX_VALUE.toLong()) break
            val size = sizeLong.toInt()
            val bodyStart = offset + 8
            val bodyEndLong = bodyStart.toLong() + size
            if (bodyEndLong > end.toLong() || bodyEndLong < bodyStart.toLong()) break
            val bodyEnd = bodyEndLong.toInt()
            state.visited++
            if (id == "LIST" && size >= 4) {
                val form = ascii(bytes, bodyStart, 4)
                val children = if (form.all { it.code in 32..126 }) {
                    parseChunks(bytes, bodyStart + 4, bodyEnd, depth + 1, state)
                } else emptyList()
                nodes.add(ChunkNode(id = id, form = form, children = children))
            } else {
                nodes.add(ChunkNode(id = id, content = bytes.copyOfRange(bodyStart, bodyEnd)))
            }
            val next = bodyEnd.toLong() + (size and 1)
            if (next <= offset.toLong() || next > Int.MAX_VALUE.toLong()) break
            offset = next.toInt()
        }
        return nodes
    }

    private fun flatten(node: ChunkNode): List<ChunkNode> {
        val output = mutableListOf<ChunkNode>()
        fun walk(current: ChunkNode) {
            output.add(current)
            current.children.forEach(::walk)
        }
        walk(node)
        return output
    }

    private fun decodeString(raw: ByteArray): String {
        if (raw.size >= 8 && ascii(raw, 0, 4) == "Utf8") {
            val length = u32(raw, 4)
            if (length <= raw.size - 8L) {
                return String(raw, 8, length.toInt(), Charsets.UTF_8).trim().trimEnd('\u0000')
            }
        }
        val end = raw.indexOf(0).let { if (it >= 0) it else raw.size }
        return String(raw, 0, end, Charset.forName("ISO-8859-1")).trim()
    }

    private fun readFiniteDouble(bytes: ByteArray, offset: Int): Double? {
        if (offset < 0 || offset + 8 > bytes.size) return null
        val value = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).double
        return value.takeIf { it.isFinite() }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) return ""
        return String(bytes, offset, length, Charset.forName("ISO-8859-1"))
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }
}
