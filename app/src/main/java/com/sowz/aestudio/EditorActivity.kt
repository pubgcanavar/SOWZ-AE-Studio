package com.sowz.aestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Native video editing foundation: sequential clips, trim, text/color effects, transforms, saved projects and MP4 export.
 * This is intentionally a working foundation, not a claim of full After Effects compatibility.
 */
@OptIn(UnstableApi::class)
class EditorActivity : Activity() {
    private companion object {
        const val REQUEST_IMPORT_VIDEO = 2101
        const val REQUEST_EXPORT_FILE = 2102
        const val REQUEST_SAVE_PROJECT = 2103
        const val REQUEST_OPEN_PROJECT = 2104
        const val REQUEST_ADD_CLIPS = 2105
        const val SCALE_MAX = 1000
        const val MIN_CLIP_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private var sourceUri: Uri? = null
    private val additionalClipUris = mutableListOf<Uri>()
    private var pendingExportUri: Uri? = null
    private var temporaryOutput: File? = null
    private var durationMs = 0L
    private var clipStartMs = 0L
    private var clipEndMs = 0L
    private var restoreStartMs: Long? = null
    private var restoreEndMs: Long? = null
    private var brightnessValue = 0f
    private var contrastValue = 0f
    private var rotationDegrees = 0
    private var overlayText = ""
    private var exportClipCount = 1
    private var draggingScrubber = false

    private lateinit var playerView: PlayerView
    private lateinit var scrubber: SeekBar
    private lateinit var inPoint: SeekBar
    private lateinit var outPoint: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var status: TextView
    private lateinit var trimSummary: TextView
    private lateinit var clipQueueSummary: TextView
    private lateinit var removeLastClipButton: TextView
    private lateinit var brightnessSeek: SeekBar
    private lateinit var contrastSeek: SeekBar
    private lateinit var effectSummary: TextView
    private lateinit var rotationSummary: TextView
    private val rotationButtons = mutableMapOf<Int, TextView>()
    private lateinit var textOverlaySummary: TextView
    private lateinit var exportButton: TextView

    private val syncPlayback = object : Runnable {
        override fun run() {
            val activePlayer = player
            if (activePlayer != null && durationMs > 0) {
                if (!draggingScrubber) {
                    scrubber.progress = ((activePlayer.currentPosition.coerceAtLeast(0L).toDouble() /
                        durationMs.toDouble()) * SCALE_MAX).toInt().coerceIn(0, SCALE_MAX)
                }
                playbackTime.text = "${formatTime(activePlayer.currentPosition)} / ${formatTime(durationMs)}"
            }
            handler.postDelayed(this, 200L)
        }
    }

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
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        handler.post(syncPlayback)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(syncPlayback)
        handler.post(syncPlayback)
    }

