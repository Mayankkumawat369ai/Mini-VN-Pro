package com.mayank.minivnpro

import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import com.google.common.collect.ImmutableList

val textColorPalette = listOf(
    Color.White,
    Color(0xFFFFD600), // Yellow
    Color(0xFFFF5252), // Red
    Color(0xFF40C4FF), // Sky Blue
    Color(0xFF69F0AE), // Green
    Color.Black
)

@UnstableApi
fun buildTextExportEffects(
    text: String,
    anchorX: Float,
    anchorY: Float,
    scale: Float,
    color: Color,
    hasBackground: Boolean
): List<Effect> {
    if (text.isBlank()) return emptyList()

    val spannable = SpannableString("  $text  ").apply {
        setSpan(
            ForegroundColorSpan(color.toArgb()),
            0,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (hasBackground) {
            val bgColor = if (color == Color.Black) 0xCCFFFFFF.toInt() else 0x99000000.toInt()
            setSpan(
                BackgroundColorSpan(bgColor),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        setSpan(
            RelativeSizeSpan(2.0f),
            0,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(anchorX, anchorY)
        .setOverlayFrameAnchor(0f, 0f)
        .setScale(scale, scale)
        .build()

    val textOverlay = TextOverlay.createStaticTextOverlay(spannable, settings)
    return listOf(OverlayEffect(ImmutableList.of(textOverlay)))
}
