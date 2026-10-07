package app.hommaterial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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

@Composable
fun RoomRename(room: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(room) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.room_rename_title, room)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(str(R.string.room_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank() && name.trim() != room) {
                Text(str(R.string.room_rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
