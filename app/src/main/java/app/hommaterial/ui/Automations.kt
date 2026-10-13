package app.hommaterial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.hommaterial.data.Device
import app.hommaterial.data.TRIGGER_HUMIDITY_ABOVE
import app.hommaterial.data.TRIGGER_HUMIDITY_BELOW
import app.hommaterial.data.TRIGGER_OFF
import app.hommaterial.data.TRIGGER_ON
import app.hommaterial.data.TRIGGER_TEMPERATURE_ABOVE
import app.hommaterial.data.TRIGGER_TEMPERATURE_BELOW
import app.hommaterial.quick.degrees
import app.hommaterial.str
import kotlin.math.roundToInt

private val HUMIDITY_TRIGGERS = setOf(TRIGGER_HUMIDITY_ABOVE, TRIGGER_HUMIDITY_BELOW)
private val THRESHOLD_TRIGGERS = HUMIDITY_TRIGGERS + TRIGGER_TEMPERATURE_ABOVE + TRIGGER_TEMPERATURE_BELOW

private fun amount(trigger: String, threshold: Double): String =
    if (trigger in HUMIDITY_TRIGGERS) "${threshold.roundToInt()}%" else degrees(threshold)

/** The condition of an automation as a sentence, about the device called [name]. */
private fun conditionText(name: String, trigger: String, threshold: Double): String = when (trigger) {
    TRIGGER_TEMPERATURE_ABOVE -> str(R.string.automation_temperature_above, name, amount(trigger, threshold))
    TRIGGER_TEMPERATURE_BELOW -> str(R.string.automation_temperature_below, name, amount(trigger, threshold))
    TRIGGER_HUMIDITY_ABOVE -> str(R.string.automation_humidity_above, name, amount(trigger, threshold))
    TRIGGER_HUMIDITY_BELOW -> str(R.string.automation_humidity_below, name, amount(trigger, threshold))
    TRIGGER_ON -> str(R.string.automation_on, name)
    else -> str(R.string.automation_off, name)
}

/** What can be waited for on [device], with the words that offer it. */
private fun triggersOf(device: Device, state: UiState): List<Pair<String, Int>> = buildList {
    if (device.isSensor) {
        add(TRIGGER_TEMPERATURE_ABOVE to R.string.trigger_temperature_above)
        add(TRIGGER_TEMPERATURE_BELOW to R.string.trigger_temperature_below)
        // Not every sensor measures the humidity.
        if (state.states[device.applianceId]?.humidity != null) {
            add(TRIGGER_HUMIDITY_ABOVE to R.string.trigger_humidity_above)
            add(TRIGGER_HUMIDITY_BELOW to R.string.trigger_humidity_below)
        }
    }
    if (device.hasPower) {
        add(TRIGGER_ON to R.string.trigger_on)
        add(TRIGGER_OFF to R.string.trigger_off)
    }
}

/** The automations: those there are, and the questions that make a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutomationsDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    // A new automation is made one answer at a time: the device, what to wait for, how much, what to do.
    var creating by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<Device?>(null) }
    var trigger by remember { mutableStateOf<String?>(null) }
    var threshold by remember { mutableStateOf<Double?>(null) }
    val askNotifications = rememberNotificationRequest()
    val reset = { creating = false; device = null; trigger = null; threshold = null }
    val watchable = state.placed.filter { it.applianceId !in state.hidden && (it.isSensor || it.hasPower) }
    val watched = device
    val waited = trigger

    when {
        !creating -> Unit
        watched == null -> ListDialog(
            title = str(R.string.automation_pick_device),
            items = watchable.map { it.name to (it.room ?: str(R.string.room_none)) },
            onDismiss = reset,
        ) { device = watchable[it] }
        waited == null -> {
            val triggers = triggersOf(watched, state)
            ListDialog(watched.name, triggers.map { str(it.second) to null }, onDismiss = reset) {
                trigger = triggers[it].first
            }
        }
        waited in THRESHOLD_TRIGGERS && threshold == null -> ThresholdDialog(
            title = str(triggersOf(watched, state).first { it.first == waited }.second),
            trigger = waited,
            current = state.states[watched.applianceId]?.let {
                if (waited in HUMIDITY_TRIGGERS) it.humidity?.toDouble() else it.temperature?.takeIf { t -> !t.isNaN() }
            },
            onPick = { threshold = it },
            onDismiss = reset,
        )
        else -> ActionPicker(
            state = state,
            kinds = listOf(ACTION_POWER, ACTION_MACRO, ACTION_REMOTE),
            onPick = { action ->
                // How it went is told by a notification.
                askNotifications()
                val limit = threshold ?: 0.0
                val title = conditionText(watched.name, waited, limit)
                vm.addAutomation(watched, waited, limit, action, title, describe(action, state))
                reset()
            },
            onDismiss = reset,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(str(R.string.s_automations)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = str(R.string.back))
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
                    Text(
                        str(R.string.automations_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (automation in state.automations) {
                        SectionCard {
                            ListItem(
                                headlineContent = { Text(automation.title) },
                                supportingContent = { Text(automation.label) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                trailingContent = {
                                    Row {
                                        Switch(
                                            checked = automation.enabled,
                                            onCheckedChange = { vm.setAutomationEnabled(automation, it) },
                                        )
                                        IconButton(onClick = { vm.deleteAutomation(automation.id) }) {
                                            Icon(Icons.Outlined.Close, str(R.string.automation_delete))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    SectionCard {
                        ListItem(
                            headlineContent = { Text(str(R.string.automation_new)) },
                            leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { creating = true },
                        )
                    }
                }
            }
        }
    }
}

/** Asks for a temperature or a humidity, starting near the [current] one and moving it by steps. */
@Composable
private fun ThresholdDialog(
    title: String,
    trigger: String,
    current: Double?,
    onPick: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val humidity = trigger in HUMIDITY_TRIGGERS
    val above = trigger == TRIGGER_TEMPERATURE_ABOVE || trigger == TRIGGER_HUMIDITY_ABOVE
    val step = if (humidity) 5.0 else 0.5
    // A little past the reading of now, on the side being waited for: otherwise it would hold at once.
    val start = (current?.roundToInt()?.toDouble() ?: if (humidity) 50.0 else 22.0) +
        (if (humidity) 10.0 else 2.0) * (if (above) 1 else -1)
    var value by remember { mutableDoubleStateOf(if (humidity) start.coerceIn(0.0, 100.0) else start) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { value -= step }, enabled = !humidity || value > 0.0) {
                    Icon(Icons.Outlined.Remove, contentDescription = str(R.string.alert_lower))
                }
                Text(
                    amount(trigger, value),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                FilledTonalIconButton(onClick = { value += step }, enabled = !humidity || value < 100.0) {
                    Icon(Icons.Outlined.Add, contentDescription = str(R.string.alert_raise))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(value) }) { Text(str(R.string.automation_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
