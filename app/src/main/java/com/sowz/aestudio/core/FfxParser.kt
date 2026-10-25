package com.sowz.aestudio.core

import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

/**
 * Read-only FFX preset inspector.
 *
 * The public file shape used here is the RIFX / FaFX container with a head chunk.
 * The proprietary preset payload is not fully decoded. The version integer is displayed
 * only as a raw marker; no version conversion or byte patching is performed.
 */
object FfxParser {
    private const val MAX_CHUNKS = 100_000
    private const val MAX_DEPTH = 32

    fun isLikelyFfx(bytes: ByteArray): Boolean =
        bytes.size >= 86 &&
            ascii(bytes, 0, 4) == "RIFX" &&
            ascii(bytes, 8, 4) == "FaFX" &&
            ascii(bytes, 12, 4) == "head"

    fun inspect(fileName: String, bytes: ByteArray): ProjectReport {
        if (bytes.size < 86) throw IllegalArgumentException("This FFX file is too small to be a normal After Effects preset.")
        if (ascii(bytes, 0, 4) != "RIFX" || ascii(bytes, 8, 4) != "FaFX" || ascii(bytes, 12, 4) != "head") {
            throw IllegalArgumentException("FFX signature mismatch. Expected RIFX / FaFX / head. The file may be damaged or may not be an After Effects preset.")
        }
        val declared = u32(bytes, 4)
        val safeEnd = if (declared in 4..Int.MAX_VALUE.toLong()) minOf(bytes.size.toLong(), 8L + declared).toInt() else bytes.size
        val counts = linkedMapOf<String, Int>()
        val metadata = mutableListOf<String>()
        val readable = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var visited = 0
        val headSize = u32(bytes, 16)
        metadata.add("Container signature: RIFX / FaFX")
        metadata.add("Declared container payload size: $declared bytes")
        metadata.add("head chunk payload size: $headSize bytes")
        if (bytes.size >= 28) {
            val marker = u32(bytes, 24)
            metadata.add("Raw preset format-version marker at 0x18: $marker (candidate field; version mapping is not assumed)")
        }
        metadata.add("File SHA-256: " + sha256(bytes))
        metadata.add("Bytes present: " + bytes.size)
        if (declared + 8 > bytes.size) warnings.add("Container header declares more bytes than are present; the preset may be truncated.")
        if (declared + 8 < bytes.size) warnings.add("Bytes exist beyond the declared RIFX container end; these bytes are not interpreted as trusted preset data.")

        fun scan(start: Int, end: Int, depth: Int) {
            if (depth > MAX_DEPTH) {
                warnings.add("Chunk nesting stopped at the $MAX_DEPTH-level safety cap.")
                return
            }
            var offset = start
            while (offset.toLong() + 8 <= end.toLong() && visited < MAX_CHUNKS) {
                val tag = ascii(bytes, offset, 4)
                if (tag.length != 4 || tag.any { it.code !in 32..126 }) {
                    warnings.add("Non-printable chunk tag found at offset 0x" + offset.toString(16) + "; scanning this region stopped.")
                    return
                }
                val sizeLong = u32(bytes, offset + 4)
                if (sizeLong > Int.MAX_VALUE.toLong()) {
                    warnings.add("Chunk $tag has an unsafe size; remaining scan stopped.")
                    return
                }
                val size = sizeLong.toInt()
                val body = offset + 8
                val endLong = body.toLong() + size
                if (endLong > end.toLong() || endLong < body.toLong()) {
                    warnings.add("Chunk $tag at 0x" + offset.toString(16) + " extends outside its container; scan stopped.")
                    return
                }
                val bodyEnd = endLong.toInt()
                visited++
                counts[tag] = (counts[tag] ?: 0) + 1
                if (tag == "LIST" && size >= 4) {
                    val listType = ascii(bytes, body, 4)
                    if (listType.all { it.code in 32..126 }) {
                        val key = "LIST:$listType"
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                    scan(body + 4, bodyEnd, depth + 1)
                } else if ((tag == "tdmn" || tag == "Utf8" || tag == "TEXT" || tag == "STR ") && size in 1..2048) {
                    val text = String(bytes, body, size, Charsets.UTF_8).replace("\u0000", "").trim()
                    if (text.length >= 2 && text.any { it.isLetterOrDigit() } && readable.size < 160) {
                        readable.add("[$tag] " + text.take(180))
                    }
                }
                offset = bodyEnd + (size and 1)
            }
            if (visited >= MAX_CHUNKS) warnings.add("Chunk scan reached the $MAX_CHUNKS-chunk safety cap.")
        }

        scan(12, safeEnd, 0)
        readable.take(100).forEach { metadata.add("Readable preset string: $it") }
        warnings.add("This is a structural inspector, not a full FFX decoder. It does not render or apply After Effects effects.")
        warnings.add("Imported FFX bytes are preserved unchanged. Compatibility with the effect/plugin set still depends on the target editor.")
        if (counts.isEmpty()) warnings.add("No child chunks were recognized; inspect the file signature and source application version.")
        val chunkSummary = if (counts.isEmpty()) "No chunk tags detected." else counts.toSortedMap().entries.joinToString(", ") { it.key + "=" + it.value }
        val hash = sha256(bytes)
        return ProjectReport(
            fileName = fileName,
            format = "FFX preset (read-only inspector)",
            fileSize = bytes.size.toLong(),
            parsed = true,
            summary = "Recognized the RIFX / FaFX / head header. Scanned $visited chunks. SHA-256: $hash. Chunk inventory: $chunkSummary",
            items = readable.take(100).map { ProjectItem(it, "Preset string", "Read-only decoded string; meaning not inferred") },
            chunkCounts = counts,
            metadata = metadata.take(120),
            warnings = warnings
        )
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
