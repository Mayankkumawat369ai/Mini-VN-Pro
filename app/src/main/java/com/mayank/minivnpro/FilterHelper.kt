package com.mayank.minivnpro

import android.graphics.ColorMatrix
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

data class SpeedCurvePreset(
    val name: String,
    val points: List<Float> // 5 editable points: Start, 25%, Mid, 75%, End
)

val speedCurvePresets = listOf(
    SpeedCurvePreset("Normal", listOf(1.0f, 1.0f, 1.0f, 1.0f, 1.0f)),
    SpeedCurvePreset("Fast→Slow", listOf(2.2f, 1.7f, 1.1f, 0.6f, 0.4f)),
    SpeedCurvePreset("Slow→Fast", listOf(0.4f, 0.7f, 1.1f, 1.7f, 2.2f)),
    SpeedCurvePreset("Hero", listOf(2.0f, 1.4f, 0.4f, 1.4f, 2.0f)),
    SpeedCurvePreset("Bullet", listOf(2.2f, 0.5f, 0.4f, 0.5f, 2.2f)),
    SpeedCurvePreset("Montage", listOf(0.5f, 1.6f, 2.2f, 1.4f, 0.5f))
)

fun isDefaultSpeedCurve(points: List<Float>): Boolean {
    return points.all { kotlin.math.abs(it - 1.0f) < 0.05f }
}

// Smoothly interpolated speed at any normalized progress (0f..1f)
fun getSmoothSpeedAtProgress(points: List<Float>, progress: Float): Float {
    if (points.size < 2) return points.firstOrNull() ?: 1.0f
    val p = progress.coerceIn(0f, 1f) * (points.size - 1)
    val i = p.toInt().coerceIn(0, points.size - 2)
    val frac = p - i
    val speed = points[i] + (points[i + 1] - points[i]) * frac
    return speed.coerceIn(0.3f, 2.5f)
}

fun estimateCurveDurationMs(rawDurationMs: Long, points: List<Float>): Long {
    if (points.isEmpty() || isDefaultSpeedCurve(points)) return rawDurationMs
    val steps = 20
    val segDur = rawDurationMs.toFloat() / steps
    var total = 0f
    for (s in 0 until steps) {
        val progress = (s + 0.5f) / steps
        val sp = getSmoothSpeedAtProgress(points, progress)
        total += segDur / sp
    }
    return total.toLong().coerceAtLeast(500L)
}

// Smooth 20-step speed curve for Media3 Export
@UnstableApi
class CurveSpeedProvider(
    private val clipDurationUs: Long,
    private val points: List<Float>
) : SpeedProvider {
    private val steps = 20
    private val segmentUs = (clipDurationUs / steps).coerceAtLeast(50_000L)

    override fun getSpeed(timeUs: Long): Float {
        if (clipDurationUs <= 0L) return 1.0f
        val progress = (timeUs.coerceAtLeast(0L).toFloat() / clipDurationUs.toFloat()).coerceIn(0f, 1f)
        return getSmoothSpeedAtProgress(points, progress)
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (clipDurationUs <= 0L) return C.TIME_UNSET
        val currentStep = (timeUs.coerceAtLeast(0L) / segmentUs).toInt()
        if (currentStep >= steps - 1) return C.TIME_UNSET
        val nextTime = (currentStep + 1) * segmentUs
        return if (nextTime > timeUs && nextTime < clipDurationUs) nextTime else C.TIME_UNSET
    }
}

