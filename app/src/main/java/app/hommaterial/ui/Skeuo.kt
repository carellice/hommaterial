package app.hommaterial.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sin

/*
 * The skeuomorphic look, chosen in Settings → Appearance → Style: linen and leather under the
 * screens, ivory keys that light up as glass, a brushed bar on top, a glossy black bar at the
 * bottom and the switch of the phones of 2012. With the look off every piece here falls back to the
 * Material component it stands for, so the Material app stays exactly as it was.
 */

const val LOOK_MATERIAL = 0
const val LOOK_SKEUO = 1

/** Whether the screens are drawn in the skeuomorphic look. */
internal val LocalSkeuo = staticCompositionLocalOf { false }

/** The materials of the skeuomorphic look, light or dark. */
internal class SkeuoMaterials(val dark: Boolean, scheme: ColorScheme) {
    /** The accent as colored glass: deep enough under white text in both themes. */
    val tint = if (dark) lerp(scheme.primaryContainer, scheme.primary, 0.3f) else scheme.primary
    val gelTop = lerp(tint, Color.White, if (dark) 0.4f else 0.38f)
    val gelMid = lerp(tint, Color.White, 0.12f)
    val gelLow = lerp(tint, Color.Black, if (dark) 0.3f else 0.18f)
    val gelEdge = lerp(tint, Color.Black, if (dark) 0.6f else 0.45f)
    val metal = if (dark) lerp(scheme.primaryContainer, Color(0xFF2E3238), 0.65f) else lerp(scheme.primary, Color(0xFF6E7886), 0.48f)
    val metalHi = lerp(metal, Color.White, if (dark) 0.2f else 0.45f)
    val metalLow = lerp(metal, Color.Black, 0.18f)
    val raise = if (dark) listOf(Color(0xFF57514A), Color(0xFF48423B), Color(0xFF3E3832), Color(0xFF34302A))
    else listOf(Color(0xFFFFFFFF), Color(0xFFF6F2EA), Color(0xFFECE6DA), Color(0xFFE2DBCD))
    val pressed = if (dark) listOf(Color(0xFF2A2621), Color(0xFF36312B)) else listOf(Color(0xFFD9D2C3), Color(0xFFE8E2D6))
    val panel = if (dark) listOf(Color(0xFF403A33), Color(0xFF35302A)) else listOf(Color(0xFFFFFEFB), Color(0xFFF5F1E8))
    val edge = if (dark) Color(0xB3000000) else Color(0x42463419)
    val highlight = if (dark) Color(0x21FFFFFF) else Color(0xF2FFFFFF)
    val field = if (dark) Color(0xFF1E1B18) else Color.White
    val paper = if (dark) Color(0xFF3B3529) else Color(0xFFFFFCE9)
    val rule = if (dark) Color(0x3378A0E6) else Color(0x525484CD)
    val margin = if (dark) Color(0x59E66464) else Color(0x80D64E4E)
    val engraved = if (dark) Color(0xFFA1978A) else Color(0xFF8B8274)
    /** The text pressed into the surface: a light line under it, or a dark one over it in the dark. */
    val emboss = Shadow(if (dark) Color(0xBF000000) else Color(0xD9FFFFFF), Offset(0f, if (dark) -1.5f else 1.5f), 0f)
    val onGlass = Shadow(Color(0x66000000), Offset(0f, -1.5f), 0f)
}

internal val LocalSkeuoMaterials = staticCompositionLocalOf<SkeuoMaterials?> { null }

