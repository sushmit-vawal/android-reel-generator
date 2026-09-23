package com.reelgenerator.rendering

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.reelgenerator.ExportPolicy
import com.reelgenerator.planning.ReelPlan

@UnstableApi
object ReelCompositionFactory {
    fun create(plan: ReelPlan): Composition {
        plan.validated()
        val items = plan.clips.map { segment ->
            val media = MediaItem.Builder().setUri(segment.source.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(segment.trimStartMs).setEndPositionMs(segment.trimEndMs).build()
            ).build()
            EditedMediaItem.Builder(media).setRemoveAudio(true).setFrameRate(30).setEffects(Effects(emptyList(), listOf(
                Presentation.createForWidthAndHeight(ExportPolicy.WIDTH, ExportPolicy.HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
            ))).build()
        }
        // One sequential video track, never parallel picture-in-picture decoders.
        return Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(TimedTextOverlay(plan)))))).build()
    }
}
