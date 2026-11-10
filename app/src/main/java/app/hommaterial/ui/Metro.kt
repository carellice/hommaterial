package app.hommaterial.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Modern UI look of Windows Phone, chosen in Settings → Appearance → Style: pure black or white
 * under the screens, square tiles in one vivid accent, light type set large, square buttons with a
 * thick border and the tilt of a tile under the finger. As with the skeuomorphic look, every piece
 * falls back to its Material component when the look is off.
 */

const val LOOK_METRO = 2

/** Whether the screens are drawn in the Modern UI look. */
internal val LocalMetro = staticCompositionLocalOf { false }

/** The accent made vivid as on Windows Phone: the same hue, full and bright, under white text. */
internal fun metroAccent(base: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    // A gray accent stays gray: only a real hue is pushed to full color.
    hsv[1] = if (hsv[1] < 0.12f) hsv[1] else 0.88f
    hsv[2] = 0.82f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Black or white, gray panels and the vivid accent everywhere Material would tint. */
internal fun metroScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val accent = metroAccent(base.primary)
    val common = base.copy(
        primary = accent, onPrimary = Color.White,
        primaryContainer = accent, onPrimaryContainer = Color.White,
        secondary = accent, onSecondary = Color.White,
        secondaryContainer = accent, onSecondaryContainer = Color.White,
        surfaceTint = Color.Transparent,
    )
    return if (dark) common.copy(
        background = Color.Black, onBackground = Color.White,
        surface = Color.Black, onSurface = Color.White, onSurfaceVariant = Color(0xFFA6A6A6),
        surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF141414), surfaceContainer = Color(0xFF1F1F1F),
        surfaceContainerHigh = Color(0xFF2B2B2B), surfaceContainerHighest = Color(0xFF363636),
        surfaceBright = Color(0xFF363636), surfaceDim = Color.Black, surfaceVariant = Color(0xFF2B2B2B),
        outline = Color(0xFFBFBFBF), outlineVariant = Color(0xFF3D3D3D),
    ) else common.copy(
        background = Color.White, onBackground = Color.Black,
        surface = Color.White, onSurface = Color.Black, onSurfaceVariant = Color(0xFF5E5E5E),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7), surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerHigh = Color(0xFFE6E6E6), surfaceContainerHighest = Color(0xFFDCDCDC),
        surfaceBright = Color.White, surfaceDim = Color(0xFFDCDCDC), surfaceVariant = Color(0xFFE6E6E6),
        outline = Color(0xFF404040), outlineVariant = Color(0xFFCCCCCC),
    )
}

/** Square corners everywhere. */
internal val MetroShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(CornerSize(0.dp)),
    small = androidx.compose.foundation.shape.RoundedCornerShape(CornerSize(0.dp)),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(CornerSize(0.dp)),
    large = androidx.compose.foundation.shape.RoundedCornerShape(CornerSize(0.dp)),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(CornerSize(0.dp)),
)

/** The big titles light and a little tighter, as the headers of the hubs. */
internal val MetroTypography: Typography = Typography().let { t ->
    fun TextStyle.light() = copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp)
    t.copy(
        displayLarge = t.displayLarge.light(), displayMedium = t.displayMedium.light(), displaySmall = t.displaySmall.light(),
        headlineLarge = t.headlineLarge.light(), headlineMedium = t.headlineMedium.light(), headlineSmall = t.headlineSmall.light(),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Normal),
    )
}

/** The tilt of Windows Phone: what is held sinks a little into the screen. Watches the touch, never takes it. */
@Composable
internal fun Modifier.metroTilt(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.55f, stiffness = 900f), label = "tilt")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; rotationX = (1f - scale) * 40f }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(PointerEventPass.Initial)
                pressed = false
            }
        }
}

/** A tile: the accent when on, a gray square when off. */
@Composable
internal fun MetroTile(on: Boolean, modifier: Modifier, clicks: Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color by animateColorAsState(if (on) colors.primary else colors.surfaceContainerHigh, label = "tile")
    Surface(
        color = color,
        contentColor = if (on) Color.White else colors.onSurface,
        shape = RectangleShape,
        modifier = modifier.metroTilt().then(clicks),
        content = content,
    )
}

/** The header of a page: big light words on the bare background. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetroTopBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit) {
    TopAppBar(
        title = { ProvideTextStyle(LocalTextStyle.current.merge(MaterialTheme.typography.headlineMedium), title) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/** The toggle of Windows Phone 8: a framed track that fills with the accent, and a square thumb. */
@Composable
internal fun MetroSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val thumb by animateDpAsState(if (checked) 40.dp else 0.dp, spring(dampingRatio = 0.8f, stiffness = 900f), label = "thumb")
    val frame = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    Box(
        modifier
            .padding(vertical = 12.dp)
            .size(54.dp, 24.dp)
            .border(2.dp, frame)
            .then(
                if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier,
            ),
    ) {
        if (checked) Box(Modifier.padding(4.dp).width(thumb).fillMaxHeight().background(if (enabled) colors.primary else frame))
        Box(Modifier.offset(x = thumb).width(14.dp).fillMaxHeight().background(frame))
    }
}

/** A choice: framed when free, filled with the accent when chosen. */
@Composable
internal fun MetroFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, leadingIcon: (@Composable () -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = leadingIcon,
        shape = RectangleShape,
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = colors.onSurface, selectedBorderColor = colors.primary, borderWidth = 2.dp, selectedBorderWidth = 2.dp,
        ),
        colors = FilterChipDefaults.filterChipColors(
            labelColor = colors.onSurface,
            selectedContainerColor = colors.primary,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
        modifier = modifier.metroTilt(),
    )
}

/** The button of a secondary action: a frame of two pixels, as every button of the phone. */
@Composable
internal fun MetroTonalButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = RectangleShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
        modifier = modifier.metroTilt(),
        content = content,
    )
}

/** The button of the main action: a square of the accent. */
@Composable
internal fun MetroButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, contentPadding: PaddingValues, content: @Composable RowScope.() -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White),
        modifier = modifier.metroTilt(),
        content = content,
    )
}

/** The rounded corners of a card or a key in Material and the skeuomorphic look, square in Modern UI. */
@Composable
internal fun corners(radius: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape =
    if (LocalMetro.current) RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(radius)
