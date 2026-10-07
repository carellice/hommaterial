package app.hommaterial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.UiState
import app.hommaterial.data.Device
import app.hommaterial.data.DeviceState
import app.hommaterial.data.statusText
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val NO_ROOM = "Altro"
private const val FAVORITES = "Preferiti"
private val TILE_HEIGHT = 116.dp
private val TIMER_CHOICES = listOf("15 min" to 15, "30 min" to 30, "1 ora" to 60, "2 ore" to 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, vm: HomeViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Title(state.updatedAt) }, actions = { OverflowMenu(state, vm) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val visible = state.devices.filter { state.showHidden || it.applianceId !in state.hidden }
        // Named rooms first, alphabetically; devices without a room close the list.
        val rooms = visible.groupBy { it.room ?: NO_ROOM }
            .toSortedMap(compareBy<String> { it == NO_ROOM }.thenBy { it.lowercase() })

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (visible.isEmpty() && !state.refreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nessun dispositivo", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(156.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Favorites are repeated at the top and stay in their rooms too.
                val favorites = visible.filter { it.applianceId in state.favorites }.sortedBy { it.name.lowercase() }
                if (favorites.isNotEmpty()) {
                    item(key = "favorites", span = { GridItemSpan(maxLineSpan) }) { SectionHeader(FAVORITES) }
                    items(favorites, key = { "favorite:${it.applianceId}" }) { DeviceTile(it, state, vm) }
                }
                for ((room, devices) in rooms) {
                    item(key = "room:$room", span = { GridItemSpan(maxLineSpan) }) {
                        // "Altro" gathers unrelated devices: switching them together makes no sense.
                        // Hidden devices stay out of it even while they are being shown.
                        val switchable = if (room == NO_ROOM) emptyList() else {
                            devices.filter { it.hasPower && it.applianceId !in state.hidden }
                        }
                        if (switchable.size > 1) {
                            SectionHeader(room) { on -> vm.setRoomPower(switchable, on) }
                        } else {
                            SectionHeader(room)
                        }
                    }
                    items(devices, key = { it.applianceId }) { DeviceTile(it, state, vm) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(name: String, onPower: ((Boolean) -> Unit)? = null) {
    // The fixed height keeps headers aligned whether or not they carry the power button.
    Row(Modifier.padding(top = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onPower != null) {
            // A menu rather than a toggle: a whole room should not switch on a stray tap.
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = "Accendi o spegni $name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Accendi tutto") }, onClick = { open = false; onPower(true) })
                    DropdownMenuItem(text = { Text("Spegni tutto") }, onClick = { open = false; onPower(false) })
                }
            }
        }
    }
}

@Composable
private fun DeviceTile(device: Device, state: UiState, vm: HomeViewModel) {
    DeviceTile(
        device = device,
        state = state.states[device.applianceId],
        busy = device.applianceId in state.busy,
        hidden = device.applianceId in state.hidden,
        favorite = device.applianceId in state.favorites,
        tile = state.tileDevices.indexOf(device.applianceId).takeIf { it >= 0 },
        tilesFull = null !in state.tileDevices,
        timer = state.timers[device.applianceId]?.takeIf { it > System.currentTimeMillis() },
        vm = vm,
    )
}

