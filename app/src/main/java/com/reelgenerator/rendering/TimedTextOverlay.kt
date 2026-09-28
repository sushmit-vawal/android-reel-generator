package com.reelgenerator.rendering

import android.graphics.*
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
            if (index >= 0) drawText(frame, plan.textBeats[index])
            lastIndex = index
        }
        return frame
    }
    private fun drawText(frame: Bitmap, beat: com.reelgenerator.planning.TextBeat) {
        require(beat.text.isNotBlank() && beat.endMs > beat.startMs) { "Invalid text beat cannot be rendered." }
        val canvas = Canvas(frame)
        val fitted = TextLayoutEngine.fit(beat.text, plan.textStyle)
        check(fitted.layout.width > 0 && fitted.layout.height > 0) { "Text layout is empty." }
        val resolvedTypeface = Typeface.create(plan.textStyle.typeface.ifBlank { "sans-serif" }, Typeface.BOLD)
            ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val top = fitted.bounds.top + (fitted.bounds.height() - fitted.layout.height) * plan.textStyle.placementY.coerceIn(0f, 1f)
        val left = fitted.bounds.left + (fitted.bounds.width() - fitted.layout.width) * plan.textStyle.placementX.coerceIn(0f, 1f)
        check(left >= 0f && top >= 0f && left + fitted.layout.width <= frame.width && top + fitted.layout.height <= frame.height) { "Text is outside the video frame." }
        canvas.save(); canvas.translate(left, top)
        fitted.layout.paint.color = Color.WHITE
        fitted.layout.paint.alpha = 255
        fitted.layout.paint.typeface = resolvedTypeface
        fitted.layout.paint.setShadowLayer(6f, 0f, 3f, Color.BLACK)
        fitted.layout.draw(canvas)
        canvas.restore()
    }
    override fun release() {
        try { super.release() } finally { bitmap?.recycle(); bitmap = null; lastIndex = Int.MIN_VALUE }
    }
}
