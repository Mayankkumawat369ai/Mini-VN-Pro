package com.mayank.minivnpro

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter

enum class VideoFilter(val title: String) {
    ORIGINAL("Original"),
    BW("B&W"),
    VINTAGE("Vintage"),
    COOL("Cool"),
    VIVID("Vivid"),
    CINEMATIC("Cinematic")
}

@UnstableApi
fun buildVideoEffects(
    filter: VideoFilter,
    brightness: Float,
    contrast: Float
): List<Effect> {
    val effects = mutableListOf<Effect>()

    when (filter) {
        VideoFilter.ORIGINAL -> {}
        VideoFilter.BW -> effects.add(RgbFilter.createGrayscaleFilter())
        VideoFilter.VINTAGE -> effects.add(
            RgbAdjustment.Builder()
                .setRedScale(1.18f)
                .setGreenScale(1.04f)
                .setBlueScale(0.78f)
                .build()
        )
        VideoFilter.COOL -> effects.add(
            RgbAdjustment.Builder()
                .setRedScale(0.85f)
                .setGreenScale(0.98f)
                .setBlueScale(1.22f)
                .build()
        )
        VideoFilter.VIVID -> {
            effects.add(Contrast(0.28f))
            effects.add(
                RgbAdjustment.Builder()
                    .setRedScale(1.12f)
                    .setGreenScale(1.10f)
                    .setBlueScale(1.12f)
                    .build()
            )
        }
        VideoFilter.CINEMATIC -> {
            effects.add(Contrast(0.38f))
            effects.add(
                RgbAdjustment.Builder()
                    .setRedScale(1.06f)
                    .setGreenScale(0.96f)
                    .setBlueScale(0.86f)
                    .build()
            )
        }
    }

    if (brightness != 0f) {
        val scale = (1f + brightness).coerceIn(0.3f, 1.8f)
        effects.add(
            RgbAdjustment.Builder()
                .setRedScale(scale)
                .setGreenScale(scale)
                .setBlueScale(scale)
                .build()
        )
    }

    if (contrast != 0f) {
        effects.add(Contrast(contrast.coerceIn(-0.7f, 0.7f)))
    }

    return effects
}
