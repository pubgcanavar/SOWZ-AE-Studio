package com.sowz.aestudio.core

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Bounded, read-only project inspector.
 * Binary .aep is a version-sensitive private format. This inspects the RIFX container
 * and chunk framing but deliberately does not claim complete AE semantic parsing.
 */
object AepParser {
    private const val MAX_INPUT_BYTES = 512L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 5_000
    private const val MAX_ZIP_TOTAL_BYTES = 600L * 1024L * 1024L
    private const val MAX_ZIP_PROJECT_BYTES = 256L * 1024L * 1024L
    private const val MAX_XML_BYTES = 64L * 1024L * 1024L
    private const val MAX_ITEMS = 400
    private const val MAX_CHUNKS = 200_000

    fun parse(fileName: String, bytes: ByteArray): ProjectReport {
        if (bytes.isEmpty()) throw IllegalArgumentException("The selected file is empty.")
        if (bytes.size.toLong() > MAX_INPUT_BYTES) throw IllegalArgumentException("File exceeds the 512 MB safety limit.")
        return when (extension(fileName)) {
            "aep" -> parseBinary(fileName, bytes)
            "aepx" -> parseXmlProject(fileName, bytes, "AEPX (XML project)")
            "aet" -> parseXmlProject(fileName, bytes, "AET (XML template)")
            "zip" -> parseZip(fileName, bytes)
            else -> when {
                looksLikeXml(bytes) -> parseXmlProject(fileName, bytes, "XML project (extension not recognized)")
                looksLikeRifx(bytes) -> parseBinary(fileName, bytes)
                else -> throw IllegalArgumentException("Unsupported file. Choose an .aep, .aepx, .aet or .zip project package.")
            }
        }
    }

