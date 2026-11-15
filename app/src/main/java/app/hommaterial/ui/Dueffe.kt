package app.hommaterial.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * The Dueffe look, chosen in Settings → Appearance → Style: the design system of Dueffe Home. Dark
 * only, an almost black ground with warm surfaces, ivory words and one gold accent; Bricolage
 * Grotesque for titles, Inter for the rest; cards in a vertical gradient with a thin golden line and
 * no shadow, gold buttons in a 135° gradient, a living aurora of golden nebulae and rising dust under
 * the screens, and a spring press instead of the ripple. As with the other looks, every piece falls
 * back to its Material component when the look is off.
 */

const val LOOK_DUEFFE = 4

/** Whether the screens are drawn in the Dueffe look. */
internal val LocalDueffe = staticCompositionLocalOf { false }

/** The tokens of the Dueffe design system. */
internal object DF {
    val Bg = Color(0xFF050505)
    val BgElevated = Color(0xFF0B0A08)
    val Surface = Color(0xFF11100D)
    val SurfaceHigh = Color(0xFF1A1711)
    val SurfaceHigher = Color(0xFF242016)
    val SurfaceContainer = Color(0xF012100C)
    val Text = Color(0xFFFFFAF0)
    val Muted = Color(0xFFB8AA8D)
    val Line = Color(0x29FFDC80)
    val LineStrong = Color(0x57FFD84D)
    val Primary = Color(0xFFFFD84D)
    val PrimaryStrong = Color(0xFFFFB800)
    val Secondary = Color(0xFFFFF0AD)
    val OnPrimary = Color(0xFF161006)
    val Success = Color(0xFFA8F0B0)
    val Error = Color(0xFFFF9B85)
    /** Opaque on Android, where the bar has no blur behind it to soften what scrolls under. */
    val Nav = Color(0xFF080705)

    /** The gold of the main actions: from the accent towards Primary Strong, at 135°. */
    val Gold = Brush.linearGradient(listOf(Primary, Color(0xFFFFCD2E)))
}

/** The curves of the design system. */
internal object DfMotion {
    val Reveal = CubicBezierEasing(.22f, .78f, .16f, 1f)
    val Letter = CubicBezierEasing(.16f, .86f, .24f, 1f)
    val Standard = CubicBezierEasing(.4f, 0f, .2f, 1f)
}

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) = Font(R.font.bricolage, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(R.font.inter, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Bricolage = FontFamily(bricolage(400), bricolage(500), bricolage(600), bricolage(700), bricolage(800))
private val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** The one dark scheme of Dueffe: the theme of the phone and the chosen accent are left aside. */
internal fun dueffeScheme(): ColorScheme = darkColorScheme(
    primary = DF.Primary, onPrimary = DF.OnPrimary,
    primaryContainer = DF.SurfaceHigher, onPrimaryContainer = DF.Secondary,
    secondary = DF.Secondary, onSecondary = DF.OnPrimary,
    secondaryContainer = DF.SurfaceHigher, onSecondaryContainer = DF.Secondary,
    tertiary = DF.PrimaryStrong, onTertiary = DF.OnPrimary,
    tertiaryContainer = Color(0x24FFB800), onTertiaryContainer = DF.Secondary,
    error = DF.Error, onError = DF.OnPrimary, errorContainer = Color(0x24FF9B85), onErrorContainer = DF.Error,
    background = DF.Bg, onBackground = DF.Text,
    surface = DF.Surface, onSurface = DF.Text, onSurfaceVariant = DF.Muted,
    surfaceVariant = DF.SurfaceHigh, surfaceTint = Color.Transparent,
    surfaceContainerLowest = DF.Bg, surfaceContainerLow = DF.BgElevated, surfaceContainer = DF.Surface,
    surfaceContainerHigh = DF.SurfaceHigh, surfaceContainerHighest = DF.SurfaceHigher,
    surfaceBright = DF.SurfaceHigher, surfaceDim = DF.Bg,
    // The toasts: Surface Higher with ivory words and a gold action.
    inverseSurface = DF.SurfaceHigher, inverseOnSurface = DF.Text, inversePrimary = DF.Primary,
    outline = DF.LineStrong, outlineVariant = DF.Line, scrim = Color.Black,
)

/** Toasts and fields 16–18, banners 22, cards 26. */
internal val DueffeShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Bricolage Grotesque for displays, headlines and big titles, Inter for the rest. */
internal val DueffeTypography = Typography(
    displayLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 48.sp, letterSpacing = (-0.035).em),
    displayMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-0.03).em),
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    headlineLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.04.em),
)

// ——— Movement ———

/** The spring press of Dueffe: pressed it shrinks to [pressed], let go it bounces back. It only watches the touch, the click stays its owner's. */
@Composable
internal fun Modifier.dueffePress(pressed: Float = 0.94f): Modifier {
    var down by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = if (down) 1f else 0.42f, stiffness = if (down) 900f else 380f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                down = false
            }
        }
}

// ——— The aurora under the screens ———

private class Dust(val x: Float, val y: Float, val speed: Float, val r: Float)

private fun seed(i: Int): Float {
    val v = sin(i * 12.9898f) * 43758.547f
    return v - floor(v)
}

private val dust = List(34) { i -> Dust(seed(i * 3 + 1), seed(i * 7 + 2), 0.35f + seed(i * 11 + 3), 0.6f + seed(i * 5 + 4) * 1.8f) }

private fun DrawScope.blob(center: Offset, radius: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)

/**
 * The living ground of Dueffe: three gold and amber nebulae drifting in 32 s and 34 grains of ivory
 * dust rising and twinkling. Every screen draws its own, all on the same clock, so one sliding over
 * another hides it.
 */
