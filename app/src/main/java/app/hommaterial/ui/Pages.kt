package app.hommaterial.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.str

/** The pages of the side bar: the whole home, the favorites, the groups, or a room by its name. */
const val PAGE_ALL = "all"
const val PAGE_FAVORITES = "favorites"
const val PAGE_GROUPS = "groups"
const val PAGE_ROOM = "room:"

/** Every page there is something to show in, in the order the side bar lists them by default. */
fun availablePages(state: UiState): List<String> {
    val visible = state.placed.filter { it.applianceId !in state.hidden }
    val rooms = visible.mapNotNull { it.room }.distinct().sortedBy { it.lowercase() }
    return buildList {
        add(PAGE_ALL)
        if (visible.any { it.applianceId in state.favorites }) add(PAGE_FAVORITES)
        if (state.groups.isNotEmpty()) add(PAGE_GROUPS)
        rooms.forEach { add(PAGE_ROOM + it) }
        // An empty name stands for the devices without a room.
        if (rooms.isNotEmpty() && visible.any { it.room == null }) add(PAGE_ROOM)
    }
}

/** The pages to move between: those chosen for the monitor, all of them on a wide screen, one on a phone. */
fun shownPages(state: UiState, wide: Boolean): List<String> {
    val available = availablePages(state)
    val monitor = state.monitor
    return when {
        monitor.enabled -> monitor.pages?.filter { it in available }?.ifEmpty { null } ?: available
        wide -> available
        else -> listOf(PAGE_ALL)
    }
}

fun pageLabel(page: String): String = when (page) {
    PAGE_ALL -> str(R.string.page_all)
    PAGE_FAVORITES -> str(R.string.favorites)
    PAGE_GROUPS -> str(R.string.s_groups)
    else -> page.removePrefix(PAGE_ROOM).ifEmpty { str(R.string.no_room) }
}

fun pageIcon(page: String): ImageVector = when (page) {
    PAGE_ALL -> Icons.Outlined.Home
    PAGE_FAVORITES -> Icons.Outlined.StarBorder
    PAGE_GROUPS -> Icons.Outlined.Layers
    else -> Icons.Outlined.MeetingRoom
}
