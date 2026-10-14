package app.hommaterial.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.ACTION_REMOTE
import app.hommaterial.data.AcSetting
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Remote
import app.hommaterial.data.RemoteKind
import app.hommaterial.quick.Quick
import app.hommaterial.str

private val POWER_BUTTONS = listOf(R.string.turn_on to "turnOn", R.string.turn_off to "turnOff")
private const val KEYS_PER_ROW = 3
private val KEY_HEIGHT = 68.dp
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
    // The search opens it too, as a touch would.
    val opening = LocalOpening.current
    LaunchedEffect(opening.value) {
        if (opening.value == Quick.remoteKey(remote.id)) {
            opening.value = null
            open = true
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(LocalHomeLook.current.tileHeight)
            .alpha(if (remote.id in state.hiddenRemotes) 0.5f else 1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable { open = true },
    ) {
        TileBody(
            icon = iconFor(remote),
            busy = remote.id in state.busy,
            name = remote.name,
            status = kindLabel(remote),
            hints = listOf(TileHint.OPEN),
        )
    }
}

/** The buttons of a remote: those of its kind, then the ones the user added by name. */
@Composable
private fun RemoteSheet(remote: Remote, state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val buttons = state.remoteButtons[remote.id].orEmpty()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    // The minutes of a timer, while the key it presses is being chosen.
    var timing by remember { mutableStateOf<Int?>(null) }
    val askNotifications = rememberNotificationRequest()
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

            // The timer: switching off for the remotes that can, a key to choose for those learnt by hand.
            if (remote.kind != RemoteKind.CUSTOM || buttons.isNotEmpty()) {
                Column(Modifier.padding(top = 20.dp)) {
                    val title = if (remote.kind == RemoteKind.CUSTOM) R.string.timer_key_in else R.string.turn_off_in
                    TimerChips(str(title), remote.id, state, vm) { minutes ->
                        askNotifications()
                        if (remote.kind == RemoteKind.CUSTOM) {
                            timing = minutes
                        } else {
                            val action = MacroAction(ACTION_REMOTE, remote.id, "turnOff")
                            vm.addTimer(action, describe(action, state), minutes)
                        }
                    }
                }
            }
            timing?.let { minutes ->
                ListDialog(str(R.string.timer_key_in), buttons.map { it to null }, onDismiss = { timing = null }) {
                    val action = MacroAction(ACTION_REMOTE, remote.id, buttons[it], custom = true)
                    vm.addTimer(action, describe(action, state), minutes)
                    timing = null
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
            // Laid out as on a remote: rows of three keys, the one that adds a key closing the last row.
            val keys: List<String?> = buttons + null
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in keys.chunked(KEYS_PER_ROW)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (name in row) {
                            if (name == null) {
                                AddKey(Modifier.weight(1f)) { adding = true }
                            } else {
                                RemoteKey(name, editing, Modifier.weight(1f)) {
                                    if (editing) {
                                        vm.setRemoteButtons(remote, buttons - name)
                                    } else {
                                        vm.sendRemote(remote, name, custom = true)
                                    }
                                }
                            }
                        }
                        repeat(KEYS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Text(
                str(R.string.remote_buttons_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Behind the PIN of the monitor the panel only controls: what is listed stays put.
            if (!LocalHomeLook.current.locked) {
                val key = Quick.remoteKey(remote.id)
                val favorite = key in state.favorites
                TextButton(onClick = { vm.setFavorite(key, !favorite) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(str(if (favorite) R.string.favorite_remove else R.string.favorite_add))
                }
                TextButton(onClick = { moving = true }) {
                    Text(state.placeOf(key)?.let { str(R.string.room_in, it) } ?: str(R.string.room_move))
                }
                if (moving) {
                    RoomPicker(
                        name = remote.name,
                        follow = null,
                        current = state.placeOf(key).orEmpty(),
                        rooms = state.allRooms,
                        onPick = { vm.setPlace(key, it); moving = false },
                        onDismiss = { moving = false },
                    )
                }
                val hidden = remote.id in state.hiddenRemotes
                TextButton(onClick = { vm.setRemoteHidden(remote, !hidden); onDismiss() }) {
                    Text(str(if (hidden) R.string.unhide else R.string.hide))
                }
            }
        }
    }
}

/** A key of a remote: what it is for is drawn when its name tells, and the name is always written. */
@Composable
private fun RemoteKey(name: String, removing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val glyph = glyphFor(name)
    Surface(
        color = if (removing) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (removing) colors.onErrorContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.height(KEY_HEIGHT).clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                removing -> Icon(Icons.Outlined.Close, str(R.string.remote_button_remove), Modifier.size(22.dp))
                glyph is Glyph.Symbol -> Icon(glyph.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                glyph is Glyph.Tint -> Box(
                    Modifier.size(20.dp).border(1.dp, colors.outline, CircleShape).background(glyph.color, CircleShape),
                )
                else -> Unit
            }
            // Alone on the key, a short name such as a number is written large.
            val alone = glyph == null && !removing
            val type = MaterialTheme.typography
            Text(
                name,
                style = if (alone && name.length <= 3) type.titleLarge else type.labelMedium,
                maxLines = if (alone) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (alone) 0.dp else 4.dp),
            )
        }
    }
}

@Composable
private fun AddKey(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = str(R.string.remote_button_add), tint = colors.onSurfaceVariant)
    }
}

/** What stands for a key besides its name: a symbol, or the color it sets. */
private sealed interface Glyph {
    class Symbol(val icon: ImageVector) : Glyph

    class Tint(val color: Color) : Glyph
}

private val KEY_COLORS = mapOf(
    0xFFFF3B30 to setOf("rosso", "red"),
    0xFF34C759 to setOf("verde", "green"),
    0xFF2962FF to setOf("blu", "blue"),
    0xFFFFD60A to setOf("giallo", "yellow"),
    0xFFFF9500 to setOf("arancione", "arancio", "orange"),
    0xFFAF52DE to setOf("viola", "purple"),
    0xFFFF6FB5 to setOf("rosa", "pink"),
    0xFF32D2FF to setOf("azzurro", "celeste", "ciano", "cyan"),
    0xFFFFFFFF to setOf("bianco", "white"),
)

/** Reads the usual names of the keys, in Italian and in English; null when the name says nothing known. */
private fun glyphFor(name: String): Glyph? {
    val text = name.lowercase()
    val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
    fun has(vararg any: String) = any.any { it in words }
    val up = "+" in text || has("su", "up", "più", "piu")
    val down = "-" in text || has("giù", "giu", "down", "meno")
    KEY_COLORS.entries.firstOrNull { (_, names) -> names.any { it in words } }?.let { return Glyph.Tint(Color(it.key)) }
    val icon = when {
        has("mute", "muto", "muta", "silenzioso") -> Icons.AutoMirrored.Outlined.VolumeOff
        words.any { it.startsWith("vol") } && up -> Icons.AutoMirrored.Outlined.VolumeUp
        words.any { it.startsWith("vol") } && down -> Icons.AutoMirrored.Outlined.VolumeDown
        has("power", "on", "off", "accendi", "spegni", "acceso", "spento") -> Icons.Outlined.PowerSettingsNew
        has("play", "riproduci") -> Icons.Outlined.PlayArrow
        has("pause", "pausa") -> Icons.Outlined.Pause
        has("stop") -> Icons.Outlined.Stop
        has("ok", "enter", "invio", "conferma") -> Icons.Outlined.RadioButtonChecked
        has("home", "casa") -> Icons.Outlined.Home
        has("back", "indietro", "return", "esci", "exit") -> Icons.AutoMirrored.Outlined.ArrowBack
        has("menu", "menù") -> Icons.Outlined.Menu
        has("left", "sinistra") -> Icons.AutoMirrored.Outlined.KeyboardArrowLeft
        has("right", "destra") -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
        has("timer", "sleep") -> Icons.Outlined.Timer
        has("luce", "luci", "light", "lampada") -> Icons.Outlined.Lightbulb
        // Last, because plus and minus also come with the volume and the like.
        up -> Icons.Outlined.KeyboardArrowUp
        down -> Icons.Outlined.KeyboardArrowDown
        else -> return null
    }
    return Glyph.Symbol(icon)
}

@Composable
private fun TvControls(remote: Remote, vm: HomeViewModel) {
    var channel by remember { mutableStateOf("") }
    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** The two-way key of a remote, for what goes up and down such as the volume. */
@Composable
private fun Rocker(@StringRes label: Int, modifier: Modifier = Modifier, onPress: (up: Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { onPress(true) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = str(R.string.remote_up, str(label)))
            }
            Text(str(label), style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = { onPress(false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.Remove, contentDescription = str(R.string.remote_down, str(label)))
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
private fun ButtonDialog(onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.remote_button_add)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.remote_button_name)) },
                supportingText = { Text(str(R.string.remote_buttons_many)) },
                minLines = 2,
                maxLines = 6,
            )
        },
        confirmButton = {
            // Several names at once, one per line or with commas between them.
            val names = typed.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            TextButton(onClick = { onAdd(names) }, enabled = names.isNotEmpty()) { Text(str(R.string.save)) }
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
