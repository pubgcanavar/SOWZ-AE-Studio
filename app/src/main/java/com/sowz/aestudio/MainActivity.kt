package com.sowz.aestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sowz.aestudio.core.AepParser
import com.sowz.aestudio.core.FfxParser
import com.sowz.aestudio.core.PresetEntry
import com.sowz.aestudio.core.PresetStore
import com.sowz.aestudio.core.ProjectReport
import com.sowz.aestudio.core.ProjectStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private enum class Tab { HOME, PROJECTS, PRESETS, INSPECTOR, SETTINGS }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ProjectStore
    private lateinit var presetStore: PresetStore
    private var pendingExportPreset: PresetEntry? = null
    private var tab = Tab.HOME
    private var report: ProjectReport? = null
    private var working = false
    private var errorMessage: String? = null

    private val bg = Color.rgb(12, 13, 16)
    private val surface = Color.rgb(21, 23, 29)
    private val surface2 = Color.rgb(28, 31, 38)
    private val line = Color.rgb(48, 52, 62)
    private val accent = Color.rgb(255, 138, 50)
    private val white = Color.rgb(244, 245, 247)
    private val muted = Color.rgb(153, 160, 173)
    private val green = Color.rgb(99, 216, 159)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        presetStore = PresetStore(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        render()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun d(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun text(value: String, size: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = true
            setLineSpacing(d(2f).toFloat(), 1f)
        }

    private fun rounded(color: Int, radius: Float = 20f, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = d(radius).toFloat()
            if (strokeColor != null) setStroke(d(1f), strokeColor)
        }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun vertical(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(value: String): TextView = text(value.uppercase(), 10.5f, muted, true).apply {
        letterSpacing = 0.13f
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, d(height.toFloat()))
    }

    private fun actionButton(title: String, secondary: Boolean = false, onClick: () -> Unit): TextView =
        text(title, 14f, if (secondary) white else Color.rgb(25, 19, 14), true).apply {
            gravity = Gravity.CENTER
            minHeight = d(48f)
            setPadding(d(15f), d(12f), d(15f), d(12f))
            background = rounded(if (secondary) surface2 else accent, 15f, if (secondary) line else null)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                onClick()
            }
        }

    private fun card(): LinearLayout = vertical().apply {
        setPadding(d(16f), d(16f), d(16f), d(16f))
        background = rounded(surface, 22f, line)
    }

    private fun addBlock(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            if (top != 0) topMargin = d(top.toFloat())
            if (bottom != 0) bottomMargin = d(bottom.toFloat())
        })
    }

    private fun foxMark(sizeDp: Int = 42): View = object : View(this) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; style = Paint.Style.FILL }
        private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val p = Path().apply {
                moveTo(w * .5f, h * .96f)
                cubicTo(w * .15f, h * .83f, w * .12f, h * .48f, w * .28f, h * .26f)
                lineTo(w * .19f, h * .02f)
                lineTo(w * .44f, h * .18f)
                lineTo(w * .56f, h * .18f)
                lineTo(w * .81f, h * .02f)
                lineTo(w * .72f, h * .26f)
                cubicTo(w * .88f, h * .48f, w * .85f, h * .83f, w * .5f, h * .96f)
                close()
            }
            canvas.drawPath(p, fill)
            canvas.drawCircle(w * .39f, h * .49f, w * .035f, cut)
            canvas.drawCircle(w * .61f, h * .49f, w * .035f, cut)
            val nose = Path().apply {
                moveTo(w * .45f, h * .61f)
                lineTo(w * .55f, h * .61f)
                lineTo(w * .5f, h * .68f)
                close()
            }
            canvas.drawPath(nose, cut)
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(d(sizeDp.toFloat()), d(sizeDp.toFloat()))
        contentDescription = "SOWZ fox mark"
    }

    private fun render() {
        val shell = vertical().apply { setBackgroundColor(bg) }
        shell.addView(buildHeader(), LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            setPadding(d(18f), d(4f), d(18f), d(20f))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = vertical()
        when (tab) {
            Tab.HOME -> buildHome(content)
            Tab.PROJECTS -> buildProjects(content)
            Tab.PRESETS -> buildPresets(content)
            Tab.INSPECTOR -> buildInspector(content)
            Tab.SETTINGS -> buildSettings(content)
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, -2))
        setContentView(shell)
    }

    private fun buildHeader(): View {
        val header = row().apply {
            setPadding(d(19f), d(17f), d(19f), d(15f))
        }
        header.addView(foxMark(42))
        val titleColumn = vertical().apply {
            setPadding(d(10f), 0, 0, 0)
            addView(text("SOWZ AE STUDIO", 13f, accent, true))
            addView(text(when (tab) {
                Tab.HOME -> "Project workspace"
                Tab.PROJECTS -> "Recent projects"
                Tab.PRESETS -> "Imported FFX presets"
                Tab.INSPECTOR -> "Project inspector"
                Tab.SETTINGS -> "Preferences"
            }, 12f, muted))
        }
        header.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text(if (working) "WORKING" else "0.1", 10f, if (working) green else muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(d(10f), d(8f), d(10f), d(8f))
            background = rounded(surface2, 12f, line)
        })
        return header
    }

    private fun buildHome(parent: LinearLayout) {
        addBlock(parent, label("Motion graphics · mobile inspector"), bottom = 12)
        val hero = card().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(36, 27, 24), Color.rgb(22, 23, 29), surface)).apply {
                cornerRadius = d(24f).toFloat()
                setStroke(d(1f), Color.rgb(91, 54, 35))
            }
            addView(text("YOUR AE PROJECTS.\nON THE GO.", 28f, white, true))
            addView(spacer(10))
            addView(text("Open and inspect After Effects project files right on your phone.", 14f, muted))
            addView(spacer(17))
            addView(row().apply {
                addView(text("AEP", 11f, accent, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.rgb(55, 37, 29), 9f)
                    setPadding(d(10f), d(6f), d(10f), d(6f))
                })
                addView(text("AEPX", 11f, white, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(surface2, 9f, line)
                    setPadding(d(10f), d(6f), d(10f), d(6f))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) }
                })
                addView(text("ZIP", 11f, white, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(surface2, 9f, line)
                    setPadding(d(10f), d(6f), d(10f), d(6f))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = d(8f) }
                })
            })
            addView(spacer(18))
            addView(actionButton(if (working) "Inspecting project…" else "+   Open AE project") { if (!working) pickFile() })
            addView(spacer(9))
            addView(actionButton("Import FFX preset", secondary = true) { if (!working) pickPresetFile() })
        }
        addBlock(parent, hero, bottom = 17)

        val stats = row()
        stats.addView(statCard("RECENT", store.list().size.toString().padStart(2, '0'), "saved entries"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(statCard("CURRENT", if (report != null) "READY" else "IDLE", if (report != null) report!!.format else "no project loaded"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(10f) })
        addBlock(parent, stats, bottom = 17)

        val info = card()
        info.addView(label("Built for creators"))
        info.addView(spacer(8))
        info.addView(text("Inspect first. Render honestly.", 19f, white, true))
        info.addView(spacer(6))
        info.addView(text("SOWZ AE Studio reads project structure and reports what it can recognize. Full Adobe effects, expressions and plugin rendering are not yet implemented.", 13f, muted))
        info.addView(spacer(12))
        info.addView(actionButton("View compatibility notes  →", secondary = true) { tab = Tab.SETTINGS; render() })
        addBlock(parent, info, bottom = 16)

        val last = store.list().firstOrNull()
        if (last != null) {
            val resume = card()
            resume.addView(label("Pick up where you left off"))
            resume.addView(spacer(8))
            resume.addView(text(last.fileName, 16f, white, true))
            resume.addView(spacer(4))
            resume.addView(text(last.summary, 12f, muted))
            resume.addView(spacer(11))
            resume.addView(actionButton("Reopen recent project", secondary = true) { reopen(last.uri) })
            addBlock(parent, resume, bottom = 16)
        }
        errorMessage?.let {
            val err = card()
            err.addView(text("Could not open file", 16f, accent, true))
            err.addView(spacer(6))
            err.addView(text(it, 13f, muted))
            addBlock(parent, err, bottom = 16)
        }
    }

    private fun statCard(title: String, value: String, caption: String): View = card().apply {
        addView(label(title))
        addView(spacer(8))
        addView(text(value, if (value.length > 6) 17f else 24f, white, true))
        addView(spacer(3))
        addView(text(caption, 11f, muted))
    }

    private fun buildProjects(parent: LinearLayout) {
        addBlock(parent, text("Project library", 24f, white, true), bottom = 5)
        addBlock(parent, text("Recent files are kept on this device only.", 13f, muted), bottom = 17)
        val entries = store.list()
        if (entries.isEmpty()) {
            val empty = card()
            empty.addView(text("No projects yet", 18f, white, true))
            empty.addView(spacer(7))
            empty.addView(text("Import an .aep, .aepx or ZIP package to get started.", 13f, muted))
            empty.addView(spacer(14))
            empty.addView(actionButton("+   Open project") { pickFile() })
            addBlock(parent, empty)
        } else {
            entries.forEach { item ->
                val entryCard = card()
                entryCard.addView(row().apply {
                    addView(text("AE", 11f, accent, true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(Color.rgb(55, 37, 29), 12f)
                        setPadding(d(10f), d(10f), d(10f), d(10f))
                    })
                    addView(vertical().apply {
                        setPadding(d(11f), 0, 0, 0)
                        addView(text(item.fileName, 15f, white, true))
                        addView(spacer(3))
                        addView(text(item.format, 11f, accent))
                        addView(spacer(4))
                        addView(text(item.summary, 12f, muted))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                entryCard.addView(spacer(12))
                entryCard.addView(actionButton("Inspect again  →", secondary = true) { reopen(item.uri) })
                addBlock(parent, entryCard, bottom = 11)
            }
            addBlock(parent, actionButton("Clear recent list", secondary = true) {
                AlertDialog.Builder(this).setTitle("Clear recent projects?")
                    .setMessage("This removes saved recent entries from this app. It does not delete your project files.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ -> store.clear(); render() }.show()
            }, bottom = 14)
        }
    }

    private fun buildPresets(parent: LinearLayout) {
        addBlock(parent, text("Preset library", 24f, white, true), bottom = 5)
        addBlock(parent, text("Keep your After Effects .ffx files organized on this device.", 13f, muted), bottom = 16)

        val importCard = card()
        importCard.addView(label("After Effects presets"))
        importCard.addView(spacer(8))
        importCard.addView(text("Import, inspect, keep and export original preset files.", 15f, white, true))
        importCard.addView(spacer(6))
        importCard.addView(text("Files are stored locally. The app does not rewrite or silently convert an FFX preset.", 12f, muted))
        importCard.addView(spacer(13))
        importCard.addView(actionButton(if (working) "Working…" else "+   Import .FFX preset") {
            if (!working) pickPresetFile()
        })
        addBlock(parent, importCard, bottom = 15)

        val entries = presetStore.list()
        val summary = row()
        summary.addView(statCard("PRESETS", entries.size.toString().padStart(2, '0'), "stored locally"), LinearLayout.LayoutParams(0, -2, 1f))
        summary.addView(statCard("SPACE", formatBytes(entries.sumOf { it.sizeBytes }), "preset files"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(10f) })
        addBlock(parent, summary, bottom = 15)

        if (entries.isEmpty()) {
            val empty = card()
            empty.addView(text("Your preset library is empty", 17f, white, true))
            empty.addView(spacer(7))
            empty.addView(text("Tap Import .FFX preset and select a preset exported by After Effects. Files are accepted only when the header matches RIFX / FaFX / head.", 13f, muted))
            addBlock(parent, empty, bottom = 12)
        } else {
            entries.forEach { entry ->
                val preset = card()
                preset.addView(row().apply {
                    addView(text("FFX", 11f, accent, true).apply {
                        gravity = Gravity.CENTER
                        setPadding(d(10f), d(10f), d(10f), d(10f))
                        background = rounded(Color.rgb(55, 37, 29), 12f)
                    })
                    addView(vertical().apply {
                        setPadding(d(11f), 0, 0, 0)
                        addView(text(entry.fileName, 15f, white, true))
                        addView(spacer(3))
                        addView(text(formatBytes(entry.sizeBytes) + "  ·  imported " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(entry.importedAt)), 11f, muted))
                        addView(spacer(4))
                        addView(text("Original file preserved", 11f, green, true))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                preset.addView(spacer(12))
                preset.addView(actionButton("Inspect preset details  →", secondary = true) { inspectPreset(entry) })
                preset.addView(spacer(7))
                preset.addView(actionButton("Export original .FFX", secondary = true) { exportPreset(entry) })
                preset.addView(spacer(7))
                preset.addView(actionButton("Remove from library", secondary = true) {
                    AlertDialog.Builder(this)
                        .setTitle("Remove preset?")
                        .setMessage("This deletes the copy stored inside SOWZ AE Studio. It does not delete any other copy of the file.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            if (presetStore.delete(entry)) Toast.makeText(this, "Preset removed", Toast.LENGTH_SHORT).show()
                            else Toast.makeText(this, "Could not remove the preset", Toast.LENGTH_LONG).show()
                            render()
                        }.show()
                })
                addBlock(parent, preset, bottom = 12)
            }
        }

        errorMessage?.let { message ->
            val error = card()
            error.addView(text("Preset action failed", 16f, accent, true))
            error.addView(spacer(6))
            error.addView(text(message, 12f, muted))
            addBlock(parent, error, bottom = 12)
        }
    }

    private fun pickPresetFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-ffx", "application/binary"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PRESET_IMPORT)
    }

    private fun importPresetUri(uri: Uri) {
        if (working) {
            Toast.makeText(this, "Please wait for the current inspection to finish.", Toast.LENGTH_SHORT).show()
            return
        }
        working = true
        errorMessage = null
        render()
        val sourceName = queryFileName(uri)
        worker.execute {
            try {
                val entry = presetStore.import(contentResolver, uri, sourceName)
                val parsed = FfxParser.inspect(entry.fileName, presetStore.readBytes(entry))
                runOnUiThread {
                    report = parsed
                    working = false
                    errorMessage = null
                    tab = Tab.PRESETS
                    render()
                    Toast.makeText(this, "FFX preset imported and stored locally", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    working = false
                    errorMessage = e.message ?: "Could not import this preset."
                    tab = Tab.PRESETS
                    render()
                }
            }
        }
    }

    private fun inspectPreset(entry: PresetEntry) {
        if (working) return
        working = true
        render()
        worker.execute {
            try {
                val parsed = FfxParser.inspect(entry.fileName, presetStore.readBytes(entry))
                runOnUiThread {
                    report = parsed
                    working = false
                    tab = Tab.INSPECTOR
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    working = false
                    errorMessage = e.message ?: "Could not inspect this preset."
                    tab = Tab.PRESETS
                    render()
                }
            }
        }
    }

    private fun exportPreset(entry: PresetEntry) {
        pendingExportPreset = entry
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, entry.fileName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT_PRESET)
    }

    private fun savePresetTo(uri: Uri) {
        val entry = pendingExportPreset
        pendingExportPreset = null
        if (entry == null) {
            Toast.makeText(this, "No preset selected for export.", Toast.LENGTH_SHORT).show()
            return
        }
        worker.execute {
            try {
                val bytes = presetStore.readBytes(entry)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalArgumentException("Could not write the exported preset.")
                runOnUiThread {
                    Toast.makeText(this, "Original FFX exported without modification", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildInspector(parent: LinearLayout) {
        addBlock(parent, text("Project inspector", 24f, white, true), bottom = 5)
        addBlock(parent, text("The report separates detected data from guesses.", 13f, muted), bottom = 16)
        val value = report
        if (value == null) {
            val panel = card()
            panel.addView(text("Nothing to inspect", 18f, white, true))
            panel.addView(spacer(7))
            panel.addView(text("Choose a project file to inspect its format, XML records or binary chunk inventory.", 13f, muted))
            panel.addView(spacer(14))
            panel.addView(actionButton("Select .AEP / .AEPX") { pickFile() })
            addBlock(parent, panel)
            return
        }

        val hero = card()
        hero.addView(label("Current project"))
        hero.addView(spacer(7))
        hero.addView(text(value.fileName, 19f, white, true))
        hero.addView(spacer(4))
        hero.addView(text(value.format, 12f, accent, true))
        hero.addView(spacer(12))
        val stats = row()
        stats.addView(statCard("FILE SIZE", formatBytes(value.fileSize), "on-device"), LinearLayout.LayoutParams(0, -2, 1f))
        stats.addView(statCard("COMP-LIKE", value.compositionCount.toString(), "recognized records"), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(9f) })
        addBlock(hero, stats, bottom = 12)
        hero.addView(text(value.summary, 13f, muted))
        addBlock(parent, hero, bottom = 12)

        if (value.metadata.isNotEmpty()) {
            val meta = card()
            meta.addView(text("Metadata & readable strings", 17f, white, true))
            meta.addView(spacer(8))
            value.metadata.take(80).forEachIndexed { index, item ->
                if (index > 0) meta.addView(spacer(5))
                meta.addView(text("•  " + item, 12f, if (item.startsWith("Possible cdta")) accent else muted))
            }
            addBlock(parent, meta, bottom = 12)
        }

        if (value.items.isNotEmpty()) {
            val items = card()
            items.addView(text("Recognized items / strings", 17f, white, true))
            items.addView(spacer(8))
            value.items.take(100).forEachIndexed { index, item ->
                if (index > 0) items.addView(spacer(6))
                items.addView(text(item.name, 13f, white, item.type == "Composition" || item.type == "Layer"))
                items.addView(text(item.type + if (item.detail.isBlank()) "" else " · " + item.detail, 10.5f, muted))
            }
            addBlock(parent, items, bottom = 12)
        }

        if (value.chunkCounts.isNotEmpty()) {
            val chunks = card()
            chunks.addView(text("Binary chunk inventory", 17f, white, true))
            chunks.addView(spacer(8))
            value.chunkCounts.toSortedMap().forEach { (tag, count) ->
                chunks.addView(row().apply {
                    addView(text(tag, 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(count.toString(), 12f, accent, true))
                })
                chunks.addView(spacer(5))
            }
            addBlock(parent, chunks, bottom = 12)
        }

        if (value.archiveEntries.isNotEmpty()) {
            val archive = card()
            archive.addView(text("Package entries", 17f, white, true))
            archive.addView(spacer(8))
            value.archiveEntries.take(60).forEach { addBlock(archive, text("•  " + it, 11f, muted), bottom = 3) }
            addBlock(parent, archive, bottom = 12)
        }

        val warnings = card()
        warnings.addView(text("Compatibility & limitations", 17f, white, true))
        warnings.addView(spacer(8))
        value.warnings.forEachIndexed { index, warning ->
            if (index > 0) warnings.addView(spacer(7))
            warnings.addView(text("!  " + warning, 12f, if (warning.startsWith("Binary") || warning.startsWith("This inspector")) accent else muted))
        }
        addBlock(parent, warnings, bottom = 12)
        addBlock(parent, actionButton("Export inspection report", secondary = true) { exportReport() }, bottom = 10)
        addBlock(parent, actionButton("Open another project") { pickFile() }, bottom = 10)
    }

    private fun buildSettings(parent: LinearLayout) {
        addBlock(parent, text("Settings & compatibility", 24f, white, true), bottom = 6)
        addBlock(parent, text("Version 0.1.0 · local-first project inspector", 13f, muted), bottom = 16)
        val compat = card()
        compat.addView(label("Supported intake"))
        compat.addView(spacer(9))
        compat.addView(text("AEP  ·  Binary structure inspection", 14f, white, true))
        compat.addView(spacer(6))
        compat.addView(text("AEPX / AET  ·  Secure XML inspection, heuristic item detection", 14f, white, true))
        compat.addView(spacer(6))
        compat.addView(text("ZIP  ·  Scans package and reports likely media files", 14f, white, true))
        compat.addView(spacer(12))
        compat.addView(text("Not implemented yet", 14f, accent, true))
        compat.addView(spacer(5))
        compat.addView(text("Exact Adobe renderer, full effects/plugins, expression evaluation, media relinking to a working timeline, editing/writing back to AEP, and final video rendering.", 13f, muted))
        addBlock(parent, compat, bottom = 12)

        val privacy = card()
        privacy.addView(label("Privacy"))
        privacy.addView(spacer(8))
        privacy.addView(text("Project bytes are processed locally by the app. No upload or account is required. Recent-project entries and URIs are stored only in app preferences.", 13f, muted))
        addBlock(parent, privacy, bottom = 12)

        val status = card()
        status.addView(label("Development status"))
        status.addView(spacer(8))
        status.addView(text("This is an early working foundation. Validate it against real, user-owned AEP files before claiming deeper compatibility.", 13f, muted))
        status.addView(spacer(12))
        status.addView(actionButton("Open source repository  ↗", secondary = true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pubgcanavar/SOWZ-AE-Studio")))
        })
        addBlock(parent, status)
    }

    private fun buildBottomBar(): View {
        val bar = row().apply {
            setPadding(d(10f), d(8f), d(10f), d(10f))
            background = rounded(Color.rgb(16, 17, 21), 0f)
        }
        listOf(
            Triple(Tab.HOME, "⌂", "Home"),
            Triple(Tab.PROJECTS, "▤", "Projects"),
            Triple(Tab.PRESETS, "✦", "Presets"),
            Triple(Tab.INSPECTOR, "⌕", "Inspect"),
            Triple(Tab.SETTINGS, "⚙", "Settings")
        ).forEach { (target, glyph, title) ->
            val selected = target == tab
            val item = vertical().apply {
                gravity = Gravity.CENTER
                setPadding(d(2f), d(8f), d(2f), d(7f))
                background = if (selected) rounded(Color.rgb(43, 32, 26), 14f) else null
                isClickable = true
                isFocusable = true
                addView(text(glyph, 20f, if (selected) accent else muted, true).apply { gravity = Gravity.CENTER })
                addView(text(title, 10f, if (selected) white else muted, selected).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, d(3f), 0, 0)
                })
                setOnClickListener { tab = target; render() }
            }
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = d(3f)
                rightMargin = d(3f)
            })
        }
        return bar
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/octet-stream", "application/zip", "application/xml", "text/xml", "application/x-zip-compressed"
            ))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_OPEN)
    }

    @Deprecated("Uses Storage Access Framework for compatibility with all target devices.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == REQUEST_OPEN) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            openUri(uri)
        } else if (requestCode == REQUEST_PRESET_IMPORT) {
            importPresetUri(uri)
        } else if (requestCode == REQUEST_SAVE_REPORT) {
            saveReportTo(uri)
        } else if (requestCode == REQUEST_EXPORT_PRESET) {
            savePresetTo(uri)
        }
    }

    private fun openUri(uri: Uri) {
        if (working) {
            Toast.makeText(this, "A project is already being inspected.", Toast.LENGTH_SHORT).show()
            return
        }
        errorMessage = null
        val name = queryFileName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "ffx") { importPresetUri(uri); return }
        if (ext !in setOf("aep", "aepx", "aet", "zip")) {
            errorMessage = "Unsupported extension ." + ext + ". Choose an .aep, .aepx, .aet or .zip file."
            tab = Tab.HOME
            render()
            return
        }
        working = true
        tab = Tab.HOME
        render()
        worker.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        total += n
                        if (total > 512L * 1024L * 1024L) throw IllegalArgumentException("File exceeds the 512 MB limit.")
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("Could not open this document. Try downloading it locally first.")
                val parsed = AepParser.parse(name, bytes)
                runOnUiThread {
                    report = parsed
                    store.add(parsed, uri.toString())
                    working = false
                    tab = Tab.INSPECTOR
                    render()
                    Toast.makeText(this, "Inspection complete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    working = false
                    errorMessage = e.message ?: "Unknown file error."
                    tab = Tab.HOME
                    render()
                }
            }
        }
    }

    private fun reopen(uriText: String) {
        try {
            openUri(Uri.parse(uriText))
        } catch (_: Exception) {
            errorMessage = "The original document is no longer accessible. Pick it again from the file browser."
            tab = Tab.HOME
            render()
        }
    }

    private fun queryFileName(uri: Uri): String {
        var result = uri.lastPathSegment?.substringAfterLast('/') ?: "Project"
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index) ?: result
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return result
    }

    private fun exportReport() {
        val value = report ?: return
        val title = value.fileName.substringBeforeLast('.') + "-SOWZ-report.txt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, title)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_SAVE_REPORT)
    }

    private fun saveReportTo(uri: Uri) {
        val value = report ?: return
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(value.toReportText())
            } ?: throw IllegalArgumentException("Could not create the report file.")
            Toast.makeText(this, "Report exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Could not export report", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(value: Long): String = when {
        value < 1024L -> value.toString() + " B"
        value < 1024L * 1024L -> String.format("%.1f KB", value / 1024.0)
        else -> String.format("%.1f MB", value / (1024.0 * 1024.0))
    }

    companion object {
        private const val REQUEST_OPEN = 7201
        private const val REQUEST_SAVE_REPORT = 7202
        private const val REQUEST_PRESET_IMPORT = 7203
        private const val REQUEST_EXPORT_PRESET = 7204
    }
}
