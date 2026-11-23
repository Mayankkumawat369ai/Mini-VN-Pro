package com.mayank.minivnpro

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
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
    Color(0xFFFF80AB), // Pink
    Color(0xFFFFAB40), // Orange
    Color.Black
)

enum class TextFontStyle(val label: String, val familyName: String, val composeFamily: FontFamily) {
    SANS("Classic", "sans-serif", FontFamily.SansSerif),
    SERIF("Stylish", "serif", FontFamily.Serif),
    MONO("Typewriter", "monospace", FontFamily.Monospace)
}

enum class TextBgMode(val label: String) {
    NONE("No Box"),
    DARK("Dark Box"),
    LIGHT("Light Box"),
    YELLOW("Yellow Tag")
}

fun getTextBgColor(mode: TextBgMode): Color {
    return when (mode) {
        TextBgMode.NONE -> Color.Transparent
        TextBgMode.DARK -> Color(0xAA000000)
        TextBgMode.LIGHT -> Color(0xCCFFFFFF)
        TextBgMode.YELLOW -> Color(0xDDFFD600)
    }
}

@UnstableApi
fun buildTextExportEffects(
    text: String,
    anchorX: Float,
    anchorY: Float,
    scale: Float,
    color: Color,
    bgMode: TextBgMode,
    fontStyle: TextFontStyle,
    isBold: Boolean,
    isItalic: Boolean
): List<Effect> {
    if (text.isBlank()) return emptyList()

    val formatted = text.lines().joinToString("\n") { "  $it  " }
    val spannable = SpannableString(formatted).apply {
        setSpan(
            ForegroundColorSpan(color.toArgb()),
            0,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        if (bgMode != TextBgMode.NONE) {
            setSpan(
                BackgroundColorSpan(getTextBgColor(bgMode).toArgb()),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val styleInt = when {
            isBold && isItalic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        setSpan(StyleSpan(styleInt), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan(fontStyle.familyName), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(2.0f), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(anchorX, anchorY)
        .setOverlayFrameAnchor(0f, 0f)
        .setScale(scale, scale)
        .build()

    val textOverlay = TextOverlay.createStaticTextOverlay(spannable, settings)
    return listOf(OverlayEffect(ImmutableList.of(textOverlay)))
}
