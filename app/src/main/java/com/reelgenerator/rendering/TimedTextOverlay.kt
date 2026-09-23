package com.reelgenerator.rendering

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.reelgenerator.ExportPolicy
import com.reelgenerator.planning.ReelPlan

/** One reusable bitmap, independent of clip cuts. Media3 supplies composition timeline timestamps. */
@UnstableApi
class TimedTextOverlay(private val plan: ReelPlan) : BitmapOverlay() {
    private var bitmap: Bitmap? = null
    private var lastIndex = Int.MIN_VALUE
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = bitmap ?: Bitmap.createBitmap(ExportPolicy.WIDTH, ExportPolicy.HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val timeMs = presentationTimeUs / 1_000
        val index = plan.textBeats.indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }
        if (index != lastIndex) {
            frame.eraseColor(Color.TRANSPARENT)
            if (index >= 0) drawText(frame, plan.textBeats[index].text)
            lastIndex = index
        }
        return frame
    }
    private fun drawText(frame: Bitmap, text: String) {
        val canvas = Canvas(frame)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = plan.textStyle.fontSizePx
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setShadowLayer(5f, 0f, 3f, Color.BLACK)
        }
        val contentWidth = (ExportPolicy.WIDTH * 0.778f).toInt()
        fun layout() = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(12f, 1f).setIncludePad(false).build()
        var textLayout = layout()
        val maxTextHeight = (ExportPolicy.HEIGHT * 0.333f).toInt()
        while (textLayout.height > maxTextHeight && paint.textSize > 40f) { paint.textSize -= 2; textLayout = layout() }
        check(textLayout.height <= maxTextHeight) { "Caption cannot fit within the text area." }
        val top = (ExportPolicy.HEIGHT - textLayout.height) / 2f
        // Retain the physically verified Phase 2 treatment. Visual redesign is Phase 3C.
        val side = ExportPolicy.WIDTH * 0.0815f
        val inset = ExportPolicy.WIDTH * 0.0296f
        val radius = ExportPolicy.WIDTH * 0.0222f
        canvas.drawRoundRect(side, top - inset, ExportPolicy.WIDTH - side, top + textLayout.height + inset, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(175, 12, 15, 22) })
        canvas.save(); canvas.translate(ExportPolicy.WIDTH * 0.111f, top); textLayout.draw(canvas); canvas.restore()
    }
    override fun release() {
        try { super.release() } finally { bitmap?.recycle(); bitmap = null; lastIndex = Int.MIN_VALUE }
    }
}
