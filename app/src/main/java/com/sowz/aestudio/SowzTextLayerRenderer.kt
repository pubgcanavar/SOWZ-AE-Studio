package com.sowz.aestudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.sowz.aestudio.core.EditLayer
import java.io.File
import java.io.FileOutputStream

/** Renders editable text layers into cached transparent PNGs for Media3 composition tracks. */
internal object SowzTextLayerRenderer {
    private const val MAX_PIXEL_COUNT = 8_294_400L

    fun render(
        context: Context,
        layer: EditLayer,
        width: Int,
        height: Int
    ): File {
        require(layer.contentText.isNullOrBlank().not()) { "Text layer has no text content." }
        require(width in 16..8192 && height in 16..8192) { "Text layer dimensions are out of range." }
        require(width.toLong() * height.toLong() <= MAX_PIXEL_COUNT) {
            "Text layer render is limited to 8.3 megapixels."
        }

        val text = layer.contentText.orEmpty().take(4_000)
        val cacheName = "sowz-text-${Integer.toHexString(layer.id.hashCode())}-" +
            "${Integer.toHexString(text.hashCode())}-${layer.textColorArgb.toUInt().toString(16)}-" +
            "${layer.textSizePx.toBits().toUInt().toString(16)}-${width}x${height}.png"
        val output = File(context.cacheDir, cacheName)
        if (output.isFile && output.length() > 0L) return output

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = layer.textColorArgb
                textSize = layer.textSizePx.coerceIn(8f, 512f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setShadowLayer(2.5f, 0f, 2f, 0x99000000.toInt())
            }

            val lines = text.replace("\r\n", "\n").replace('\r', '\n')
                .split('\n').take(20)
            val fontMetrics = paint.fontMetrics
            val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * 1.12f
            val visibleLines = lines.take(20)
            val blockHeight = lineHeight * visibleLines.size
            var baseline = (height - blockHeight) / 2f - fontMetrics.ascent
            visibleLines.forEach { line ->
                canvas.drawText(line.take(180), width / 2f, baseline, paint)
                baseline += lineHeight
            }

            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not encode the text layer PNG."
                }
                stream.fd.sync()
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            bitmap.recycle()
        }
        return output
    }
}
