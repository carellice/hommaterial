package app.hommaterial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.ACTION_MACRO
import app.hommaterial.data.ACTION_POWER
import app.hommaterial.data.ACTION_REMOTE
import app.hommaterial.data.ACTION_WAIT
import app.hommaterial.data.Macro
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Remote
import app.hommaterial.data.RemoteKind
import app.hommaterial.plural
import app.hommaterial.quick.Quick
import app.hommaterial.str

private val WAIT_SECONDS = listOf(1, 2, 3, 5, 10, 30, 60)

/** The line under the name of a custom device: what it depends on, or how much it does. */
internal fun macroStatus(macro: Macro, state: UiState): String {
    val watched = state.devices.find { it.applianceId == macro.condition }
    return if (watched != null) {
        str(R.string.macro_depends, watched.name)
    } else {
        plural(R.plurals.macro_actions, macro.steps.size)
    }
}

/** A custom device as a tile: a tap runs its actions, holding it opens them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MacroTile(macro: Macro, state: UiState, vm: HomeViewModel) {
    var editing by remember { mutableStateOf(false) }
    if (editing) MacroEditor(macro, state, vm, onDismiss = { editing = false })
    val locked = LocalHomeLook.current.locked
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .clip(RoundedCornerShape(20.dp))
            // Behind the PIN of the monitor the panel only runs them.
            .combinedClickable(onClick = { vm.runMacro(macro) }, onLongClick = { if (!locked) editing = true }),
    ) {
        TileBody(Icons.Outlined.AutoAwesome, macro.id in state.busy, macro.name, macroStatus(macro, state))
    }
}

/** What an action does, as a sentence. */
internal fun describe(action: MacroAction, state: UiState): String = when (action.type) {
    ACTION_MACRO -> str(
        R.string.schedule_run,
        state.macros.find { it.id == action.target }?.name ?: str(R.string.macro_gone),
    )
    ACTION_WAIT -> plural(R.plurals.macro_wait, action.value.toIntOrNull() ?: 0)
    ACTION_POWER -> {
        val name = state.devices.find { it.applianceId == action.target }?.name ?: str(R.string.macro_gone)
        str(if (action.value == "on") R.string.voice_on else R.string.voice_off, name)
    }
    else -> {
        val remote = state.remotes.find { it.id == action.target }?.name ?: str(R.string.macro_gone)
        str(R.string.macro_press, if (action.custom) action.value else commandLabel(action.value), remote)
    }
}

/** The keys every remote of a kind has, besides those the user added. */
private fun standardCommands(remote: Remote): List<String> = when (remote.kind) {
    RemoteKind.CUSTOM -> emptyList()
    RemoteKind.TV -> listOf("turnOn", "turnOff", "volumeAdd", "volumeSub", "channelAdd", "channelSub")
    else -> listOf("turnOn", "turnOff")
}

private fun commandLabel(command: String): String = when (command) {
    "turnOn" -> str(R.string.turn_on)
    "turnOff" -> str(R.string.turn_off)
    "volumeAdd" -> str(R.string.remote_up, str(R.string.remote_volume))
    "volumeSub" -> str(R.string.remote_down, str(R.string.remote_volume))
    "channelAdd" -> str(R.string.remote_up, str(R.string.remote_channel))
    "channelSub" -> str(R.string.remote_down, str(R.string.remote_channel))
    else -> command
}

