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
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
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
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.GaussianBlur
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
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.sowz.aestudio.core.CubeLutData
import com.sowz.aestudio.core.CubeLutParser
import com.sowz.aestudio.core.ClipTrimRange
import com.sowz.aestudio.core.ClipQueueSnapshot
import com.sowz.aestudio.core.EditHistory
import com.sowz.aestudio.core.AudioTrackSettings
import com.sowz.aestudio.core.AepNativeReader
import com.sowz.aestudio.core.AepProjectImporter
import com.sowz.aestudio.core.NativeEffectMapper
import com.sowz.aestudio.core.NativeEffectStack
import com.sowz.aestudio.core.EditLayer
import com.sowz.aestudio.core.LayerKind
import com.sowz.aestudio.core.TimelineProject
import com.sowz.aestudio.core.TimelineEditorOps
import com.sowz.aestudio.core.TimelineProjectStore
import com.sowz.aestudio.core.TransformProperty
import com.sowz.aestudio.core.ColorGradeSettings
import com.sowz.aestudio.core.FfxEffect
import com.sowz.aestudio.core.FfxParser
import com.sowz.aestudio.core.PresetStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native video editing foundation: sequential clips, trim, text/color effects, transforms, saved projects and MP4 export.
 * This is intentionally a working foundation, not a claim of full After Effects compatibility.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
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
        const val REQUEST_IMPORT_AEP = 2109
        const val REQUEST_ADD_VIDEO_LAYER = 2110
        const val SCALE_MAX = 1000
        const val MIN_CLIP_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var compositionPlayer: CompositionPlayer? = null
    private var timelineProject: TimelineProject? = null
    private var pendingTimelineProject: TimelineProject? = null
    private var selectedTimelineLayerId: String? = null
    private var compositionPlayheadMs = 0L
    private var transformer: Transformer? = null
    private var sourceUri: Uri? = null
    private var backgroundAudioUri: Uri? = null
    private var backgroundAudioSettings = AudioTrackSettings()
    private var sourceAudioSettings = AudioTrackSettings(gain = 1f)
    private val additionalClipUris = mutableListOf<Uri>()
    // Kept index-aligned with additionalClipUris; every queued entry owns trim and speed settings.
    private val additionalClipTrims = mutableListOf<ClipTrimRange>()
    private val additionalClipSpeeds = mutableListOf<Float>()
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
    private var colorGrade = ColorGradeSettings()
    private var blurSigma = 0f
    private var shakeEnabled = false
    private var shakeAmplitudePixels = 8f
    private var shakeFrequencyHz = 8f
    private var shakeRotationDegrees = 1.5f
    private var mainClipSpeed = 1f
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
    private lateinit var timelineOverview: EffectCraftTimelineView
    private lateinit var compositionTimelineView: MultiTrackTimelineView
    private lateinit var selectedLayerSummary: TextView
    private lateinit var keyframeSummary: TextView
    private val transformSeekBars = mutableMapOf<TransformProperty, SeekBar>()
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
    private lateinit var hueSeek: SeekBar
    private lateinit var saturationSeek: SeekBar
    private lateinit var lightnessSeek: SeekBar
    private lateinit var blurSeek: SeekBar
    private lateinit var shakeToggle: TextView
    private lateinit var shakeAmplitudeSeek: SeekBar
    private lateinit var shakeFrequencySeek: SeekBar
    private lateinit var shakeRotationSeek: SeekBar
    private lateinit var shakeSummary: TextView
    private lateinit var mainSpeedSeek: SeekBar
    private lateinit var speedSummary: TextView
    private lateinit var effectSummary: TextView
    private lateinit var lutSummary: TextView
    private lateinit var ffxPresetSummary: TextView
    private lateinit var rotationSummary: TextView
    private val rotationButtons = mutableMapOf<Int, TextView>()
    private lateinit var textOverlaySummary: TextView
    private lateinit var exportProgressBar: ProgressBar
    private lateinit var exportButton: TextView

    private val exportProgressHolder = ProgressHolder()
    private val syncExportProgress = object : Runnable {
        override fun run() {
            val active = transformer ?: return
            try {
                val state = active.getProgress(exportProgressHolder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = exportProgressHolder.progress.coerceIn(0, 100)
                    if (::exportProgressBar.isInitialized) {
                        exportProgressBar.progress = percent
                        exportProgressBar.visibility = View.VISIBLE
                    }
                    if (::status.isInitialized && !exportButton.isEnabled) {
                        status.text = "Rendering " + percent + "% · " + exportClipCount + " clip(s)"
                    }
                }
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    handler.postDelayed(this, 500L)
                }
            } catch (_: Exception) {
                // Completion/error callbacks remain authoritative.
            }
        }
    }

    private val syncPlayback = object : Runnable {
        override fun run() {
            val activePlayer = activePreviewPlayer()
            if (activePlayer != null && durationMs > 0) {
                if (!draggingScrubber) {
                    scrubber.progress = ((activePlayer.currentPosition.coerceAtLeast(0L).toDouble() /
                        durationMs.toDouble()) * SCALE_MAX).toInt().coerceIn(0, SCALE_MAX)
                }
                if (::timelineOverview.isInitialized) {
                    timelineOverview.playheadMs = activePlayer.currentPosition.coerceAtLeast(0L)
                }
                if (::compositionTimelineView.isInitialized) {
                    compositionPlayheadMs = activePlayer.currentPosition.coerceAtLeast(0L)
                    compositionTimelineView.playheadMs = compositionPlayheadMs
                    refreshKeyframeSummary()
                }
                playbackTime.text = "${formatTime(activePlayer.currentPosition)} / ${formatTime(durationMs)}"
            }
            handler.postDelayed(this, 200L)
        }
    }

    // EffectCraft dark-theme tokens: app #121212, header #1f1f1f, panel #232323, blue #2d8ceb.
    private val bg = Color.rgb(0x12, 0x12, 0x12)
    private val surface = Color.rgb(0x1f, 0x1f, 0x1f)
    private val surface2 = Color.rgb(0x23, 0x23, 0x23)
    private val border = Color.rgb(0x34, 0x34, 0x34)
    private val accent = Color.rgb(0x2d, 0x8c, 0xeb)
    private val white = Color.rgb(0xc8, 0xc8, 0xc8)
    private val muted = Color.rgb(0x9a, 0x9a, 0x9a)
    private val green = Color.rgb(0x65, 0xb9, 0x8e)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        setContentView(buildUi())
        handler.post(syncPlayback)
        val requestedPreset = intent.getStringExtra("ffx_preset_name")
        if (!requestedPreset.isNullOrBlank()) {
            handler.postDelayed({ applyStoredFfxPreset(requestedPreset) }, 250L)
        } else if (intent.getBooleanExtra("open_project_picker", false)) {
            handler.postDelayed({ pickProject() }, 250L)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(syncPlayback)
        handler.post(syncPlayback)
    }

    override fun onPause() {
        handler.removeCallbacks(syncPlayback)
        activePreviewPlayer()?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(syncPlayback)
        handler.removeCallbacks(syncExportProgress)
        transformer?.cancel()
        playerView.player = null
        compositionPlayer?.release()
        compositionPlayer = null
        player?.release()
        player = null
        temporaryOutput?.delete()
        super.onDestroy()
    }

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        background = rounded(surface, 8f, border)
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
        val sectionTargets = linkedMapOf<String, View>()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(10f))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = body("‹  HOME", 11f, muted, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
            background = rounded(surface2, 12f, border)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }
        toolbar.addView(back)
        toolbar.addView(body("SOWZ AE  /  STUDIO", 11f, accent, true).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        add(header, toolbar)
        add(header, body("Composition", 23f, white, true), top = 8)
        add(header, body("Viewer  ·  timeline  ·  layer controls  ·  effects", 11.5f, muted), top = 2)
        val exportAction = body("EXPORT", 10f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = rounded(surface2, 7f, border)
            isClickable = true
            isFocusable = true
            setOnClickListener { startExportPicker() }
        }
        toolbar.addView(exportAction)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            setPadding(dp(10f), dp(3f), dp(10f), dp(22f))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val sectionNav = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(12f), dp(2f), dp(12f), dp(8f))
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sectionChips = linkedMapOf<String, TextView>()
        var activeSection = "VIEWER"
        fun refreshSectionChips() {
            sectionChips.forEach { (name, chip) ->
                val selected = name == activeSection
                chip.background = rounded(if (selected) accent else surface2, 7f, if (selected) null else border)
                chip.setTextColor(if (selected) Color.rgb(12, 18, 25) else muted)
            }
        }
        listOf("MEDIA", "VIEWER", "TIMELINE", "ANIMATE", "AUDIO", "EFFECTS", "FFX", "EXPORT").forEach { name ->
            val chip = body(name, 9.5f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(11f), dp(8f), dp(11f), dp(8f))
                background = rounded(surface2, 7f, border)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    activeSection = name
                    refreshSectionChips()
                    sectionTargets[name]?.let { target -> scroll.smoothScrollTo(0, target.top) }
                }
            }
            sectionChips[name] = chip
            navRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply {
                if (navRow.childCount > 0) marginStart = dp(6f)
            })
        }
        refreshSectionChips()
        sectionNav.addView(navRow)
        root.addView(sectionNav, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val sourcePanel = panel()
        sectionTargets["MEDIA"] = sourcePanel
        add(sourcePanel, label("Media source"))
        add(sourcePanel, space(7))
        sourceLabel = body("No video imported yet", 14f, white, true)
        add(sourcePanel, sourceLabel)
        add(sourcePanel, body("Local video files stay on this device. Select a clip to start.", 12f, muted), top = 5)
        add(sourcePanel, button("＋  Import video", secondary = false) { pickVideo() }, top = 13)
        add(sourcePanel, button("Open saved project (.sowzproj)", secondary = true) { pickProject() }, top = 8)
        add(sourcePanel, button("Import After Effects project (.aep)", secondary = true) { pickAepProject() }, top = 8)
        add(content, sourcePanel, bottom = 13)

        val previewPanel = panel()
        sectionTargets["VIEWER"] = previewPanel
        add(previewPanel, label("Composition viewer"))
        add(previewPanel, space(9))
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
            val previewHeightDp = minOf(235f, (widthDp * 9f / 16f).coerceAtLeast(150f))
            layoutParams = LinearLayout.LayoutParams(-1, dp(previewHeightDp))
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
                    if (durationMs > 0) activePreviewPlayer()?.seekTo(durationMs * scrubber.progress / SCALE_MAX)
                    draggingScrubber = false
                    refreshTimelineOverview()
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
        sectionTargets["TIMELINE"] = timelinePanel
        add(timelinePanel, label("Timeline sequence"))
        add(timelinePanel, body("Manage every queued clip: move it, duplicate it, or remove it. Export follows this order. The trim handles still apply to the main clip; added clips use their full duration.", 12f, muted), top = 5)
        clipQueueSummary = body("Main clip: none", 13f, white, true)
        add(timelinePanel, clipQueueSummary, top = 9)
        timelineOverview = EffectCraftTimelineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(132f)).apply {
                topMargin = dp(10f)
                bottomMargin = dp(8f)
            }
            onSeekPosition = { positionMs ->
                if (durationMs > 0L) {
                    val safePosition = positionMs.coerceIn(0L, durationMs)
                    scrubber.progress = ((safePosition.toDouble() / durationMs) * SCALE_MAX)
                        .toInt().coerceIn(0, SCALE_MAX)
                    activePreviewPlayer()?.seekTo(safePosition)
                    compositionPlayheadMs = safePosition
                    timelineOverview.playheadMs = safePosition
                    if (::compositionTimelineView.isInitialized) compositionTimelineView.playheadMs = safePosition
                    refreshKeyframeSummary()
                }
            }
            onTrimRangeChanged = { newInMs, newOutMs ->
                if (durationMs > 0L) {
                    inPoint.progress = ((newInMs.toDouble() / durationMs) * SCALE_MAX)
                        .toInt().coerceIn(0, SCALE_MAX)
                    outPoint.progress = ((newOutMs.toDouble() / durationMs) * SCALE_MAX)
                        .toInt().coerceIn(0, SCALE_MAX)
                    updateTrimUi()
                }
            }
        }
        timelinePanel.addView(timelineOverview)

        add(timelinePanel, label("COMPOSITION · MULTI-TRACK"), top = 12)
        add(timelinePanel, body("Independent video layers can overlap. Drag a clip to change its start time; tap the ruler to move the playhead.", 11f, muted), top = 4)
        val compositionScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, dp(5f), 0, dp(5f))
        }
        compositionTimelineView = MultiTrackTimelineView(this).apply {
            project = timelineProject
            selectedLayerId = selectedTimelineLayerId
            playheadMs = compositionPlayheadMs
            onSeek = { position ->
                compositionPlayheadMs = position
                activePreviewPlayer()?.seekTo(position)
                if (durationMs > 0L) {
                    scrubber.progress = ((position.toDouble() / durationMs) * SCALE_MAX).toInt().coerceIn(0, SCALE_MAX)
                    playbackTime.text = "${formatTime(position)} / ${formatTime(durationMs)}"
                }
                refreshKeyframeSummary()
            }
            onSelectLayer = { id ->
                selectedTimelineLayerId = id
                refreshLayerInspector()
            }
            onMoveLayer = { id, newStart ->
                runCatching {
                    timelineProject = TimelineEditorOps.moveLayer(
                        timelineProject ?: error("No composition is loaded."),
                        id,
                        newStart
                    )
                    refreshCompositionTimeline()
                    refreshLayerInspector()
                    refreshCompositionPreview()
                    status.text = "Layer moved · timeline and preview updated."
                    status.setTextColor(green)
                }.onFailure { toast(it.message ?: "Could not move this layer.") }
            }
            onSelectKeyframe = { id, time ->
                selectedTimelineLayerId = id
                compositionPlayheadMs = time
                activePreviewPlayer()?.seekTo(time)
                refreshLayerInspector()
            }
        }
        compositionScroll.addView(compositionTimelineView)
        timelinePanel.addView(compositionScroll, LinearLayout.LayoutParams(-1, -2))
        val layerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        layerActions.addView(button("＋ Video layer", secondary = true) { pickVideoLayer() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5f) })
        layerActions.addView(button("＋ Audio", secondary = true) { pickBackgroundAudio() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5f) })
        add(timelinePanel, layerActions, top = 8)

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

        val animationPanel = panel()
        sectionTargets["ANIMATE"] = animationPanel
        add(animationPanel, label("ANIMATION · TRANSFORM KEYFRAMES"))
        selectedLayerSummary = body("Select a layer to edit its transform.", 13f, white, true)
        add(animationPanel, selectedLayerSummary, top = 8)
        keyframeSummary = body("No keyframes on the selected layer.", 11f, muted)
        add(animationPanel, keyframeSummary, top = 4)
        addTransformControl(animationPanel, TransformProperty.OPACITY, "Opacity · 0–100%", 100)
        addTransformControl(animationPanel, TransformProperty.POSITION_X, "Position X · -100% to +100%", 200)
        addTransformControl(animationPanel, TransformProperty.POSITION_Y, "Position Y · -100% to +100%", 200)
        addTransformControl(animationPanel, TransformProperty.SCALE_X, "Scale X · 25% to 300%", 300)
        addTransformControl(animationPanel, TransformProperty.SCALE_Y, "Scale Y · 25% to 300%", 300)
        addTransformControl(animationPanel, TransformProperty.ROTATION, "Rotation · -180° to +180°", 360)
        val keyframeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyframeActions.addView(button("◀ Keyframe", secondary = true) { navigateSelectedKeyframe(false) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5f) })
        keyframeActions.addView(button("Keyframe ▶", secondary = true) { navigateSelectedKeyframe(true) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(5f) })
        add(animationPanel, keyframeActions, top = 8)
        add(animationPanel, button("＋ Add keyframes for all transforms") { addAllTransformKeyframes() }, top = 8)
        add(animationPanel, body("Sliders add or update a keyframe at the playhead. Values interpolate over time and are shared by preview and export.", 11f, muted), top = 8)
        add(content, animationPanel, bottom = 13)

        val audioPanel = panel()
        sectionTargets["AUDIO"] = audioPanel
        add(audioPanel, label("Background audio · separate track"))
        add(audioPanel, body("Balance the original clip sound against the added music. The CompositionPlayer previews the same audio layer used by MP4 export.", 12f, muted), top = 5)
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
            removeAudioLayerFromTimeline()
            refreshBackgroundAudioControls()
            refreshCompositionPreview()
            status.text = "Background audio removed from the composition."
            status.setTextColor(muted)
        }, top = 8)
        refreshSourceAudioControls()
        refreshBackgroundAudioControls()
        add(content, audioPanel, bottom = 13)

        val effectsPanel = panel()
        sectionTargets["EFFECTS"] = effectsPanel
        add(effectsPanel, label("Color effects"))
        add(effectsPanel, body("Live preview and MP4 export share the same native GPU colour effects.", 12f, muted), top = 5)
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
        add(effectsPanel, label("Hue · -180° to +180°"), top = 8)
        hueSeek = SeekBar(this).apply {
            max = 360
            progress = 180
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateColorValues()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, hueSeek)
        add(effectsPanel, label("Saturation · -100 to +100"), top = 5)
        saturationSeek = SeekBar(this).apply {
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
        add(effectsPanel, saturationSeek)
        add(effectsPanel, label("Lightness · -100 to +100"), top = 5)
        lightnessSeek = SeekBar(this).apply {
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
        add(effectsPanel, lightnessSeek)
        add(effectsPanel, label("Gaussian Blur · 0 to 40"), top = 7)
        blurSeek = SeekBar(this).apply {
            max = 100
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    blurSigma = if (progress < 2) 0f else (progress / 100f) * 40f
                    refreshPreviewEffects()
                    effectSummary.text = buildEffectSummary()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, blurSeek)

        add(effectsPanel, label("SOWZ Camera Shake · original native effect"), top = 12)
        shakeToggle = button("SOWZ SHAKE · OFF", secondary = true) {
            shakeEnabled = !shakeEnabled
            refreshShakeControls()
            refreshPreviewEffects()
        }
        add(effectsPanel, shakeToggle, top = 7)
        add(effectsPanel, label("Amplitude · 0–32 px"), top = 7)
        shakeAmplitudeSeek = SeekBar(this).apply {
            max = 32
            progress = shakeAmplitudePixels.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    shakeAmplitudePixels = progress.toFloat()
                    refreshShakeControls()
                    refreshPreviewEffects()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, shakeAmplitudeSeek)
        add(effectsPanel, label("Frequency · 0.25–30 Hz"), top = 7)
        shakeFrequencySeek = SeekBar(this).apply {
            max = 120
            progress = ((shakeFrequencyHz - 0.25f) / 29.75f * max).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    shakeFrequencyHz = 0.25f + (progress / 120f) * 29.75f
                    refreshShakeControls()
                    refreshPreviewEffects()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, shakeFrequencySeek)
        add(effectsPanel, label("Rotation · 0–15°"), top = 7)
        shakeRotationSeek = SeekBar(this).apply {
            max = 150
            progress = (shakeRotationDegrees * 10f).toInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    shakeRotationDegrees = progress / 10f
                    refreshShakeControls()
                    refreshPreviewEffects()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, shakeRotationSeek)
        shakeSummary = body("OFF · deterministic motion", 11f, muted, true)
        add(effectsPanel, shakeSummary, top = 4)
        refreshShakeControls()
        add(effectsPanel, button("SOWZ FX Browser / Web Search", secondary = true) { openEffectBrowser() }, top = 10)

        add(effectsPanel, label("Main clip speed · 0.25x to 4x"), top = 12)
        mainSpeedSeek = SeekBar(this).apply {
            max = 100
            progress = 20
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateMainSpeedFromSeek()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        add(effectsPanel, mainSpeedSeek)
        speedSummary = body("Main clip speed · 1x", 11f, muted, true)
        add(effectsPanel, speedSummary, top = 4)
        effectSummary = body("Brightness 0.00 · Contrast 0.00\nHue 0° · Saturation 0 · Lightness 0\nBlur 0", 12f, accent, true)
        add(effectsPanel, effectSummary, top = 7)
        add(effectsPanel, button("Reset color controls", secondary = true) {
            shakeEnabled = false
            shakeAmplitudePixels = 8f
            shakeFrequencyHz = 8f
            shakeRotationDegrees = 1.5f
            shakeAmplitudeSeek.progress = 8
            shakeFrequencySeek.progress = 31
            shakeRotationSeek.progress = 15
            refreshShakeControls()
            brightnessSeek.progress = 100
            contrastSeek.progress = 100
            hueSeek.progress = 180
            saturationSeek.progress = 100
            lightnessSeek.progress = 100
            blurSeek.progress = 0
            mainSpeedSeek.progress = 20
            blurSigma = 0f
            mainClipSpeed = 1f
            activePreviewPlayer()?.setPlaybackSpeed(1f)
            updateColorValues()
            speedSummary.text = "Main clip speed · 1x"
            speedSummary.setTextColor(muted)
        }, top = 9)
        add(content, effectsPanel, bottom = 13)

        val lutPanel = panel()
        add(lutPanel, label("CC / 3D LUT"))
        add(lutPanel, body("Import a standard .cube colour-correction LUT. It is applied to the preview and rendered MP4; this is separate from After Effects .ffx presets.", 12f, muted), top = 5)
        lutSummary = body("No .cube CC LUT loaded", 13f, white, true)
        add(lutPanel, lutSummary, top = 9)
        add(lutPanel, button("Import CC LUT (.cube)") { pickCubeLut() }, top = 10)
        add(lutPanel, button("Remove CC LUT", secondary = true) {
            lutLoadToken++
            selectedCubeLut = null
            selectedCubeLutUri = null
            refreshCubeLutSummary()
            refreshPreviewEffects()
            status.text = "CC LUT removed."
            status.setTextColor(muted)
        }, top = 8)
        refreshCubeLutSummary()
        add(content, lutPanel, bottom = 13)

        val ffxPanel = panel()
        sectionTargets["FFX"] = ffxPanel
        add(ffxPanel, label("After Effects preset library"))
        add(ffxPanel, body("Import .ffx presets, keep the originals in SOWZ, and apply the static effect values that have a tested native mapping.", 12f, muted), top = 5)
        ffxPresetSummary = body("No FFX preset applied", 13f, white, true)
        add(ffxPanel, ffxPresetSummary, top = 9)
        add(ffxPanel, button("＋  Import and apply .FFX") { pickFfxPreset() }, top = 10)
        add(ffxPanel, button("Browse saved FFX presets", secondary = true) { showFfxPresetLibrary() }, top = 8)
        add(ffxPanel, body("Current mapping: static ADBE Brightness & Contrast only. Other effects, animation and third-party plug-ins are stored but not applied.", 11f, muted), top = 7)
        add(content, ffxPanel, bottom = 13)

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
            refreshPreviewEffects()
        }, top = 8)
        refreshTextOverlay()
        add(content, textPanel, bottom = 13)

        val exportPanel = panel()
        sectionTargets["EXPORT"] = exportPanel
        add(exportPanel, label("Project and export"))
        add(exportPanel, body("Save your editable trim points and source reference, or render the selected range to MP4.", 12f, muted), top = 5)
        add(exportPanel, button("Save project (.sowzproj)", secondary = true) { startProjectSavePicker() }, top = 10)
        status = body("Ready when a video is imported.", 12f, green)
        add(exportPanel, status, top = 11)
        exportProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        add(exportPanel, exportProgressBar, top = 9)
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
        pendingTimelineProject = null
        timelineProject = null
        selectedTimelineLayerId = null
        compositionPlayheadMs = 0L
        compositionPlayer?.release()
        compositionPlayer = null
        additionalClipUris.clear()
        additionalClipTrims.clear()
        additionalClipSpeeds.clear()
        blurSigma = 0f
        shakeEnabled = false
        shakeAmplitudePixels = 8f
        shakeFrequencyHz = 8f
        shakeRotationDegrees = 1.5f
        resetQueueHistory()
        refreshClipQueueSummary()
        if (::blurSeek.isInitialized) blurSeek.progress = 0
        if (::shakeAmplitudeSeek.isInitialized) shakeAmplitudeSeek.progress = 8
        if (::shakeFrequencySeek.isInitialized) shakeFrequencySeek.progress = 31
        if (::shakeRotationSeek.isInitialized) shakeRotationSeek.progress = 15
        if (::shakeToggle.isInitialized) refreshShakeControls()
        if (::mainSpeedSeek.isInitialized) mainSpeedSeek.progress = 20
        if (::speedSummary.isInitialized) {
            speedSummary.text = "Main clip speed · 1x"
            speedSummary.setTextColor(muted)
        }
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
            additionalClipSpeeds.add(1f)
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

    private fun editAdditionalClipSpeed(index: Int) {
        if (index !in additionalClipUris.indices) return
        val existing = additionalClipSpeeds.getOrElse(index) { 1f }
        val values = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        val labels = values.map { formatSpeed(it) }.toTypedArray()
        val selected = values.indexOfFirst { kotlin.math.abs(it - existing) < 0.001f }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Speed · " + queryDisplayName(additionalClipUris[index]))
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                additionalClipSpeeds[index] = values[which]
                recordQueueEdit()
                refreshClipQueueSummary()
                status.text = "Clip speed set to " + formatSpeed(values[which]) + "."
                status.setTextColor(if (values[which] == 1f) muted else green)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentQueueSnapshot(): ClipQueueSnapshot =
        ClipQueueSnapshot(
            uriStrings = additionalClipUris.map { it.toString() },
            trims = additionalClipTrims.toList(),
            speeds = additionalClipSpeeds.let {
                if (it.size == additionalClipUris.size) it.toList() else List(additionalClipUris.size) { 1f }
            }
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
        additionalClipSpeeds.clear()
        additionalClipSpeeds.addAll(snapshot.speeds)
        while (additionalClipSpeeds.size < additionalClipUris.size) additionalClipSpeeds.add(1f)
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
            val clipSpeed = additionalClipSpeeds.getOrElse(index) { 1f }
            info.addView(body(formatSpeed(clipSpeed) + " speed", 10f, if (clipSpeed == 1f) muted else accent, true))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

            fun addAction(glyph: String, hint: String, enabled: Boolean, action: () -> Unit) {
                row.addView(
                    queueAction(glyph, hint, enabled, action),
                    LinearLayout.LayoutParams(dp(38f), dp(38f)).apply { marginStart = dp(4f) }
                )
            }
            addAction("✂", "Edit clip IN/OUT points", true) { editAdditionalClipTrim(index) }
            addAction("▶", "Edit clip speed", true) { editAdditionalClipSpeed(index) }
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
        val reorderedSpeeds = com.sowz.aestudio.core.ClipQueueOperations.move(additionalClipSpeeds, index, target)
        additionalClipUris.clear()
        additionalClipUris.addAll(reordered)
        additionalClipTrims.clear()
        additionalClipTrims.addAll(reorderedTrims)
        additionalClipSpeeds.clear()
        additionalClipSpeeds.addAll(reorderedSpeeds)
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
        additionalClipSpeeds.add(index + 1, additionalClipSpeeds.getOrElse(index) { 1f })
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
                if (index in additionalClipSpeeds.indices) additionalClipSpeeds.removeAt(index)
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
            REQUEST_IMPORT_AEP -> loadAepProject(data?.data ?: return, data.flags)
            REQUEST_ADD_VIDEO_LAYER -> addVideoLayer(data?.data ?: return, data.flags)
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
        addAudioLayerToTimeline(uri)
        refreshBackgroundAudioControls()
        refreshCompositionPreview()
        status.text = "Background audio added · composition preview and export updated."
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
        refreshTimelineOverview()
    }

    private fun activePreviewPlayer(): Player? = compositionPlayer ?: player

    private fun refreshCompositionTimeline() {
        if (!::compositionTimelineView.isInitialized) return
        compositionTimelineView.project = timelineProject
        compositionTimelineView.selectedLayerId = selectedTimelineLayerId
        compositionTimelineView.playheadMs = compositionPlayheadMs
    }

    private fun initialiseTimelineProject(uri: Uri, sourceDurationMs: Long) {
        val mainLayer = EditLayer(
            id = "main-video",
            name = queryDisplayName(uri),
            kind = LayerKind.VIDEO,
            sourceUri = uri.toString(),
            timelineStartMs = 0L,
            sourceInMs = 0L,
            sourceOutMs = sourceDurationMs.coerceAtLeast(1L),
            zIndex = 0
        )
        val restored = pendingTimelineProject
        pendingTimelineProject = null
        timelineProject = if (restored == null) {
            TimelineProject(
                id = "sowz-${System.currentTimeMillis()}",
                name = queryDisplayName(uri).substringBeforeLast('.').ifBlank { "Untitled Composition" },
                width = 1080,
                height = 1920,
                frameRate = 30,
                durationMs = sourceDurationMs.coerceAtLeast(1L),
                layers = listOf(mainLayer)
            )
        } else {
            val kept = restored.layers.mapNotNull { layer ->
                when {
                    layer.id == "main-video" -> mainLayer.copy(
                        opacity = layer.opacity,
                        positionX = layer.positionX,
                        positionY = layer.positionY,
                        scaleX = layer.scaleX,
                        scaleY = layer.scaleY,
                        rotationDegrees = layer.rotationDegrees,
                        enabled = layer.enabled
                    )
                    layer.kind == LayerKind.VIDEO && layer.sourceUri != null -> {
                        val start = layer.timelineStartMs.coerceIn(0L, sourceDurationMs - 1L)
                        val sourceEnd = layer.sourceOutMs.coerceAtMost(sourceDurationMs - start)
                        if (sourceEnd <= layer.sourceInMs) null
                        else layer.copy(
                            timelineStartMs = start,
                            sourceOutMs = sourceEnd,
                            zIndex = layer.zIndex.coerceAtLeast(1)
                        )
                    }
                    layer.kind == LayerKind.AUDIO && layer.sourceUri != null -> layer.copy(
                        timelineStartMs = 0L,
                        sourceInMs = 0L,
                        sourceOutMs = sourceDurationMs.coerceAtLeast(1L)
                    )
                    else -> layer
                }
            }.toMutableList()
            if (kept.none { it.id == "main-video" }) kept.add(0, mainLayer)
            restored.copy(
                durationMs = sourceDurationMs.coerceAtLeast(1L),
                layers = kept.distinctBy { it.id }
            )
        }
        if (backgroundAudioUri != null && timelineProject?.layers?.none { it.kind == LayerKind.AUDIO } == true) {
            addAudioLayerToTimeline(backgroundAudioUri!!)
        }
        selectedTimelineLayerId = timelineProject?.layers?.firstOrNull()?.id
        compositionPlayheadMs = 0L
        refreshCompositionTimeline()
        refreshLayerInspector()
    }

    private fun addAudioLayerToTimeline(uri: Uri) {
        val project = timelineProject ?: return
        val existing = project.layers.firstOrNull { it.kind == LayerKind.AUDIO }
        val layer = EditLayer(
            id = existing?.id ?: "audio-main",
            name = queryDisplayName(uri),
            kind = LayerKind.AUDIO,
            sourceUri = uri.toString(),
            timelineStartMs = 0L,
            sourceInMs = 0L,
            sourceOutMs = project.durationMs.coerceAtLeast(1L),
            zIndex = 0
        )
        timelineProject = if (existing == null) TimelineEditorOps.addLayer(project, layer)
        else project.replaceLayer(layer)
        refreshCompositionTimeline()
    }

    private fun removeAudioLayerFromTimeline() {
        val project = timelineProject ?: return
        timelineProject = project.copy(layers = project.layers.filterNot { it.kind == LayerKind.AUDIO })
        if (selectedTimelineLayerId == "audio-main") {
            selectedTimelineLayerId = project.layers.firstOrNull { it.kind == LayerKind.VIDEO }?.id
        }
        refreshCompositionTimeline()
        refreshLayerInspector()
    }

    private fun pickVideoLayer() {
        if (sourceUri == null || durationMs <= 0L) {
            toast("Import the main composition video first.")
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_ADD_VIDEO_LAYER)
    }

    private fun addVideoLayer(uri: Uri, flags: Int) {
        val granted = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (granted != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, granted)
            } catch (_: Exception) { }
        }
        val name = queryDisplayName(uri)
        status.text = "Reading layer media duration…"
        status.setTextColor(accent)
        Thread {
            val duration = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0L }
                        ?: error("The video provider did not report a duration.")
                } finally {
                    retriever.release()
                }
            }
            handler.post {
                duration.onSuccess { sourceDuration ->
                    val project = timelineProject ?: run {
                        toast("Import the main video first.")
                        return@onSuccess
                    }
                    val start = compositionPlayheadMs.coerceIn(0L, (project.durationMs - 1L).coerceAtLeast(0L))
                    val length = min(sourceDuration, (project.durationMs - start).coerceAtLeast(1L))
                    val nextZ = (project.layers.filter { it.kind == LayerKind.VIDEO }.maxOfOrNull { it.zIndex } ?: 0) + 1
                    val layer = EditLayer(
                        id = "video-layer-${System.currentTimeMillis()}",
                        name = name.substringBeforeLast('.').ifBlank { "Video layer" },
                        kind = LayerKind.VIDEO,
                        sourceUri = uri.toString(),
                        timelineStartMs = start,
                        sourceInMs = 0L,
                        sourceOutMs = length.coerceAtLeast(1L),
                        zIndex = nextZ
                    )
                    runCatching { TimelineEditorOps.addLayer(project, layer) }
                        .onSuccess { updated ->
                            timelineProject = updated
                            selectedTimelineLayerId = layer.id
                            refreshCompositionTimeline()
                            refreshLayerInspector()
                            refreshCompositionPreview()
                            status.text = "Added V${nextZ + 1} · ${layer.name} · ${formatTime(length)}"
                            status.setTextColor(green)
                            toast("Video layer added to the composition.")
                        }
                        .onFailure { toast(it.message ?: "Could not add this video layer.") }
                }.onFailure { error ->
                    status.text = "Could not inspect layer media: ${error.message ?: "unknown error"}"
                    status.setTextColor(accent)
                }
            }
        }.start()
    }

    private fun createPreviewComposition(): Composition {
        val source = sourceUri ?: error("No source video is loaded.")
        val project = timelineProject ?: error("Composition timeline is not initialized.")
        val mainLayer = project.layers.firstOrNull { it.id == "main-video" }
            ?: error("The composition has no primary video layer.")

        val mainMedia = MediaItem.fromUri(source)
        val mainEdited = EditedMediaItem.Builder(mainMedia)
            .setDurationUs(durationMs.coerceAtLeast(1L) * 1_000L)
            .setEffects(
                Effects(
                    buildAudioProcessors(sourceAudioSettings.gain),
                    buildVideoEffects() + SowzLayerTransformEffect(mainLayer)
                )
            )
            .build()
        val sequences = mutableListOf(
            EditedMediaItemSequence.withAudioAndVideoFrom(listOf(mainEdited))
        )

        project.layers
            .filter { it.kind == LayerKind.VIDEO && it.id != "main-video" && it.enabled && !it.sourceUri.isNullOrBlank() }
            .sortedBy { it.zIndex }
            .forEach { layer ->
                val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                if (layer.timelineStartMs > 0L) builder.addGap(layer.timelineStartMs * 1_000L)
                val media = MediaItem.Builder()
                    .setUri(Uri.parse(layer.sourceUri))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(layer.sourceInMs)
                            .setEndPositionMs(layer.sourceOutMs)
                            .build()
                    )
                    .build()
                val edited = EditedMediaItem.Builder(media)
                    .setDurationUs(layer.durationMs * 1_000L)
                    .setEffects(Effects(emptyList(), listOf(SowzLayerTransformEffect(layer))))
                    .build()
                sequences.add(builder.addItem(edited).build())
            }

        backgroundAudioUri?.let { audioUri ->
            val audio = EditedMediaItem.Builder(MediaItem.fromUri(audioUri))
                .setDurationUs(project.durationMs * 1_000L)
                .setEffects(Effects(buildAudioProcessors(backgroundAudioSettings.gain), emptyList()))
                .build()
            val audioSequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(audio)
                .setIsLooping(true)
                .build()
            sequences.add(audioSequence)
        }
        return Composition.Builder(sequences).build()
    }

    private fun refreshCompositionPreview() {
        if (!::playerView.isInitialized || sourceUri == null || durationMs <= 0L) return
        val project = timelineProject
        val needsCompositionPlayer = project?.layers?.any {
            it.kind == LayerKind.VIDEO && it.id != "main-video" && it.enabled
        } == true || backgroundAudioUri != null

        if (!needsCompositionPlayer) {
            if (compositionPlayer != null) {
                val old = compositionPlayer
                compositionPlayer = null
                playerView.player = player
                old?.release()
            }
            return
        }

        val position = (activePreviewPlayer()?.currentPosition ?: compositionPlayheadMs)
            .coerceIn(0L, durationMs)
        val wasPlaying = activePreviewPlayer()?.playWhenReady == true
        val old = compositionPlayer
        runCatching {
            val next = CompositionPlayer.Builder(this).build()
            next.setComposition(createPreviewComposition(), position)
            compositionPlayer = next
            playerView.player = next
            next.prepare()
            next.playWhenReady = wasPlaying
            old?.release()
        }.onFailure { error ->
            compositionPlayer = null
            playerView.player = player
            old?.release()
            status.text = "Layered preview unavailable: ${error.message ?: "unsupported composition"}"
            status.setTextColor(accent)
        }
    }

    private fun refreshPreviewEffects() {
        player?.setVideoEffects(buildVideoEffects())
        if (compositionPlayer != null) refreshCompositionPreview()
    }

    private fun refreshKeyframeSummary() {
        if (!::keyframeSummary.isInitialized) return
        val selected = timelineProject?.layers?.firstOrNull { it.id == selectedTimelineLayerId }
        if (selected == null) {
            keyframeSummary.text = "Select a layer to inspect keyframes."
            return
        }
        val count = listOf(
            selected.opacity, selected.positionX, selected.positionY,
            selected.scaleX, selected.scaleY, selected.rotationDegrees
        ).sumOf { it.keyframes.size }
        val times = TimelineEditorOps.keyframeTimes(selected)
        keyframeSummary.text = if (count == 0) {
            "No keyframes · playhead ${formatTime(compositionPlayheadMs)}"
        } else {
            "$count keyframe(s) · " + times.joinToString("  ·  ") { formatTime(it) }
        }
    }

    private fun refreshLayerInspector() {
        val selected = timelineProject?.layers?.firstOrNull { it.id == selectedTimelineLayerId }
        if (selected == null) {
            if (::selectedLayerSummary.isInitialized) selectedLayerSummary.text = "Select a layer to edit its transform."
            refreshKeyframeSummary()
            return
        }
        selectedLayerSummary.text = "${selected.name} · ${selected.kind.name} · ${if (selected.enabled) "VISIBLE" else "HIDDEN"}"
        val localTime = (compositionPlayheadMs - selected.timelineStartMs).coerceAtLeast(0L)
        transformSeekBars.forEach { (property, seek) ->
            val value = when (property) {
                TransformProperty.OPACITY -> selected.opacity.valueAt(localTime)
                TransformProperty.POSITION_X -> selected.positionX.valueAt(localTime)
                TransformProperty.POSITION_Y -> selected.positionY.valueAt(localTime)
                TransformProperty.SCALE_X -> selected.scaleX.valueAt(localTime)
                TransformProperty.SCALE_Y -> selected.scaleY.valueAt(localTime)
                TransformProperty.ROTATION -> selected.rotationDegrees.valueAt(localTime)
            }
            seek.progress = transformProgress(property, value)
            seek.isEnabled = selected.kind != LayerKind.AUDIO
        }
        refreshKeyframeSummary()
    }

    private fun addTransformControl(
        parent: LinearLayout,
        property: TransformProperty,
        title: String,
        maxProgress: Int
    ) {
        add(parent, label(title), top = 9)
        val seek = SeekBar(this).apply {
            max = maxProgress
            progress = transformProgress(property, defaultTransformValue(property))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    updateSelectedLayerTransform(property, transformValue(property, progress))
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    refreshCompositionPreview()
                    refreshKeyframeSummary()
                }
            })
        }
        transformSeekBars[property] = seek
        add(parent, seek)
    }

    private fun defaultTransformValue(property: TransformProperty): Float = when (property) {
        TransformProperty.OPACITY, TransformProperty.SCALE_X, TransformProperty.SCALE_Y -> 1f
        TransformProperty.POSITION_X, TransformProperty.POSITION_Y, TransformProperty.ROTATION -> 0f
    }

    private fun transformValue(property: TransformProperty, progress: Int): Float = when (property) {
        TransformProperty.OPACITY -> progress / 100f
        TransformProperty.POSITION_X, TransformProperty.POSITION_Y -> (progress - 100) / 100f
        TransformProperty.SCALE_X, TransformProperty.SCALE_Y -> 0.25f + (progress / 300f) * 2.75f
        TransformProperty.ROTATION -> (progress - 180).toFloat()
    }

    private fun transformProgress(property: TransformProperty, value: Float): Int = when (property) {
        TransformProperty.OPACITY -> (value.coerceIn(0f, 1f) * 100f).roundToInt()
        TransformProperty.POSITION_X, TransformProperty.POSITION_Y ->
            ((value.coerceIn(-1f, 1f) + 1f) * 100f).roundToInt()
        TransformProperty.SCALE_X, TransformProperty.SCALE_Y ->
            (((value.coerceIn(0.25f, 3f) - 0.25f) / 2.75f) * 300f).roundToInt()
        TransformProperty.ROTATION -> (value.coerceIn(-180f, 180f) + 180f).roundToInt()
    }

    private fun updateSelectedLayerTransform(property: TransformProperty, value: Float) {
        val id = selectedTimelineLayerId ?: return
        val project = timelineProject ?: return
        val layer = project.layers.firstOrNull { it.id == id } ?: return
        if (layer.kind == LayerKind.AUDIO) return
        val time = compositionPlayheadMs.coerceIn(layer.timelineStartMs, layer.timelineEndMs)
        runCatching {
            timelineProject = TimelineEditorOps.addKeyframe(project, id, property, time, value)
            compositionTimelineView.project = timelineProject
            selectedTimelineLayerId = id
            refreshKeyframeSummary()
        }.onFailure { toast(it.message ?: "Could not create the keyframe.") }
    }

    private fun addAllTransformKeyframes() {
        val id = selectedTimelineLayerId ?: return
        var updated = timelineProject ?: return
        val layer = updated.layers.firstOrNull { it.id == id } ?: return
        if (layer.kind == LayerKind.AUDIO) {
            toast("Audio gain automation is not available in this transform panel yet.")
            return
        }
        val time = compositionPlayheadMs.coerceIn(layer.timelineStartMs, layer.timelineEndMs)
        TransformProperty.values().forEach { property ->
            val value = TimelineEditorOps.propertyValueAt(updated, id, property, time)
            updated = TimelineEditorOps.addKeyframe(updated, id, property, time, value)
        }
        timelineProject = updated
        refreshCompositionTimeline()
        refreshLayerInspector()
        refreshCompositionPreview()
        status.text = "Transform keyframes added at ${formatTime(time)}."
        status.setTextColor(green)
    }

    private fun navigateSelectedKeyframe(forward: Boolean) {
        val layer = timelineProject?.layers?.firstOrNull { it.id == selectedTimelineLayerId } ?: return
        val times = TimelineEditorOps.keyframeTimes(layer)
        val target = if (forward) times.firstOrNull { it > compositionPlayheadMs }
        else times.lastOrNull { it < compositionPlayheadMs }
        if (target == null) {
            toast(if (forward) "There is no next keyframe." else "There is no previous keyframe.")
            return
        }
        compositionPlayheadMs = target
        activePreviewPlayer()?.seekTo(target)
        if (durationMs > 0L) scrubber.progress = ((target.toDouble() / durationMs) * SCALE_MAX).toInt().coerceIn(0, SCALE_MAX)
        if (::compositionTimelineView.isInitialized) compositionTimelineView.playheadMs = target
        refreshLayerInspector()
    }

    private fun pickProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_PROJECT)
    }

    private fun pickAepProject() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_AEP)
    }

    private fun loadAepProject(uri: Uri, flags: Int) {
        val name = queryDisplayName(uri)
        if (!name.endsWith(".aep", ignoreCase = true)) {
            status.text = "This reader currently expects a binary .aep file. AEPX XML import is not enabled."
            status.setTextColor(accent)
            toast("Choose a binary .aep project.")
            return
        }
        try {
            if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                contentResolver.takePersistableUriPermission(
                    uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (_: Exception) {
            // Some document providers only issue temporary grants.
        }
        status.text = "Reading After Effects project: $name…"
        status.setTextColor(accent)
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 96 * 1024 * 1024) {
                            "AEP exceeds the 96 MiB on-device parser limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Could not read the selected AEP file.")
                AepProjectImporter.parse(AepNativeReader().inspectAep(bytes))
            }
            handler.post {
                result.onSuccess { summary ->
                    val mapped = NativeEffectMapper.map(summary.effects)
                    AlertDialog.Builder(this)
                        .setTitle("AEP analysis · $name")
                        .setMessage(
                            summary.reportText() + "\n\nSOWZ-compatible static effects: " +
                                if (mapped.hasSupportedValues) mapped.appliedEffects.joinToString(", ")
                                else "none found"
                        )
                        .setPositiveButton(
                            if (mapped.hasSupportedValues) "Apply compatible effects" else "Close"
                        ) { _, _ ->
                            if (mapped.hasSupportedValues) applyNativeEffectStack(mapped, name)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    status.text = "AEP scanned · ${summary.compositionCount} comps · ${summary.layerCount} layers · ${summary.effects.size} effects"
                    status.setTextColor(green)
                }.onFailure { error ->
                    status.text = "Could not read AEP: ${error.message ?: "unknown parser error"}"
                    status.setTextColor(accent)
                    toast("AEP import failed. The file may use an unsupported or damaged structure.")
                }
            }
        }.start()
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
                .put("hueAdjustmentDegrees", colorGrade.hueAdjustmentDegrees.toDouble())
                .put("saturationAdjustment", colorGrade.saturationAdjustment.toDouble())
                .put("lightnessAdjustment", colorGrade.lightnessAdjustment.toDouble())
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
                            .put("trimEndMs", trim.endMs ?: -1L)
                            .put("speed", additionalClipSpeeds.getOrElse(index) { 1f }))
                    }
                })
                .put("mainClipSpeed", mainClipSpeed.toDouble())
                .put("blurSigma", blurSigma.toDouble())
                .put("shakeEnabled", shakeEnabled)
                .put("shakeAmplitudePixels", shakeAmplitudePixels.toDouble())
                .put("shakeFrequencyHz", shakeFrequencyHz.toDouble())
                .put("shakeRotationDegrees", shakeRotationDegrees.toDouble())
                .put("timelineProject", timelineProject?.let { TimelineProjectStore.encode(it) })
                .put("frameRate", 30)
                .put("createdWith", "SOWZ AE Studio 1.3.0")
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
                if (result.size > 20 * 1024 * 1024) error("Project file exceeds the 20 MB safety limit.")
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
            val timelineJson = document.optString("timelineProject")
            pendingTimelineProject = timelineJson.takeIf { it.isNotBlank() && it != "null" }
                ?.let { TimelineProjectStore.decode(it) }
            additionalClipUris.clear()
            additionalClipTrims.clear()
            additionalClipSpeeds.clear()
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
                    val savedSpeed = clipObject?.optDouble("speed", 1.0)?.toFloat() ?: 1f
                    additionalClipTrims.add(range)
                    additionalClipSpeeds.add(if (savedSpeed.isFinite()) savedSpeed.coerceIn(0.25f, 4f) else 1f)
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
            val savedHue = document.optDouble("hueAdjustmentDegrees", 0.0).toFloat()
            val savedSaturation = document.optDouble("saturationAdjustment", 0.0).toFloat()
            val savedLightness = document.optDouble("lightnessAdjustment", 0.0).toFloat()
            val savedMainSpeed = document.optDouble("mainClipSpeed", 1.0).toFloat()
            val savedBlurSigma = document.optDouble("blurSigma", 0.0).toFloat()
            shakeEnabled = document.optBoolean("shakeEnabled", false)
            val savedShakeAmplitude = document.optDouble("shakeAmplitudePixels", 8.0).toFloat()
            val savedShakeFrequency = document.optDouble("shakeFrequencyHz", 8.0).toFloat()
            val savedShakeRotation = document.optDouble("shakeRotationDegrees", 1.5).toFloat()
            shakeAmplitudePixels = if (savedShakeAmplitude.isFinite()) savedShakeAmplitude.coerceIn(0f, 32f) else 8f
            shakeFrequencyHz = if (savedShakeFrequency.isFinite()) savedShakeFrequency.coerceIn(0.25f, 30f) else 8f
            shakeRotationDegrees = if (savedShakeRotation.isFinite()) savedShakeRotation.coerceIn(0f, 15f) else 1.5f
            mainClipSpeed = if (savedMainSpeed.isFinite()) savedMainSpeed.coerceIn(0.25f, 4f) else 1f
            blurSigma = if (savedBlurSigma.isFinite()) savedBlurSigma.coerceIn(0f, 40f) else 0f
            colorGrade = ColorGradeSettings(
                hueAdjustmentDegrees = if (savedHue.isFinite()) savedHue.coerceIn(-180f, 180f) else 0f,
                saturationAdjustment = if (savedSaturation.isFinite()) savedSaturation.coerceIn(-100f, 100f) else 0f,
                lightnessAdjustment = if (savedLightness.isFinite()) savedLightness.coerceIn(-100f, 100f) else 0f
            )
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
            hueSeek.progress = (colorGrade.hueAdjustmentDegrees + 180f).toInt().coerceIn(0, 360)
            saturationSeek.progress = (colorGrade.saturationAdjustment + 100f).toInt().coerceIn(0, 200)
            lightnessSeek.progress = (colorGrade.lightnessAdjustment + 100f).toInt().coerceIn(0, 200)
            blurSeek.progress = kotlin.math.round((blurSigma / 40f) * 100f).toInt().coerceIn(0, 100)
            shakeAmplitudeSeek.progress = shakeAmplitudePixels.toInt().coerceIn(0, 32)
            shakeFrequencySeek.progress = (((shakeFrequencyHz - 0.25f) / 29.75f) * 120f).toInt().coerceIn(0, 120)
            shakeRotationSeek.progress = (shakeRotationDegrees * 10f).toInt().coerceIn(0, 150)
            refreshShakeControls()
            mainSpeedSeek.progress = kotlin.math.round(((mainClipSpeed - 0.25f) / 3.75f) * 100f).toInt().coerceIn(0, 100)
            updateColorValues()
            updateMainSpeedFromSeek()
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
                        nextPlayer.setPlaybackSpeed(mainClipSpeed)
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
                        initialiseTimelineProject(uri, value)
                        playbackTime.text = "${formatTime(0L)} / ${formatTime(value)}"
                        refreshCompositionPreview()
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
        nextPlayer.setPlaybackSpeed(mainClipSpeed)
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
            refreshTimelineOverview()
            return
        }
        clipStartMs = (durationMs * inPoint.progress / SCALE_MAX).coerceIn(0L, durationMs)
        clipEndMs = (durationMs * outPoint.progress / SCALE_MAX).coerceIn(0L, durationMs)
        if (clipEndMs <= clipStartMs) {
            trimSummary.text = "Choose a valid IN and OUT range."
        } else {
            trimSummary.text = "IN ${formatTime(clipStartMs)}   ·   OUT ${formatTime(clipEndMs)}   ·   ${String.format(Locale.ROOT, "%.2f", (clipEndMs - clipStartMs) / 1000.0)} s"
        }
        refreshTimelineOverview()
    }

    private fun refreshTimelineOverview() {
        if (!::timelineOverview.isInitialized) return
        timelineOverview.durationMs = durationMs
        timelineOverview.inMs = clipStartMs
        timelineOverview.outMs = clipEndMs
        timelineOverview.playheadMs = activePreviewPlayer()?.currentPosition?.coerceAtLeast(0L) ?: 0L
        timelineOverview.hasAudioTrack = backgroundAudioUri != null
        refreshCompositionTimeline()
    }

    private fun updateColorValues() {
        brightnessValue = ((brightnessSeek.progress - 100) / 100f).coerceIn(-1f, 1f)
        contrastValue = ((contrastSeek.progress - 100) / 100f).coerceIn(-1f, 1f)
        colorGrade = ColorGradeSettings(
            hueAdjustmentDegrees = (hueSeek.progress - 180).toFloat(),
            saturationAdjustment = (saturationSeek.progress - 100).toFloat(),
            lightnessAdjustment = (lightnessSeek.progress - 100).toFloat()
        )
        effectSummary.text = buildEffectSummary()
        refreshPreviewEffects()
    }

    private fun refreshShakeControls() {
        if (!::shakeToggle.isInitialized) return
        shakeToggle.text = if (shakeEnabled) "SOWZ SHAKE · ON" else "SOWZ SHAKE · OFF"
        shakeSummary.text = (if (shakeEnabled) "ON" else "OFF") +
            " · ${shakeAmplitudePixels.toInt()} px · ${String.format(Locale.ROOT, "%.1f", shakeFrequencyHz)} Hz · ${String.format(Locale.ROOT, "%.1f", shakeRotationDegrees)}°"
        shakeSummary.setTextColor(if (shakeEnabled) accent else muted)
    }

    private fun applyNativeEffectStack(stack: NativeEffectStack, sourceName: String) {
        stack.brightness?.let { brightnessValue = it }
        stack.contrast?.let { contrastValue = it }
        stack.hueDegrees?.let { colorGrade = colorGrade.copy(hueAdjustmentDegrees = it) }
        stack.saturation?.let { colorGrade = colorGrade.copy(saturationAdjustment = it) }
        stack.lightness?.let { colorGrade = colorGrade.copy(lightnessAdjustment = it) }
        stack.blurSigma?.let { blurSigma = it }
        if (::brightnessSeek.isInitialized) brightnessSeek.progress = ((brightnessValue + 1f) * 100f).toInt().coerceIn(0, 200)
        if (::contrastSeek.isInitialized) contrastSeek.progress = ((contrastValue + 1f) * 100f).toInt().coerceIn(0, 200)
        if (::hueSeek.isInitialized) hueSeek.progress = (colorGrade.hueAdjustmentDegrees + 180f).toInt().coerceIn(0, 360)
        if (::saturationSeek.isInitialized) saturationSeek.progress = (colorGrade.saturationAdjustment + 100f).toInt().coerceIn(0, 200)
        if (::lightnessSeek.isInitialized) lightnessSeek.progress = (colorGrade.lightnessAdjustment + 100f).toInt().coerceIn(0, 200)
        if (::blurSeek.isInitialized) blurSeek.progress = (blurSigma / 40f * 100f).toInt().coerceIn(0, 100)
        if (::effectSummary.isInitialized) updateColorValues()
        refreshPreviewEffects()

        val applied = stack.appliedEffects.joinToString(", ").ifBlank { "none" }
        val skipped = stack.skippedEffects.take(8).joinToString(", ").ifBlank { "none" }
        status.text = "$sourceName · applied: $applied · unsupported/animated: $skipped"
        status.setTextColor(if (stack.hasSupportedValues) green else accent)
        if (::ffxPresetSummary.isInitialized) {
            ffxPresetSummary.text = "$sourceName · ${if (stack.hasSupportedValues) "compatible effects applied" else "stored / scanned only"}"
        }
        toast(if (stack.hasSupportedValues) "Compatible native effects applied." else "File read; no supported static effects found.")
    }

    private fun openEffectBrowser() {
        val query = EditText(this).apply {
            setSingleLine(true)
            setText("Sapphire S_Shake official documentation and download")
            setSelection(text.length)
            hint = "Search effects and plug-ins"
            setTextColor(white)
            setHintTextColor(muted)
        }
        val info = body(
            "SOWZ Camera Shake is a native Android effect. Desktop .aex plug-ins are not Android libraries. Use the official publisher for commercial desktop plug-ins.",
            12f, muted
        )
        val content = panel()
        add(content, info)
        add(content, query, top = 10)
        AlertDialog.Builder(this)
            .setTitle("SOWZ FX Browser")
            .setView(content)
            .setPositiveButton("Search Web") { _, _ ->
                val search = Uri.encode(query.text.toString().trim())
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$search")))
                }.onFailure { toast("No browser is available to open web search.") }
            }
            .setNeutralButton("Official Sapphire downloads") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://borisfx.com/downloads/?product=sapphire&os=windows%2bmac%2blinux")))
                }.onFailure { toast("Could not open the official publisher page.") }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun buildEffectSummary(): String = buildString {
        append("Brightness ").append(String.format(Locale.ROOT, "%.2f", brightnessValue))
        append(" · Contrast ").append(String.format(Locale.ROOT, "%.2f", contrastValue))
        append("\nHue ").append(colorGrade.hueAdjustmentDegrees.toInt()).append("°")
        append(" · Saturation ").append(colorGrade.saturationAdjustment.toInt())
        append(" · Lightness ").append(colorGrade.lightnessAdjustment.toInt())
        append("\nBlur ").append(if (blurSigma < 0.01f) "0" else String.format(Locale.ROOT, "%.1f", blurSigma))
        append("\nSOWZ Shake ").append(if (shakeEnabled) "ON" else "OFF")
    }

    private fun updateMainSpeedFromSeek() {
        mainClipSpeed = 0.25f + (mainSpeedSeek.progress / 100f) * 3.75f
        mainClipSpeed = (kotlin.math.round(mainClipSpeed * 100f) / 100f).coerceIn(0.25f, 4f)
        speedSummary.text = "Main clip speed · " + formatSpeed(mainClipSpeed)
        speedSummary.setTextColor(if (kotlin.math.abs(mainClipSpeed - 1f) < 0.001f) muted else accent)
        activePreviewPlayer()?.setPlaybackSpeed(mainClipSpeed)
    }

    private fun formatSpeed(speed: Float): String =
        if (kotlin.math.abs(speed - speed.toInt()) < 0.001f) speed.toInt().toString() + "x"
        else String.format(Locale.ROOT, "%.2fx", speed)

    private fun buildSpeedProvider(speed: Float): SpeedProvider = object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
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
        if (abs(colorGrade.hueAdjustmentDegrees) > 0.001f ||
            abs(colorGrade.saturationAdjustment) > 0.001f ||
            abs(colorGrade.lightnessAdjustment) > 0.001f
        ) {
            effects.add(
                HslAdjustment.Builder()
                    .adjustHue(colorGrade.hueAdjustmentDegrees)
                    .adjustSaturation(colorGrade.saturationAdjustment)
                    .adjustLightness(colorGrade.lightnessAdjustment)
                    .build()
            )
        }
        if (blurSigma > 0.01f) effects.add(GaussianBlur(blurSigma))
        if (shakeEnabled && (shakeAmplitudePixels > 0.01f || shakeRotationDegrees > 0.01f)) {
            effects.add(
                SowzShakeEffect(
                    amplitudePixels = shakeAmplitudePixels,
                    frequencyHz = shakeFrequencyHz,
                    rotationDegrees = shakeRotationDegrees,
                    seed = 1
                )
            )
        }
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
                    refreshPreviewEffects()
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
        status.text = "Saving and decoding FFX preset: $name…"
        status.setTextColor(accent)
        Thread {
            val result = runCatching {
                val store = PresetStore(this)
                val entry = store.import(contentResolver, uri, name)
                val effects = FfxParser.parseEffects(store.readBytes(entry))
                entry.fileName to effects
            }
            handler.post {
                result.onSuccess { (savedName, effects) ->
                    applyParsedFfxPreset(savedName, effects)
                }.onFailure { error ->
                    status.text = "Could not import FFX: ${error.message ?: "unknown format"}"
                    status.setTextColor(accent)
                    toast("FFX import failed. Check that the file is a valid After Effects preset.")
                }
            }
        }.start()
    }

    private fun showFfxPresetLibrary() {
        val entries = PresetStore(this).list()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("FFX preset library")
                .setMessage("No presets saved yet. Import an .ffx preset to add it to this library.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = entries.map { it.fileName + "  ·  " + formatFileSize(it.sizeBytes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Apply saved FFX preset")
            .setItems(labels) { _, index ->
                entries.getOrNull(index)?.let { applyStoredFfxPreset(it.fileName) }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun applyStoredFfxPreset(fileName: String) {
        val entry = PresetStore(this).list().firstOrNull { it.fileName == fileName }
        if (entry == null) {
            status.text = "This preset is no longer in the local library."
            status.setTextColor(accent)
            return
        }
        status.text = "Loading saved preset: $fileName…"
        status.setTextColor(accent)
        Thread {
            val result = runCatching {
                val bytes = PresetStore(this).readBytes(entry)
                fileName to FfxParser.parseEffects(bytes)
            }
            handler.post {
                result.onSuccess { (savedName, effects) ->
                    applyParsedFfxPreset(savedName, effects)
                }.onFailure { error ->
                    status.text = "Could not read saved FFX: ${error.message ?: "unknown error"}"
                    status.setTextColor(accent)
                }
            }
        }.start()
    }

    private fun applyParsedFfxPreset(name: String, effects: List<FfxEffect>) {
        if (::ffxPresetSummary.isInitialized) {
            ffxPresetSummary.text = name
            ffxPresetSummary.setTextColor(white)
        }
        if (effects.isEmpty()) {
            status.text = "Preset saved, but no effect blocks could be decoded."
            status.setTextColor(accent)
            if (::ffxPresetSummary.isInitialized) {
                ffxPresetSummary.text = "$name · stored, no values applied"
            }
            toast("FFX stored. No editable effect values were found.")
            return
        }
        applyNativeEffectStack(NativeEffectMapper.fromFfx(effects), name)
    }

    private fun formatFileSize(size: Long): String =
        when {
            size < 1024L -> "$size B"
            size < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", size / 1024f)
            else -> String.format(Locale.ROOT, "%.1f MB", size / (1024f * 1024f))
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
                refreshPreviewEffects()
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
        refreshPreviewEffects()
    }

    private fun refreshRotationControls() {
        if (::rotationSummary.isInitialized) rotationSummary.text = "Rotation: ${rotationDegrees}°"
        rotationButtons.forEach { (degrees, control) ->
            val selected = degrees == rotationDegrees
            control.background = rounded(if (selected) accent else surface2, 12f, if (selected) null else border)
            control.setTextColor(if (selected) Color.rgb(25, 19, 14) else white)
        }
    }

    private fun startExportProgressPolling() {
        handler.removeCallbacks(syncExportProgress)
        handler.post(syncExportProgress)
    }

    private fun stopExportProgressPolling() {
        handler.removeCallbacks(syncExportProgress)
    }

    private fun resetExportProgressUi() {
        if (::exportProgressBar.isInitialized) {
            exportProgressBar.progress = 0
            exportProgressBar.visibility = View.GONE
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
            .setSpeed(buildSpeedProvider(mainClipSpeed))
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
                    .setSpeed(buildSpeedProvider(additionalClipSpeeds.getOrElse(index) { 1f }))
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
                    stopExportProgressPolling()
                    if (::exportProgressBar.isInitialized) exportProgressBar.progress = 100
                    copyExportToDestination(temp, destination)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    runOnUiThread {
                        stopExportProgressPolling()
                        resetExportProgressUi()
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
            startExportProgressPolling()
        } catch (error: Exception) {
            stopExportProgressPolling()
            resetExportProgressUi()
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
                    stopExportProgressPolling()
                    status.text = "Export complete · " + exportClipCount + " clip(s) · " + (temp.length() / 1024L) + " KB written."
                    status.setTextColor(green)
                    toast("MP4 export completed.")
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    temporaryOutput = null
                    transformer = null
                    resetExportProgressUi()
                    temp.delete()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    stopExportProgressPolling()
                    status.text = "Could not save exported video: " + (error.message ?: "unknown error")
                    status.setTextColor(accent)
                    exportButton.isEnabled = true
                    exportButton.alpha = 1f
                    temporaryOutput = null
                    transformer = null
                    resetExportProgressUi()
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
        stopExportProgressPolling()
        temporaryOutput?.delete()
        temporaryOutput = null
        transformer = null
        resetExportProgressUi()
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
