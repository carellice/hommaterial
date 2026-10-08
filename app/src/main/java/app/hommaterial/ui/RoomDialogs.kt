package app.hommaterial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hommaterial.R
import app.hommaterial.data.Device
import app.hommaterial.str

/**
 * Lets the user move [device] to one of the [rooms], to a new one or to none. [onPick] receives
 * the room, an empty text for no room at all, or null to follow Alexa again.
 */
@Composable
fun RoomPicker(
    device: Device,
    current: String?,
    rooms: List<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var created by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.room_pick_title, device.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choice(
                    str(R.string.room_as_alexa, device.room ?: str(R.string.room_none)),
                    selected = current == null,
                ) { onPick(null) }
                for (room in rooms) Choice(room, selected = current == room) { onPick(room) }
                Choice(str(R.string.room_none), selected = current == "") { onPick("") }
                OutlinedTextField(
                    value = created,
                    onValueChange = { created = it },
                    label = { Text(str(R.string.room_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(created.trim()) }, enabled = created.isNotBlank()) {
                Text(str(R.string.room_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label)
    }
}

/** A device offered in a list of choices, with a line saying where it is now. */
class Pick(val key: String, val label: String, val hint: String? = null)

/**
 * Edits a room or a group: its name and which of the [picks] belong to it. [onDelete] is null
 * for what cannot be deleted, such as the rooms that come from Alexa.
 */
@Composable
fun MembersDialog(
    title: String,
    hint: String,
    name: String,
    picks: List<Pick>,
    members: Set<String>,
    onSave: (String, Set<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(name) }
    var chosen by remember { mutableStateOf(members) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(str(R.string.room_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    for (pick in picks) {
                        Check(pick, checked = pick.key in chosen) {
                            chosen = if (it) chosen + pick.key else chosen - pick.key
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(typed.trim(), chosen) }, enabled = typed.isNotBlank()) {
                Text(str(R.string.save))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(str(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
            }
        },
    )
}

/**
 * Chooses among the [picks], in the order they are ticked and no more than [limit] when there is
 * one. [onSave] receives null when the user takes [reset], which goes back to no choice at all.
 */
@Composable
fun PicksDialog(
    title: String,
    picks: List<Pick>,
    initial: List<String>,
    limit: Int?,
    reset: String = str(R.string.shortcuts_use_favorites),
    onSave: (List<String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(initial.filter { key -> picks.any { it.key == key } }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (limit != null) {
                    Text(
                        str(R.string.shortcuts_count, chosen.size, limit),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for (pick in picks) {
                        val checked = pick.key in chosen
                        Check(pick, checked, enabled = checked || limit == null || chosen.size < limit) {
                            chosen = if (it) chosen + pick.key else chosen - pick.key
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text(str(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text(reset) }
                TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun Check(pick: Pick, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column {
            Text(pick.label)
            if (pick.hint != null) {
                Text(
                    pick.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