/** Creates a custom device, when [macro] is null, or edits one: its name, what it depends on, its actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MacroEditor(macro: Macro?, state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(macro?.name.orEmpty()) }
    var condition by remember { mutableStateOf(macro?.condition) }
    var steps by remember { mutableStateOf(macro?.steps.orEmpty()) }
    var otherwise by remember { mutableStateOf(macro?.otherwise.orEmpty()) }
    var choosingCondition by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val switchable = state.placed.filter { it.hasPower }
    val watched = switchable.find { it.applianceId == condition }

    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && macro != null) {
        ConfirmDialog(
            title = str(R.string.macro_delete_title, macro.name),
            text = str(R.string.macro_delete_text),
            confirm = str(R.string.delete),
            onConfirm = { vm.deleteMacro(macro.id); onDismiss() },
            onDismiss = { confirmDelete = false },
        )
    }
    if (choosingCondition) {
        ListDialog(
            title = str(R.string.macro_condition),
            items = listOf(str(R.string.macro_condition_none) to null) +
                switchable.map { it.name to (it.room ?: str(R.string.room_none)) },
            onPick = { index ->
                condition = if (index == 0) null else switchable[index - 1].applianceId
                choosingCondition = false
            },
            onDismiss = { choosingCondition = false },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(str(if (macro == null) R.string.macro_new else R.string.macro_edit)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = str(R.string.cancel))
                        }
                    },
                    actions = {
                        if (macro != null) {
                            TextButton(onClick = { confirmDelete = true }) { Text(str(R.string.delete)) }
                        }
                        TextButton(
                            onClick = {
                                // Without the device it depended on, what was done when it was off is dropped.
                                val saved = Macro(macro?.id.orEmpty(), name.trim(), watched?.applianceId, steps)
                                vm.saveMacro(if (watched == null) saved else saved.copy(otherwise = otherwise))
                                onDismiss()
                            },
                            enabled = name.isNotBlank() && (steps.isNotEmpty() || otherwise.isNotEmpty()),
                        ) {
                            Text(str(R.string.save))
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .wrapContentWidth()
                        .widthIn(max = 640.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(str(R.string.room_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SectionCard {
                        ListItem(
                            headlineContent = { Text(str(R.string.macro_condition)) },
                            supportingContent = { Text(watched?.name ?: str(R.string.macro_condition_none)) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { choosingCondition = true },
                        )
                    }
                    // Only once it exists: a tile runs what is saved.
                    if (macro != null) {
                        val key = Quick.macroKey(macro.id)
                        if (moving) {
                            RoomPicker(
                                name = macro.name,
                                follow = null,
                                current = state.placeOf(key).orEmpty(),
                                rooms = state.allRooms,
                                onPick = { vm.setPlace(key, it); moving = false },
                                onDismiss = { moving = false },
                            )
                        }
                        SectionCard {
                            ListItem(
                                headlineContent = { Text(str(R.string.room_label)) },
                                supportingContent = { Text(state.placeOf(key) ?: str(R.string.room_none)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable { moving = true },
                            )
                        }
                        val tile = state.tileDevices.indexOf(Quick.macroKey(macro.id)).takeIf { it >= 0 }
                        val full = null !in state.tileDevices
                        SectionCard {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        when {
                                            tile != null -> str(R.string.tile_remove, tile + 1)
                                            full -> str(R.string.tiles_full)
                                            else -> str(R.string.tile_add)
                                        },
                                    )
                                },
                                supportingContent = { Text(str(R.string.macro_surfaces)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable(enabled = tile != null || !full) {
                                    vm.setOnTile(macro, tile == null)
                                },
                            )
                        }
                    }
                    if (watched == null) {
                        ActionList(str(R.string.macro_steps), steps, state) { steps = it }
                    } else {
                        ActionList(str(R.string.macro_when_on, watched.name), steps, state) { steps = it }
                        ActionList(str(R.string.macro_when_off, watched.name), otherwise, state) { otherwise = it }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionCard(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), content = content)
}

/** The actions of one branch, in the order they run, with what moves one up, removes it or adds one. */
@Composable
private fun ActionList(
    title: String,
    actions: List<MacroAction>,
    state: UiState,
    onChange: (List<MacroAction>) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (adding) ActionPicker(state, onPick = { onChange(actions + it); adding = false }, onDismiss = { adding = false })
    SectionCard {
        Column(Modifier.padding(bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            actions.forEachIndexed { index, action ->
                ListItem(
                    leadingContent = { Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(describe(action, state)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = {
                                    val moved = actions.toMutableList()
                                    moved.add(index - 1, moved.removeAt(index))
                                    onChange(moved)
                                },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = str(R.string.move_up))
                            }
                            IconButton(onClick = { onChange(actions.filterIndexed { i, _ -> i != index }) }) {
                                Icon(Icons.Outlined.Close, contentDescription = str(R.string.macro_remove_action))
                            }
                        }
                    },
                )
            }
            ListItem(
                headlineContent = { Text(str(R.string.macro_add_action)) },
                leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { adding = true },
            )
        }
    }
}