// 100% Freeze-free GPU ColorMatrix for Live Preview (No ExoPlayer restarts!)
fun buildPreviewColorMatrix(
    filter: VideoFilter,
    brightness: Float,
    contrast: Float,
    saturation: Float
): ColorMatrix {
    val cm = ColorMatrix()

    when (filter) {
        VideoFilter.ORIGINAL -> {}
        VideoFilter.BW -> cm.setSaturation(0f)
        VideoFilter.WARM -> cm.postConcat(ColorMatrix(floatArrayOf(
            1.16f, 0f, 0f, 0f, 0f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 0.86f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        VideoFilter.VINTAGE -> {
            val sat = ColorMatrix().apply { setSaturation(0.75f) }
            cm.postConcat(sat)
            cm.postConcat(ColorMatrix(floatArrayOf(
                1.18f, 0f, 0f, 0f, 0f,
                0f, 1.04f, 0f, 0f, 0f,
                0f, 0f, 0.76f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        VideoFilter.COOL -> cm.postConcat(ColorMatrix(floatArrayOf(
            0.84f, 0f, 0f, 0f, 0f,
            0f, 0.98f, 0f, 0f, 0f,
            0f, 0f, 1.22f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
        VideoFilter.VIVID -> {
            val sat = ColorMatrix().apply { setSaturation(1.35f) }
            cm.postConcat(sat)
            val c = 1.2f
            val t = (-0.5f * c + 0.5f) * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        VideoFilter.CINEMATIC -> {
            val sat = ColorMatrix().apply { setSaturation(0.85f) }
            cm.postConcat(sat)
            val c = 1.25f
            val t = (-0.5f * c + 0.5f) * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(
                c * 1.06f, 0f, 0f, 0f, t,
                0f, c * 0.96f, 0f, 0f, t,
                0f, 0f, c * 0.88f, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        VideoFilter.MOODY -> {
            val sat = ColorMatrix().apply { setSaturation(0.65f) }
            cm.postConcat(sat)
            cm.postConcat(ColorMatrix(floatArrayOf(
                1.15f, 0f, 0f, 0f, -20f,
                0f, 1.15f, 0f, 0f, -20f,
                0f, 0f, 1.15f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        VideoFilter.FADE -> {
            val sat = ColorMatrix().apply { setSaturation(0.8f) }
            cm.postConcat(sat)
            cm.postConcat(ColorMatrix(floatArrayOf(
                0.85f, 0f, 0f, 0f, 25f,
                0f, 0.85f, 0f, 0f, 25f,
                0f, 0f, 0.85f, 0f, 25f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
    }

    if (saturation != 0f) {
        val satScale = (1f + saturation / 100f).coerceIn(0f, 2f)
        cm.postConcat(ColorMatrix().apply { setSaturation(satScale) })
    }

    if (contrast != 0f) {
        val c = (1f + contrast).coerceIn(0.4f, 1.7f)
        val t = (-0.5f * c + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    if (brightness != 0f) {
        val b = (1f + brightness).coerceIn(0.35f, 1.75f)
        cm.postConcat(ColorMatrix(floatArrayOf(
            b, 0f, 0f, 0f, 0f,
            0f, b, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    return cm
}

// Media3 Effects used ONLY during Export
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
            RgbAdjustment.Builder().setRedScale(1.16f).setGreenScale(1.05f).setBlueScale(0.86f).build()
        )
        VideoFilter.VINTAGE -> {
            effects.add(HslAdjustment.Builder().adjustSaturation(-25f).build())
            effects.add(RgbAdjustment.Builder().setRedScale(1.18f).setGreenScale(1.04f).setBlueScale(0.76f).build())
        }
        VideoFilter.COOL -> effects.add(
            RgbAdjustment.Builder().setRedScale(0.84f).setGreenScale(0.98f).setBlueScale(1.22f).build()
        )
        VideoFilter.VIVID -> {
            effects.add(Contrast(0.24f))
            effects.add(HslAdjustment.Builder().adjustSaturation(35f).build())
        }
        VideoFilter.CINEMATIC -> {
            effects.add(Contrast(0.36f))
            effects.add(HslAdjustment.Builder().adjustSaturation(-15f).build())
            effects.add(RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(0.96f).setBlueScale(0.88f).build())
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
        effects.add(RgbAdjustment.Builder().setRedScale(scale).setGreenScale(scale).setBlueScale(scale).build())
    }

    if (contrast != 0f) {
        effects.add(Contrast(contrast.coerceIn(-0.65f, 0.65f)))
    }

    if (saturation != 0f) {
        effects.add(HslAdjustment.Builder().adjustSaturation(saturation.coerceIn(-90f, 90f)).build())
    }

    if (rotationDegrees % 360 != 0) {
        // Negative angle matches clockwise Android View rotation
        val exportAngle = (360 - (rotationDegrees % 360)) % 360
        effects.add(
            ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(exportAngle.toFloat())
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
