package app.hommaterial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.TvSearch
import app.hommaterial.UiState
import app.hommaterial.data.Remote
import app.hommaterial.str

/**
 * Pairs an LG TV: the TVs that answered the search on the network, or one by its address, and
 * then the wait while the TV asks the user on its screen.
 */
@Composable
internal fun LgTvDialog(search: TvSearch, vm: HomeViewModel) {
    var address by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = vm::closeTvSearch,
        title = { Text(str(R.string.lgtv_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val pairing = search.pairing
                if (pairing != null) {
                    Waiting(if (search.asking) str(R.string.lgtv_asking) else pairing)
                } else {
                    Text(str(R.string.lgtv_how), style = MaterialTheme.typography.bodySmall)
                    when {
                        search.searching -> Waiting(str(R.string.lgtv_searching))
                        search.found.isEmpty() -> Text(
                            str(R.string.lgtv_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    for (tv in search.found) {
                        ListItem(
                            headlineContent = { Text(tv.name ?: tv.host) },
                            supportingContent = tv.name?.let { { Text(tv.host) } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.pairLgTv(tv.host) },
                        )
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it.trim() },
                        label = { Text(str(R.string.lgtv_address)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (search.pairing == null) {
                TextButton(onClick = { vm.pairLgTv(address) }, enabled = address.isNotBlank()) {
                    Text(str(R.string.lgtv_pair))
                }
            }
        },
        dismissButton = {
            Row {
                if (search.pairing == null && !search.searching) {
                    TextButton(onClick = vm::searchLgTvs) { Text(str(R.string.lgtv_search_again)) }
                }
                TextButton(onClick = vm::closeTvSearch) { Text(str(R.string.cancel)) }
            }
        },
    )
}

/** The keys of the LG remote that can be added as buttons, with their names; the numbers name themselves. */
private val LG_KEYS = listOf(
    "HOME" to R.string.lg_key_home,
    "BACK" to R.string.lg_key_back,
    "ENTER" to R.string.lg_key_ok,
    "EXIT" to R.string.lg_key_exit,
    "MENU" to R.string.lg_key_menu,
    "INFO" to R.string.lg_key_info,
    "GUIDE" to R.string.lg_key_guide,
    "MUTE" to R.string.lg_key_mute,
    "RED" to R.string.lg_key_red,
    "GREEN" to R.string.lg_key_green,
    "YELLOW" to R.string.lg_key_yellow,
    "BLUE" to R.string.lg_key_blue,
    "UP" to R.string.lg_key_up,
    "DOWN" to R.string.lg_key_down,
    "LEFT" to R.string.lg_key_left,
    "RIGHT" to R.string.lg_key_right,
) + (1..9).map { "$it" to null } + ("0" to null)

/** The commands of the player, which webOS takes as requests rather than as keys. */
internal val LG_MEDIA = listOf("play", "pause", "stop", "rewind", "fastForward")

/** What a command of an LG TV is called: an app by its name on the TV, a key by its own. */
internal fun lgLabel(command: String, apps: Map<String, String>): String = when {
    command.startsWith("app:") -> command.substring(4).let { apps[it] ?: it }
    command.startsWith("key:") -> command.substring(4).let { key ->
        LG_KEYS.find { it.first == key }?.second?.let(::str) ?: key
    }
    else -> commandLabel(command)
}

/**
 * The keys of the LG remote that matter most, on top of those every TV has: the arrows with OK,
 * then Back, Home and the sound, then the player.
 */
@Composable
internal fun LgControls(remote: Remote, vm: HomeViewModel) {
    fun press(key: String) = vm.sendRemote(remote, "key:$key")
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PadKey(str(R.string.lg_key_up), Icons.Outlined.KeyboardArrowUp) { press("UP") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PadKey(str(R.string.lg_key_left), Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { press("LEFT") }
            PadKey(str(R.string.lg_key_ok), null, primary = true) { press("ENTER") }
            PadKey(str(R.string.lg_key_right), Icons.AutoMirrored.Outlined.KeyboardArrowRight) { press("RIGHT") }
        }
        PadKey(str(R.string.lg_key_down), Icons.Outlined.KeyboardArrowDown) { press("DOWN") }
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val keys = listOf(
            Triple(R.string.lg_key_back, Icons.AutoMirrored.Outlined.ArrowBack, "BACK"),
            Triple(R.string.lg_key_home, Icons.Outlined.Home, "HOME"),
            Triple(R.string.lg_key_mute, Icons.AutoMirrored.Outlined.VolumeOff, "MUTE"),
        )
        for ((label, icon, key) in keys) {
            // Narrow margins, as three of them share the width with their names written out.
            FilledTonalButton(
                onClick = { press(key) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(str(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        val media = listOf(
            "rewind" to Icons.Outlined.FastRewind,
            "play" to Icons.Outlined.PlayArrow,
            "pause" to Icons.Outlined.Pause,
            "fastForward" to Icons.Outlined.FastForward,
        )
        for ((command, icon) in media) {
            FilledTonalIconButton(onClick = { vm.sendRemote(remote, command) }, modifier = Modifier.size(52.dp)) {
                Icon(icon, contentDescription = commandLabel(command))
            }
        }
    }
}

/** A key of the arrows: round, with OK in the middle set apart. */
@Composable
private fun PadKey(label: String, icon: ImageVector?, primary: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (primary) colors.primaryContainer else colors.secondaryContainer,
        contentColor = if (primary) colors.onPrimaryContainer else colors.onSecondaryContainer,
        shape = CircleShape,
        modifier = Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp))
            } else {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * Adds a button to an LG TV: one of its apps or inputs, asked to the TV as the dialog opens, or a
 * key of its remote.
 */
@Composable
internal fun LgButtonDialog(remote: Remote, state: UiState, vm: HomeViewModel, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val tv = state.lgTvs.find { it.id == remote.id } ?: return
    LaunchedEffect(remote.id) { vm.refreshLgApps(remote) }
    val asking = remote.id in state.busy
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.remote_button_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Heading(str(R.string.lgtv_apps))
                when {
                    tv.apps.isNotEmpty() -> for ((id, title) in tv.apps) Choice(title) { onAdd("app:$id") }
                    asking -> Waiting(str(R.string.lgtv_apps_loading))
                    else -> Text(str(R.string.lgtv_apps_none), style = MaterialTheme.typography.bodyMedium)
                }
                Heading(str(R.string.lgtv_keys))
                for (command in LG_MEDIA) Choice(commandLabel(command)) { onAdd(command) }
                for ((key, label) in LG_KEYS) Choice(label?.let(::str) ?: key) { onAdd("key:$key") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** Renames an LG TV in the app, which is the name the home, the custom devices and the voice use. */
@Composable
internal fun LgRenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.room_rename_title, name)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.room_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(typed) }, enabled = typed.isNotBlank()) { Text(str(R.string.room_rename)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Waiting(text: String) {
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
