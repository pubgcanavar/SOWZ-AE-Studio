package com.sowz.aestudio.core

data class ProjectItem(val name: String, val type: String, val detail: String = "")

data class ProjectReport(
    val fileName: String,
    val format: String,
    val fileSize: Long,
    val parsed: Boolean,
    val summary: String,
    val items: List<ProjectItem> = emptyList(),
    val chunkCounts: Map<String, Int> = emptyMap(),
    val metadata: List<String> = emptyList(),
    val archiveEntries: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val compositionCount: Int get() = items.count { it.type.equals("Composition", true) }
    val layerCount: Int get() = items.count { it.type.equals("Layer", true) }

    fun toReportText(): String = buildString {
        appendLine("SOWZ AE Studio — Project inspection report")
        appendLine("File: " + fileName)
        appendLine("Format: " + format)
        appendLine("Size: " + fileSize + " bytes")
        appendLine("Parsed: " + parsed)
        appendLine()
        appendLine("Summary")
        appendLine(summary)
        appendLine()
        appendLine("Metadata")
        if (metadata.isEmpty()) appendLine("No confidently recognized composition metadata.")
        else metadata.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("Recognized items")
        if (items.isEmpty()) appendLine("No typed composition/layer records were recognized.")
        else items.forEach { appendLine("- [" + it.type + "] " + it.name + if (it.detail.isBlank()) "" else " — " + it.detail) }
        appendLine()
        appendLine("Binary chunk inventory")
        if (chunkCounts.isEmpty()) appendLine("Not applicable")
        else chunkCounts.toSortedMap().forEach { appendLine("- " + it.key + ": " + it.value) }
        appendLine()
        appendLine("Archive entries (sample)")
        archiveEntries.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("Warnings and limitations")
        warnings.forEach { appendLine("- " + it) }
        appendLine()
        appendLine("This report is an inspector result, not proof that the project can be rendered identically to Adobe After Effects.")
    }
}

data class SavedProject(
    val fileName: String,
    val uri: String,
    val format: String,
    val summary: String,
    val openedAt: Long
)