    override fun onPause() {
        handler.removeCallbacks(syncPlayback)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncPlayback)
        transformer?.cancel()
        playerView.player = null
        player?.release()
        player = null
        temporaryOutput?.delete()
        super.onDestroy()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16f), dp(15f), dp(16f), dp(15f))
        background = rounded(surface, 20f, border)
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value.uppercase(Locale.ROOT)
        textSize = 10.5f
        letterSpacing = 0.12f
        setTextColor(muted)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun body(value: String, size: Float = 14f, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
            setLineSpacing(dp(2f).toFloat(), 1f)
        }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1f), stroke)
        }

    private fun button(title: String, secondary: Boolean = false, onClick: () -> Unit): TextView =
        body(title, 14f, if (secondary) white else Color.rgb(25, 19, 14), true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = rounded(if (secondary) surface2 else accent, 14f, if (secondary) border else null)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(70L).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90L).start()
                }.start()
                onClick()
            }
        }

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp.toFloat()))
    }

    private fun add(parent: LinearLayout, child: View, bottom: Int = 0, top: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            if (top > 0) topMargin = dp(top.toFloat())
            if (bottom > 0) bottomMargin = dp(bottom.toFloat())
        })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19f), dp(17f), dp(19f), dp(13f))
        }
        add(header, body("SOWZ  /  MOTION", 12f, accent, true))
        add(header, body("Video editor", 24f, white, true), top = 3)
        add(header, body("Import • trim • preview • export", 12f, muted), top = 3)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            setPadding(dp(16f), dp(3f), dp(16f), dp(22f))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val sourcePanel = panel()
        add(sourcePanel, label("Media source"))
        add(sourcePanel, space(7))
        sourceLabel = body("No video imported yet", 14f, white, true)
        add(sourcePanel, sourceLabel)
        add(sourcePanel, body("Local video files stay on this device. Select a clip to start.", 12f, muted), top = 5)
        add(sourcePanel, button("＋  Import video", secondary = false) { pickVideo() }, top = 13)
        add(sourcePanel, button("Open saved project (.sowzproj)", secondary = true) { pickProject() }, top = 8)
        add(content, sourcePanel, bottom = 13)

        val previewPanel = panel()
        add(previewPanel, label("Preview"))
        add(previewPanel, space(9))
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, dp(235f))
        }
        previewPanel.addView(playerView)
        playbackTime = body("00:00.000 / 00:00.000", 11f, muted)
        add(previewPanel, playbackTime, top = 9)
        add(previewPanel, label("Playhead"), top = 12)
        scrubber = SeekBar(this).apply {
            max = SCALE_MAX
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && durationMs > 0) {
                        val position = durationMs * progress / SCALE_MAX
                        playbackTime.text = "${formatTime(position)} / ${formatTime(durationMs)}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    draggingScrubber = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (durationMs > 0) player?.seekTo(durationMs * scrubber.progress / SCALE_MAX)
                    draggingScrubber = false
                }
            })
        }
        add(previewPanel, scrubber)
        add(content, previewPanel, bottom = 13)

        val trimPanel = panel()
        add(trimPanel, label("Timeline · trim range"))
        add(trimPanel, body("Move the IN and OUT handles to choose the section to export.", 12f, muted), top = 5)
        add(trimPanel, label("IN point"), top = 14)
        inPoint = SeekBar(this).apply {
            max = SCALE_MAX
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || durationMs <= 0) return
                    val gap = minGapProgress()
                    val safe = progress.coerceAtMost((outPoint.progress - gap).coerceAtLeast(0))
                    if (safe != progress) inPoint.progress = safe
                    updateTrimUi()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(trimPanel, inPoint)
        add(trimPanel, label("OUT point"), top = 7)
        outPoint = SeekBar(this).apply {
            max = SCALE_MAX
            progress = SCALE_MAX
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || durationMs <= 0) return
                    val gap = minGapProgress()
                    val safe = progress.coerceAtLeast((inPoint.progress + gap).coerceAtMost(SCALE_MAX))
                    if (safe != progress) outPoint.progress = safe
                    updateTrimUi()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(trimPanel, outPoint)
        trimSummary = body("IN 00:00.000   ·   OUT 00:00.000   ·   0.0 s", 12f, accent, true)
        add(trimPanel, trimSummary, top = 8)
        add(content, trimPanel, bottom = 13)

        val timelinePanel = panel()
        add(timelinePanel, label("Timeline sequence"))
        add(timelinePanel, body("The main clip plays first. Add videos to play after it in order; the selected trim range applies to the main clip and added clips use their full duration.", 12f, muted), top = 5)
        clipQueueSummary = body("Main clip: none", 13f, white, true)
        add(timelinePanel, clipQueueSummary, top = 9)
        add(timelinePanel, button("＋  Add videos to final cut") { pickAdditionalClips() }, top = 10)
        removeLastClipButton = button("Remove last added clip", secondary = true) {
            if (additionalClipUris.isNotEmpty()) {
                additionalClipUris.removeAt(additionalClipUris.lastIndex)
                refreshClipQueueSummary()
            }
        }
        add(timelinePanel, removeLastClipButton, top = 8)
        refreshClipQueueSummary()
        add(content, timelinePanel, bottom = 13)

        val effectsPanel = panel()
        add(effectsPanel, label("Color effects"))
        add(effectsPanel, body("Brightness and contrast run on the exported frames and are previewed with the same Media3 effects.", 12f, muted), top = 5)
        add(effectsPanel, label("Brightness"), top = 12)
        brightnessSeek = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateColorValues()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, brightnessSeek)
        add(effectsPanel, label("Contrast"), top = 5)
        contrastSeek = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateColorValues()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, contrastSeek)
        effectSummary = body("Brightness 0.00  ·  Contrast 0.00", 12f, accent, true)
        add(effectsPanel, effectSummary, top = 7)
        add(effectsPanel, button("Reset color controls", secondary = true) {
            brightnessSeek.progress = 100
            contrastSeek.progress = 100
            updateColorValues()
        }, top = 9)
        add(content, effectsPanel, bottom = 13)

        val transformPanel = panel()
        add(transformPanel, label("Transform"))
        add(transformPanel, body("Rotate the source before rendering. Preview and export use the same transform.", 12f, muted), top = 5)
        rotationSummary = body("Rotation: 0°", 12f, accent, true)
        add(transformPanel, rotationSummary, top = 10)
        val rotationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(0, 90, 180, 270).forEachIndexed { index, degrees ->
            val control = button("${degrees}°", secondary = degrees != rotationDegrees) { setRotation(degrees) }
            rotationButtons[degrees] = control
            rotationRow.addView(control, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) leftMargin = dp(6f)
            })
        }
        transformPanel.addView(rotationRow)
        refreshRotationControls()
        add(content, transformPanel, bottom = 13)

        val textPanel = panel()
        add(textPanel, label("Text overlay"))
        add(textPanel, body("Add a title or watermark. It is rendered into the preview and exported MP4.", 12f, muted), top = 5)
        textOverlaySummary = body("No text overlay", 13f, white, true)
        add(textPanel, textOverlaySummary, top = 9)
        add(textPanel, button("Add / edit text") { editTextOverlay() }, top = 10)
        add(textPanel, button("Remove text overlay", secondary = true) {
            overlayText = ""
            refreshTextOverlay()
            player?.setVideoEffects(buildVideoEffects())
        }, top = 8)
        refreshTextOverlay()
        add(content, textPanel, bottom = 13)

        val exportPanel = panel()
        add(exportPanel, label("Project and export"))
        add(exportPanel, body("Save your editable trim points and source reference, or render the selected range to MP4.", 12f, muted), top = 5)
        add(exportPanel, button("Save project (.sowzproj)", secondary = true) { startProjectSavePicker() }, top = 10)
        status = body("Ready when a video is imported.", 12f, green)
        add(exportPanel, status, top = 11)
        exportButton = button("Export trimmed MP4") { startExportPicker() }
        add(exportPanel, exportButton, top = 10)
        add(exportPanel, button("Cancel export", secondary = true) { cancelExport() }, top = 8)
        add(content, exportPanel, bottom = 15)

        add(content, body("Current milestone: real single-clip trim/export. Multi-layer compositing, text/shape layers, AE .ffx effect translation and advanced effects are separate stages and are not falsely marked as complete.", 11f, muted))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun pickVideo() {
        restoreStartMs = null
        restoreEndMs = null
        additionalClipUris.clear()
        refreshClipQueueSummary()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_VIDEO)
    }

    private fun pickAdditionalClips() {
        if (sourceUri == null) {
            toast("Import the main video first.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ADD_CLIPS)
    }

    private fun addAdditionalClips(data: Intent?) {
        val main = sourceUri
        val flags = (data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
        val incoming = mutableListOf<Uri>()
        data?.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) {
                clips.getItemAt(index)?.uri?.let { incoming.add(it) }
            }
        }
        if (incoming.isEmpty()) data?.data?.let { incoming.add(it) }
        if (incoming.isEmpty()) return

        var added = 0
        for (uri in incoming) {
            if (uri == main || uri in additionalClipUris) continue
            if (additionalClipUris.size >= 20) break
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Some providers only issue temporary grants; the current session may still read them.
            }
            additionalClipUris.add(uri)
            added++
        }
        refreshClipQueueSummary()
        toast(if (added > 0) "Added $added clip(s) to the final cut." else "No new clips were added.")
    }

    private fun refreshClipQueueSummary() {
        if (!::clipQueueSummary.isInitialized) return
        val mainName = if (::sourceLabel.isInitialized && sourceLabel.text.isNotBlank()) {
            sourceLabel.text.toString()
        } else "Main clip"
        val shownNames = additionalClipUris.take(4).map { queryDisplayName(it) }
        val more = additionalClipUris.size - shownNames.size
        clipQueueSummary.text = buildString {
            append("01  ").append(mainName)
            shownNames.forEachIndexed { index, name ->
                append("\n").append(String.format(Locale.ROOT, "%02d", index + 2)).append("  ").append(name)
            }
            if (more > 0) append("\n… and ").append(more).append(" more")
            append("\nTotal clips: ").append(1 + additionalClipUris.size)
        }
        if (::removeLastClipButton.isInitialized) {
            removeLastClipButton.isEnabled = additionalClipUris.isNotEmpty()
            removeLastClipButton.alpha = if (additionalClipUris.isNotEmpty()) 1f else 0.45f
        }
    }

    private fun startExportPicker() {
        if (sourceUri == null || durationMs <= 0L) {
            toast("Import a video and wait for it to finish loading first.")
            return
        }
        if (clipEndMs <= clipStartMs) {
            toast("Choose a valid IN and OUT range.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, "SOWZ-edit.mp4")
        }
        startActivityForResult(intent, REQUEST_EXPORT_FILE)
    }

    @Deprecated("Uses Activity result callbacks for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT_VIDEO -> {
                val uri = data?.data ?: return
                loadVideo(uri, data.flags)
            }
            REQUEST_EXPORT_FILE -> {
                pendingExportUri = data?.data ?: return
                beginExport()
            }
            REQUEST_SAVE_PROJECT -> saveProject(data?.data ?: return)
            REQUEST_OPEN_PROJECT -> loadProject(data?.data ?: return)
            REQUEST_ADD_CLIPS -> addAdditionalClips(data)
        }
    }

    private fun pickProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_PROJECT)
    }

    private fun startProjectSavePicker() {
        if (sourceUri == null || durationMs <= 0L || clipEndMs <= clipStartMs) {
            toast("Import a video and choose a valid trim range before saving a project.")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "SOWZ-project.sowzproj")
        }
        startActivityForResult(intent, REQUEST_SAVE_PROJECT)
    }

    private fun saveProject(destination: Uri) {
        try {
            val source = sourceUri ?: error("No source video is loaded.")
            if (durationMs <= 0L || clipEndMs <= clipStartMs) error("The current trim range is invalid.")
            val document = org.json.JSONObject()
                .put("kind", "SOWZ-AE-STUDIO-PROJECT")
                .put("schemaVersion", 1)
                .put("projectName", sourceLabel.text.toString().substringBeforeLast('.'))
                .put("sourceUri", source.toString())
                .put("sourceDisplayName", sourceLabel.text.toString())
                .put("sourceDurationMs", durationMs)
                .put("trimStartMs", clipStartMs)
                .put("trimEndMs", clipEndMs)
                .put("brightness", brightnessValue.toDouble())
                .put("contrast", contrastValue.toDouble())
                .put("rotationDegrees", rotationDegrees)
                .put("textOverlay", overlayText)
                .put("additionalClipUris", org.json.JSONArray().apply {
                    additionalClipUris.forEach { put(it.toString()) }
                })
                .put("frameRate", 30)
                .put("createdWith", "SOWZ AE Studio 0.4.0")
                .toString(2)
            val output = contentResolver.openOutputStream(destination, "w")
                ?: error("Could not open the project destination.")
            output.use { it.write(document.toByteArray(Charsets.UTF_8)) }
            status.text = "Project saved · ${formatTime(clipStartMs)}–${formatTime(clipEndMs)}"
            status.setTextColor(green)
            toast("SOWZ project saved.")
        } catch (error: Exception) {
            status.text = "Could not save project: ${error.message ?: "unknown error"}"
            status.setTextColor(accent)
        }
    }

    private fun loadProject(projectUri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(projectUri)?.use { input ->
                val result = input.readBytes()
                if (result.size > 1_048_576) error("Project file exceeds the 1 MB safety limit.")
                result
            } ?: error("Could not read the selected project.")
            val document = org.json.JSONObject(String(bytes, Charsets.UTF_8))
            if (document.optString("kind") != "SOWZ-AE-STUDIO-PROJECT") {
                error("This is not a SOWZ .sowzproj file.")
            }
            if (document.optInt("schemaVersion", 0) != 1) {
                error("This project version is not supported by this build.")
            }
            val sourceString = document.optString("sourceUri")
            if (sourceString.isBlank()) error("The project has no source media reference.")
            additionalClipUris.clear()
            val savedClips = document.optJSONArray("additionalClipUris")
            if (savedClips != null) {
                for (index in 0 until min(savedClips.length(), 20)) {
                    val clipValue = savedClips.optString(index)
                    if (clipValue.isBlank() || clipValue == sourceString) continue
                    val clipUri = Uri.parse(clipValue)
                    if (clipUri !in additionalClipUris) additionalClipUris.add(clipUri)
                }
            }
            refreshClipQueueSummary()
            val start = document.optLong("trimStartMs", 0L)
            val end = document.optLong("trimEndMs", 0L)
            if (start < 0L || end <= start) error("The project contains an invalid trim range.")
            restoreStartMs = start
            restoreEndMs = end
            brightnessValue = document.optDouble("brightness", 0.0).toFloat().coerceIn(-1f, 1f)
            contrastValue = document.optDouble("contrast", 0.0).toFloat().coerceIn(-1f, 1f)
            rotationDegrees = document.optInt("rotationDegrees", 0).let { if (it in setOf(0, 90, 180, 270)) it else 0 }
            overlayText = document.optString("textOverlay", "").take(120)
            brightnessSeek.progress = ((brightnessValue + 1f) * 100f).toInt().coerceIn(0, 200)
            contrastSeek.progress = ((contrastValue + 1f) * 100f).toInt().coerceIn(0, 200)
            updateColorValues()
            refreshRotationControls()
            refreshTextOverlay()
            loadVideo(Uri.parse(sourceString), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            status.text = "Project loaded · opening source media…"
            status.setTextColor(accent)
        } catch (error: Exception) {
            restoreStartMs = null
            restoreEndMs = null
            status.text = "Could not open project: ${error.message ?: "unknown error"}"
            status.setTextColor(accent)
        }
    }

    private fun loadVideo(uri: Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some document providers issue temporary read grants only; the current session can still use them.
        }

        player?.release()
        player = null
        sourceUri = uri
        durationMs = 0L
        clipStartMs = 0L
        clipEndMs = 0L
        inPoint.progress = 0
        outPoint.progress = SCALE_MAX
        scrubber.progress = 0
        sourceLabel.text = queryDisplayName(uri)
        refreshClipQueueSummary()
        status.text = "Opening video…"
        status.setTextColor(accent)

        val nextPlayer = ExoPlayer.Builder(this).build()
        player = nextPlayer
        playerView.player = nextPlayer
        nextPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val value = nextPlayer.duration
                    if (value != C.TIME_UNSET && value > 0L) {
                        durationMs = value
                        val requestedStart = restoreStartMs
                        val requestedEnd = restoreEndMs
                        if (requestedStart != null && requestedEnd != null) {
                            clipStartMs = requestedStart.coerceIn(0L, (value - 1L).coerceAtLeast(0L))
                            clipEndMs = requestedEnd.coerceIn((clipStartMs + 1L).coerceAtMost(value), value)
                            inPoint.progress = ((clipStartMs.toDouble() / value.toDouble()) * SCALE_MAX).toInt()
                            outPoint.progress = ((clipEndMs.toDouble() / value.toDouble()) * SCALE_MAX).toInt().coerceIn(1, SCALE_MAX)
                            restoreStartMs = null
                            restoreEndMs = null
                        } else {
                            clipStartMs = 0L
                            clipEndMs = value
                            inPoint.progress = 0
                            outPoint.progress = SCALE_MAX
                        }
                        updateTrimUi()
                        playbackTime.text = "${formatTime(0L)} / ${formatTime(value)}"
                        status.text = "Video ready · ${formatTime(value)} total"
                        status.setTextColor(green)
                        exportButton.isEnabled = true
                        exportButton.alpha = 1f
                    } else {
                        status.text = "The provider did not report a seekable video duration."
                        status.setTextColor(accent)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                status.text = "Cannot preview this file: ${error.errorCodeName}"
                status.setTextColor(accent)
            }
        })
        nextPlayer.setMediaItem(MediaItem.fromUri(uri))
        nextPlayer.setVideoEffects(buildVideoEffects())
        nextPlayer.prepare()
        nextPlayer.playWhenReady = false
    }

    private fun minGapProgress(): Int {
        if (durationMs <= 0L) return 1
        val minimumMs = min(MIN_CLIP_MS, durationMs)
        return ceil(minimumMs.toDouble() / durationMs.toDouble() * SCALE_MAX).toInt().coerceIn(1, SCALE_MAX)
    }

    private fun updateTrimUi() {
        if (durationMs <= 0L) {
            clipStartMs = 0L
            clipEndMs = 0L
            trimSummary.text = "Import a video to set the trim range."
            return
        }
        clipStartMs = (durationMs * inPoint.progress / SCALE_MAX).coerceIn(0L, durationMs)
        clipEndMs = (durationMs * outPoint.progress / SCALE_MAX).coerceIn(0L, durationMs)
        if (clipEndMs <= clipStartMs) {
            trimSummary.text = "Choose a valid IN and OUT range."
        } else {
            trimSummary.text = "IN ${formatTime(clipStartMs)}   ·   OUT ${formatTime(clipEndMs)}   ·   ${String.format(Locale.ROOT, "%.2f", (clipEndMs - clipStartMs) / 1000.0)} s"
        }
    }

    private fun updateColorValues() {
        brightnessValue = ((brightnessSeek.progress - 100) / 100f).coerceIn(-1f, 1f)
        contrastValue = ((contrastSeek.progress - 100) / 100f).coerceIn(-1f, 1f)
        effectSummary.text = "Brightness ${String.format(Locale.ROOT, "%.2f", brightnessValue)}  ·  Contrast ${String.format(Locale.ROOT, "%.2f", contrastValue)}"
        player?.setVideoEffects(buildVideoEffects())
    }

    private fun buildVideoEffects(): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (abs(brightnessValue) > 0.001f) effects.add(Brightness(brightnessValue))
        if (abs(contrastValue) > 0.001f) effects.add(Contrast(contrastValue))
        if (rotationDegrees != 0) {
            effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotationDegrees.toFloat()).build())
        }
        if (overlayText.isNotBlank()) {
            val styledText = SpannableString(overlayText).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val settings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, -0.78f)
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(0.7f, 0.7f)
                .build()
            effects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(styledText, settings))))
        }
        return effects
    }

    private fun editTextOverlay() {
        val input = EditText(this).apply {
            setText(overlayText)
            hint = "Your title or watermark"
            setSingleLine(false)
            maxLines = 3
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        AlertDialog.Builder(this)
            .setTitle("Text overlay")
            .setMessage("This text is rendered on the video. Keep it short for best fit.")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                overlayText = input.text?.toString()?.trim()?.replace("\n", " ")?.take(120).orEmpty()
                refreshTextOverlay()
                player?.setVideoEffects(buildVideoEffects())
                status.text = if (overlayText.isBlank()) "Text overlay cleared." else "Text overlay updated."
                status.setTextColor(if (overlayText.isBlank()) muted else green)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshTextOverlay() {
        if (!::textOverlaySummary.isInitialized) return
        textOverlaySummary.text = if (overlayText.isBlank()) "No text overlay" else overlayText
        textOverlaySummary.setTextColor(if (overlayText.isBlank()) muted else white)
    }

    private fun setRotation(degrees: Int) {
        rotationDegrees = if (degrees in setOf(0, 90, 180, 270)) degrees else 0
        refreshRotationControls()
        player?.setVideoEffects(buildVideoEffects())
    }

    private fun refreshRotationControls() {
        if (::rotationSummary.isInitialized) rotationSummary.text = "Rotation: ${rotationDegrees}°"
        rotationButtons.forEach { (degrees, control) ->
            val selected = degrees == rotationDegrees
            control.background = rounded(if (selected) accent else surface2, 12f, if (selected) null else border)
            control.setTextColor(if (selected) Color.rgb(25, 19, 14) else white)
        }
    }

    private fun beginExport() {
        val source = sourceUri
        val destination = pendingExportUri
        if (source == null || destination == null || durationMs <= 0L) {
            status.text = "Export cancelled: media or destination is missing."
            status.setTextColor(accent)
            return
        }
        if (clipEndMs <= clipStartMs) {
            status.text = "Export cancelled: trim range is invalid."
            status.setTextColor(accent)
            return
        }

        val temp = File(cacheDir, "sowz-export-${System.currentTimeMillis()}.mp4")
        temporaryOutput = temp
        val clippedItem = MediaItem.Builder()
            .setUri(source)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build()
            )
            .build()
        val editedItem = EditedMediaItem.Builder(clippedItem)
            .setEffects(Effects(emptyList(), buildVideoEffects()))
            .build()
        val sequenceItems = mutableListOf(editedItem)
        additionalClipUris.forEach { clipUri ->
            sequenceItems.add(
                EditedMediaItem.Builder(MediaItem.fromUri(clipUri))
                    .setEffects(Effects(emptyList(), buildVideoEffects()))
                    .build()
            )
        }
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(sequenceItems)
        val composition = Composition.Builder(sequence).build()
        exportClipCount = sequenceItems.size
        status.text = "Rendering ${sequenceItems.size} clip(s)… keep the app open until export finishes."
        status.setTextColor(accent)
        exportButton.isEnabled = false
        exportButton.alpha = 0.55f

        val activeTransformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    copyExportToDestination(temp, destination)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    runOnUiThread {
                        status.text = "Export failed: ${exportException.message ?: exportException.errorCodeName}"
                        status.setTextColor(accent)
                        exportButton.isEnabled = true
                        exportButton.alpha = 1f
                        temp.delete()
                        temporaryOutput = null
                        transformer = null
                    }
                }
            }).build()
        transformer = activeTransformer
        try {
            activeTransformer.start(composition, temp.absolutePath)
        } catch (error: Exception) {
            status.text = "Could not start export: ${error.message ?: "unknown error"}"
            status.setTextColor(accent)
            exportButton.isEnabled = true
            exportButton.alpha = 1f
            temp.delete()
            temporaryOutput = null
            transformer = null
        }
    }

    private fun copyExportToDestination(temp: File, destination: Uri) {
        Thread {
            try {
                if (!temp.isFile || temp.length() <= 0L) error("Rendered output is empty.")
                val output = contentResolver.openOutputStream(destination, "w")
                    ?: error("Could not open the selected export location.")
                output.use { sink -> temp.inputStream().use { input -> input.copyTo(sink) } }
                runOnUiThread {
                    status.text = "Export complete · ${exportClipCount} clip(s) · ${temp.length() / 1024L} KB written."
                    status.setTextColor(green)
                    toast("MP4 export completed.")
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    temporaryOutput = null
                    transformer = null
                    temp.delete()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "Could not save exported video: ${error.message ?: "unknown error"}"
                    status.setTextColor(accent)
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    temporaryOutput = null
                    transformer = null
                    temp.delete()
                }
            }
        }.start()
    }

    private fun cancelExport() {
        val active = transformer
        if (active == null) {
            toast("There is no active export to cancel.")
            return
        }
        active.cancel()
        temporaryOutput?.delete()
        temporaryOutput = null
        transformer = null
        status.text = "Export cancelled."
        status.setTextColor(muted)
        exportButton.isEnabled = true
        exportButton.alpha = 1f
        toast("Export cancelled.")
    }

    private fun queryDisplayName(uri: Uri): String {
        val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index) ?: uri.lastPathSegment ?: "Selected video"
            }
        }
        return uri.lastPathSegment ?: "Selected video"
    }

    private fun formatTime(value: Long): String {
        val safe = value.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
