package com.humblesolutions.ainotetaker.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.humblesolutions.ainotetaker.i18n.Language

/** The geometry the app repeats: 20dp cards, pill buttons 52dp tall, 44dp touch targets. */
object TasShape {
    val radiusSm = 10.dp
    /** Inputs and small surfaces. */
    val radius = 14.dp
    /** Cards. */
    val radiusLg = 20.dp
    val pill = 999.dp
    val tap = 44.dp
    val button = 52.dp
}

private val LocalTasColors = staticCompositionLocalOf { LightColors }
private val LocalTasTypography = staticCompositionLocalOf { AppTypography }
private val LocalLanguage = staticCompositionLocalOf { Language.ENGLISH }

/** Everything a screen needs from the theme: `Tas.colors`, `Tas.type`, `Tas.language`. */
object Tas {
    val colors: TasColors
        @Composable @ReadOnlyComposable get() = LocalTasColors.current
    val type: TasTypography
        @Composable @ReadOnlyComposable get() = LocalTasTypography.current
    val language: Language
        @Composable @ReadOnlyComposable get() = LocalLanguage.current
}

/** Wraps the app. Light only, English only. */
@Composable
fun TasTheme(
    language: Language = Language.ENGLISH,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = LightColors

    // Dark status and navigation bar icons on the light app.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as? Activity)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalTasColors provides colors,
        LocalTasTypography provides AppTypography,
        LocalLanguage provides language,
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = colors.primary,
                onPrimary = colors.primaryForeground,
                background = colors.background,
                onBackground = colors.foreground,
                surface = colors.card,
                onSurface = colors.cardForeground,
                surfaceContainerHigh = colors.card,
                error = colors.destructive,
                onError = colors.destructiveForeground,
                outline = colors.border,
            ),
            content = content,
        )
    }
}

/** `Strings.get` with the language already applied. Every screen uses this. */
@Composable
@ReadOnlyComposable
fun tr(key: String, vararg args: Pair<String, Any?>): String =
    com.humblesolutions.ainotetaker.i18n.Strings.get(key, Tas.language, args.toMap())
