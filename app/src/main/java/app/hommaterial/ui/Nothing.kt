package app.hommaterial.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hommaterial.R

/*
 * The Nothing look, chosen in Settings → Appearance → Style: monochrome on OLED black or warm
 * off-white, Space Grotesk for the words, Space Mono for labels and data, the dot matrix of Doto for
 * the titles of the pages, a faint grid of dots under every screen, pill buttons, hairline borders,
 * no shadows and quick ease-out fades. Red is the one signal. As with the other looks, every piece
 * falls back to its Material component when the look is off.
 */

const val LOOK_NOTHING = 3

/** Whether the screens are drawn in the Nothing look. */
internal val LocalNothing = staticCompositionLocalOf { false }

/** The signal red of Nothing: errors, the urgent, the one dot that matters. */
internal val NothingRed = Color(0xFFD71921)

/** The subtle ease-out of Nothing: no spring, no bounce. */
internal val NothingEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: Int) = Font(R.font.space_grotesk, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private val Doto = FontFamily(Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))))
private val SpaceGrotesk = FontFamily(grotesk(300), grotesk(400), grotesk(500), grotesk(700))
internal val SpaceMono = FontFamily(Font(R.font.space_mono, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))

/** Grays only, the brightest for what matters most; the chosen accent is left aside, red signals. */
internal fun nothingScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color.White, onPrimaryContainer = Color.Black,
    secondary = Color(0xFFE8E8E8), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF222222), onSecondaryContainer = Color.White,
    tertiary = NothingRed, onTertiary = Color.White,
    tertiaryContainer = Color(0x26D71921), onTertiaryContainer = Color(0xFFFF6B70),
    error = NothingRed, onError = Color.White, errorContainer = Color(0x26D71921), onErrorContainer = Color(0xFFFF6B70),
    background = Color.Black, onBackground = Color(0xFFE8E8E8),
    surface = Color.Black, onSurface = Color(0xFFE8E8E8), onSurfaceVariant = Color(0xFF999999),
    surfaceVariant = Color(0xFF1A1A1A), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0B), surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A), surfaceContainerHighest = Color(0xFF222222),
    surfaceBright = Color(0xFF222222), surfaceDim = Color.Black,
    inverseSurface = Color(0xFF222222), inverseOnSurface = Color(0xFFE8E8E8), inversePrimary = Color.Black,
    outline = Color(0xFF333333), outlineVariant = Color(0xFF222222), scrim = Color.Black,
) else lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color.Black, onPrimaryContainer = Color.White,
    secondary = Color(0xFF1A1A1A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color.Black,
    tertiary = NothingRed, onTertiary = Color.White,
    tertiaryContainer = Color(0x26D71921), onTertiaryContainer = Color(0xFFA30F15),
    error = NothingRed, onError = Color.White, errorContainer = Color(0x26D71921), onErrorContainer = Color(0xFFA30F15),
    background = Color(0xFFF5F5F5), onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFF5F5F5), onSurface = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFF666666),
    surfaceVariant = Color(0xFFF0F0F0), surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F0), surfaceContainerHighest = Color(0xFFE8E8E8),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE8E8E8),
    inverseSurface = Color(0xFF1A1A1A), inverseOnSurface = Color.White, inversePrimary = Color.White,
    outline = Color(0xFFCCCCCC), outlineVariant = Color(0xFFE8E8E8), scrim = Color.Black,
)

