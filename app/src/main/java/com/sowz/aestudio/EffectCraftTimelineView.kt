package com.sowz.aestudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Compact mobile timeline using EffectCraft's dark panel/ruler/track visual language.
 * This is a native SOWZ implementation, not a copied desktop renderer.
 *
 * The blue lane represents the source clip and its active trim range. The second lane is
 * shown only when a background-audio file has actually been selected.
 */
internal class EffectCraftTimelineView(context: Context) : View(context) {
    var durationMs: Long = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var inMs: Long = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var outMs: Long = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var playheadMs: Long = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var hasAudioTrack: Boolean = false
        set(value) { field = value; invalidate() }
    var onSeekPosition: ((Long) -> Unit)? = null
    var onTrimRangeChanged: ((Long, Long) -> Unit)? = null

    private enum class DragMode { NONE, PLAYHEAD, IN_POINT, OUT_POINT }
    private var dragMode = DragMode.NONE

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff1b1b1b.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff343434.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff232323.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff242424.toInt() }
    private val videoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff315d83.toInt() }
    private val audioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff365b48.toInt() }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff2d8ceb.toInt()
        strokeWidth = dp(1.5f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff707070.toInt()
        strokeWidth = dp(1f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff9a9a9a.toInt()
        textSize = dp(9.5f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val brightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffd7d7d7.toInt()
        textSize = dp(9.5f)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val trimHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xffe6e6e6.toInt()
        strokeWidth = dp(2f)
    }

    private val timeLeft get() = dp(43f)
    private val timeRight get() = (width - dp(10f)).coerceAtLeast(timeLeft + dp(20f))
    private val timeWidth get() = (timeRight - timeLeft).coerceAtLeast(dp(1f))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, dp(7f), dp(7f), panelPaint)
        canvas.drawRoundRect(bounds, dp(7f), dp(7f), borderPaint)

        val rulerBottom = dp(30f)
        canvas.drawRoundRect(
            RectF(dp(1f), dp(1f), width - dp(1f), rulerBottom),
            dp(6f), dp(6f), rulerPaint
        )

        val total = durationMs.coerceAtLeast(1L)
        for (i in 0..4) {
            val x = timeLeft + timeWidth * (i / 4f)
            canvas.drawLine(x, dp(8f), x, dp(112f), tickPaint)
            canvas.drawLine(x, dp(20f), x, dp(29f), accentPaint)
            val seconds = total * i / 4L / 1000L
            val label = String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
            canvas.drawText(label, (x + dp(3f)).coerceAtMost(timeRight - dp(29f)), dp(17f), textPaint)
        }

        val videoTop = dp(39f)
        val videoBottom = dp(66f)
        val audioTop = dp(78f)
        val audioBottom = dp(104f)
        canvas.drawText("V1", dp(10f), dp(56f), brightTextPaint)
        canvas.drawText("A1", dp(10f), dp(95f), brightTextPaint)

        val fullVideo = RectF(timeLeft, videoTop, timeRight, videoBottom)
        canvas.drawRoundRect(fullVideo, dp(4f), dp(4f), trackPaint)
        val leftFraction = (inMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val rightFraction = (outMs.toFloat() / total.toFloat()).coerceIn(leftFraction, 1f)
        val selected = RectF(
            timeLeft + timeWidth * leftFraction,
            videoTop,
            timeLeft + timeWidth * rightFraction,
            videoBottom
        )
        if (selected.width() > dp(2f)) {
            canvas.drawRoundRect(selected, dp(4f), dp(4f), videoPaint)
            if (selected.width() > dp(76f)) {
                canvas.drawText("SOURCE VIDEO", selected.left + dp(7f), videoTop + dp(17f), brightTextPaint)
            }
            // Visible IN/OUT handles: drag either edge to adjust the export range.
            canvas.drawLine(selected.left, videoTop - dp(3f), selected.left, videoBottom + dp(3f), trimHandlePaint)
            canvas.drawLine(selected.right, videoTop - dp(3f), selected.right, videoBottom + dp(3f), trimHandlePaint)
            canvas.drawCircle(selected.left, videoTop, dp(3f), trimHandlePaint)
            canvas.drawCircle(selected.right, videoTop, dp(3f), trimHandlePaint)
        }

        val audioLane = RectF(timeLeft, audioTop, timeRight, audioBottom)
        canvas.drawRoundRect(audioLane, dp(4f), dp(4f), trackPaint)
        if (hasAudioTrack) {
            canvas.drawRoundRect(
                RectF(timeLeft, audioTop, timeRight, audioBottom),
                dp(4f), dp(4f), audioPaint
            )
            canvas.drawText("MUSIC TRACK", timeLeft + dp(7f), audioTop + dp(17f), brightTextPaint)
        } else {
            canvas.drawText("NO AUDIO TRACK", timeLeft + dp(7f), audioTop + dp(17f), textPaint)
        }

        val playFraction = (playheadMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val playX = timeLeft + timeWidth * playFraction
        canvas.drawLine(playX, dp(23f), playX, dp(113f), accentPaint)
        val head = Path().apply {
            moveTo(playX - dp(4f), dp(23f))
            lineTo(playX + dp(4f), dp(23f))
            lineTo(playX, dp(29f))
            close()
        }
        canvas.drawPath(head, accentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val leftX = timeLeft + timeWidth * (inMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val rightX = timeLeft + timeWidth * (outMs.toFloat() / durationMs.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                val handleSlop = dp(16f)
                dragMode = when {
                    kotlin.math.abs(event.x - leftX) <= handleSlop && onTrimRangeChanged != null -> DragMode.IN_POINT
                    kotlin.math.abs(event.x - rightX) <= handleSlop && onTrimRangeChanged != null -> DragMode.OUT_POINT
                    else -> DragMode.PLAYHEAD
                }
                updateDrag(event.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateDrag(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateDrag(event.x)
                when (dragMode) {
                    DragMode.PLAYHEAD -> onSeekPosition?.invoke(playheadMs)
                    DragMode.IN_POINT, DragMode.OUT_POINT -> onTrimRangeChanged?.invoke(inMs, outMs)
                    DragMode.NONE -> Unit
                }
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun updateDrag(x: Float) {
        val fraction = ((x - timeLeft) / timeWidth).coerceIn(0f, 1f)
        val position = (durationMs * fraction).toLong()
        when (dragMode) {
            DragMode.PLAYHEAD -> playheadMs = position
            DragMode.IN_POINT -> inMs = position.coerceAtMost((outMs - 500L).coerceAtLeast(0L))
            DragMode.OUT_POINT -> outMs = position.coerceAtLeast((inMs + 500L).coerceAtMost(durationMs))
            DragMode.NONE -> Unit
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
