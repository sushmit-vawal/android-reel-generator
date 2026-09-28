package com.reelgenerator.planning

import com.reelgenerator.ReelCategory

/** Chooses one coherent presentation per reel. The seed keeps output reproducible while the
 * category and text shape prevent a batch from looking like five identical templates. */
object PresentationPlanner {
    fun choose(category: ReelCategory, text: String, seed: Int, slot: Int = 0, brightness: Double? = null): TextStyle {
        val preset = when {
            category == ReelCategory.HUMOR -> TypographyPreset.HUMOR
            text.length > 100 -> TypographyPreset.CINEMATIC
            category == ReelCategory.MOTIVATION -> TypographyPreset.IMPACT
            category == ReelCategory.LIFESTYLE -> TypographyPreset.MINIMAL
            else -> listOf(TypographyPreset.CLEAN, TypographyPreset.SOCIAL, TypographyPreset.CINEMATIC)[Math.floorMod(seed + slot, 3)]
        }
        val alignment = if (preset == TypographyPreset.MINIMAL) TextAlignment.LEFT else TextAlignment.CENTER
        val treatment = when (preset) {
            TypographyPreset.IMPACT, TypographyPreset.HUMOR -> TextTreatment.OUTLINE
            TypographyPreset.CINEMATIC -> TextTreatment.SHADOW
            TypographyPreset.MINIMAL -> TextTreatment.DIRECT
            else -> TextTreatment.BACKDROP
        }
        val typeface = when (preset) { TypographyPreset.CINEMATIC -> "serif"; TypographyPreset.SOCIAL -> "sans-serif-medium"; TypographyPreset.HUMOR -> "sans-serif-condensed"; else -> "sans-serif" }
        return TextStyle(preset, when (preset) { TypographyPreset.IMPACT -> 72f; TypographyPreset.MINIMAL -> 58f; else -> 64f },
            typeface = typeface, alignment = alignment, treatment = if (brightness != null && brightness > .62) TextTreatment.OUTLINE else treatment,
            foregroundColor = 0xffffffff.toInt(), emphasisColor = 0xffffffff.toInt(),
            lineCount = if (text.length > 80) 3 else 2)
    }
}
