package app.hommaterial.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.Dp
import app.hommaterial.music.MusicPlayer
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hommaterial.R
import app.hommaterial.str

/**
 * The pages of the app the bottom bar moves between: the home, the lists of Alexa, the calendar
 * ([TAB_CALENDAR]) and, once some are chosen, the notes of Notion ([TAB_NOTES]) and the music of the
 * phone ([TAB_MUSIC]).
 */
const val TAB_HOME = "home"
const val TAB_LISTS = "lists"

/** The page a widget asked to open, until the home takes it. */
internal val requestedTab = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

/** The room the floating bar takes over the bottom of a page. */
internal val BOTTOM_NAV_SPACE = 88.dp

/**
 * The room the floating bars take over the bottom of a page, for what scrolls under them to clear
 * it: the one of the pages and, while something is in its queue, the one of the music above it.
 */
internal val BOTTOM_BAR_SPACE: Dp
    @Composable get() = BOTTOM_NAV_SPACE + if (MusicPlayer.now.collectAsState().value.queued) MUSIC_BAR_SPACE else 0.dp

private class Tab(val key: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(TAB_HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(TAB_LISTS, R.string.s_lists, Icons.Outlined.Checklist, Icons.Filled.Checklist),
    Tab(TAB_CALENDAR, R.string.s_calendar, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab(TAB_NOTES, R.string.s_notes, Icons.Outlined.Description, Icons.Filled.Description),
    Tab(TAB_MUSIC, R.string.s_music, Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
)

/** Where a tab sits in the bar, for the pages to slide in from its side. */
internal fun tabIndex(key: String) = TABS.indexOfFirst { it.key == key }

/**
 * The floating bar at the bottom of the app: a pill over the page, where the chosen tab grows to
 * show its name on a tinted background and the others keep only their icon.
 */
@Composable
internal fun FloatingBottomBar(
    current: String,
    visible: Boolean,
    notes: Boolean,
    music: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The application bar of Windows Phone slides straight in and out, with no bounce, as on the web.
    val metroEase = androidx.compose.animation.core.CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
    val metroBar = LocalMetro.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (metroBar) slideInVertically(androidx.compose.animation.core.tween(260, easing = metroEase)) { it * 2 }
        else slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn(),
        exit = if (metroBar) slideOutVertically(androidx.compose.animation.core.tween(200, easing = metroEase)) { it * 2 }
        else slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it * 2 } + fadeOut(),
    ) {
        val tabs = @Composable {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val shown = TABS.filter { (notes || it.key != TAB_NOTES) && (music || it.key != TAB_MUSIC) }
                // Five tabs fit on a narrow phone only a little closer together.
                val padding = if (shown.size > 4) 12.dp else 18.dp
                for (tab in shown) TabPill(tab, tab.key == current, padding) { onSelect(tab.key) }
            }
        }
        if (LocalSkeuo.current) {
            Box(Modifier.skeuoTabBar(corners(22.dp))) { tabs() }
        } else if (LocalMetro.current) {
            // The application bar of Windows Phone: a flat strip across the bottom edge, its icons in rings.
            val inset = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Surface(
                shape = androidx.compose.ui.graphics.RectangleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().offset(y = 16.dp + inset),
            ) {
                Box(Modifier.padding(bottom = inset), contentAlignment = Alignment.Center) { tabs() }
            }
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) { tabs() }
        }
    }
}

@Composable
private fun TabPill(tab: Tab, selected: Boolean, padding: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val skeuo = LocalSkeuo.current
    val metro = LocalMetro.current
    // In the black bar of the skeuomorphic look the chosen tab sits on glass, its icon lit.
    val background by animateColorAsState(
        when {
            skeuo || metro -> Color.Transparent
            selected -> colors.secondaryContainer
            else -> colors.secondaryContainer.copy(alpha = 0f)
        },
        label = "tab background",
    )
    val content by animateColorAsState(
        when {
            skeuo -> if (selected) Color.White else Color(0xFF9C9C9C)
            metro -> if (selected) colors.onSurface else colors.onSurfaceVariant
            selected -> colors.onSecondaryContainer
            else -> colors.onSurfaceVariant
        },
        label = "tab content",
    )
    val shape = if (skeuo) corners(15.dp) else CircleShape
    // The icon of the tab just chosen gives a little bounce.
    val scale by animateFloatAsState(
        if (selected || metro) 1f else 0.92f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tab icon",
    )
    Row(
        Modifier
            // Modern UI: room for the ring and its word under it, no rounded ripple, the tilt of a tile instead.
            .height(if (metro) 58.dp else 48.dp)
            .then(if (metro) Modifier.metroTilt() else Modifier.clip(shape))
            .background(background)
            .then(if (skeuo && selected) Modifier.background(SkeuoTabGlass).border(1.dp, Color(0x99000000), shape) else Modifier)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = if (metro) null else androidx.compose.material3.ripple(),
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = @Composable {
            Icon(
                if (selected) tab.selectedIcon else tab.icon,
                contentDescription = if (selected) null else str(tab.label),
                tint = if (skeuo && selected) skeuoLitIcon() else if (metro && selected) Color.White else content,
                modifier = Modifier.size(if (metro) 20.dp else 24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
        if (metro) {
            // The ring of an icon of the application bar, filled with the accent when chosen, its word under it.
            val ring by animateColorAsState(if (selected) colors.primary else Color.Transparent, label = "tab ring")
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(ring).border(2.dp, if (selected) colors.primary else content, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { icon() }
                Text(str(tab.label).lowercase(), style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
            }
            return@Row
        }
        icon()
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(),
        ) {
            Text(
                if (metro) str(tab.label).lowercase() else str(tab.label),
                style = if (metro) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
