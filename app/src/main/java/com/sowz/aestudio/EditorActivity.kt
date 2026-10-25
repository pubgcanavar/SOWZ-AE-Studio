package com.sowz.aestudio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * First real editing path: import a local video, preview it, select a trim range and export MP4.
 * This is intentionally a working foundation, not a claim of full After Effects compatibility.
 */
class EditorActivity : Activity() {
    private companion object {
        const val REQUEST_IMPORT_VIDEO = 2101
        const val REQUEST_EXPORT_FILE = 2102
        const val SCALE_MAX = 1000
        const val MIN_CLIP_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private var sourceUri: Uri? = null
    private var pendingExportUri: Uri? = null
    private var temporaryOutput: File? = null
    private var durationMs = 0L
    private var clipStartMs = 0L
    private var clipEndMs = 0L
    private var draggingScrubber = false

    private lateinit var playerView: PlayerView
    private lateinit var scrubber: SeekBar
    private lateinit var inPoint: SeekBar
    private lateinit var outPoint: SeekBar
    private lateinit var playbackTime: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var status: TextView
    private lateinit var trimSummary: TextView
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

        val exportPanel = panel()
        add(exportPanel, label("Export"))
        add(exportPanel, body("Exports the selected video range to a new MP4 file using Android Media3 Transformer.", 12f, muted), top = 5)
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
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT_VIDEO)
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
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMPORT_VIDEO -> loadVideo(uri, data.flags)
            REQUEST_EXPORT_FILE -> {
                pendingExportUri = uri
                beginExport()
            }
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
                        clipStartMs = 0L
                        clipEndMs = value
                        inPoint.progress = 0
                        outPoint.progress = SCALE_MAX
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
        val editedItem = EditedMediaItem.Builder(clippedItem).build()
        status.text = "Rendering selected range… keep the app open until export finishes."
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
            activeTransformer.start(editedItem, temp.absolutePath)
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
                    status.text = "Export complete · ${temp.length() / 1024L} KB written to your chosen location."
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
