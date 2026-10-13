package app.hommaterial.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.AcSetting
import app.hommaterial.data.Remote
import app.hommaterial.data.RemoteKind
import app.hommaterial.str

private val POWER_BUTTONS = listOf(R.string.turn_on to "turnOn", R.string.turn_off to "turnOff")
private val AC_TEMPERATURES = 16..30
// The numbers SwitchBot gives the modes and the fan speeds of an air conditioner.
private val AC_MODES = listOf(
    1 to R.string.ac_auto,
    2 to R.string.ac_cool,
    5 to R.string.ac_heat,
    3 to R.string.ac_dry,
    4 to R.string.ac_fan,
)
private val AC_FANS = listOf(
    1 to R.string.ac_auto,
    2 to R.string.ac_fan_low,
    3 to R.string.ac_fan_medium,
    4 to R.string.ac_fan_high,
)

private fun iconFor(remote: Remote): ImageVector = when (remote.kind) {
    RemoteKind.TV -> Icons.Outlined.Tv
    RemoteKind.AIR_CONDITIONER -> Icons.Outlined.AcUnit
    else -> Icons.Outlined.SettingsRemote
}

private fun kindLabel(remote: Remote): String = when (remote.kind) {
    RemoteKind.TV -> str(R.string.remote_tv)
    RemoteKind.AIR_CONDITIONER -> str(R.string.remote_ac)
    else -> str(R.string.remote_generic)
}

/** A remote as a tile. It has no state to show or to switch: a tap opens its buttons. */
@Composable
internal fun RemoteTile(remote: Remote, state: UiState, vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    if (open) RemoteSheet(remote, state, vm, onDismiss = { open = false })
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .clip(RoundedCornerShape(20.dp))
            .clickable { open = true },
    ) {
        TileBody(iconFor(remote), busy = remote.id in state.busy, name = remote.name, status = kindLabel(remote))
    }
}

/** The buttons of a remote: those of its kind, then the ones the user added by name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteSheet(remote: Remote, state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val buttons = state.remoteButtons[remote.id].orEmpty()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (adding) {
        ButtonDialog(
            onAdd = { vm.setRemoteButtons(remote, (buttons + it).distinct()); adding = false },
            onDismiss = { adding = false },
        )
    }
    DetailsContainer(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(remote), contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(remote.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        kindLabel(remote),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (remote.id in state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            when (remote.kind) {
                RemoteKind.AIR_CONDITIONER -> AcControls(remote, state.acSettings[remote.id] ?: AcSetting(), vm)
                // A remote learnt button by button has no power button the app could know of.
                RemoteKind.CUSTOM -> Unit
                else -> {
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((label, command) in POWER_BUTTONS) {
                            FilledTonalButton({ vm.sendRemote(remote, command) }, Modifier.weight(1f)) {
                                Text(str(label))
                            }
                        }
                    }
                    if (remote.kind == RemoteKind.TV) TvControls(remote, vm)
                }
            }

            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    str(R.string.remote_buttons),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (buttons.isNotEmpty()) {
                    TextButton(onClick = { editing = !editing }) {
                        Text(str(if (editing) R.string.remote_buttons_done else R.string.remote_buttons_edit))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in buttons) {
                    if (editing) {
                        InputChip(
                            selected = false,
                            onClick = { vm.setRemoteButtons(remote, buttons - name) },
                            label = { Text(name) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = str(R.string.remote_button_remove),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    } else {
                        AssistChip(
                            onClick = { vm.sendRemote(remote, name, custom = true) },
                            label = { Text(name) },
                        )
                    }
                }
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(str(R.string.remote_button_add)) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
            Text(
                str(R.string.remote_buttons_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TvControls(remote: Remote, vm: HomeViewModel) {
    var channel by remember { mutableStateOf("") }
    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Rocker(R.string.remote_volume, Modifier.weight(1f)) { up ->
            vm.sendRemote(remote, if (up) "volumeAdd" else "volumeSub")
        }
        Rocker(R.string.remote_channel, Modifier.weight(1f)) { up ->
            vm.sendRemote(remote, if (up) "channelAdd" else "channelSub")
        }
    }
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = channel,
            onValueChange = { text -> channel = text.filter { it.isDigit() }.take(4) },
            label = { Text(str(R.string.remote_channel_number)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(onClick = { vm.sendRemote(remote, "SetChannel", channel) }, enabled = channel.isNotEmpty()) {
            Text(str(R.string.remote_channel_go))
        }
    }
}

/** A pair of buttons for something that goes down and up, such as the volume. */
@Composable
private fun Rocker(@StringRes label: Int, modifier: Modifier = Modifier, onPress: (up: Boolean) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(str(label), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalIconButton(onClick = { onPress(false) }) {
                Icon(Icons.Outlined.Remove, contentDescription = str(R.string.remote_down, str(label)))
            }
            FilledTonalIconButton(onClick = { onPress(true) }) {
                Icon(Icons.Outlined.Add, contentDescription = str(R.string.remote_up, str(label)))
            }
        }
    }
}

/**
 * Temperature, mode and fan are chosen first and sent together by the power buttons: an air
 * conditioner takes everything in one signal, and beeps at each one.
 */
@Composable
private fun AcControls(remote: Remote, setting: AcSetting, vm: HomeViewModel) {
    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(str(R.string.temperature), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { vm.setAc(remote, setting.copy(temperature = setting.temperature - 1)) },
            enabled = setting.temperature > AC_TEMPERATURES.first,
        ) {
            Icon(Icons.Outlined.Remove, contentDescription = str(R.string.alert_lower))
        }
        Text(
            "${setting.temperature}°",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FilledTonalIconButton(
            onClick = { vm.setAc(remote, setting.copy(temperature = setting.temperature + 1)) },
            enabled = setting.temperature < AC_TEMPERATURES.last,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = str(R.string.alert_raise))
        }
    }
    Choices(R.string.ac_mode, AC_MODES, setting.mode.coerceAtLeast(1)) { vm.setAc(remote, setting.copy(mode = it)) }
    Choices(R.string.ac_fan_speed, AC_FANS, setting.fan) { vm.setAc(remote, setting.copy(fan = it)) }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { vm.sendAc(remote, setting, on = true) }, modifier = Modifier.weight(1f)) {
            Text(str(R.string.ac_send))
        }
        FilledTonalButton(onClick = { vm.sendAc(remote, setting, on = false) }, modifier = Modifier.weight(1f)) {
            Text(str(R.string.turn_off))
        }
    }
}

@Composable
private fun Choices(@StringRes title: Int, choices: List<Pair<Int, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Text(str(title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in choices) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(str(label)) })
        }
    }
}

@Composable
private fun ButtonDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.remote_button_add)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.remote_button_name)) },
                supportingText = { Text(str(R.string.remote_buttons_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(typed.trim()) }, enabled = typed.isNotBlank()) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** Asks for the two keys of the SwitchBot account, saying where the SwitchBot app shows them. */
@Composable
internal fun SwitchBotDialog(onConnect: (token: String, secret: String) -> Unit, onDismiss: () -> Unit) {
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.switchbot_connect)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(str(R.string.switchbot_how), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(str(R.string.switchbot_token)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(str(R.string.switchbot_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(token, secret) }, enabled = token.isNotBlank() && secret.isNotBlank()) {
                Text(str(R.string.switchbot_link))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