@Composable
private fun Title(updatedAt: Long?) {
    Column {
        Text("Hommaterial")
        if (updatedAt != null) {
            // Recomputed every half minute so that the age keeps growing while the screen stays open.
            val now by produceState(System.currentTimeMillis(), updatedAt) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(30_000)
                }
            }
            Text(
                ageText(updatedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ageText(updatedAt: Long, now: Long): String {
    val minutes = (now - updatedAt) / 60_000
    return when {
        minutes < 1 -> "Aggiornato adesso"
        minutes == 1L -> "Aggiornato 1 minuto fa"
        minutes < 60 -> "Aggiornato $minutes minuti fa"
        minutes < 120 -> "Aggiornato 1 ora fa"
        minutes < 24 * 60 -> "Aggiornato ${minutes / 60} ore fa"
        else -> "Aggiornato più di un giorno fa"
    }
}

@Composable
private fun OverflowMenu(state: UiState, vm: HomeViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Aggiorna") }, onClick = { open = false; vm.refresh() })
        if (state.hidden.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text(if (state.showHidden) "Non mostrare i nascosti" else "Mostra i nascosti") },
                onClick = { open = false; vm.toggleShowHidden() },
            )
        }
        DropdownMenuItem(
            text = { Text("Controlla aggiornamenti") },
            onClick = { open = false; vm.checkForUpdate(manual = true) },
        )
        DropdownMenuItem(text = { Text("Esci") }, onClick = { open = false; vm.logout() })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceTile(
    device: Device,
    state: DeviceState?,
    busy: Boolean,
    hidden: Boolean,
    favorite: Boolean,
    tile: Int?,
    tilesFull: Boolean,
    timer: Long?,
    vm: HomeViewModel,
) {
    val on = state?.power == true
    val powerKnown = state?.power != null
    val colors = MaterialTheme.colorScheme
    var details by remember { mutableStateOf(false) }

    Surface(
        color = if (on) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(TILE_HEIGHT)
            .alpha(if (hidden) 0.5f else 1f)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(
                onClick = {
                    when {
                        !device.hasPower -> Unit
                        powerKnown -> vm.setPower(device, !on)
                        // Without a reported state (infrared devices, unreachable ones) a toggle would be a guess.
                        else -> details = true
                    }
                },
                onLongClick = { details = true },
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(device), contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                device.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusText(device, state) + (timer?.let { " · fino alle ${clockTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.75f),
            )
        }
    }

    if (details) DeviceSheet(device, state, hidden, favorite, tile, tilesFull, timer, vm, onDismiss = { details = false })
}

/** Everything beyond the tap-to-toggle: explicit on/off, timer, brightness, favorites, tile, hiding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    device: Device,
    state: DeviceState?,
    hidden: Boolean,
    favorite: Boolean,
    tile: Int?,
    tilesFull: Boolean,
    timer: Long?,
    vm: HomeViewModel,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(device), contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        statusText(device, state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (device.hasPower) {
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { vm.setPower(device, true) }, modifier = Modifier.weight(1f)) {
                        Text("Accendi")
                    }
                    FilledTonalButton(onClick = { vm.setPower(device, false) }, modifier = Modifier.weight(1f)) {
                        Text("Spegni")
                    }
                }
            }

            if (device.hasPower) {
                Text(
                    "Spegni tra",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                if (timer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Si spegne alle ${clockTime(timer)}", modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelTimer(device) }) { Text("Annulla timer") }
                    }
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, minutes) in TIMER_CHOICES) {
                            AssistChip(onClick = { vm.setTimer(device, minutes) }, label = { Text(label) })
                        }
                    }
                }
            }

            if (device.isSensor) HistoryCharts(remember(device, state) { vm.readings(device) })

            if (device.hasBrightness) {
                Text(
                    "Luminosità",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                BrightnessSlider(state?.brightness ?: 100) { vm.setBrightness(device, it) }
            }

            TextButton(
                onClick = { vm.setFavorite(device, !favorite) },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (favorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti")
            }
            if (device.hasPower) {
                TextButton(onClick = { vm.setOnTile(device, tile == null) }, enabled = tile != null || !tilesFull) {
                    Text(
                        when {
                            tile != null -> "Togli dalle Impostazioni rapide (riquadro ${tile + 1})"
                            tilesFull -> "Impostazioni rapide: riquadri tutti occupati"
                            else -> "Metti nelle Impostazioni rapide"
                        },
                    )
                }
            }
            TextButton(onClick = { vm.setHidden(device, !hidden); onDismiss() }) {
                Text(if (hidden) "Mostra di nuovo nella lista" else "Nascondi dalla lista")
            }
        }
    }
}

@Composable
private fun BrightnessSlider(brightness: Int, onChange: (Int) -> Unit) {
    // Local while dragging; the command goes out once, when the finger lifts.
    var value by remember(brightness) { mutableFloatStateOf(brightness.toFloat()) }
    Slider(
        value = value,
        onValueChange = { value = it },
        onValueChangeFinished = { onChange(value.roundToInt().coerceIn(1, 100)) },
        valueRange = 1f..100f,
    )
}

private fun clockTime(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

private fun iconFor(device: Device): ImageVector = when (device.category) {
    "LIGHT" -> Icons.Outlined.Lightbulb
    "SMARTPLUG" -> Icons.Outlined.Power
    "SWITCH" -> Icons.Outlined.ToggleOn
    "TV" -> Icons.Outlined.Tv
    "VACUUM_CLEANER" -> Icons.Outlined.CleaningServices
    "TEMPERATURE_SENSOR", "THERMOSTAT" -> Icons.Outlined.Thermostat
    else -> Icons.Outlined.DevicesOther
}
