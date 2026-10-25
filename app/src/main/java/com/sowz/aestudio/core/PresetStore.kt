package com.sowz.aestudio.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

data class PresetEntry(
    val fileName: String,
    val file: File,
    val sizeBytes: Long,
    val importedAt: Long
)

class PresetStore(context: Context) {
    private val directory = File(context.filesDir, "ffx-presets").apply { mkdirs() }

    fun list(): List<PresetEntry> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("ffx", true) }
        .sortedByDescending { it.lastModified() }
        .map { PresetEntry(it.name, it, it.length(), it.lastModified()) }

    @Synchronized
    fun import(resolver: ContentResolver, uri: Uri, sourceName: String): PresetEntry {
        val safeName = sanitize(sourceName)
        val bytes = resolver.openInputStream(uri)?.use { readBounded(it, MAX_PRESET_BYTES) }
            ?: throw IllegalArgumentException("Could not read this preset file.")
        // Validate before saving; a renamed non-FFX file should not enter the preset library.
        FfxParser.inspect(safeName, bytes)
        var target = File(directory, safeName)
        if (target.exists()) {
            val base = safeName.substringBeforeLast('.', safeName)
            var n = 2
            do {
                target = File(directory, "$base ($n).ffx")
                n++
            } while (target.exists() && n < 10000)
        }
        val temp = File(directory, target.name + ".tmp")
        try {
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(target)) throw IllegalStateException("Preset was read but could not be saved to the local library.")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return PresetEntry(target.name, target, target.length(), target.lastModified())
    }

    fun readBytes(entry: PresetEntry): ByteArray {
        val file = requireOwned(entry.file)
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("This preset is no longer in the library.")
        if (file.length() > MAX_PRESET_BYTES) throw IllegalArgumentException("Preset exceeds the 64 MB safety limit.")
        return file.readBytes()
    }

    @Synchronized
    fun delete(entry: PresetEntry): Boolean = requireOwned(entry.file).delete()

    private fun requireOwned(file: File): File {
        val canonicalDir = directory.canonicalFile
        val canonical = file.canonicalFile
        if (canonical.parentFile != canonicalDir) throw SecurityException("Preset path is outside the local library.")
        return canonical
    }

    private fun sanitize(sourceName: String): String {
        val onlyName = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val base = onlyName.substringBeforeLast('.', onlyName)
            .replace(Regex("[^A-Za-z0-9 _().-]"), "_")
            .trim()
            .trim('.')
            .take(100)
            .ifBlank { "After Effects Preset" }
        return "$base.ffx"
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalArgumentException("FFX preset exceeds the 64 MB safety limit.")
            out.write(buffer, 0, n)
        }
        if (out.size() == 0) throw IllegalArgumentException("The preset file is empty.")
        return out.toByteArray()
    }

    companion object {
        const val MAX_PRESET_BYTES = 64L * 1024L * 1024L
    }
}
