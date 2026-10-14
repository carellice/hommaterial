package app.hommaterial.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.ACTION_MACRO
import app.hommaterial.data.ACTION_POWER
import app.hommaterial.data.ACTION_REMOTE
import app.hommaterial.data.MacroAction
import app.hommaterial.data.Schedule
import app.hommaterial.data.nextTime
import app.hommaterial.quick.clock
import app.hommaterial.str
import java.text.DateFormatSymbols
import java.util.Calendar

// Monday first, as the week is read here.
private val WEEK = listOf(
    Calendar.MONDAY,
    Calendar.TUESDAY,
    Calendar.WEDNESDAY,
    Calendar.THURSDAY,
    Calendar.FRIDAY,
    Calendar.SATURDAY,
    Calendar.SUNDAY,
)
private val IN_MINUTES = listOf(5, 10, 15, 30, 60, 120)

private fun dayName(day: Int): String = DateFormatSymbols.getInstance().shortWeekdays[day]

/** When a schedule runs next, or on which days, as a line of text. */
internal fun whenText(schedule: Schedule): String {
    val text = when {
        schedule.days.isEmpty() -> {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = schedule.at }
            val ahead = then.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)
            val day = when {
                then.get(Calendar.YEAR) != now.get(Calendar.YEAR) || ahead !in 0..1 -> dateText(schedule.at)
                ahead == 0 -> str(R.string.schedule_today)
                else -> str(R.string.schedule_tomorrow)
            }
            str(R.string.schedule_once, day, clock(schedule.at))
        }
        schedule.days.size == WEEK.size -> str(R.string.schedule_daily, clock(schedule.at))
        else -> {
            val days = WEEK.filter { it in schedule.days }.joinToString(", ") { dayName(it) }
            str(R.string.schedule_once, days, clock(schedule.at))
        }
    }
    return if (schedule.enabled) text else str(R.string.schedule_paused, text)
}

private fun duration(minutes: Int): String = when (minutes) {
    60 -> str(R.string.timer_60)
    120 -> str(R.string.timer_120)
    else -> str(R.string.monitor_minutes, minutes)
}

/**
 * The usual delays of a timer, and one of the user's own. [canTurnOn] lets that one switch on
 * instead of off; [onChoose] receives the minutes and whether to switch on.
 */
@Composable
internal fun TimerChoices(canTurnOn: Boolean = false, onChoose: (minutes: Int, on: Boolean) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, minutes) in TIMER_CHOICES) {
            AssistChip(onClick = { onChoose(minutes, false) }, label = { Text(str(label)) })
        }
        AssistChip(onClick = { custom = true }, label = { Text(str(R.string.timer_custom)) })
    }
    if (custom) {
        CustomTimerDialog(
            canTurnOn,
            onChoose = { minutes, on -> custom = false; onChoose(minutes, on) },
            onDismiss = { custom = false },
        )
    }
}

