package com.reelgenerator.rendering

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.reelgenerator.ExportPolicy
import com.reelgenerator.planning.*

data class FittedText(val layout: StaticLayout, val sizePx: Float, val bounds: RectF)

/** Deterministic layout shared by previews and export. It keeps safe-area and readability
 * decisions in one place so a caption cannot become clipped only on the device encoder. */
object TextLayoutEngine {
    fun fit(text: String, style: TextStyle, minSize: Float = 40f, maxSize: Float = style.fontSizePx): FittedText {
        val bounds = style.safeArea.bounds(ExportPolicy.WIDTH, ExportPolicy.HEIGHT)
        val width = style.safeArea.contentWidth(ExportPolicy.WIDTH).coerceAtLeast(240)
        var size = maxSize.coerceIn(minSize, 96f)
        var layout = build(text, style, size, width)
        while ((layout.height > bounds.height() * .72f || (style.lineCount > 0 && layout.lineCount > style.lineCount)) && size > minSize) {
            size -= 2f; layout = build(text, style, size, width)
        }
        require(layout.height <= bounds.height() * .72f) { "Caption cannot fit within the protected text area." }
        return FittedText(layout, size, bounds)
    }

    private fun build(text: String, style: TextStyle, size: Float, width: Int): StaticLayout {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = android.graphics.Typeface.create(style.typeface, android.graphics.Typeface.BOLD) }
        val alignment = when (style.alignment) { TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL; TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE; TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setAlignment(alignment).setLineSpacing(size * .16f, 1f).setIncludePad(false).build()
    }
}