/** Cards at most 16, compact pieces 8, technical ones 4. */
internal val NothingShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Doto for the displays, Space Grotesk for the words, Space Mono spaced wide for the small labels and data. */
internal val NothingTypography: Typography = Typography().let { t ->
    fun TextStyle.dots() = copy(fontFamily = Doto, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    fun TextStyle.words(weight: FontWeight = fontWeight ?: FontWeight.Normal) = copy(fontFamily = SpaceGrotesk, fontWeight = weight)
    fun TextStyle.mono() = copy(fontFamily = SpaceMono, fontWeight = FontWeight.Normal, letterSpacing = 0.06.em)
    t.copy(
        displayLarge = t.displayLarge.dots(), displayMedium = t.displayMedium.dots(), displaySmall = t.displaySmall.dots(),
        headlineLarge = t.headlineLarge.words(FontWeight.Light), headlineMedium = t.headlineMedium.words(FontWeight.Light),
        headlineSmall = t.headlineSmall.words(),
        titleLarge = t.titleLarge.words(), titleMedium = t.titleMedium.words(FontWeight.Medium), titleSmall = t.titleSmall.words(FontWeight.Medium),
        bodyLarge = t.bodyLarge.words(), bodyMedium = t.bodyMedium.words(), bodySmall = t.bodySmall.words(),
        // Material sets menus, buttons and tabs in labelLarge, in mixed case: Space Grotesk reads better there than Space Mono.
        labelLarge = t.labelLarge.words(FontWeight.Medium), labelMedium = t.labelMedium.mono(), labelSmall = t.labelSmall.mono(),
    )
}

/** One dot of the grid in a tile of 16 dp, repeated under the whole screen. */
private fun dotTile(sizePx: Int, radiusPx: Float, color: Color): Bitmap =
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawCircle(sizePx / 2f, sizePx / 2f, radiusPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
    }

/** The faint dot matrix of Nothing OS, over the color of the background. */
@Composable
internal fun nothingDots(): Brush {
    val density = LocalDensity.current
    val dot = MaterialTheme.colorScheme.outline
    return remember(density, dot) {
        val size = with(density) { 16.dp.roundToPx() }
        val bitmap = dotTile(size, with(density) { 0.9.dp.toPx() }, dot.copy(alpha = 0.55f)).asImageBitmap()
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}

/** A tile: a hairline card when off, inverted — white on black, black on white — when on. */
@Composable
internal fun NothingTile(on: Boolean, modifier: Modifier, clicks: Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(if (on) colors.primary else colors.surfaceContainer, tween(200, easing = NothingEase), label = "tile")
    val ink by animateColorAsState(if (on) colors.onPrimary else colors.onSurface, tween(200, easing = NothingEase), label = "tile ink")
    Surface(
        color = color,
        contentColor = ink,
        shape = RoundedCornerShape(16.dp),
        border = if (on) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier.then(clicks),
        content = content,
    )
}

/** The header of a page: its title in the dot matrix, on the bare background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NothingTopBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = { ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp, lineHeight = 34.sp)), title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/** A physical switch: a pill track and a round thumb, the track lit white — or black — when on. */
@Composable
internal fun NothingSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val thumb by animateDpAsState(if (checked) 20.dp else 0.dp, tween(200, easing = NothingEase), label = "thumb")
    val track by animateColorAsState(if (checked) colors.primary else colors.outline, tween(200, easing = NothingEase), label = "track")
    val knob by animateColorAsState(if (checked) colors.onPrimary else colors.onSurfaceVariant, tween(200, easing = NothingEase), label = "knob")
    Box(
        modifier
            .padding(vertical = 10.dp)
            .size(48.dp, 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(track, CircleShape)
            .then(
                if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier,
            )
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = thumb).size(20.dp).background(knob, CircleShape))
    }
}

/** A choice: a pill with a hairline when free, inverted when chosen. */
@Composable
internal fun NothingFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, leadingIcon: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { ProvideTextStyle(MaterialTheme.typography.labelMedium, label) },
        leadingIcon = leadingIcon,
        shape = CircleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = colors.outline, selectedBorderColor = colors.primary, borderWidth = 1.dp, selectedBorderWidth = 1.dp,
        ),
        colors = FilterChipDefaults.filterChipColors(
            labelColor = colors.onSurfaceVariant,
            iconColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            selectedLeadingIconColor = colors.onPrimary,
        ),
        modifier = modifier,
    )
}

/** The secondary button: a pill with a hairline. */
@Composable
internal fun NothingTonalButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        modifier = modifier,
        content = content,
    )
}

/** The main button: a white pill with black words, or the reverse in the light theme. */
@Composable
internal fun NothingButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
        modifier = modifier,
        content = content,
    )
}

/** A thin outlined ring for the icon of a section, instead of a filled badge. */
@Composable
internal fun Modifier.nothingRing(): Modifier = border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