/** The colors of the skeuomorphic look over those of the accent: warm paper, or leather in the dark. */
internal fun skeuoScheme(base: ColorScheme, dark: Boolean): ColorScheme = if (dark) base.copy(
    background = Color(0xFF26221E), onBackground = Color(0xFFEFE8DC),
    surface = Color(0xFF26221E), onSurface = Color(0xFFEFE8DC), onSurfaceVariant = Color(0xFFBDB3A4),
    surfaceContainerLowest = Color(0xFF1C1916), surfaceContainerLow = Color(0xFF2E2A25), surfaceContainer = Color(0xFF36312B),
    surfaceContainerHigh = Color(0xFF3E3832), surfaceContainerHighest = Color(0xFF48423A),
    surfaceBright = Color(0xFF48423A), surfaceDim = Color(0xFF1C1916),
    secondaryContainer = Color(0xFF4A433A), onSecondaryContainer = Color(0xFFEFE8DC),
    outline = Color(0xFF85796A), outlineVariant = Color(0xFF4D463D),
) else base.copy(
    background = Color(0xFFE4DED2), onBackground = Color(0xFF2E2820),
    surface = Color(0xFFE4DED2), onSurface = Color(0xFF2E2820), onSurfaceVariant = Color(0xFF625A4E),
    surfaceContainerLowest = Color(0xFFFFFEFA), surfaceContainerLow = Color(0xFFF3EFE6), surfaceContainer = Color(0xFFF7F4EC),
    surfaceContainerHigh = Color(0xFFEEE9DE), surfaceContainerHighest = Color(0xFFE3DDD0),
    surfaceBright = Color(0xFFFFFEFA), surfaceDim = Color(0xFFD9D2C4),
    secondaryContainer = Color(0xFFE6E0D3), onSecondaryContainer = Color(0xFF3A3328),
    outline = Color(0xFF988F81), outlineVariant = Color(0xFFCBC3B5),
)

/** Tighter corners, as on the keys and panels of the era. */
internal val SkeuoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

// ——— The textures, worked out once: linen for the light theme, leather for the dark one ———

private const val TEXTURE = 256

private fun hash(x: Int, y: Int, seed: Int): Float {
    var h = x * 374761393 + y * 668265263 + seed * 144269504
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
}

/** Smooth noise that wraps around the tile, so the texture repeats without seams. */
private fun valueNoise(x: Float, y: Float, cells: Int, seed: Int): Float {
    val fx = x * cells / TEXTURE
    val fy = y * cells / TEXTURE
    val x0 = floor(fx).toInt()
    val y0 = floor(fy).toInt()
    val tx = (fx - x0).let { it * it * (3 - 2 * it) }
    val ty = (fy - y0).let { it * it * (3 - 2 * it) }
    fun at(cx: Int, cy: Int) = hash(Math.floorMod(cx, cells), Math.floorMod(cy, cells), seed)
    val top = at(x0, y0) + (at(x0 + 1, y0) - at(x0, y0)) * tx
    val bottom = at(x0, y0 + 1) + (at(x0 + 1, y0 + 1) - at(x0, y0 + 1)) * tx
    return top + (bottom - top) * ty
}

