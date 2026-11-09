package com.sowz.aestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sowz.aestudio.core.PresetEntry
import com.sowz.aestudio.core.PresetStore
import java.text.DecimalFormat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Native SOWZ AE home and preset library. */
class MainActivity : Activity() {
    private enum class Tab { HOME, STUDIO, PRESETS, SETTINGS }
    private companion object {
        const val REQUEST_IMPORT_FFX = 4601
        const val REQUEST_EXPORT_FFX = 4602
        const val EXTRA_FFX_PRESET_NAME = "ffx_preset_name"
    }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var presetStore: PresetStore
    private var tab = Tab.HOME
    private var working = false
    private var message: String? = null
    private var pendingExport: PresetEntry? = null
    private val bg = Color.rgb(12, 13, 16)
    private val surface = Color.rgb(21, 23, 29)
    private val surface2 = Color.rgb(29, 32, 39)
    private val border = Color.rgb(48, 52, 62)
    private val accent = Color.rgb(255, 138, 50)
    private val white = Color.rgb(244, 245, 247)
    private val muted = Color.rgb(153, 160, 173)
    private val green = Color.rgb(99, 216, 159)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
            setLineSpacing(d(2f).toFloat(), 1f)
        }
    private fun rounded(color: Int, radius: Float = 20f, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = d(radius).toFloat()
            if (stroke != null) setStroke(d(1f), stroke)
        }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun card() = vertical().apply {
        setPadding(d(16f), d(16f), d(16f), d(16f))
        background = rounded(surface, 22f, border)
    }
    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, d(height.toFloat()))
    }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            if (top > 0) topMargin = d(top.toFloat())
            if (bottom > 0) bottomMargin = d(bottom.toFloat())
        })
    }
    private fun label(value: String) = text(value.uppercase(), 10.5f, muted, true).apply {
        letterSpacing = 0.13f
    }
    private fun button(title: String, secondary: Boolean = false, onClick: () -> Unit): TextView =
        text(title, 14f, if (secondary) white else Color.rgb(25, 19, 14), true).apply {
            gravity = Gravity.CENTER
            minHeight = d(48f)
            setPadding(d(15f), d(12f), d(15f), d(12f))
            background = rounded(if (secondary) surface2 else accent, 15f, if (secondary) border else null)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(65L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(85L).start()
                }.start()
                onClick()
            }
        }

    private fun render() {
        val shell = vertical().apply { setBackgroundColor(bg) }
        shell.addView(buildHeader(), LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            setPadding(d(17f), d(5f), d(17f), d(18f))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = vertical()
        when (tab) {
            Tab.HOME -> buildHome(content)
            Tab.STUDIO -> buildStudio(content)
            Tab.PRESETS -> buildPresets(content)
            Tab.SETTINGS -> buildSettings(content)
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        shell.addView(buildBottomBar(), LinearLayout.LayoutParams(-1, -2))
        setContentView(shell)
    }

    private fun buildHeader(): View {
        val header = row().apply { setPadding(d(18f), d(15f), d(18f), d(13f)) }
        header.addView(text("S", 21f, Color.rgb(24, 18, 13), true).apply {
            gravity = Gravity.CENTER
            background = rounded(accent, 14f)
            layoutParams = LinearLayout.LayoutParams(d(42f), d(42f))
        })
        header.addView(vertical().apply {
            setPadding(d(11f), 0, 0, 0)
            addView(text("SOWZ AE", 13f, accent, true))
            addView(text(when (tab) {
                Tab.HOME -> "Creative workspace"
                Tab.STUDIO -> "Studio tools"
                Tab.PRESETS -> "After Effects presets"
                Tab.SETTINGS -> "App preferences"
            }, 12f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text(if (working) "SAVING" else "1.5", 10f, if (working) green else muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(d(10f), d(8f), d(10f), d(8f))
            background = rounded(surface2, 12f, border)
        })
        return header
    }

    private fun buildHome(parent: LinearLayout) {
        add(parent, label("MOBILE MOTION WORKSPACE"), bottom = 11)
        val hero = card().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(52, 34, 26), Color.rgb(27, 25, 29), Color.rgb(20, 22, 28))
            ).apply {
                cornerRadius = d(25f).toFloat()
                setStroke(d(1f), Color.rgb(91, 57, 39))
            }
            add(this, text("MAKE IT MOVE.", 28f, white, true))
            add(this, spacer(8))
            add(this, text("A native editing workspace for video, colour, audio and reusable presets.", 14f, muted))
            add(this, spacer(18))
            add(this, button("＋   Create new project") { openEditor() })
            add(this, spacer(8))
            add(this, button("Open saved project  →", true) { openEditor(openProject = true) })
        }
        add(parent, hero, bottom = 15)

        val shortcuts = row()
        shortcuts.addView(quickCard("01", "EDIT", "Video & timeline") { tab = Tab.STUDIO; render() },
            LinearLayout.LayoutParams(0, -2, 1f))
        shortcuts.addView(quickCard("02", "FFX", "Preset library") { tab = Tab.PRESETS; render() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(9f) })
        shortcuts.addView(quickCard("03", "CC", "Colour tools") { openEditor() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(9f) })
        add(parent, shortcuts, bottom = 15)

        val features = card()
        add(features, label("IN THIS WORKSPACE"))
        add(features, spacer(10))
        add(features, featureRow("01", "Video editing", "Preview, trim, reorder clips and export MP4."), bottom = 12)
        add(features, featureRow("02", "Colour controls", "Brightness, contrast, HSL, blur, rotation and .cube LUT."), bottom = 12)
        add(features, featureRow("03", "Audio", "Control source volume and add a separate music track for export."), bottom = 12)
        add(features, featureRow("04", "FFX library", "Preserve original presets and apply the static values SOWZ supports."))
        add(parent, features, bottom = 14)

        message?.let {
            val notice = card()
            add(notice, text("NOTICE", 11f, accent, true))
            add(notice, spacer(5))
            add(notice, text(it, 13f, muted))
            add(parent, notice)
        }
    }

    private fun quickCard(number: String, title: String, subtitle: String, onClick: () -> Unit): View =
        card().apply {
            setPadding(d(11f), d(13f), d(11f), d(13f))
            add(this, text(number, 10f, accent, true))
            add(this, spacer(9))
            add(this, text(title, 14f, white, true))
            add(this, text(subtitle, 10.5f, muted))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun featureRow(number: String, title: String, description: String): View = row().apply {
        addView(text(number, 11f, accent, true).apply {
            gravity = Gravity.CENTER
            background = rounded(surface2, 10f)
            setPadding(d(9f), d(7f), d(9f), d(7f))
        })
        addView(vertical().apply {
            setPadding(d(11f), 0, 0, 0)
            addView(text(title, 14f, white, true))
            addView(text(description, 11.5f, muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun buildStudio(parent: LinearLayout) {
        add(parent, text("Studio", 25f, white, true), bottom = 5)
        add(parent, text("These tools open the native SOWZ editor. No separate website or desktop layout is loaded.", 13f, muted), bottom = 14)
        val editor = card()
        add(editor, label("NATIVE EDITOR"))
        add(editor, spacer(8))
        add(editor, text("Video workspace", 20f, white, true))
        add(editor, spacer(5))
        add(editor, text("Import footage, preview, trim, assemble a sequential cut, adjust colour and audio, then export an MP4.", 13f, muted))
        add(editor, spacer(14))
        add(editor, button("Open SOWZ editor  →") { openEditor() })
        add(editor, spacer(8))
        add(editor, button("Open saved .sowzproj", true) { openEditor(openProject = true) })
        add(parent, editor, bottom = 13)
        val notes = card()
        add(notes, label("CURRENTLY WORKING TOOLS"))
        add(notes, spacer(8))
        add(notes, featureRow("A", "Preview & trim", "Source preview and IN / OUT range controls."), bottom = 11)
        add(notes, featureRow("B", "Clip sequence", "Add clips, reorder, duplicate, remove, undo and redo."), bottom = 11)
        add(notes, featureRow("C", "Colour & overlays", "Native GPU colour effects, text overlay and transform."), bottom = 11)
        add(notes, featureRow("D", "Export", "Render the current sequence to MP4."))
        add(parent, notes)
    }

    private fun buildPresets(parent: LinearLayout) {
        val entries = presetStore.list()
        add(parent, text("FFX Presets", 25f, white, true), bottom = 5)
        add(parent, text("Store original .ffx files here. Apply a preset from this library inside the native editor.", 13f, muted), bottom = 14)
        val import = card()
        add(import, label("PRESET LIBRARY"))
        add(import, spacer(7))
        add(import, text("${entries.size} saved preset(s)", 18f, white, true))
        add(import, spacer(5))
        add(import, text("Files stay on this device. Importing a preset does not mean every AE effect can be reproduced by Android.", 12f, muted))
        add(import, spacer(12))
        add(import, button(if (working) "Importing…" else "＋   Import .FFX preset") {
            if (!working) pickFfxPreset()
        })
        add(parent, import, bottom = 14)
        if (entries.isEmpty()) {
            val empty = card()
            add(empty, text("No presets yet", 17f, white, true))
            add(empty, spacer(6))
            add(empty, text("Import an After Effects .ffx file. SOWZ will preserve the original and apply only effects it can map to native controls.", 13f, muted))
            add(parent, empty)
            return
        }
        entries.forEach { entry ->
            val item = card()
            add(item, row().apply {
                addView(text("FFX", 11f, accent, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.rgb(53, 37, 29), 10f)
                    setPadding(d(10f), d(8f), d(10f), d(8f))
                })
                addView(vertical().apply {
                    setPadding(d(11f), 0, 0, 0)
                    addView(text(entry.fileName.take(42), 14f, white, true).apply { maxLines = 2 })
                    addView(text(formatBytes(entry.sizeBytes) + " · saved locally", 11f, muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            add(item, spacer(11))
            add(item, button("Apply in editor  →") { openEditor(presetName = entry.fileName) })
            add(item, spacer(7))
            val actions = row()
            actions.addView(button("Export original", true) { exportPreset(entry) },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Delete", true) { confirmDelete(entry) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = d(8f) })
            add(item, actions)
            add(parent, item, bottom = 10)
        }
    }

    private fun buildSettings(parent: LinearLayout) {
        add(parent, text("Settings", 25f, white, true), bottom = 5)
        add(parent, text("App information and honest format support.", 13f, muted), bottom = 14)
        val engine = card()
        add(engine, label("RENDER ENGINE"))
        add(engine, spacer(8))
        add(engine, text("Native Android · Media3", 17f, white, true))
        add(engine, spacer(5))
        add(engine, text("The editor runs inside the Android app. The previous embedded EffectCraft web screen is no longer part of the workflow.", 12f, muted))
        add(parent, engine, bottom = 12)
        val formats = card()
        add(formats, label("FORMAT SUPPORT"))
        add(formats, spacer(8))
        add(formats, text("Video / audio", 14f, white, true))
        add(formats, text("Depends on Android device codecs; export is MP4.", 12f, muted), bottom = 10)
        add(formats, text("CC .cube", 14f, white, true))
        add(formats, text("Standard 3D LUT import is supported.", 12f, muted), bottom = 10)
        add(formats, text("After Effects .ffx", 14f, white, true))
        add(formats, text("Original file storage and effect-name decoding; only static Brightness & Contrast values currently map to SOWZ controls. Other effects are not applied.", 12f, muted), bottom = 10)
        add(formats, text("After Effects .aep / .aex", 14f, white, true))
        add(formats, text("Not supported as editable Android projects or runnable desktop plug-ins. SOWZ no longer shows an AEP inspection report as if it were an editing workflow.", 12f, muted))
        add(parent, formats, bottom = 12)
        val privacy = card()
        add(privacy, label("ON-DEVICE STORAGE"))
        add(privacy, spacer(7))
        add(privacy, text("Imported FFX presets and SOWZ project settings are stored locally. Source media is referenced, not silently uploaded.", 12f, muted))
        add(parent, privacy)
    }

    private fun buildBottomBar(): View {
        val bar = row().apply {
            setPadding(d(9f), d(7f), d(9f), d(8f))
            background = rounded(Color.rgb(16, 17, 21), 0f)
        }
        listOf(
            Triple(Tab.HOME, "⌂", "Home"),
            Triple(Tab.STUDIO, "▣", "Studio"),
            Triple(Tab.PRESETS, "✦", "FFX"),
            Triple(Tab.SETTINGS, "⚙", "Settings")
        ).forEach { (target, glyph, title) ->
            val selected = target == tab
            val item = vertical().apply {
                gravity = Gravity.CENTER
                setPadding(d(2f), d(7f), d(2f), d(6f))
                background = if (selected) rounded(Color.rgb(43, 32, 26), 14f) else null
                addView(text(glyph, 19f, if (selected) accent else muted, true).apply { gravity = Gravity.CENTER })
                addView(text(title, 10f, if (selected) white else muted, selected).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, d(3f), 0, 0)
                })
                isClickable = true
                isFocusable = true
                setOnClickListener { tab = target; render() }
            }
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = d(3f); rightMargin = d(3f)
            })
        }
        return bar
    }

    private fun openEditor(openProject: Boolean = false, presetName: String? = null) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            if (openProject) putExtra("open_project_picker", true)
            if (!presetName.isNullOrBlank()) putExtra(EXTRA_FFX_PRESET_NAME, presetName)
        })
    }

    private fun pickFfxPreset() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/x-ffx", "application/binary"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMPORT_FFX)
    }

    @Deprecated("Storage Access Framework is used for broad document-provider compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            REQUEST_IMPORT_FFX -> importPreset(data.data!!)
            REQUEST_EXPORT_FFX -> savePreset(data.data!!)
        }
    }

    private fun importPreset(uri: Uri) {
        if (working) return
        working = true
        message = null
        render()
        val name = queryDisplayName(uri)
        worker.execute {
            try {
                presetStore.import(contentResolver, uri, name)
                runOnUiThread {
                    working = false
                    tab = Tab.PRESETS
                    message = null
                    render()
                    Toast.makeText(this, "FFX preset saved to the local library.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    working = false
                    tab = Tab.PRESETS
                    message = error.message ?: "Could not import this FFX preset."
                    render()
                }
            }
        }
    }

    private fun exportPreset(entry: PresetEntry) {
        pendingExport = entry
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, entry.fileName)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT_FFX)
    }

    private fun savePreset(uri: Uri) {
        val entry = pendingExport
        pendingExport = null
        if (entry == null) return
        working = true
        render()
        worker.execute {
            try {
                val bytes = presetStore.readBytes(entry)
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalArgumentException("The selected destination cannot be written.")
                runOnUiThread {
                    working = false
                    render()
                    Toast.makeText(this, "Original FFX file exported.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    working = false
                    message = error.message ?: "Could not export this preset."
                    render()
                }
            }
        }
    }

    private fun confirmDelete(entry: PresetEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete preset?")
            .setMessage("This removes ${entry.fileName} from the SOWZ AE local library. Other copies are not deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (presetStore.delete(entry)) {
                    message = null
                    render()
                    Toast.makeText(this, "Preset removed.", Toast.LENGTH_SHORT).show()
                } else {
                    message = "Could not remove the preset."
                    render()
                }
            }.show()
    }

    private fun queryDisplayName(uri: Uri): String {
        var result = "After Effects Preset.ffx"
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index) ?: result
                }
            }
        return result
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return DecimalFormat("0.#").format(kb) + " KB"
        return DecimalFormat("0.##").format(kb / 1024.0) + " MB"
    }
}
