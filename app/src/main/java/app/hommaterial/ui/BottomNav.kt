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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** The pages of the app the bottom bar moves between: the home, the lists of Alexa and the calendar ([TAB_CALENDAR]). */
const val TAB_HOME = "home"
const val TAB_LISTS = "lists"

/** The room the floating bar takes over the bottom of a page, for what scrolls under it to clear it. */
internal val BOTTOM_BAR_SPACE = 88.dp

private class Tab(val key: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab(TAB_HOME, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(TAB_LISTS, R.string.s_lists, Icons.Outlined.Checklist, Icons.Filled.Checklist),
    Tab(TAB_CALENDAR, R.string.s_calendar, Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
)

/** Where a tab sits in the bar, for the pages to slide in from its side. */
internal fun tabIndex(key: String) = TABS.indexOfFirst { it.key == key }

/**
 * The floating bar at the bottom of the app: a pill over the page, where the chosen tab grows to
 * show its name on a tinted background and the others keep only their icon.
 */
@Composable
internal fun FloatingBottomBar(current: String, visible: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it * 2 } + fadeOut(),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (tab in TABS) TabPill(tab, tab.key == current) { onSelect(tab.key) }
            }
        }
    }
}

@Composable
private fun TabPill(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.secondaryContainer.copy(alpha = 0f),
        label = "tab background",
    )
    val content by animateColorAsState(
        if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        label = "tab content",
    )
    // The icon of the tab just chosen gives a little bounce.
    val scale by animateFloatAsState(
        if (selected) 1f else 0.92f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tab icon",
    )
    Row(
        Modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) tab.selectedIcon else tab.icon,
            contentDescription = if (selected) null else str(tab.label),
            tint = content,
            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkHorizontally(spring(stiffness = Spring.StiffnessMedium)) + fadeOut(),
        ) {
            Text(
                str(tab.label),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