private fun texture(dark: Boolean): Bitmap {
    val pixels = IntArray(TEXTURE * TEXTURE)
    val base = if (dark) intArrayOf(0x26, 0x22, 0x1E) else intArrayOf(0xE4, 0xDE, 0xD2)
    for (y in 0 until TEXTURE) for (x in 0 until TEXTURE) {
        val shade = if (dark) {
            // Leather: soft blotches, creases where the noise turns, and a fine grain.
            val blotch = valueNoise(x.toFloat(), y.toFloat(), 8, 1) * 0.6f + valueNoise(x.toFloat(), y.toFloat(), 32, 2) * 0.4f
            val crease = 1f - kotlin.math.abs(sin(blotch * 18f))
            (blotch - 0.5f) * 14f - crease * crease * 9f + (hash(x, y, 3) - 0.5f) * 5f
        } else {
            // Linen: threads along each row and each column, each a little darker or lighter.
            val row = hash(0, y, 4) - 0.5f
            val column = hash(x, 0, 5) - 0.5f
            val slub = valueNoise(x.toFloat(), y.toFloat(), 64, 6) - 0.5f
            row * 9f + column * 7f + slub * 5f + (hash(x, y, 7) - 0.5f) * 4f
        }
        val r = (base[0] + shade).toInt().coerceIn(0, 255)
        val g = (base[1] + shade).toInt().coerceIn(0, 255)
        val b = (base[2] + shade * 0.9f).toInt().coerceIn(0, 255)
        pixels[y * TEXTURE + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, TEXTURE, TEXTURE, Bitmap.Config.ARGB_8888)
}

private val linen by lazy { texture(dark = false).asImageBitmap() }
private val leather by lazy { texture(dark = true).asImageBitmap() }

/** The table the screens lie on. */
internal fun textureBrush(dark: Boolean): Brush = ShaderBrush(ImageShader(if (dark) leather else linen, TileMode.Repeated, TileMode.Repeated))

/** Behind a whole screen: the color of the theme, or the texture in the skeuomorphic look. */
@Composable
internal fun ScreenSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val skeuo = LocalSkeuoMaterials.current
    if (skeuo == null) {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    } else {
        Surface(modifier.fillMaxSize().background(textureBrush(skeuo.dark)), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, content = content)
    }
}

/** The background a Scaffold paints: none in the skeuomorphic look, where [screenTexture] lies under it. */
@Composable
internal fun screenColor(): Color = if (LocalSkeuo.current) Color.Transparent else MaterialTheme.colorScheme.background

@Composable
internal fun Modifier.screenTexture(): Modifier = LocalSkeuoMaterials.current?.let { background(textureBrush(it.dark)) } ?: this

// ——— Raised pieces ———

/** A key standing out of the surface: ivory, or lit glass when [lit]. */
@Composable
internal fun Modifier.skeuoRaised(shape: Shape, lit: Boolean = false, elevation: androidx.compose.ui.unit.Dp = 3.dp): Modifier {
    val m = LocalSkeuoMaterials.current ?: return this
    val colors = if (lit) listOf(m.gelTop, m.gelMid, m.tint, m.gelLow) else m.raise
    return this
        .shadow(elevation, shape, clip = false, ambientColor = if (lit) m.tint else Color.Black, spotColor = if (lit) m.tint else Color.Black)
        .clip(shape)
        .background(Brush.verticalGradient(0f to colors[0], 0.5f to colors[1], 0.51f to colors[2], 1f to colors[3]))
        .border(1.dp, if (lit) m.gelEdge else m.edge, shape)
        .drawWithContent {
            drawContent()
            // The light catching the upper edge.
            val inset = 1.5.dp.toPx()
            drawLine(if (lit) Color(0x8CFFFFFF) else m.highlight, Offset(size.height / 6, inset), Offset(size.width - size.height / 6, inset), 1.dp.toPx())
        }
}

/** A flat panel of the era: a grouped table, a card. */
@Composable
internal fun Modifier.skeuoPanel(shape: Shape): Modifier {
    val m = LocalSkeuoMaterials.current ?: return this
    return this
        .shadow(2.dp, shape, clip = false)
        .clip(shape)
        .background(Brush.verticalGradient(m.panel))
        .border(1.dp, m.edge, shape)
}

/** A field cut into the surface: its upper edge in shadow. */
@Composable
internal fun Modifier.skeuoInset(shape: Shape): Modifier {
    val m = LocalSkeuoMaterials.current ?: return this
    return this
        .clip(shape)
        .border(1.dp, m.edge, shape)
        .drawWithContent {
            drawContent()
            drawLine(Color(0x29000000), Offset(size.height / 2, 1.5.dp.toPx()), Offset(size.width - size.height / 2, 1.5.dp.toPx()), 2.5.dp.toPx())
        }
}

