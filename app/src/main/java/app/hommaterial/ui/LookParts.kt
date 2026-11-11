package app.hommaterial.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * More pieces that change with the look: the slider, the tabs over a page, the entries of the side
 * bar and the glass over the cards of the home. In Material each is the component it stands for.
 */

/** A slider: a groove with lit glass and a chrome knob in the skeuomorphic look, a flat bar and a square thumb in Modern UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val m = LocalSkeuoMaterials.current
    val metro = LocalMetro.current
    if (m == null && !metro) {
        Slider(value, onValueChange, modifier, enabled, valueRange, steps, onValueChangeFinished)
        return
    }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    val colors = MaterialTheme.colorScheme
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            if (m != null) {
                Box(
                    Modifier
                        .size(28.dp)
                        .shadow(3.dp, CircleShape)
                        .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFD5D5D5))), CircleShape)
                        .border(1.dp, Color(0x59000000), CircleShape)
                        .drawWithContent {
                            drawContent()
                            drawCircle(Color(0x14000000), radius = size.minDimension / 6)
                        },
                )
            } else {
                Box(Modifier.size(12.dp, 28.dp).background(if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)))
            }
        },
        track = {
            if (m != null) {
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val r = CornerRadius(size.height / 2)
                    val groove = if (m.dark) listOf(Color(0xFF1A1714), Color(0xFF34302A)) else listOf(Color(0xFFD0CCC4), Color(0xFFF7F5F1))
                    drawRoundRect(Brush.verticalGradient(groove), cornerRadius = r)
                    if (fraction > 0f) {
                        drawRoundRect(
                            Brush.verticalGradient(listOf(m.gelLow, m.tint, m.gelMid)),
                            size = Size((size.width * fraction).coerceAtLeast(size.height), size.height),
                            cornerRadius = r,
                        )
                    }
                    drawRoundRect(m.edge, cornerRadius = r, style = Stroke(1.dp.toPx()))
                    // Cut into the surface: the upper edge of the groove stays in shadow.
                    drawLine(Color(0x40000000), Offset(r.x, 1.5.dp.toPx()), Offset(size.width - r.x, 1.5.dp.toPx()), 1.5.dp.toPx())
                }
            } else {
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    drawRect(colors.surfaceContainerHighest)
                    drawRect(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f), size = Size(size.width * fraction, size.height))
                }
            }
        },
    )
}

/**
 * The tabs over a page: a row of tabs in Material, a segmented control of keys in the
 * skeuomorphic look, the pivot of Windows Phone (big lowercase words, the others dimmed) in Modern UI.
 */
@Composable
internal fun AppTabRow(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val m = LocalSkeuoMaterials.current
    val colors = MaterialTheme.colorScheme
    when {
        LocalMetro.current -> Row(
            modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            titles.forEachIndexed { i, title ->
                Text(
                    title.lowercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (i == selected) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier
                        .semantics { role = Role.Tab; this.selected = i == selected }
                        .clickable { onSelect(i) }
                        .metroTilt(),
                )
            }
        }
        m != null -> {
            val shape = RoundedCornerShape(9.dp)
            Row(
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(36.dp)
                    .skeuoRaised(shape, elevation = 2.dp),
            ) {
                titles.forEachIndexed { i, title ->
                    val on = i == selected
                    if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(m.edge))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .then(
                                if (on) {
                                    Modifier
                                        .background(Brush.verticalGradient(listOf(m.gelLow, m.tint)))
                                        .drawWithContent {
                                            drawContent()
                                            // Pressed in: the shadow of the edge falls inside.
                                            drawLine(Color(0x59000000), Offset(0f, 1.5.dp.toPx()), Offset(size.width, 1.5.dp.toPx()), 2.dp.toPx())
                                        }
                                } else {
                                    Modifier
                                },
                            )
                            .semantics { role = Role.Tab; this.selected = on }
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge.merge(
                                TextStyle(fontWeight = FontWeight.Bold, shadow = if (on) m.onGlass else m.emboss),
                            ),
                            color = if (on) Color.White else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        else -> PrimaryTabRow(selectedTabIndex = selected, modifier = modifier) {
            titles.forEachIndexed { i, title ->
                Tab(selected = i == selected, onClick = { onSelect(i) }, text = { Text(title, maxLines = 1) })
            }
        }
    }
}

/** An entry of the wide side bar: lit glass when chosen in the skeuomorphic look, an accent strip in Modern UI. */
@Composable
internal fun AppNavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val m = LocalSkeuoMaterials.current
    val colors = MaterialTheme.colorScheme
    if (m == null && !LocalMetro.current) {
        NavigationDrawerItem(
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            icon = { Icon(icon, contentDescription = null) },
            selected = selected,
            onClick = onClick,
        )
        return
    }
    val metro = m == null
    val shape = if (metro) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(10.dp)
    val content = when {
        selected -> Color.White
        metro -> colors.onSurface
        else -> colors.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (metro) 52.dp else 48.dp)
            .then(
                when {
                    metro && selected -> Modifier.background(colors.primary)
                    metro -> Modifier
                    selected -> Modifier.skeuoRaised(shape, lit = true, elevation = 3.dp)
                    else -> Modifier.clip(shape)
                },
            )
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .then(if (metro) Modifier.metroTilt() else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (!metro && !selected) m!!.engraved else content, modifier = Modifier.size(22.dp))
        Text(
            if (metro) label.lowercase() else label,
            style = if (metro) {
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light)
            } else {
                MaterialTheme.typography.labelLarge.merge(TextStyle(fontWeight = FontWeight.Bold, shadow = if (selected) m!!.onGlass else m!!.emboss))
            },
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/** An entry of the narrow side bar: a key with its icon in the skeuomorphic look, an icon in a ring in Modern UI. */
@Composable
internal fun AppRailItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val m = LocalSkeuoMaterials.current
    val colors = MaterialTheme.colorScheme
    if (m == null && !LocalMetro.current) {
        NavigationRailItem(
            selected = selected,
            onClick = onClick,
            icon = { Icon(icon, contentDescription = null) },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
        return
    }
    Column(
        Modifier
            .width(80.dp)
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (m != null) {
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.size(44.dp).then(if (selected) Modifier.skeuoBadge(shape) else Modifier.skeuoRaised(shape, elevation = 2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) Color.White else m.engraved, modifier = Modifier.size(24.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontWeight = FontWeight.Bold, shadow = m.emboss)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Box(
                Modifier
                    .metroTilt()
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else Color.Transparent)
                    .border(2.dp, if (selected) colors.primary else colors.onSurface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) Color.White else colors.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(label.lowercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The cards of the home that carry their own colors (the sky, the team): under glass in the
 * skeuomorphic look, with its gloss and edge; nothing more in the others.
 */
@Composable
internal fun Modifier.lookGlass(shape: Shape): Modifier {
    LocalSkeuoMaterials.current ?: return this
    return this
        .shadow(5.dp, shape, clip = false)
        .clip(shape)
        .border(1.dp, Color(0x66000000), shape)
        .drawWithContent {
            drawContent()
            // The gloss over the upper half and the light along the top edge.
            drawRect(
                Brush.verticalGradient(0f to Color(0x40FFFFFF), 1f to Color(0x0DFFFFFF)),
                size = size.copy(height = size.height * 0.45f),
            )
            drawLine(Color(0x80FFFFFF), Offset(12.dp.toPx(), 1.dp.toPx()), Offset(size.width - 12.dp.toPx(), 1.dp.toPx()), 1.dp.toPx())
        }
}
