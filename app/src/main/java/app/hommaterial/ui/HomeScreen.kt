package app.hommaterial.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material.icons.outlined.Tv
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
import kotlin.math.roundToInt

private const val NO_ROOM = "Altro"
private val TILE_HEIGHT = 116.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, vm: HomeViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Hommaterial") }, actions = { OverflowMenu(state, vm) }) },
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
                for ((room, devices) in rooms) {
                    item(key = "room:$room", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            room,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    items(devices, key = { it.applianceId }) { device ->
                        DeviceTile(
                            device = device,
                            state = state.states[device.applianceId],
                            busy = device.applianceId in state.busy,
                            hidden = device.applianceId in state.hidden,
                            vm = vm,
                        )
                    }
                }
            }
        }
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
private fun DeviceTile(device: Device, state: DeviceState?, busy: Boolean, hidden: Boolean, vm: HomeViewModel) {
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
                statusText(device, state),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.75f),
            )
        }
    }

    if (details) DeviceSheet(device, state, hidden, vm, onDismiss = { details = false })
}

/** Everything beyond the tap-to-toggle: explicit on/off, brightness, hiding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(device: Device, state: DeviceState?, hidden: Boolean, vm: HomeViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
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

            if (device.hasBrightness) {
                Text(
                    "Luminosità",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 20.dp),
                )
                BrightnessSlider(state?.brightness ?: 100) { vm.setBrightness(device, it) }
            }

            TextButton(
                onClick = { vm.setHidden(device, !hidden); onDismiss() },
                modifier = Modifier.padding(top = 12.dp),
            ) {
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

private fun statusText(device: Device, state: DeviceState?): String = when {
    state == null -> "…"
    !state.reachable -> "Non raggiungibile"
    device.isSensor && state.temperature != null && !state.temperature.isNaN() ->
        "%.1f°".format(state.temperature) + (state.humidity?.let { " · $it%" } ?: "")
    state.power == true && device.hasBrightness && state.brightness != null -> "Acceso · ${state.brightness}%"
    state.power == true -> "Acceso"
    state.power == false -> "Spento"
    else -> "Stato sconosciuto"
}

private fun iconFor(device: Device): ImageVector = when (device.category) {
    "LIGHT" -> Icons.Outlined.Lightbulb
    "SMARTPLUG" -> Icons.Outlined.Power
    "SWITCH" -> Icons.Outlined.ToggleOn
    "TV" -> Icons.Outlined.Tv
    "VACUUM_CLEANER" -> Icons.Outlined.CleaningServices
    "TEMPERATURE_SENSOR", "THERMOSTAT" -> Icons.Outlined.Thermostat
    else -> Icons.Outlined.DevicesOther
}