/** The blue rule under a line of a legal pad and the red margin on its left. */
@Composable
internal fun Modifier.legalPadLine(): Modifier {
    val m = LocalSkeuoMaterials.current ?: return this
    return drawWithContent {
        drawContent()
        drawLine(m.rule, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
        val x = 54.dp.toPx()
        drawLine(m.margin, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    }
}

/** A small rounded square of glass with an icon, as in the settings of the phones of 2012. */
@Composable
internal fun Modifier.skeuoBadge(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    val m = LocalSkeuoMaterials.current ?: return this
    return this
        .shadow(2.dp, shape, clip = false)
        .clip(shape)
        .background(Brush.verticalGradient(0f to m.gelTop, 0.5f to m.gelMid, 0.51f to m.tint, 1f to m.gelLow))
        .border(1.dp, m.gelEdge, shape)
}

/**
 * The surface of a tile of the home. [clicks] is what a touch does, kept inside the shape so the
 * ripple follows its corners; [modifier] gives the size.
 */
@Composable
internal fun TileSurface(on: Boolean, modifier: Modifier, clicks: Modifier, content: @Composable () -> Unit) {
    if (LocalMetro.current) return MetroTile(on, modifier, clicks, content)
    val colors = MaterialTheme.colorScheme
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        Surface(
            color = if (on) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
            shape = RoundedCornerShape(20.dp),
            modifier = modifier.clip(RoundedCornerShape(20.dp)).then(clicks),
            content = content,
        )
        return
    }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.skeuoRaised(shape, lit = on, elevation = if (on) 6.dp else 3.dp).then(clicks)) {
        CompositionLocalProvider(
            LocalContentColor provides if (on) Color.White else colors.onSurface,
            LocalTileShadow provides if (on) m.onGlass else m.emboss,
            LocalTileIcon provides if (on) Color.White else m.engraved,
        ) { content() }
    }
}

/** The line under the words of a tile, and the color of its icon: none and the content color in Material. */
internal val LocalTileShadow = staticCompositionLocalOf<Shadow?> { null }
internal val LocalTileIcon = staticCompositionLocalOf { Color.Unspecified }

/** [style] with the line the skeuomorphic look draws under the words of a tile. */
@Composable
internal fun TextStyle.onTile(): TextStyle = LocalTileShadow.current?.let { copy(shadow = it) } ?: this

// ——— Components that change their look ———

/** The bar on top of a screen: brushed metal in the accent in the skeuomorphic look. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalMetro.current) return MetroTopBar(title, navigationIcon, actions)
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        TopAppBar(title = title, navigationIcon = navigationIcon, actions = actions)
        return
    }
    // On the metal what uses the accent, as the spinner of a refresh, turns white.
    val onMetal = MaterialTheme.colorScheme.copy(primary = Color.White)
    TopAppBar(
        title = { ProvideTextStyle(LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.Bold, shadow = m.onGlass)), title) },
        navigationIcon = navigationIcon,
        actions = { MaterialTheme(colorScheme = onMetal) { androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = actions) } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
        modifier = Modifier
            .shadow(4.dp)
            .background(Brush.verticalGradient(0f to m.metalHi, 0.55f to m.metal, 1f to m.metalLow))
            .drawWithContent {
                drawContent()
                drawLine(Color(0x73FFFFFF), Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
                drawLine(lerp(m.metal, Color.Black, 0.5f), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            },
    )
}

/** The on/off switch: a groove with a chrome knob that slides over the lit glass. */
@Composable
internal fun AppSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (LocalMetro.current) return MetroSwitch(checked, onCheckedChange, modifier, enabled)
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled)
        return
    }
    val knob by animateDpAsState(if (checked) 28.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = 700f), label = "knob")
    val groove = CircleShape
    Box(
        modifier
            .padding(vertical = 9.dp)
            .size(58.dp, 30.dp)
            .clip(groove)
            .background(
                if (checked) Brush.verticalGradient(listOf(m.gelLow, m.tint, m.gelMid))
                else Brush.verticalGradient(if (m.dark) listOf(Color(0xFF1A1714), Color(0xFF34302A)) else listOf(Color(0xFFD8D4CC), Color(0xFFF7F5F1))),
            )
            .border(1.dp, if (checked) m.gelEdge else m.edge, groove)
            .drawWithContent {
                // The groove is cut into the surface: its upper edge stays in shadow.
                drawContent()
                drawLine(Color(0x47000000), Offset(size.height / 2, 1.5.dp.toPx()), Offset(size.width - size.height / 2, 1.5.dp.toPx()), 2.dp.toPx())
            }
            .then(
                if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier,
            ),
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(30.dp)
                .shadow(2.dp, CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFD9D9D9))), CircleShape)
                .border(1.dp, Color(0x40000000), CircleShape),
        )
    }
}

