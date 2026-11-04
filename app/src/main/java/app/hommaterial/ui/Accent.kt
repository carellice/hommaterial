package app.hommaterial.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color

/** The accent that follows the wallpaper of the phone, where Android has it. */
const val ACCENT_AUTO = "auto"

/** A color the app can take: its [seed] is the swatch shown in the settings. */
internal class Accent(val key: String, val label: Int, val seed: Color, val light: ColorScheme, val dark: ColorScheme)

/** Whether the phone gives the colors of its wallpaper to the apps. */
internal val hasWallpaperColors get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The colors of the app for the [accent] chosen, light or [dark]. */
internal fun accentScheme(accent: String, dark: Boolean, context: Context): ColorScheme {
    // Without the colors of the wallpaper, the automatic accent is the first, as the settings show it.
    val chosen = ACCENTS.find { it.key == accent } ?: ACCENTS.first().takeUnless { hasWallpaperColors }
    return when {
        chosen != null -> if (dark) chosen.dark else chosen.light
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> ACCENTS.first().let { if (dark) it.dark else it.light }
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
}
