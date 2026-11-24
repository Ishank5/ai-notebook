package com.humblesolutions.ainotetaker.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.humblesolutions.ainotetaker.R

/** Manrope, the one typeface of the app. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

/** Every text style the app uses. */
data class TasTypography(
    val display2xl: TextStyle,
    val displayXl: TextStyle,
    val displayLg: TextStyle,
    val heading: TextStyle,
    val subheading: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val tiny: TextStyle,
    /** Small section label above a card's content. */
    val label: TextStyle,
    /** The recorder's clock: big, bold, tabular figures so it does not jitter. */
    val timer: TextStyle,
    /** Smaller tabular figures (a paused or saving clock). */
    val stat: TextStyle,
)

val AppTypography = TasTypography(
    display2xl = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em,
    ),
    displayXl = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em,
    ),
    displayLg = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.015).em,
    ),
    heading = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em,
    ),
    subheading = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    tiny = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp,
    ),
    label = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.01.em,
    ),
    timer = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 60.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = "tnum",
    ),
    stat = TextStyle(
        fontFamily = Manrope, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = "tnum",
    ),
)