@Composable
internal fun Modifier.dueffeAurora(): Modifier {
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(32_000, easing = LinearEasing)), label = "aurora phase")
    return drawBehind {
        drawRect(DF.Bg)
        val w = size.width
        val h = size.height
        blob(Offset(w * (0.18f + 0.14f * cos(a)), h * 0.06f + 50f * sin(a * 2)), w * 0.95f, Color(0x38FFB800))
        blob(Offset(w * (0.95f + 0.08f * sin(a)), h * (0.38f + 0.05f * cos(a))), w * 0.75f, Color(0x24FFD84D))
        blob(Offset(w * (0.35f + 0.2f * sin(a + 1.3f)), h * (0.92f + 0.04f * sin(a * 3))), w * 1.05f, Color(0x1F8A5A00))
        val phase = a / (2 * PI).toFloat()
        dust.forEach { d ->
            val y = ((d.y - phase * d.speed) % 1f + 1f) % 1f
            val twinkle = 0.5f + 0.5f * sin(a * 6 * d.speed + d.x * 20)
            drawCircle(
                DF.Secondary.copy(alpha = 0.08f + 0.28f * twinkle * (1f - y * 0.6f)),
                radius = d.r * density,
                center = Offset(w * d.x + 14f * sin(a * 2 + d.y * 9), h * y),
            )
        }
    }
}

// ——— Pieces ———

/** A card of Dueffe: a vertical gradient from Surface High to Surface, a thin golden line, no shadow. */
internal fun Modifier.dueffeCard(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(26.dp)): Modifier =
    clip(shape).background(Brush.verticalGradient(listOf(DF.SurfaceHigh, DF.Surface))).border(1.dp, DF.Line, shape)

/** The icon of a section: a squircle on Surface High with a golden line. */
internal fun Modifier.dueffeIcon(): Modifier {
    val shape = RoundedCornerShape(27)
    return clip(shape).background(DF.SurfaceHigh).border(1.dp, DF.Line, shape)
}

/** A tile: a card when off, the gold when on; it springs when pressed. */
@Composable
internal fun DueffeTile(on: Boolean, modifier: Modifier, clicks: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val gold by animateFloatAsState(if (on) 1f else 0f, tween(320, easing = DfMotion.Standard), label = "tile gold")
    val ink by animateColorAsState(if (on) DF.OnPrimary else DF.Text, tween(320, easing = DfMotion.Standard), label = "tile ink")
    Surface(
        color = Color.Transparent,
        contentColor = ink,
        shape = shape,
        border = BorderStroke(1.dp, if (on) DF.Primary.copy(alpha = 0.6f) else DF.Line),
        modifier = modifier
            .dueffePress()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(DF.SurfaceHigh, DF.Surface)))
            .drawBehind { if (gold > 0f) drawRect(DF.Gold, alpha = gold) }
            .then(clicks),
        content = content,
    )
}

/** The header of a page: its title in Bricolage, on the bare aurora. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DueffeTopBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = { ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.headlineMedium), title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = DF.SurfaceContainer,
            titleContentColor = DF.Text,
            navigationIconContentColor = DF.Text,
            actionIconContentColor = DF.Muted,
        ),
    )
}

/** The switch: a pill track, golden when on, a round thumb that springs across. */
@Composable
internal fun DueffeSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) {
    val thumb by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "thumb")
    val gold by animateFloatAsState(if (checked) 1f else 0f, tween(260, easing = DfMotion.Standard), label = "track")
    val knob by animateColorAsState(if (checked) DF.OnPrimary else DF.Muted, tween(260), label = "knob")
    Box(
        modifier
            .padding(vertical = 10.dp)
            .size(50.dp, 28.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(DF.Surface)
            .drawBehind { if (gold > 0f) drawRect(DF.Gold, alpha = gold) }
            .border(1.dp, if (checked) DF.Primary else DF.LineStrong, CircleShape)
            .then(
                if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier,
            )
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = thumb).size(20.dp).background(knob, CircleShape))
    }
}

/** A choice: a pill on Surface with a golden line, full gold when chosen. */
@Composable
internal fun DueffeFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, leadingIcon: (@Composable () -> Unit)?) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { ProvideTextStyle(MaterialTheme.typography.labelLarge, label) },
        leadingIcon = leadingIcon,
        shape = CircleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = DF.Line, selectedBorderColor = DF.Primary, borderWidth = 1.dp, selectedBorderWidth = 1.dp,
        ),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = DF.Surface,
            labelColor = DF.Text,
            iconColor = DF.Muted,
            selectedContainerColor = DF.Primary,
            selectedLabelColor = DF.OnPrimary,
            selectedLeadingIconColor = DF.OnPrimary,
        ),
        modifier = modifier.dueffePress(0.92f),
    )
}

/** The secondary button: Surface with the strong golden line, ivory words. */
@Composable
internal fun DueffeTonalButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = shape,
        border = BorderStroke(1.dp, if (enabled) DF.LineStrong else DF.Line),
        colors = ButtonDefaults.buttonColors(containerColor = DF.Surface, contentColor = DF.Text, disabledContainerColor = DF.Surface),
        modifier = modifier.dueffePress(0.95f),
        content = content,
    )
}

/** The main button: the gold gradient at 135° with dark words. */
@Composable
internal fun DueffeButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = DF.OnPrimary, disabledContainerColor = DF.Surface),
        modifier = modifier
            .dueffePress(0.95f)
            .clip(shape)
            .then(if (enabled) Modifier.background(DF.Gold) else Modifier.border(1.dp, DF.Line, shape)),
        content = content,
    )
}
