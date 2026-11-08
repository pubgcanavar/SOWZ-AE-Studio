package com.sowz.aestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
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
import com.sowz.aestudio.core.CubeLutData
import com.sowz.aestudio.core.CubeLutParser
import com.sowz.aestudio.core.ClipTrimRange
import com.sowz.aestudio.core.ClipQueueSnapshot
import com.sowz.aestudio.core.EditHistory
import com.sowz.aestudio.core.AudioTrackSettings
import java.io.ByteArrayOutputStream
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
        const val REQUEST_IMPORT_LUT = 2106
        const val REQUEST_IMPORT_FFX = 2107
        const val REQUEST_IMPORT_AUDIO = 2108
        const val SCALE_MAX = 1000
        const val MIN_CLIP_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private var sourceUri: Uri? = null
    private var backgroundAudioUri: Uri? = null
    private var backgroundAudioSettings = AudioTrackSettings()
    private var sourceAudioSettings = AudioTrackSettings(gain = 1f)
    private val additionalClipUris = mutableListOf<Uri>()
    // Kept index-aligned with additionalClipUris; duplicated source URIs can have different trim ranges.
    private val additionalClipTrims = mutableListOf<ClipTrimRange>()
    private val clipQueueHistory = EditHistory(ClipQueueSnapshot(emptyList(), emptyList()))
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
    private var selectedCubeLut: CubeLutData? = null
    private var selectedCubeLutUri: Uri? = null
    private var lutLoadToken = 0
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
    private lateinit var clipQueueControls: LinearLayout
    private lateinit var undoQueueButton: TextView
    private lateinit var redoQueueButton: TextView
    private lateinit var sourceAudioGainSummary: TextView
    private lateinit var sourceAudioGainSeek: SeekBar
    private lateinit var backgroundAudioSummary: TextView
    private lateinit var backgroundAudioGainSummary: TextView
    private lateinit var backgroundAudioGainSeek: SeekBar
    private lateinit var brightnessSeek: SeekBar
    private lateinit var contrastSeek: SeekBar
    private lateinit var effectSummary: TextView
    private lateinit var lutSummary: TextView
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
        add(timelinePanel, body("Manage every queued clip: move it, duplicate it, or remove it. Export follows this order. The trim handles still apply to the main clip; added clips use their full duration.", 12f, muted), top = 5)
        clipQueueSummary = body("Main clip: none", 13f, white, true)
        add(timelinePanel, clipQueueSummary, top = 9)
        clipQueueControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        add(timelinePanel, clipQueueControls, top = 8)
        val historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        undoQueueButton = button("↶  Undo", secondary = true) { undoQueueEdit() }
        redoQueueButton = button("↷  Redo", secondary = true) { redoQueueEdit() }
        historyRow.addView(undoQueueButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6f) })
        historyRow.addView(redoQueueButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6f) })
        add(timelinePanel, historyRow, top = 8)
        add(timelinePanel, button("＋  Add videos to final cut") { pickAdditionalClips() }, top = 10)
        refreshClipQueueSummary()
        add(content, timelinePanel, bottom = 13)

        val audioPanel = panel()
        add(audioPanel, label("Background audio · separate track"))
        add(audioPanel, body("Balance the original clip sound against the added music. These mix controls affect MP4 export; the live viewer still previews the source clip without the extra track.", 12f, muted), top = 5)
        add(audioPanel, label("Original video audio"), top = 10)
        sourceAudioGainSeek = SeekBar(this).apply {
            max = 100
            progress = sourceAudioSettings.percentage
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    sourceAudioSettings = sourceAudioSettings.copy(gain = progress / 100f)
                    refreshSourceAudioControls()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(audioPanel, sourceAudioGainSeek)
        sourceAudioGainSummary = body("100% · original audio", 11f, accent, true)
        add(audioPanel, sourceAudioGainSummary, top = 4)
        backgroundAudioSummary = body("No background audio selected", 13f, white, true)
        add(audioPanel, backgroundAudioSummary, top = 9)
        add(audioPanel, button("＋  Import audio track") { pickBackgroundAudio() }, top = 10)
        add(audioPanel, label("Music volume"), top = 12)
        backgroundAudioGainSeek = SeekBar(this).apply {
            max = 100
            progress = backgroundAudioSettings.percentage
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    backgroundAudioSettings = backgroundAudioSettings.copy(gain = progress / 100f)
                    refreshBackgroundAudioControls()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(audioPanel, backgroundAudioGainSeek)
        backgroundAudioGainSummary = body("70% volume", 11f, accent, true)
        add(audioPanel, backgroundAudioGainSummary, top = 4)
        add(audioPanel, button("Remove background audio", secondary = true) {
            backgroundAudioUri = null
            refreshBackgroundAudioControls()
            status.text = "Background audio removed from the project."
            status.setTextColor(muted)
        }, top = 8)
        refreshSourceAudioControls()
        refreshBackgroundAudioControls()
        add(content, audioPanel, bottom = 13)

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

        val lutPanel = panel()
        add(lutPanel, label("CC / 3D LUT"))
        add(lutPanel, body("Import a standard .cube colour-correction LUT. It is applied to the preview and rendered MP4; this is separate from After Effects .ffx presets.", 12f, muted), top = 5)
        lutSummary = body("No .cube CC LUT loaded", 13f, white, true)
        add(lutPanel, lutSummary, top = 9)
        add(lutPanel, button("Import CC LUT (.cube)") { pickCubeLut() }, top = 10)
        add(lutPanel, button("Import After Effects preset (.ffx)", secondary = true) { pickFfxPreset() }, top = 8)
        add(lutPanel, body("FFX translation is limited to static Brightness & Contrast values. Other effects are listed but not falsely marked as applied.", 11f, muted), top = 5)
        add(lutPanel, button("Remove CC LUT", secondary = true) {
            lutLoadToken++
            selectedCubeLut = null
            selectedCubeLutUri = null
            refreshCubeLutSummary()
            player?.setVideoEffects(buildVideoEffects())
            status.text = "CC LUT removed."
            status.setTextColor(muted)
        }, top = 8)
        refreshCubeLutSummary()
        add(content, lutPanel, bottom = 13)

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

        add(content, body("Current milestone: native video editing with a limited .ffx translator for static Brightness & Contrast. Unsupported effects, keyframes and third-party plug-ins are never reported as applied.", 11f, muted))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun pickVideo() {
        restoreStartMs = null
        restoreEndMs = null
        additionalClipUris.clear()
        additionalClipTrims.clear()
        resetQueueHistory()
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
            additionalClipTrims.add(ClipTrimRange())
            added++
        }
        if (added > 0) recordQueueEdit()
        refreshClipQueueSummary()
        toast(if (added > 0) "Added $added clip(s) to the final cut." else "No new clips were added.")
    }

    private fun clipTrimLabel(range: ClipTrimRange): String =
        if (range.startMs == 0L && range.endMs == null) {
            "Full source · no trim"
        } else {
            "IN " + formatTime(range.startMs) + " · OUT " +
                (range.endMs?.let { formatTime(it) } ?: "source end")
        }

    private fun editAdditionalClipTrim(index: Int) {
        val uri = additionalClipUris.getOrNull(index) ?: return
        status.text = "Reading source duration for " + queryDisplayName(uri) + "…"
        status.setTextColor(accent)
        Thread {
            val duration = try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: error("The provider did not report a readable video duration.")
                } finally {
                    retriever.release()
                }
            } catch (error: Exception) {
                error
            }
            handler.post {
                if (index !in additionalClipUris.indices || additionalClipUris[index] != uri) return@post
                if (duration is Exception) {
                    status.text = "Cannot read clip duration: " + (duration.message ?: "unsupported media")
                    status.setTextColor(accent)
                    toast("Could not read this clip's duration.")
                    return@post
                }
                showAdditionalClipTrimDialog(index, (duration as Long).coerceAtLeast(1L))
            }
        }.start()
    }

    private fun showAdditionalClipTrimDialog(index: Int, sourceDurationMs: Long) {
        val existing = additionalClipTrims.getOrElse(index) { ClipTrimRange() }
        val safeRange = try {
            existing.clampToDuration(sourceDurationMs)
        } catch (_: IllegalArgumentException) {
            ClipTrimRange()
        }
        val inputPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(8f), dp(18f), 0)
        }
        val inField = EditText(this).apply {
            hint = "IN point in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.ROOT, "%.3f", safeRange.startMs / 1000.0))
        }
        val outField = EditText(this).apply {
            hint = "OUT point in seconds"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText(String.format(Locale.ROOT, "%.3f", (safeRange.endMs ?: sourceDurationMs) / 1000.0))
        }
        inputPanel.addView(body("Source duration: " + formatTime(sourceDurationMs), 12f, muted))
        inputPanel.addView(body("IN point (seconds)", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        inputPanel.addView(inField)
        inputPanel.addView(body("OUT point (seconds)", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
        inputPanel.addView(outField)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Trim · " + queryDisplayName(additionalClipUris[index]))
            .setMessage("Set an independent source range for this clip. The values are seconds; the original file stays unchanged.")
            .setView(inputPanel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val startSeconds = inField.text.toString().trim().toDoubleOrNull()
                val endSeconds = outField.text.toString().trim().toDoubleOrNull()
                if (startSeconds == null || endSeconds == null ||
                    !startSeconds.isFinite() || !endSeconds.isFinite() ||
                    startSeconds < 0.0 || endSeconds <= startSeconds ||
                    endSeconds * 1000.0 > sourceDurationMs.toDouble()
                ) {
                    inField.error = "Use a valid IN value."
                    outField.error = "OUT must be after IN and within the source duration."
                    return@setOnClickListener
                }
                val startMs = (startSeconds * 1000.0).toLong()
                val endMs = (endSeconds * 1000.0).toLong()
                try {
                    additionalClipTrims[index] = ClipTrimRange(startMs, endMs).clampToDuration(sourceDurationMs)
                    recordQueueEdit()
                } catch (error: IllegalArgumentException) {
                    outField.error = error.message
                    return@setOnClickListener
                }
                refreshClipQueueSummary()
                status.text = "Clip trim applied · " + formatTime(startMs) + "–" + formatTime(endMs)
                status.setTextColor(green)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun currentQueueSnapshot(): ClipQueueSnapshot =
        ClipQueueSnapshot(
            uriStrings = additionalClipUris.map { it.toString() },
            trims = additionalClipTrims.toList()
        )

    private fun recordQueueEdit() {
        clipQueueHistory.commit(currentQueueSnapshot())
        refreshQueueHistoryButtons()
    }

    private fun resetQueueHistory() {
        clipQueueHistory.reset(currentQueueSnapshot())
        refreshQueueHistoryButtons()
    }

    private fun restoreQueueSnapshot(snapshot: ClipQueueSnapshot) {
        additionalClipUris.clear()
        snapshot.uriStrings.forEach { additionalClipUris.add(Uri.parse(it)) }
        additionalClipTrims.clear()
        additionalClipTrims.addAll(snapshot.trims)
        refreshClipQueueSummary()
    }

    private fun undoQueueEdit() {
        val snapshot = clipQueueHistory.undo()
        if (snapshot == null) {
            toast("Nothing to undo.")
            return
        }
        restoreQueueSnapshot(snapshot)
        refreshQueueHistoryButtons()
        status.text = "Undo completed."
        status.setTextColor(muted)
    }

    private fun redoQueueEdit() {
        val snapshot = clipQueueHistory.redo()
        if (snapshot == null) {
            toast("Nothing to redo.")
            return
        }
        restoreQueueSnapshot(snapshot)
        refreshQueueHistoryButtons()
        status.text = "Redo completed."
        status.setTextColor(green)
    }

    private fun refreshQueueHistoryButtons() {
        if (!::undoQueueButton.isInitialized || !::redoQueueButton.isInitialized) return
        undoQueueButton.isEnabled = clipQueueHistory.canUndo
        undoQueueButton.alpha = if (clipQueueHistory.canUndo) 1f else 0.42f
        redoQueueButton.isEnabled = clipQueueHistory.canRedo
        redoQueueButton.alpha = if (clipQueueHistory.canRedo) 1f else 0.42f
    }

    private fun refreshClipQueueSummary() {
        if (!::clipQueueSummary.isInitialized || !::clipQueueControls.isInitialized) return
        val mainName = if (::sourceLabel.isInitialized && sourceLabel.text.isNotBlank()) {
            sourceLabel.text.toString()
        } else "Main clip"
        clipQueueSummary.text = "01  " + mainName + "\n" +
            (additionalClipUris.size + 1) + " clip(s) in export sequence"
        clipQueueControls.removeAllViews()

        if (additionalClipUris.isEmpty()) {
            clipQueueControls.addView(body("No added clips yet. Import videos to build your sequence.", 11f, muted))
            refreshQueueHistoryButtons()
            return
        }

        additionalClipUris.forEachIndexed { index, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9f), dp(7f), dp(7f), dp(7f))
                background = rounded(surface2, 12f, border)
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            info.addView(body("CLIP " + String.format(Locale.ROOT, "%02d", index + 2), 9.5f, accent, true))
            info.addView(body(queryDisplayName(uri), 12f, white, true))
            info.addView(body(clipTrimLabel(additionalClipTrims.getOrElse(index) { ClipTrimRange() }), 10f, muted))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

            fun addAction(glyph: String, hint: String, enabled: Boolean, action: () -> Unit) {
                row.addView(
                    queueAction(glyph, hint, enabled, action),
                    LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginStart = dp(4f) }
                )
            }
            addAction("✂", "Edit clip IN/OUT points", true) { editAdditionalClipTrim(index) }
            addAction("↑", "Move clip up", index > 0) { moveAdditionalClip(index, -1) }
            addAction("↓", "Move clip down", index < additionalClipUris.lastIndex) { moveAdditionalClip(index, 1) }
            addAction("+", "Duplicate clip", additionalClipUris.size < 20) { duplicateAdditionalClip(index) }
            addAction("×", "Remove clip", true) { confirmRemoveAdditionalClip(index) }

            clipQueueControls.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f) }
            )
        }
        refreshQueueHistoryButtons()
    }

    private fun queueAction(glyph: String, hint: String, enabled: Boolean, action: () -> Unit): TextView =
        body(glyph, 17f, if (enabled) white else muted, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(38f)
            minHeight = dp(38f)
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
            background = rounded(if (enabled) surface else bg, 10f, border)
            contentDescription = hint
            isClickable = enabled
            isFocusable = enabled
            alpha = if (enabled) 1f else 0.38f
            setOnClickListener { if (enabled) action() }
        }

    private fun moveAdditionalClip(index: Int, offset: Int) {
        val target = index + offset
        if (index !in additionalClipUris.indices || target !in additionalClipUris.indices) return
        val reordered = com.sowz.aestudio.core.ClipQueueOperations.move(additionalClipUris, index, target)
        val reorderedTrims = com.sowz.aestudio.core.ClipQueueOperations.move(additionalClipTrims, index, target)
        additionalClipUris.clear()
        additionalClipUris.addAll(reordered)
        additionalClipTrims.clear()
        additionalClipTrims.addAll(reorderedTrims)
        recordQueueEdit()
        refreshClipQueueSummary()
        status.text = "Clip moved. Export order updated."
        status.setTextColor(green)
    }

    private fun duplicateAdditionalClip(index: Int) {
        val uri = additionalClipUris.getOrNull(index) ?: return
        if (additionalClipUris.size >= 20) {
            toast("The project can contain up to 20 added clips.")
            return
        }
        additionalClipUris.add(index + 1, uri)
        additionalClipTrims.add(index + 1, additionalClipTrims.getOrElse(index) { ClipTrimRange() })
        recordQueueEdit()
        refreshClipQueueSummary()
        status.text = "Clip duplicated in the sequence."
        status.setTextColor(green)
    }

    private fun confirmRemoveAdditionalClip(index: Int) {
        val uri = additionalClipUris.getOrNull(index) ?: return
        val name = queryDisplayName(uri)
        AlertDialog.Builder(this)
            .setTitle("Remove clip from sequence?")
            .setMessage("Remove " + name + " from this project? The original media file will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val updated = com.sowz.aestudio.core.ClipQueueOperations.remove(additionalClipUris, index)
                additionalClipUris.clear()
                additionalClipUris.addAll(updated)
                if (index in additionalClipTrims.indices) additionalClipTrims.removeAt(index)
                recordQueueEdit()
                refreshClipQueueSummary()
                status.text = "Clip removed from sequence. Source media is unchanged."
                status.setTextColor(muted)
            }
            .show()
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
            REQUEST_IMPORT_LUT -> loadCubeLut(data?.data ?: return, data.flags)
            REQUEST_IMPORT_FFX -> loadFfxPreset(data?.data ?: return, data.flags)
            REQUEST_IMPORT_AUDIO -> loadBackgroundAudio(data?.data ?: return, data.flags)
        }
    }

    private fun pickBackgroundAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_AUDIO)
    }

    private fun loadBackgroundAudio(uri: Uri, flags: Int) {
        if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some document providers only issue temporary read grants.
            }
        }
        backgroundAudioUri = uri
        refreshBackgroundAudioControls()
        status.text = "Background audio selected. It will be mixed during MP4 export; the live viewer still previews the video track only."
        status.setTextColor(green)
        toast("Background audio added to the export composition.")
    }

    private fun refreshSourceAudioControls() {
        if (::sourceAudioGainSeek.isInitialized) {
            val expected = sourceAudioSettings.percentage
            if (sourceAudioGainSeek.progress != expected) {
                sourceAudioGainSeek.progress = expected
            }
        }
        if (::sourceAudioGainSummary.isInitialized) {
            val percentage = sourceAudioSettings.percentage
            sourceAudioGainSummary.text = if (percentage == 0) {
                "Muted · original clip audio"
            } else {
                percentage.toString() + "% · original audio"
            }
        }
    }

    private fun refreshBackgroundAudioControls() {
        if (::backgroundAudioSummary.isInitialized) {
            val uri = backgroundAudioUri
            backgroundAudioSummary.text = if (uri == null) {
                "No background audio selected"
            } else {
                queryDisplayName(uri)
            }
            backgroundAudioSummary.setTextColor(if (uri == null) muted else white)
        }
        if (::backgroundAudioGainSeek.isInitialized) {
            val expected = backgroundAudioSettings.percentage
            if (backgroundAudioGainSeek.progress != expected) {
                backgroundAudioGainSeek.progress = expected
            }
        }
        if (::backgroundAudioGainSummary.isInitialized) {
            backgroundAudioGainSummary.text = backgroundAudioSettings.percentage.toString() + "% volume"
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
                .put("cubeLutUri", selectedCubeLutUri?.toString())
                .put("cubeLutTitle", selectedCubeLut?.title)
                .put("backgroundAudioUri", backgroundAudioUri?.toString())
                .put("backgroundAudioGain", backgroundAudioSettings.gain.toDouble())
                .put("sourceAudioGain", sourceAudioSettings.gain.toDouble())
                .put("additionalClipUris", org.json.JSONArray().apply {
                    additionalClipUris.forEachIndexed { index, uri ->
                        val trim = additionalClipTrims.getOrElse(index) { ClipTrimRange() }
                        put(org.json.JSONObject()
                            .put("uri", uri.toString())
                            .put("trimStartMs", trim.startMs)
                            .put("trimEndMs", trim.endMs ?: -1L))
                    }
                })
                .put("frameRate", 30)
                .put("createdWith", "SOWZ AE Studio 1.1.0")
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
            additionalClipTrims.clear()
            val savedClips = document.optJSONArray("additionalClipUris")
            if (savedClips != null) {
                for (index in 0 until min(savedClips.length(), 20)) {
                    val rawClip = savedClips.opt(index)
                    val clipObject = rawClip as? org.json.JSONObject
                    val clipValue = if (clipObject != null) clipObject.optString("uri") else rawClip as? String ?: ""
                    if (clipValue.isBlank() || clipValue == sourceString) continue
                    val clipUri = Uri.parse(clipValue)
                    additionalClipUris.add(clipUri)
                    val startMs = clipObject?.optLong("trimStartMs", 0L) ?: 0L
                    val endValue = clipObject?.optLong("trimEndMs", -1L) ?: -1L
                    val range = try {
                        ClipTrimRange(startMs, endValue.takeIf { it > 0L })
                    } catch (_: IllegalArgumentException) {
                        ClipTrimRange()
                    }
                    additionalClipTrims.add(range)
                }
            }
            resetQueueHistory()
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
            val savedAudioUri = document.optString("backgroundAudioUri", "")
            backgroundAudioUri = savedAudioUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val savedGain = document.optDouble("backgroundAudioGain", 0.70).toFloat()
            backgroundAudioSettings = AudioTrackSettings(
                gain = if (savedGain.isFinite()) savedGain.coerceIn(0f, 1f) else 0.70f
            )
            val savedSourceGain = document.optDouble("sourceAudioGain", 1.0).toFloat()
            sourceAudioSettings = AudioTrackSettings(
                gain = if (savedSourceGain.isFinite()) savedSourceGain.coerceIn(0f, 1f) else 1f
            )
            refreshSourceAudioControls()
            refreshBackgroundAudioControls()
            selectedCubeLut = null
            selectedCubeLutUri = null
            lutLoadToken++
            val cubeUriText = document.optString("cubeLutUri", "")
            if (cubeUriText.isNotBlank()) {
                loadCubeLut(Uri.parse(cubeUriText), 0, showStatus = false)
            } else {
                refreshCubeLutSummary()
            }
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

    /**
     * Transformer requires every audio-bearing item in a Composition to output 16-bit PCM
     * with the same channel count. Normalize audio inputs to stereo before optional gain.
     */
    private fun buildAudioProcessors(gain: Float? = null): List<AudioProcessor> {
        val processors = mutableListOf<AudioProcessor>()
        processors.add(ToInt16PcmAudioProcessor())

        val channelMixer = ChannelMixingAudioProcessor()
        for (inputChannels in 1..6) {
            channelMixer.putChannelMixingMatrix(
                ChannelMixingMatrix.createForConstantPower(inputChannels, 2)
            )
        }
        processors.add(channelMixer)

        if (gain != null) {
            processors.add(
                GainProcessor(
                    DefaultGainProvider.Builder(gain.coerceIn(0f, 1f)).build()
                )
            )
        }
        return processors
    }

    private fun buildVideoEffects(): List<Effect> {
        val effects = mutableListOf<Effect>()
        if (abs(brightnessValue) > 0.001f) effects.add(Brightness(brightnessValue))
        if (abs(contrastValue) > 0.001f) effects.add(Contrast(contrastValue))
        if (rotationDegrees != 0) {
            effects.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(rotationDegrees.toFloat()).build())
        }
        selectedCubeLut?.let { lut ->
            effects.add(SingleColorLut.createFromCube(lut.cube))
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

    private fun pickCubeLut() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_LUT)
    }

    private fun loadCubeLut(uri: Uri, flags: Int = 0, showStatus: Boolean = true) {
        val name = queryDisplayName(uri)
        if (!name.endsWith(".cube", ignoreCase = true)) {
            status.text = "Choose a .cube 3D LUT file. An .ffx preset is a different format."
            status.setTextColor(accent)
            toast("Only standard .cube CC LUT files are supported by this importer.")
            return
        }
        if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some document providers provide temporary grants only.
            }
        }
        val requestToken = ++lutLoadToken
        if (showStatus) {
            status.text = "Reading CC LUT: $name…"
            status.setTextColor(accent)
        }
        Thread {
            val result = try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 12 * 1024 * 1024) {
                            "The .cube file exceeds the 12 MB safety limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Could not read the selected .cube file.")
                CubeLutParser.parse(name, bytes)
            } catch (error: Exception) {
                error
            }
            handler.post {
                if (requestToken != lutLoadToken) return@post
                if (result is CubeLutData) {
                    selectedCubeLut = result
                    selectedCubeLutUri = uri
                    refreshCubeLutSummary()
                    player?.setVideoEffects(buildVideoEffects())
                    status.text = "CC LUT applied: ${result.title} · ${result.size}³"
                    status.setTextColor(green)
                    toast("CC LUT loaded and applied to preview/export.")
                } else {
                    val error = result as? Exception
                    status.text = "Could not apply CC LUT: ${error?.message ?: "invalid file"}"
                    status.setTextColor(accent)
                    toast(error?.message ?: "Could not parse the .cube file.")
                }
            }
        }.start()
    }

    private fun refreshCubeLutSummary() {
        if (!::lutSummary.isInitialized) return
        val lut = selectedCubeLut
        lutSummary.text = if (lut == null) {
            "No .cube CC LUT loaded"
        } else {
            "${lut.title} · ${lut.size}³ LUT · ${lut.entryCount} colour points"
        }
        lutSummary.setTextColor(if (lut == null) muted else white)
    }


    private fun pickFfxPreset() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_FFX)
    }

    /**
     * Translate only the verified, static subset that maps cleanly to native controls.
     * This does not execute an FFX payload or pretend third-party plug-ins are compatible.
     */
    private fun loadFfxPreset(uri: Uri, flags: Int) {
        val name = queryDisplayName(uri)
        if (!name.endsWith(".ffx", ignoreCase = true)) {
            status.text = "Choose an After Effects .ffx preset file."
            status.setTextColor(accent)
            toast("This importer expects an .ffx file.")
            return
        }
        try {
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                contentResolver.takePersistableUriPermission(uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            // Some document providers issue temporary grants only.
        }
        status.text = "Reading and decoding FFX preset: $name…"
        status.setTextColor(accent)
        Thread {
            val result: Any = try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 32 * 1024 * 1024) { "The FFX file exceeds the 32 MB safety limit." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Could not read the selected FFX file.")
                require(com.sowz.aestudio.core.FfxParser.isLikelyFfx(bytes)) {
                    "The file does not have the expected RIFX / FaFX / head signature."
                }
                com.sowz.aestudio.core.FfxParser.parseEffects(bytes)
            } catch (error: Exception) {
                error
            }
            handler.post {
                if (result is Exception) {
                    status.text = "Could not decode FFX: ${result.message ?: "unknown format"}"
                    status.setTextColor(accent)
                    toast("FFX import failed. See the status message for details.")
                    return@post
                }
                val effects = (result as? List<*>)?.filterIsInstance<com.sowz.aestudio.core.FfxEffect>().orEmpty()
                if (effects.isEmpty()) {
                    status.text = "No usable effect blocks found. This preset may be animated, property-based, or use an unsupported layout."
                    status.setTextColor(accent)
                    toast("No compatible FFX effects were found.")
                    return@post
                }

                val supported = effects.firstOrNull { effect ->
                    effect.matchName.contains("Brightness & Contrast", ignoreCase = true) ||
                        effect.displayName.contains("Brightness & Contrast", ignoreCase = true)
                }
                var didApply = false
                var appliedName = ""
                if (supported != null) {
                    val brightness = supported.parameters.firstOrNull { it.name.equals("Brightness", ignoreCase = true) }
                    val contrast = supported.parameters.firstOrNull { it.name.equals("Contrast", ignoreCase = true) }
                    val canApplyBrightness = brightness?.value != null && !brightness.isAnimated
                    val canApplyContrast = contrast?.value != null && !contrast.isAnimated
                    if (canApplyBrightness || canApplyContrast) {
                        if (canApplyBrightness) {
                            brightnessValue = ((brightness!!.value!!.toFloat()) / 100f).coerceIn(-1f, 1f)
                            brightnessSeek.progress = ((brightnessValue + 1f) * 100f).toInt().coerceIn(0, 200)
                        }
                        if (canApplyContrast) {
                            contrastValue = ((contrast!!.value!!.toFloat()) / 100f).coerceIn(-1f, 1f)
                            contrastSeek.progress = ((contrastValue + 1f) * 100f).toInt().coerceIn(0, 200)
                        }
                        updateColorValues()
                        didApply = true
                        appliedName = supported.displayName
                    }
                }

                val skipped = effects.filterNot { it === supported }
                val skippedNames = skipped.map { it.displayName }.distinct().take(4)
                status.text = if (didApply) {
                    buildString {
                        append("Applied FFX subset: ").append(appliedName)
                        append(". Found ").append(effects.size).append(" effect(s).")
                        if (skipped.isNotEmpty()) append(" Not applied: ").append(skippedNames.joinToString(", "))
                        if (supported?.parameters?.any { it.isAnimated } == true) append(". Animated properties were not reproduced.")
                        append(" Review the result before export.")
                    }
                } else {
                    "Decoded ${effects.size} effect(s), but no supported static Brightness & Contrast values were found. Detected: " +
                        effects.map { it.displayName }.distinct().take(5).joinToString(", ")
                }
                status.setTextColor(if (didApply) green else accent)
                toast(if (didApply) "Supported FFX color values applied." else "Preset inspected, but no compatible effect was applied.")
            }
        }.start()
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
            .setEffects(Effects(buildAudioProcessors(sourceAudioSettings.gain), buildVideoEffects()))
            .build()
        val sequenceItems = mutableListOf(editedItem)
        additionalClipUris.forEachIndexed { index, clipUri ->
            val trim = additionalClipTrims.getOrElse(index) { ClipTrimRange() }
            val clipItem = MediaItem.Builder()
                .setUri(clipUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trim.startMs)
                        .apply { trim.endMs?.let { setEndPositionMs(it) } }
                        .build()
                )
                .build()
            sequenceItems.add(
                EditedMediaItem.Builder(clipItem)
                    .setEffects(Effects(buildAudioProcessors(sourceAudioSettings.gain), buildVideoEffects()))
                    .build()
            )
        }
        val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(sequenceItems)
        val composition = backgroundAudioUri?.let { audioUri ->
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUri))
                .setEffects(Effects(buildAudioProcessors(backgroundAudioSettings.gain), emptyList()))
                .build()
            val audioSequence = EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
                .buildUpon()
                .setIsLooping(true)
                .build()
            Composition.Builder(videoSequence, audioSequence).build()
        } ?: Composition.Builder(videoSequence).build()
        exportClipCount = sequenceItems.size
        status.text = "Rendering ${sequenceItems.size} clip(s)" + if (backgroundAudioUri != null) " with background audio…" else "… keep the app open until export finishes."
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