/** Builds an action of one of the [kinds], one question at a time: what kind, on what, and what exactly. */
@Composable
internal fun ActionPicker(
    state: UiState,
    kinds: List<String> = listOf(ACTION_POWER, ACTION_REMOTE, ACTION_WAIT),
    onPick: (MacroAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    val switchable = state.placed.filter { it.hasPower }
    val remote = state.remotes.find { it.id == target }
    when {
        kind == null -> {
            // Remotes and custom devices are offered only to who has some.
            val offered = listOfNotNull(
                ACTION_POWER to R.string.macro_kind_power,
                (ACTION_MACRO to R.string.schedule_kind_macro).takeIf { state.macros.isNotEmpty() },
                (ACTION_REMOTE to R.string.macro_kind_remote).takeIf { state.remotes.isNotEmpty() },
                ACTION_WAIT to R.string.macro_kind_wait,
            ).filter { it.first in kinds }
            ListDialog(str(R.string.macro_add_action), offered.map { str(it.second) to null }, onDismiss) {
                kind = offered[it].first
            }
        }
        kind == ACTION_MACRO -> ListDialog(
            title = str(R.string.schedule_pick_macro),
            items = state.macros.map { it.name to macroStatus(it, state) },
            onDismiss = onDismiss,
        ) { onPick(MacroAction(ACTION_MACRO, state.macros[it].id)) }
        kind == ACTION_WAIT -> ListDialog(
            title = str(R.string.macro_kind_wait),
            items = WAIT_SECONDS.map { plural(R.plurals.macro_wait, it) to null },
            onDismiss = onDismiss,
        ) { onPick(MacroAction(ACTION_WAIT, value = WAIT_SECONDS[it].toString())) }
        kind == ACTION_POWER && target == null -> ListDialog(
            title = str(R.string.macro_pick_device),
            items = switchable.map { it.name to (it.room ?: str(R.string.room_none)) },
            onDismiss = onDismiss,
        ) { target = switchable[it].applianceId }
        kind == ACTION_POWER -> ListDialog(
            title = switchable.find { it.applianceId == target }?.name.orEmpty(),
            items = listOf(str(R.string.turn_on) to null, str(R.string.turn_off) to null),
            onDismiss = onDismiss,
        ) { onPick(MacroAction(ACTION_POWER, target.orEmpty(), if (it == 0) "on" else "off")) }
        remote == null -> ListDialog(
            title = str(R.string.macro_pick_remote),
            items = state.remotes.map { it.name to it.type },
            onDismiss = onDismiss,
        ) { target = state.remotes[it].id }
        else -> {
            val standard = standardCommands(remote)
            val keys = state.remoteButtons[remote.id].orEmpty()
            ListDialog(
                title = remote.name,
                items = standard.map { commandLabel(it) to null } + keys.map { it to null },
                empty = str(R.string.macro_no_keys),
                onDismiss = onDismiss,
            ) { index ->
                val custom = index >= standard.size
                val command = if (custom) keys[index - standard.size] else standard[index]
                onPick(MacroAction(ACTION_REMOTE, remote.id, command, custom))
            }
        }
    }
}

/** A list to pick one entry from; each has a label and may have a line under it. */
@Composable
private fun ListDialog(
    title: String,
    items: List<Pair<String, String?>>,
    onDismiss: () -> Unit,
    empty: String? = null,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty() && empty != null) Text(empty)
                items.forEachIndexed { index, (label, hint) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = hint?.let { { Text(it) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(index) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