/** Asks for a delay in hours and minutes and, with [canTurnOn], whether to switch off or on then. */
@Composable
private fun CustomTimerDialog(canTurnOn: Boolean, onChoose: (minutes: Int, on: Boolean) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(false) }
    val total = (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.timer_custom_title)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { text -> hours = text.filter { it.isDigit() }.take(2) },
                        label = { Text(str(R.string.timer_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { text ->
                            val typed = text.filter { it.isDigit() }.take(2)
                            minutes = if ((typed.toIntOrNull() ?: 0) > 59) "59" else typed
                        },
                        label = { Text(str(R.string.timer_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canTurnOn) {
                    Text(
                        str(R.string.timer_then),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !on, onClick = { on = false }, label = { Text(str(R.string.turn_off)) })
                        FilterChip(selected = on, onClick = { on = true }, label = { Text(str(R.string.turn_on)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(total, on) }, enabled = total > 0) { Text(str(R.string.timer_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/**
 * The timer of a remote or of a custom device, [target]: the delays to choose from, or the one
 * that is running with what cancels it. [onChoose] receives the minutes; with [onTurnOn] a timer
 * of the user's own can also switch on, and that one receives them instead.
 */
@Composable
internal fun TimerChips(
    title: String,
    target: String,
    state: UiState,
    vm: HomeViewModel,
    onTurnOn: ((Int) -> Unit)? = null,
    onChoose: (Int) -> Unit,
) {
    val running = state.schedules.find { it.days.isEmpty() && it.enabled && it.action.target == target }
    Text(title, style = MaterialTheme.typography.labelLarge)
    if (running != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                str(R.string.schedule_once, running.label, clock(running.at)),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.deleteSchedule(running.id) }) { Text(str(R.string.cancel_timer)) }
        }
    } else {
        TimerChoices(canTurnOn = onTurnOn != null) { minutes, on ->
            if (on) onTurnOn?.invoke(minutes) else onChoose(minutes)
        }
    }
}

/** The actions set to happen at a time: what is waiting, and how to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchedulesDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    // The action chosen, while its time is being asked.
    var chosen by remember { mutableStateOf<MacroAction?>(null) }
    val askNotifications = rememberNotificationRequest()

    if (picking) {
        ActionPicker(
            state = state,
            kinds = listOf(ACTION_POWER, ACTION_MACRO, ACTION_REMOTE),
            onPick = { chosen = it; picking = false },
            onDismiss = { picking = false },
        )
    }
    chosen?.let { action ->
        WhenDialog(
            onPick = { at, days ->
                // How it went is told by a notification.
                askNotifications()
                vm.addSchedule(action, describe(action, state), at, days)
                chosen = null
            },
            onDismiss = { chosen = null },
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(str(R.string.s_schedules)) },
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
                        str(R.string.schedules_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (schedule in state.schedules) {
                        SectionCard {
                            ListItem(
                                headlineContent = { Text(schedule.label) },
                                supportingContent = { Text(whenText(schedule)) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                trailingContent = {
                                    Row {
                                        // One that runs once is either waiting or deleted.
                                        if (schedule.days.isNotEmpty()) {
                                            Switch(
                                                checked = schedule.enabled,
                                                onCheckedChange = { vm.setScheduleEnabled(schedule, it) },
                                            )
                                        }
                                        IconButton(onClick = { vm.deleteSchedule(schedule.id) }) {
                                            Icon(Icons.Outlined.Close, str(R.string.schedule_delete))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    SectionCard {
                        ListItem(
                            headlineContent = { Text(str(R.string.schedule_new)) },
                            leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { picking = true },
                        )
                    }
                }
            }
        }
    }
}

/** Asks when: at a time, once or on some days of the week, or in a while from now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhenDialog(onPick: (at: Long, days: Set<Int>) -> Unit, onDismiss: () -> Unit) {
    var timed by remember { mutableStateOf(true) }
    var minutes by remember { mutableIntStateOf(30) }
    var days by remember { mutableStateOf(setOf<Int>()) }
    val now = remember { Calendar.getInstance() }
    val time = rememberTimePickerState(
        initialHour = now.get(Calendar.HOUR_OF_DAY),
        initialMinute = now.get(Calendar.MINUTE),
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.schedule_when)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(timed, onClick = { timed = true }, label = { Text(str(R.string.schedule_at_time)) })
                    FilterChip(!timed, onClick = { timed = false }, label = { Text(str(R.string.schedule_in_a_while)) })
                }
                if (timed) {
                    TimeInput(state = time, modifier = Modifier.padding(top = 16.dp))
                    Text(str(R.string.schedule_repeat), style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (day in WEEK) {
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(dayName(day)) },
                            )
                        }
                    }
                    if (days.isEmpty()) {
                        Text(
                            str(R.string.schedule_repeat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (choice in IN_MINUTES) {
                            FilterChip(
                                selected = choice == minutes,
                                onClick = { minutes = choice },
                                label = { Text(duration(choice)) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val from = System.currentTimeMillis()
                    if (timed) {
                        onPick(nextTime(time.hour, time.minute, days, from), days)
                    } else {
                        onPick(from + minutes * 60_000L, emptySet())
                    }
                },
            ) {
                Text(str(R.string.schedule_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
