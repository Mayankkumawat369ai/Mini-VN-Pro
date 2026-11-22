package com.mayank.minivnpro

import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect

enum class VideoFilter(val title: String) {
    ORIGINAL("Original"),
    BW("B&W"),
    VINTAGE("Vintage"),
    COOL("Cool"),
    VIVID("Vivid"),
    CINEMATIC("Cinematic")
}

val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)

@UnstableApi
fun buildPreviewVideoEffects(
    filter: VideoFilter,
    brightness: Float,
    contrast: Float,
    rotationDegrees: Int
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

    if (rotationDegrees % 360 != 0) {
        effects.add(
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(rotationDegrees.toFloat())
                .build()
        )
    }

    return effects
}

@UnstableApi
fun buildSpeedAudioProcessors(speed: Float, removeAudio: Boolean): List<AudioProcessor> {
    if (speed == 1.0f || removeAudio) return emptyList()
    val sonic = SonicAudioProcessor()
    sonic.setSpeed(speed)
    return listOf(sonic)
}

@UnstableApi
fun buildSpeedVideoEffects(speed: Float): List<Effect> {
    if (speed == 1.0f) return emptyList()
    return listOf(SpeedChangeEffect(speed))
}
