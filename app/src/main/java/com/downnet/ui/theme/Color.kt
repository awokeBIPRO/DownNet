package com.downnet.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand fallback palettes, used before Android 12 or when dynamic color is
 * unavailable. Indigo primary with a cyan tertiary keeps platform accents
 * (YouTube red / X near-black / Instagram gradient) legible on top.
 */
private val Indigo = Color(0xFFB4C5FF)
private val IndigoDim = Color(0xFF4A5FA8)
private val OnIndigo = Color(0xFF11276B)
private val IndigoContainer = Color(0xFF2A3F76)
private val OnIndigoContainer = Color(0xFFDBE2FF)
private val Cyan = Color(0xFF7CD9F5)
private val CyanContainer = Color(0xFF0F4E63)
private val OnCyanContainer = Color(0xFFC4EDFF)

val DarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = OnIndigo,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = OnIndigoContainer,
    secondary = Cyan,
    onSecondary = Color(0xFF003544),
    secondaryContainer = CyanContainer,
    onSecondaryContainer = OnCyanContainer,
    tertiary = Color(0xFFFFB4BE),
    onTertiary = Color(0xFF5F1130),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outline = Color(0xFF8E9099),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

val LightColors = lightColorScheme(
    primary = IndigoDim,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE2FF),
    onPrimaryContainer = Color(0xFF0A1B66),
    secondary = Color(0xFF07677F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC4EDFF),
    onSecondaryContainer = Color(0xFF001F28),
    tertiary = Color(0xFF8E4955),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFDF9F9),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDF9F9),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
)