    private fun parseZip(fileName: String, bytes: ByteArray): ProjectReport {
        var entryCount = 0
        var totalRead = 0L
        val sample = mutableListOf<String>()
        val projectWarnings = mutableListOf<String>()
        var projectName: String? = null
        var projectBytes: ByteArray? = null
        var projectExt: String? = null
        var mediaCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) throw IllegalArgumentException("ZIP contains too many entries; limit is " + MAX_ZIP_ENTRIES + ".")
                val path = entry.name.replace('\\', '/')
                val unsafe = path.startsWith("/") || path.split('/').any { it == ".." }
                if (!entry.isDirectory && !unsafe) {
                    if (sample.size < 80) sample.add(path.take(180))
                    val ext = extension(path)
                    val isProject = ext == "aep" || ext == "aepx" || ext == "aet"
                    val capture = if (isProject && projectBytes == null) ByteArrayOutputStream() else null
                    val buffer = ByteArray(16 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        entryBytes += n
                        totalRead += n
                        if (totalRead > MAX_ZIP_TOTAL_BYTES) throw IllegalArgumentException("ZIP expands beyond the 600 MB safety limit.")
                        if (entryBytes > MAX_INPUT_BYTES) throw IllegalArgumentException("A ZIP entry exceeds the safety limit.")
                        if (capture != null) {
                            if (entryBytes > MAX_ZIP_PROJECT_BYTES) throw IllegalArgumentException("The project inside ZIP exceeds the 256 MB import limit.")
                            capture.write(buffer, 0, n)
                        }
                    }
                    if (isProject && capture != null && projectBytes == null) {
                        projectName = path.substringAfterLast('/')
                        projectBytes = capture.toByteArray()
                        projectExt = ext
                    } else if (!isProject && isLikelyMedia(ext)) mediaCount++
                } else {
                    while (zip.read(ByteArray(8192)) != -1) { }
                }
                zip.closeEntry()
            }
        }
        val selectedBytes = projectBytes ?: throw IllegalArgumentException("No .aep, .aepx or .aet project file was found in this ZIP.")
        val selectedName = projectName ?: "Project"
        val selectedExt = projectExt ?: "aep"
        val inner = when (selectedExt) {
            "aep" -> parseBinary(selectedName, selectedBytes)
            "aepx" -> parseXmlProject(selectedName, selectedBytes, "AEPX inside ZIP")
            else -> parseXmlProject(selectedName, selectedBytes, "AET inside ZIP")
        }
        projectWarnings.addAll(inner.warnings)
        projectWarnings.add("ZIP scanning does not extract media into a working folder or relink footage yet.")
        if (mediaCount > 0) projectWarnings.add(mediaCount.toString() + " likely media asset(s) were found in the package.")
        if (entryCount > sample.size) projectWarnings.add("Only the first " + sample.size + " file paths are listed.")
        return inner.copy(
            fileName = fileName,
            format = "ZIP package → " + inner.format,
            fileSize = bytes.size.toLong(),
            archiveEntries = sample,
            warnings = projectWarnings.distinct()
        )
    }

    private fun parseXmlProject(fileName: String, bytes: ByteArray, label: String): ProjectReport {
        if (bytes.size.toLong() > MAX_XML_BYTES) throw IllegalArgumentException("XML project exceeds the 64 MB parsing limit.")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setXIncludeAware(false)
        factory.isExpandEntityReferences = false
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val rootName = document.documentElement?.tagName.orEmpty()
        val items = mutableListOf<ProjectItem>()
        val metadata = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (rootName.isNotBlank()) metadata.add("XML root element: " + rootName)
        val all = document.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val node = all.item(i) as? Element ?: continue
            val tag = node.tagName.substringAfter(':').lowercase(Locale.ROOT)
            val type = when {
                tag.contains("composition") || tag == "comp" || tag == "compparams" -> "Composition"
                tag == "layer" || tag.endsWith("layer") -> "Layer"
                tag.contains("footage") -> "Footage"
                tag.contains("folder") -> "Folder"
                tag.contains("effect") -> "Effect"
                tag.contains("marker") -> "Marker"
                tag.contains("text") -> "Text"
                else -> null
            }
            if (type != null && items.size < MAX_ITEMS) {
                val name = firstNonBlank(
                    node.getAttribute("name"),
                    node.getAttribute("Name"),
                    node.getAttribute("itemName"),
                    node.getAttribute("comment"),
                    directChildText(node, "name"),
                    directChildText(node, "Name"),
                    directChildText(node, "string")
                )
                val normalized = if (name.isBlank()) type + " " + (items.count { it.type == type } + 1) else name
                items.add(ProjectItem(normalized.take(180), type, "XML element: " + node.tagName))
            }
            listOf("width", "height", "frameRate", "frame_rate", "duration").forEach { attr ->
                if (node.hasAttribute(attr)) {
                    val entry = node.tagName + "." + attr + " = " + node.getAttribute(attr).take(40)
                    if (metadata.size < 60 && entry !in metadata) metadata.add(entry)
                }
            }
        }
        if (items.isEmpty()) warnings.add("XML parsed, but no composition/layer records were confidently recognized.")
        else warnings.add("Recognized XML items use tag-name heuristics; compare names/counts against After Effects.")
        warnings.add("Expressions are not evaluated; effects/plugins are not rendered; project scripts are never executed.")
        val compCount = items.count { it.type == "Composition" }
        val layerCount = items.count { it.type == "Layer" }
        return ProjectReport(
            fileName = fileName,
            format = label,
            fileSize = bytes.size.toLong(),
            parsed = true,
            summary = "XML parsed successfully. Recognized " + compCount + " composition-like, " + layerCount + " layer-like and " + items.size + " total item record(s).",
            items = items.toList(),
            metadata = metadata.distinct(),
            warnings = warnings
        )
    }

    private fun parseBinary(fileName: String, bytes: ByteArray): ProjectReport {
        if (!looksLikeRifx(bytes)) throw IllegalArgumentException("Expected RIFX header was not found. The file may be damaged or unsupported.")
        if (bytes.size < 12 || ascii(bytes, 8, 4) != "Egg!") {
            throw IllegalArgumentException("RIFX header found, but the expected After Effects form signature Egg! was not found.")
        }
        val declaredSize = uint32(bytes, 4)
        val counts = linkedMapOf<String, Int>()
        val metadata = mutableListOf<String>()
        val strings = linkedSetOf<String>()
        val warnings = mutableListOf<String>()
        var visited = 0
        var possibleDimensions = 0

        fun scan(start: Int, end: Int, depth: Int) {
            if (depth > 48) {
                if ("Chunk nesting stopped at the 48-level safety cap." !in warnings) warnings.add("Chunk nesting stopped at the 48-level safety cap.")
                return
            }
            var offset = start
            while (offset <= end - 8 && visited < MAX_CHUNKS) {
                val tag = ascii(bytes, offset, 4)
                val sizeLong = uint32(bytes, offset + 4)
                if (sizeLong > Int.MAX_VALUE.toLong()) break
                val size = sizeLong.toInt()
                val body = offset + 8
                val bodyEndLong = body.toLong() + size.toLong()
                if (bodyEndLong > end.toLong() || bodyEndLong < body.toLong()) break
                val bodyEnd = bodyEndLong.toInt()
                visited++
                counts[tag] = (counts[tag] ?: 0) + 1
                if ((tag == "LIST" || tag == "RIFF" || tag == "RIFX") && size >= 4) {
                    val listType = ascii(bytes, body, 4)
                    if (listType.isNotBlank() && listType.all { it.code in 32..126 }) {
                        val key = "LIST:" + listType
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                    scan(body + 4, bodyEnd, depth + 1)
                } else if (tag.equals("Utf8", true) || tag.equals("TEXT", true) || tag.equals("STR ", true)) {
                    val raw = String(bytes, body, size, Charsets.UTF_8)
                    val cleaned = raw.replace("\u0000", "").trim()
                    if (cleaned.length >= 2 && strings.size < 200) strings.add(cleaned.take(180))
                } else if (tag == "cdta" && size >= 158) {
                    val width = uint16(bytes, body + 140)
                    val height = uint16(bytes, body + 142)
                    val fps = uint16(bytes, body + 156)
                    if (width in 16..16384 && height in 16..16384 && fps in 1..240) {
                        val value = "Possible cdta dimensions: " + width + "×" + height + ", rate field " + fps + " (heuristic)"
                        if (value !in metadata) {
                            metadata.add(value)
                            possibleDimensions++
                        }
                    }
                }
                offset = bodyEnd + (size and 1)
            }
        }
        scan(12, bytes.size, 0)
        if (visited >= MAX_CHUNKS) metadata.add("Chunk scan stopped at the safety limit of " + MAX_CHUNKS + " chunks.")
        strings.take(120).forEach { metadata.add("Readable text chunk: " + it) }
        warnings.add("Binary AEP inspection is partial and read-only; the chunk tree is not the full semantic project graph.")
        warnings.add("Layer names, expressions, keyframes, effects and footage references may not be decoded by this first version.")
        warnings.add("No composition preview is rendered. A valid RIFX header does not prove every project block is supported.")
        if (possibleDimensions == 0) warnings.add("No dimensions matching the current conservative heuristic were found.")
        if (declaredSize > 0 && declaredSize + 8 > bytes.size) warnings.add("Header declares a larger RIFX size than the bytes available; project may be truncated.")
        return ProjectReport(
            fileName = fileName,
            format = "AEP binary (RIFX inspector)",
            fileSize = bytes.size.toLong(),
            parsed = true,
            summary = "Recognized the RIFX / Egg! signature and scanned " + visited + " chunk(s). Found " +
                counts.keys.count { !it.startsWith("LIST:") } + " distinct chunk tags and " + strings.size + " readable string(s). This is structural inspection, not rendering.",
            items = strings.take(80).map { ProjectItem(it, "Readable string", "Extracted from a string chunk") },
            chunkCounts = counts,
            metadata = metadata.take(120),
            warnings = warnings
        )
    }

    private fun directChildText(parent: Element, tagName: String): String {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName.equals(tagName, true)) {
                return child.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun looksLikeXml(bytes: ByteArray): Boolean {
        val start = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return start.startsWith("<?xml", true) || start.startsWith("<AfterEffectsProject", true) || start.startsWith("<")
    }

    private fun looksLikeRifx(bytes: ByteArray): Boolean = bytes.size >= 4 && ascii(bytes, 0, 4) == "RIFX"
    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun isLikelyMedia(ext: String): Boolean = ext in setOf(
        "mov", "mp4", "m4v", "avi", "mxf", "wav", "mp3", "aif", "aiff",
        "png", "jpg", "jpeg", "tif", "tiff", "exr", "psd", "ai", "svg",
        "gif", "bmp", "webp", "otf", "ttf"
    )

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.ISO_8859_1)
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return 0
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun uint16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
