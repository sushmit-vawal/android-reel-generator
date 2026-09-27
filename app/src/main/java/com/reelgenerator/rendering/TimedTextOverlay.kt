package com.reelgenerator.rendering

import android.graphics.*
import android.text.Layout
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
            if (index >= 0) drawText(frame, plan.textBeats[index], timeMs)
            lastIndex = index
        }
        return frame
    }
    private fun drawText(frame: Bitmap, beat: com.reelgenerator.planning.TextBeat, timeMs: Long) {
        val canvas = Canvas(frame)
        val fitted = TextLayoutEngine.fit(beat.text, plan.textStyle)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = plan.textStyle.foregroundColor; textSize = fitted.sizePx
            typeface = Typeface.create(plan.textStyle.typeface, Typeface.BOLD)
            when (plan.textStyle.treatment) { com.reelgenerator.planning.TextTreatment.SHADOW, com.reelgenerator.planning.TextTreatment.DIRECT -> Unit
                else -> setShadowLayer(5f, 0f, 3f, Color.BLACK) }
        }
        val beatAge = (timeMs - beat.startMs).coerceAtLeast(0L)
        val appear = (beatAge / 180f).coerceIn(0f, 1f)
        val top = fitted.bounds.top + (fitted.bounds.height() - fitted.layout.height) * plan.textStyle.placementY.coerceIn(0f, 1f) + (1f - appear) * 18f
        val left = fitted.bounds.left + (fitted.bounds.width() - fitted.layout.width) * plan.textStyle.placementX.coerceIn(0f, 1f)
        val treatment = plan.textStyle.treatment
        if (treatment == com.reelgenerator.planning.TextTreatment.BACKDROP || treatment == com.reelgenerator.planning.TextTreatment.GRADIENT) {
            val inset = ExportPolicy.WIDTH * .018f
            canvas.drawRoundRect(left - inset, top - inset, left + fitted.layout.width + inset, top + fitted.layout.height + inset, inset, inset,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(if (treatment == com.reelgenerator.planning.TextTreatment.GRADIENT) 90 else 115, 8, 12, 18) })
        }
        canvas.save(); canvas.translate(left, top); canvas.saveLayerAlpha(0f, 0f, ExportPolicy.WIDTH.toFloat(), ExportPolicy.HEIGHT.toFloat(), (255f * appear).toInt()); fitted.layout.paint.color = paint.color; fitted.layout.draw(canvas); canvas.restore(); canvas.restore()
    }
    override fun release() {
        try { super.release() } finally { bitmap?.recycle(); bitmap = null; lastIndex = Int.MIN_VALUE }
    }
}
