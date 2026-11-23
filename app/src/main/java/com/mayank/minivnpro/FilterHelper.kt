package com.mayank.minivnpro

import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect

enum class VideoFilter(val title: String) {
    ORIGINAL("Original"),
    BW("B&W"),
    WARM("Warm"),
    VINTAGE("Vintage"),
    COOL("Cool"),
    VIVID("Vivid"),
    CINEMATIC("Cinematic"),
    MOODY("Moody"),
    FADE("Fade")
}

fun getFilterPreviewTint(filter: VideoFilter): Color {
    return when (filter) {
        VideoFilter.ORIGINAL -> Color.Transparent
        VideoFilter.BW -> Color(0x45808080)
        VideoFilter.WARM -> Color(0x30FF9800)
        VideoFilter.VINTAGE -> Color(0x38A1887F)
        VideoFilter.COOL -> Color(0x3029B6F6)
        VideoFilter.VIVID -> Color(0x20FF4081)
        VideoFilter.CINEMATIC -> Color(0x35004D40)
        VideoFilter.MOODY -> Color(0x401A237E)
        VideoFilter.FADE -> Color(0x30ECEFF1)
    }
}

data class SpeedCurvePreset(
    val name: String,
    val points: List<Float>
)

val speedCurvePresets = listOf(
    SpeedCurvePreset("Normal", listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)),
    SpeedCurvePreset("Fast→Slow", listOf(2.2f, 1.8f, 1.3f, 0.8f, 0.5f, 0.4f)),
    SpeedCurvePreset("Slow→Fast", listOf(0.4f, 0.6f, 0.9f, 1.4f, 1.9f, 2.2f)),
    SpeedCurvePreset("Hero", listOf(2.0f, 1.5f, 0.4f, 0.4f, 1.5f, 2.0f)),
    SpeedCurvePreset("Montage", listOf(0.5f, 1.4f, 2.2f, 2.0f, 1.1f, 0.5f))
)

fun isDefaultSpeedCurve(points: List<Float>): Boolean {
    return points.all { kotlin.math.abs(it - 1.0f) < 0.05f }
}

fun getSpeedAtProgress(points: List<Float>, progress: Float): Float {
    if (points.size < 2) return points.firstOrNull() ?: 1.0f
    val p = progress.coerceIn(0f, 0.999f)
    val scaled = p * (points.size - 1)
    val idx = scaled.toInt().coerceIn(0, points.lastIndex - 1)
    val frac = scaled - idx
    val interpolated = points[idx] + (points[idx + 1] - points[idx]) * frac
    return interpolated.coerceIn(0.3f, 2.5f)
}

fun estimateCurveDurationMs(rawDurationMs: Long, points: List<Float>): Long {
    if (points.isEmpty() || isDefaultSpeedCurve(points)) return rawDurationMs
    val segDur = rawDurationMs.toFloat() / points.size
    var total = 0f
    for (sp in points) {
        total += segDur / sp.coerceIn(0.3f, 2.5f)
    }
    return total.toLong().coerceAtLeast(500L)
}

@UnstableApi
class CurveSpeedProvider(
    private val clipDurationUs: Long,
    private val points: List<Float>
) : SpeedProvider {
    private val safePoints = if (points.isEmpty()) listOf(1.0f) else points.map { it.coerceIn(0.3f, 2.5f) }
    private val segmentUs = (clipDurationUs / safePoints.size).coerceAtLeast(100_000L)

    override fun getSpeed(timeUs: Long): Float {
        if (clipDurationUs <= 0L) return safePoints.first()
        val idx = (timeUs.coerceAtLeast(0L) / segmentUs).toInt().coerceIn(0, safePoints.lastIndex)
        return safePoints[idx]
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (clipDurationUs <= 0L) return C.TIME_UNSET
        val idx = (timeUs.coerceAtLeast(0L) / segmentUs).toInt()
        if (idx >= safePoints.lastIndex) return C.TIME_UNSET
        val nextTime = (idx + 1) * segmentUs
        return if (nextTime > timeUs && nextTime < clipDurationUs) nextTime else C.TIME_UNSET
    }
}

@UnstableApi
fun buildExportVideoEffects(
    filter: VideoFilter,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    rotationDegrees: Int
): List<Effect> {
    val effects = mutableListOf<Effect>()

    when (filter) {
        VideoFilter.ORIGINAL -> {}
        VideoFilter.BW -> effects.add(RgbFilter.createGrayscaleFilter())
        VideoFilter.WARM -> effects.add(
            RgbAdjustment.Builder()
                .setRedScale(1.16f)
                .setGreenScale(1.05f)
                .setBlueScale(0.86f)
                .build()
        )
        VideoFilter.VINTAGE -> {
            effects.add(HslAdjustment.Builder().adjustSaturation(-25f).build())
            effects.add(
                RgbAdjustment.Builder()
                    .setRedScale(1.18f)
                    .setGreenScale(1.04f)
                    .setBlueScale(0.76f)
                    .build()
            )
        }
        VideoFilter.COOL -> effects.add(
            RgbAdjustment.Builder()
                .setRedScale(0.84f)
                .setGreenScale(0.98f)
                .setBlueScale(1.22f)
                .build()
        )
        VideoFilter.VIVID -> {
            effects.add(Contrast(0.24f))
            effects.add(HslAdjustment.Builder().adjustSaturation(35f).build())
        }
        VideoFilter.CINEMATIC -> {
            effects.add(Contrast(0.36f))
            effects.add(HslAdjustment.Builder().adjustSaturation(-15f).build())
            effects.add(
                RgbAdjustment.Builder()
                    .setRedScale(1.08f)
                    .setGreenScale(0.96f)
                    .setBlueScale(0.88f)
                    .build()
            )
        }
        VideoFilter.MOODY -> {
            effects.add(Contrast(0.32f))
            effects.add(HslAdjustment.Builder().adjustSaturation(-35f).adjustLightness(-10f).build())
        }
        VideoFilter.FADE -> {
            effects.add(Contrast(-0.22f))
            effects.add(HslAdjustment.Builder().adjustSaturation(-20f).adjustLightness(8f).build())
        }
    }

    if (brightness != 0f) {
        val scale = (1f + brightness).coerceIn(0.35f, 1.75f)
        effects.add(
            RgbAdjustment.Builder()
                .setRedScale(scale)
                .setGreenScale(scale)
                .setBlueScale(scale)
                .build()
        )
    }

    if (contrast != 0f) {
        effects.add(Contrast(contrast.coerceIn(-0.65f, 0.65f)))
    }

    if (saturation != 0f) {
        effects.add(HslAdjustment.Builder().adjustSaturation(saturation.coerceIn(-90f, 90f)).build())
    }

    if (rotationDegrees % 360 != 0) {
        effects.add(
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-rotationDegrees.toFloat())
                .build()
        )
    }

    return effects
}

@UnstableApi
fun buildSpeedCurveEffects(
    clipDurationMs: Long,
    speedPoints: List<Float>,
    removeAudio: Boolean
): Pair<List<AudioProcessor>, List<Effect>> {
    if (isDefaultSpeedCurve(speedPoints)) {
        return emptyList<AudioProcessor>() to emptyList()
    }
    val provider = CurveSpeedProvider(clipDurationMs.coerceAtLeast(500L) * 1000L, speedPoints)
    val videoFx = listOf<Effect>(SpeedChangeEffect(provider))
    val audioFx = if (removeAudio) emptyList() else listOf<AudioProcessor>(SpeedChangingAudioProcessor(provider))
    return audioFx to videoFx
}
