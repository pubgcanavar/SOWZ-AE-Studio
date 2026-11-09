package com.sowz.aestudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.sowz.aestudio.core.EditLayer
import com.sowz.aestudio.core.LayerKind
import com.sowz.aestudio.core.TimelineEditorOps
import com.sowz.aestudio.core.TimelineProject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Editable multi-track timeline for the native SOWZ composition model.
 * Dragging a clip changes its timelineStartMs; dragging the ruler moves the playhead.
 */
internal class MultiTrackTimelineView(context: Context) : View(context) {
    var project: TimelineProject? = null
        set(value) {
            field = value
            invalidate()
            requestLayout()
        }

    var selectedLayerId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var playheadMs: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            invalidate()
        }

    var onSeek: ((Long) -> Unit)? = null
    var onSelectLayer: ((String) -> Unit)? = null
    var onMoveLayer: ((String, Long) -> Unit)? = null
    var onSelectKeyframe: ((String, Long) -> Unit)? = null

    private enum class DragMode { NONE, PLAYHEAD, LAYER }
    private var dragMode = DragMode.NONE
    private var draggedLayerId: String? = null
    private var dragStartX = 0f
    private var dragOriginalStartMs = 0L
    private var dragTargetMs = 0L

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val labelWidth get() = dp(58f)
    private val rulerHeight get() = dp(31f)
    private val rowHeight get() = dp(45f)
    private val bottomPadding get() = dp(10f)
    private val pxPerSecond get() = dp(76f)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff14171c.toInt() }
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff1e222a.toInt() }
    private val lanePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff191d24.toInt() }
    private val laneAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff171b22.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff343d4b.toInt()
        strokeWidth = dp(1f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff657080.toInt()
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff929eae.toInt()
        textSize = dp(9f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
    }
    private val brightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe5eaf2.toInt()
        textSize = dp(10f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", 1)
    }
    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff65b9ff.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val keyframePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffc56b.toInt() }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff53a8ff.toInt()
        strokeWidth = dp(1.5f)
    }
    private val whiteDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffeaf2ff.toInt() }

    private val layers: List<EditLayer>
        get() = project?.layers.orEmpty().sortedWith(
            compareByDescending<EditLayer> { it.zIndex }.thenBy { it.name }
        )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val p = project
        val desiredWidth = if (p == null) dp(320f).roundToInt() else {
            val timelineWidth = p.durationMs / 1000f * pxPerSecond
            max(
                MeasureSpec.getSize(widthMeasureSpec).toFloat(),
                labelWidth + timelineWidth + dp(28f)
            ).roundToInt().coerceAtMost(dp(12_000f).roundToInt())
        }
        val rowCount = max(2, layers.size)
        val desiredHeight = (rulerHeight + rowCount * rowHeight + bottomPadding).roundToInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundPaint.color)
        val p = project
        if (p == null) {
            canvas.drawText("Import media to create a composition", dp(14f), dp(27f), textPaint)
            return
        }
        val totalMs = p.durationMs.coerceAtLeast(1L)
        val contentWidth = (width - labelWidth - dp(10f)).coerceAtLeast(dp(40f))
        val pixelsPerMs = pxPerSecond / 1000f

        canvas.drawRect(0f, 0f, width.toFloat(), rulerHeight, rulerPaint)
        canvas.drawRect(0f, rulerHeight, labelWidth, height.toFloat(), rulerPaint)

        val tickStepSeconds = if (p.durationMs <= 30_000L) 1 else if (p.durationMs <= 120_000L) 5 else 10
        var second = 0
        while (second * 1000L <= totalMs) {
            val x = labelWidth + second * pxPerSecond
            if (x <= width - dp(3f)) {
                canvas.drawLine(x, rulerHeight, x, height.toFloat(), linePaint)
                canvas.drawLine(x, dp(21f), x, rulerHeight, tickPaint)
                if (second % tickStepSeconds == 0) {
                    val text = if (second >= 60) {
                        "%d:%02d".format(second / 60, second % 60)
                    } else {
                        "00:%02d".format(second)
                    }
                    canvas.drawText(text, x + dp(4f), dp(13f), textPaint)
                }
            }
            second += tickStepSeconds
            if (tickStepSeconds == 1 && p.durationMs > 30_000L) break
            if (second > 86_400) break
        }

        val rowCount = max(2, layers.size)
        for (row in 0 until rowCount) {
            val top = rulerHeight + row * rowHeight
            val bottom = top + rowHeight
            canvas.drawRect(
                0f, top, width.toFloat(), bottom,
                if (row % 2 == 0) lanePaint else laneAltPaint
            )
            canvas.drawLine(0f, bottom, width.toFloat(), bottom, linePaint)
        }

        if (layers.isEmpty()) {
            canvas.drawText("V1", dp(12f), rulerHeight + dp(27f), brightTextPaint)
            canvas.drawText("Add a video, audio, text or shape layer", labelWidth + dp(9f), rulerHeight + dp(27f), textPaint)
        }

        layers.forEachIndexed { row, layer ->
            val top = rulerHeight + row * rowHeight
            val centerY = top + rowHeight / 2f
            val selected = layer.id == selectedLayerId
            val laneName = laneLabel(layer)
            canvas.drawText(laneName, dp(12f), centerY + dp(3f), if (selected) brightTextPaint else textPaint)
            if (!layer.enabled) {
                canvas.drawText("◌", dp(38f), centerY + dp(3f), textPaint)
            }

            val left = labelWidth + layer.timelineStartMs * pixelsPerMs
            val right = labelWidth + layer.timelineEndMs * pixelsPerMs
            val rect = RectF(
                left,
                top + dp(6f),
                max(left + dp(18f), right),
                bottom - dp(6f)
            )
            val fill = when (layer.kind) {
                LayerKind.VIDEO -> 0xff285e92.toInt()
                LayerKind.IMAGE -> 0xff326c85.toInt()
                LayerKind.AUDIO -> 0xff356b4b.toInt()
                LayerKind.TEXT -> 0xff74558d.toInt()
                LayerKind.SHAPE -> 0xff8a6537.toInt()
            }
            val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (layer.enabled) fill else 0xff414750.toInt()
                alpha = if (selected) 255 else 218
            }
            canvas.drawRoundRect(rect, dp(5f), dp(5f), clipPaint)
            if (selected) canvas.drawRoundRect(rect, dp(5f), dp(5f), selectedBorderPaint)

            val text = layer.name.take(26)
            if (rect.width() > dp(45f)) {
                canvas.drawText(
                    text,
                    rect.left + dp(7f),
                    rect.centerY() + dp(3f),
                    if (selected) brightTextPaint else textPaint
                )
            }

            TimelineEditorOps.keyframeTimes(layer).forEach { time ->
                val keyX = labelWidth + time * pixelsPerMs
                if (keyX >= rect.left && keyX <= rect.right) {
                    val r = dp(4f)
                    val diamond = Path().apply {
                        moveTo(keyX, centerY - r)
                        lineTo(keyX + r, centerY)
                        lineTo(keyX, centerY + r)
                        lineTo(keyX - r, centerY)
                        close()
                    }
                    canvas.drawPath(diamond, keyframePaint)
                }
            }
        }

        val safePlayhead = playheadMs.coerceIn(0L, totalMs)
        val playX = labelWidth + safePlayhead * pixelsPerMs
        canvas.drawLine(playX, dp(20f), playX, height.toFloat(), playheadPaint)
        canvas.drawCircle(playX, dp(20f), dp(4f), playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = project ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pxPerMs = pxPerSecond / 1000f
                val tappedTime = ((event.x - labelWidth) / pxPerMs).toLong().coerceIn(0L, p.durationMs)
                if (event.y <= rulerHeight + dp(3f) || abs(event.x - (labelWidth + playheadMs * pxPerMs)) < dp(12f)) {
                    dragMode = DragMode.PLAYHEAD
                    playheadMs = tappedTime
                    onSeek?.invoke(playheadMs)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                val row = ((event.y - rulerHeight) / rowHeight).toInt()
                val layer = layers.getOrNull(row)
                if (layer != null) {
                    val startX = labelWidth + layer.timelineStartMs * pxPerMs
                    val endX = labelWidth + layer.timelineEndMs * pxPerMs
                    val nearestKeyframe = TimelineEditorOps.keyframeTimes(layer)
                        .minByOrNull { keyTime -> abs(labelWidth + keyTime * pxPerMs - event.x) }
                    if (nearestKeyframe != null &&
                        abs(labelWidth + nearestKeyframe * pxPerMs - event.x) <= dp(10f)
                    ) {
                        onSelectKeyframe?.invoke(layer.id, nearestKeyframe)
                        onSelectLayer?.invoke(layer.id)
                        selectedLayerId = layer.id
                        return true
                    }
                    if (event.x in startX..endX) {
                        selectedLayerId = layer.id
                        onSelectLayer?.invoke(layer.id)
                        dragMode = DragMode.LAYER
                        draggedLayerId = layer.id
                        dragStartX = event.x
                        dragOriginalStartMs = layer.timelineStartMs
                        dragTargetMs = layer.timelineStartMs
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                    selectedLayerId = layer.id
                    onSelectLayer?.invoke(layer.id)
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (dragMode) {
                    DragMode.PLAYHEAD -> {
                        playheadMs = ((event.x - labelWidth) / (pxPerSecond / 1000f))
                            .toLong().coerceIn(0L, p.durationMs)
                        onSeek?.invoke(playheadMs)
                    }
                    DragMode.LAYER -> {
                        val deltaMs = ((event.x - dragStartX) / pxPerSecond * 1000f).toLong()
                        val layer = layers.firstOrNull { it.id == draggedLayerId }
                        if (layer != null) {
                            dragTargetMs = (dragOriginalStartMs + deltaMs)
                                .coerceIn(0L, (p.durationMs - layer.durationMs).coerceAtLeast(0L))
                            invalidate()
                        }
                    }
                    DragMode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (dragMode) {
                    DragMode.PLAYHEAD -> {
                        playheadMs = ((event.x - labelWidth) / (pxPerSecond / 1000f))
                            .toLong().coerceIn(0L, p.durationMs)
                        onSeek?.invoke(playheadMs)
                    }
                    DragMode.LAYER -> draggedLayerId?.let { id ->
                        onMoveLayer?.invoke(id, dragTargetMs)
                    }
                    DragMode.NONE -> Unit
                }
                dragMode = DragMode.NONE
                draggedLayerId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                draggedLayerId = null
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun laneLabel(layer: EditLayer): String = when (layer.kind) {
        LayerKind.VIDEO -> "V${layer.zIndex + 1}"
        LayerKind.IMAGE -> "I${layer.zIndex + 1}"
        LayerKind.AUDIO -> "A${layer.zIndex + 1}"
        LayerKind.TEXT -> "T${layer.zIndex + 1}"
        LayerKind.SHAPE -> "S${layer.zIndex + 1}"
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
