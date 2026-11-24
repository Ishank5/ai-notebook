package com.humblesolutions.ainotetaker.ui.theme

import androidx.compose.ui.graphics.Color

/** The AI Notebook palette, (see the README). */
object Palette {
    val bg = Color(0xFFF4F6F5)
    val surface = Color(0xFFFFFFFF)
    val surfaceAlt = Color(0xFFEEF2F0)
    val line = Color(0xFFE1E7E4)
    val ink = Color(0xFF0F1B19)
    val inkMuted = Color(0xFF5A6B67)
    val inkFaint = Color(0xFF8C9A96)
    val primary = Color(0xFF0D7A68)
    val primaryPressed = Color(0xFF09604F)
    val primaryTint = Color(0xFFE3F3EF)
    val record = Color(0xFFE5484D)
    val recordTint = Color(0xFFFDECEC)
    val amber = Color(0xFFB7791F)
    val amberTint = Color(0xFFFBF3E2)
    val success = Color(0xFF1F8A4C)

    /** Text on the tints: the same hues, a step darker so small text stays readable. */
    val recordInk = Color(0xFFB4262B)
    val successTint = Color(0xFFE5F4EB)
    val white = Color(0xFFFFFFFF)
}

/**
 * Every colour a screen names. The slot names are the ones the screens already use; the values are the new palette.
 */
data class TasColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,

    val primary: Color,
    val primaryForeground: Color,
    val primaryPressed: Color,
    val primaryWash: Color,
    val accentForeground: Color,

    val muted: Color,
    val mutedForeground: Color,
    val faintForeground: Color,

    val record: Color,
    val recordTint: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    val destructiveWash: Color,
    val destructiveWashForeground: Color,

    val success: Color,
    val successWash: Color,
    val successWashForeground: Color,

    val warning: Color,
    val warningWash: Color,
    val warningWashForeground: Color,

    val border: Color,
    val input: Color,
    val inputBackground: Color,
    val ring: Color,
)

val LightColors = TasColors(
    background = Palette.bg,
    foreground = Palette.ink,
    card = Palette.surface,
    cardForeground = Palette.ink,

    primary = Palette.primary,
    primaryForeground = Palette.white,
    primaryPressed = Palette.primaryPressed,
    primaryWash = Palette.primaryTint,
    accentForeground = Palette.primary,

    muted = Palette.surfaceAlt,
    mutedForeground = Palette.inkMuted,
    faintForeground = Palette.inkFaint,

    record = Palette.record,
    recordTint = Palette.recordTint,
    destructive = Palette.record,
    destructiveForeground = Palette.white,
    destructiveWash = Palette.recordTint,
    destructiveWashForeground = Palette.recordInk,

    success = Palette.success,
    successWash = Palette.successTint,
    successWashForeground = Palette.success,

    warning = Palette.amber,
    warningWash = Palette.amberTint,
    warningWashForeground = Palette.amber,

    border = Palette.line,
    input = Palette.line,
    inputBackground = Palette.surfaceAlt,
    ring = Palette.primary,
)
