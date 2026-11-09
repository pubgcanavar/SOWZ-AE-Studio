package com.sowz.aestudio.core

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import org.xml.sax.InputSource
import javax.xml.parsers.SAXParserFactory

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

    private fun attributeValue(attributes: Attributes, wantedName: String): String? {
        for (index in 0 until attributes.length) {
            val qualified = attributes.getQName(index)
            val local = attributes.getLocalName(index)
            if (qualified.equals(wantedName, ignoreCase = true) ||
                local.equals(wantedName, ignoreCase = true)
            ) {
                return attributes.getValue(index)
            }
        }
        return null
    }

    private class ScanHandler : DefaultHandler() {
        var elements = 0
            private set
        var compositions = 0
            private set
        var layers = 0
            private set
        var footage = 0
            private set
        var binaryBlocks = 0
            private set
        var depth = 0
            private set
        var root = ""
            private set
        val effects = linkedSetOf<String>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            depth++
            require(depth <= MAX_DEPTH) { "AEPX nesting exceeds the 96-level safety limit." }
            elements++
            require(elements <= MAX_ELEMENTS) { "AEPX contains too many XML elements." }

            val tag = qName.orEmpty().ifBlank { localName.orEmpty() }
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

            val isEffectNode = normalized == "effect" ||
                normalized.startsWith("effect") ||
                normalized.startsWith("adbeeffect") ||
                normalized.contains("plugin")
            if (isEffectNode && effects.size < MAX_EFFECT_LABELS) {
                val label = sequenceOf(
                    AepxProjectInspector.attributeValue(attributes, "matchName"),
                    AepxProjectInspector.attributeValue(attributes, "name"),
                    AepxProjectInspector.attributeValue(attributes, "id"),
                    tag
                ).firstOrNull { !it.isNullOrBlank() } ?: tag
                effects.add(label.take(180))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            depth = (depth - 1).coerceAtLeast(0)
        }

        override fun error(exception: SAXParseException) {
            throw exception
        }

        override fun fatalError(exception: SAXParseException) {
            throw exception
        }
    }

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

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isXIncludeAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val handler = ScanHandler()
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = EntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
        reader.contentHandler = handler
        reader.errorHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(bytes)))

        require(handler.elements > 0) { "No XML elements were found in the AEPX file." }
        return AepxProjectSummary(
            rootElement = handler.root,
            elementCount = handler.elements,
            compositionCount = handler.compositions,
            layerCount = handler.layers,
            footageCount = handler.footage,
            effectElements = handler.effects.toList(),
            binaryDataBlocks = handler.binaryBlocks,
            notes = listOf(
                "Only XML element structure and effect-like labels are inspected; embedded binary payloads remain opaque.",
                "This scan cannot claim exact After Effects compatibility or create a rendered composition."
            )
        )
    }
}
