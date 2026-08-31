package com.bfg.watchfaces.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's colours.
 *
 * ## Why not dynamic colour
 *
 * This used to call `dynamicLightColorScheme`, which is the most Android-native
 * thing a Compose app can do — and it meant the app took its identity from
 * whatever wallpaper the phone happened to have. On the emulator that produced
 * a blue-violet studio, which is nothing to do with BFG.
 *
 * Material You is the right default for a utility. It is the wrong default for
 * an app whose entire subject is choosing colours: the swatches, the dial and
 * the chrome all competed, and a person could not tell which purple was theirs
 * and which was the phone's.
 *
 * So the scheme is fixed, and it is the brand's: the plum and blush that
 * `BrandMark` uses for the launcher icon, on the near-black the workbench uses.
 * Same two inks in both modes, so the app is recognisably one thing.
 */
private val PLUM = Color(0xFF80475C)
private val BLUSH = Color(0xFFF4E6EB)
private val ROSE = Color(0xFFD09AAB)
private val INK = Color(0xFF1C181A)
private val GOLD = Color(0xFFC9A227)   // the workbench accent, for what is live or valid

private val LIGHT = lightColorScheme(
    primary = PLUM,
    onPrimary = BLUSH,
    primaryContainer = BLUSH,
    onPrimaryContainer = PLUM,
    secondary = ROSE,
    onSecondary = INK,
    secondaryContainer = Color(0xFFF6ECEF),
    onSecondaryContainer = Color(0xFF4A2836),
    tertiary = GOLD,
    background = Color(0xFFFDFBFC),
    onBackground = INK,
    surface = Color(0xFFFDFBFC),
    onSurface = INK,
    surfaceVariant = Color(0xFFF2E9EC),
    onSurfaceVariant = Color(0xFF5C4650),
    outline = Color(0xFFA88E98)
)

private val DARK = darkColorScheme(
    primary = ROSE,
    onPrimary = Color(0xFF3A2029),
    primaryContainer = Color(0xFF5A303E),
    onPrimaryContainer = BLUSH,
    secondary = Color(0xFFE0B7C4),
    onSecondary = Color(0xFF3A2029),
    secondaryContainer = Color(0xFF4A2E38),
    onSecondaryContainer = BLUSH,
    tertiary = GOLD,
    background = INK,
    onBackground = Color(0xFFECE2E5),
    surface = INK,
    onSurface = Color(0xFFECE2E5),
    surfaceVariant = Color(0xFF3A3033),
    onSurfaceVariant = Color(0xFFCFBCC2),
    outline = Color(0xFF8A757C)
)

@Composable
fun BfgTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DARK else LIGHT, content = content)
}