/** A chip to choose among a few values: in the skeuomorphic look, a key that stays pressed and lit. */
@Composable
internal fun AppFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    if (LocalMetro.current) return MetroFilterChip(selected, onClick, label, modifier, leadingIcon)
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier, leadingIcon = leadingIcon)
        return
    }
    val shape = RoundedCornerShape(9.dp)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = leadingIcon,
        shape = shape,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White,
        ),
        modifier = modifier.then(
            if (selected) {
                Modifier
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(m.gelLow, m.tint)))
                    .border(1.dp, m.gelEdge, shape)
                    .drawWithContent {
                        drawContent()
                        drawLine(Color(0x59000000), Offset(4.dp.toPx(), 1.5.dp.toPx()), Offset(size.width - 4.dp.toPx(), 1.5.dp.toPx()), 2.dp.toPx())
                    }
            } else {
                Modifier.skeuoRaised(shape, elevation = 1.dp)
            },
        ),
    )
}

/** The button of a secondary action: an ivory key in the skeuomorphic look. */
@Composable
internal fun AppTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalMetro.current) return MetroTonalButton(onClick, modifier, enabled, contentPadding, content)
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)
        return
    }
    val shape = RoundedCornerShape(10.dp)
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
        ),
        modifier = modifier.skeuoRaised(shape, elevation = if (enabled) 2.dp else 0.dp),
        content = content,
    )
}

/** The button of the main action: a gel key in the accent in the skeuomorphic look. */
@Composable
internal fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalMetro.current) return MetroButton(onClick, modifier, enabled, contentPadding, content)
    val m = LocalSkeuoMaterials.current
    if (m == null) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding, content = content)
        return
    }
    val shape = RoundedCornerShape(10.dp)
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = contentPadding,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
        ),
        modifier = modifier.skeuoRaised(shape, lit = enabled, elevation = if (enabled) 3.dp else 0.dp),
        content = content,
    )
}

/** The black glossy bar at the bottom, drawn behind the tabs. */
@Composable
internal fun Modifier.skeuoTabBar(shape: Shape): Modifier = this
    .shadow(10.dp, shape, clip = false)
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF0C0C0C))))
    .drawWithContent {
        // The gloss over the upper half, and the light on its edge.
        drawRect(Brush.verticalGradient(0f to Color(0x33FFFFFF), 0.5f to Color(0x0FFFFFFF), 0.51f to Color.Transparent), size = size.copy(height = size.height / 2 + 1))
        drawContent()
        drawLine(Color(0x40FFFFFF), Offset(size.height / 2, 1.dp.toPx()), Offset(size.width - size.height / 2, 1.dp.toPx()), 1.dp.toPx())
    }
    .border(1.dp, Color.Black, shape)

/** The glass the chosen tab sits on in the black bar. */
internal val SkeuoTabGlass = Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x12FFFFFF)))

/** The icon of the chosen tab, lit in the accent. */
@Composable
internal fun skeuoLitIcon(): Color = LocalSkeuoMaterials.current?.let { lerp(it.tint, Color.White, 0.55f) } ?: Color.Unspecified
