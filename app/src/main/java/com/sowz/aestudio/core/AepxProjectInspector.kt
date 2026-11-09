package com.sowz.aestudio.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

data class AepxProjectSummary(
    val rootElement: String,
    val elementCount: Int,
    val compositionCount: Int,
    val layerCount: Int,
    val footageCount: Int,
    val effectElements: List<String>,
    val binaryDataBlocks: Int,
    val notes: List<String>
) {
    fun reportText(): String = buildString {
        append("Format: After Effects XML project")
        append("\nRoot: ").append(rootElement)
        append("\nXML elements scanned: ").append(elementCount)
        append("\nComposition-like nodes: ").append(compositionCount)
        append("\nLayer-like nodes: ").append(layerCount)
        append("\nFootage-like nodes: ").append(footageCount)
        append("\nEffect-like nodes: ").append(effectElements.size)
        append("\nEmbedded binary-data blocks: ").append(binaryDataBlocks)
        if (effectElements.isNotEmpty()) {
            append("\n\nEffect nodes:")
            effectElements.take(24).forEach { append("\n• ").append(it) }
            if (effectElements.size > 24) append("\n… and ").append(effectElements.size - 24).append(" more")
        }
        notes.forEach { append("\nNote: ").append(it) }
        append("\n\nThis is a bounded structural XML scan. It does not reconstruct a fully editable AEPX composition, decode every bdata payload, or render the project.")
    }
}

/**
 * Safe, streaming AEPX structural inspector.
 *
 * The XML serialization can contain large opaque bdata payloads. This reader deliberately
 * reports the visible tree structure and does not pretend that a tag scan reconstructs AE.
 */
object AepxProjectInspector {
    private const val MAX_INPUT_BYTES = 96 * 1024 * 1024
    private const val MAX_ELEMENTS = 250_000
    private const val MAX_DEPTH = 96
    private const val MAX_EFFECT_LABELS = 2_000

    fun inspect(bytes: ByteArray): AepxProjectSummary {
        require(bytes.isNotEmpty()) { "The selected AEPX file is empty." }
        require(bytes.size <= MAX_INPUT_BYTES) { "AEPX exceeds the 96 MiB safety limit." }
        val prefix = bytes.copyOfRange(0, minOf(bytes.size, 4096))
            .toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        require(prefix.startsWith("<?xml") || prefix.startsWith("<AfterEffects") ||
            prefix.startsWith("<Egg")) {
            "The selected file does not look like an After Effects XML project."
        }

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        val parser = factory.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")

        var elements = 0
        var compositions = 0
        var layers = 0
        var footage = 0
        var binaryBlocks = 0
        var depth = 0
        var root = ""
        val effects = linkedSetOf<String>()

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    require(depth <= MAX_DEPTH) { "AEPX nesting exceeds the 96-level safety limit." }
                    elements++
                    require(elements <= MAX_ELEMENTS) { "AEPX contains too many XML elements." }

                    val tag = parser.name.orEmpty()
                    if (root.isBlank()) root = tag
                    val normalized = tag.lowercase()
                    when {
                        normalized == "composition" || normalized == "compitem" || normalized == "comp" ->
                            compositions++
                        normalized.contains("layer") || normalized == "layr" ->
                            layers++
                        normalized.contains("footage") || normalized == "footageitem" ->
                            footage++
                        normalized == "bdata" || normalized == "binarydata" ->
                            binaryBlocks++
                    }

                    if (normalized.contains("effect") || normalized.contains("plugin")) {
                        if (effects.size < MAX_EFFECT_LABELS) {
                            val label = sequenceOf(
                                parser.getAttributeValue(null, "name"),
                                parser.getAttributeValue(null, "matchName"),
                                parser.getAttributeValue(null, "matchname"),
                                parser.getAttributeValue(null, "id"),
                                tag
                            ).firstOrNull { !it.isNullOrBlank() } ?: tag
                            effects.add(label.take(180))
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth = (depth - 1).coerceAtLeast(0)
                XmlPullParser.DOCDECL -> error("AEPX document types are disabled for safety.")
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        require(elements > 0) { "No XML elements were found in the AEPX file." }
        return AepxProjectSummary(
            rootElement = root,
            elementCount = elements,
            compositionCount = compositions,
            layerCount = layers,
            footageCount = footage,
            effectElements = effects.toList(),
            binaryDataBlocks = binaryBlocks,
            notes = listOf(
                "Only XML element structure and effect-like labels are inspected; embedded binary payloads remain opaque.",
                "This scan cannot claim exact After Effects compatibility or create a rendered composition."
            )
        )
    }
}
