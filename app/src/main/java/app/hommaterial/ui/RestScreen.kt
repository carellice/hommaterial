package app.hommaterial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.REST_CLOCK
import app.hommaterial.data.REST_DATE
import app.hommaterial.data.REST_POWER
import app.hommaterial.data.REST_SENSORS
import app.hommaterial.data.REST_TIMERS
import app.hommaterial.data.statusText
import app.hommaterial.plural
import app.hommaterial.quick.clock
import app.hommaterial.str

/**
 * What a panel shows while nobody uses it: the clock and a glance at the home, on black. At
 * [night] only the clock is left, faint. Any touch calls [onWake].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RestScreen(state: UiState, night: Boolean, onWake: () -> Unit) {
    val monitor = state.monitor
    val now by rememberNow()
    val items = if (night) setOf(REST_CLOCK) else monitor.restItems
    val devices = state.placed.filter { it.applianceId !in state.hidden }
    // Moved a little every minute, so that nothing stays lit on the same pixels for days.
    val minute = (now / 60_000).toInt()
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = null, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White.copy(alpha = if (night) 0.4f else 0.87f)) {
            Column(
                Modifier.offset(((minute % 5 - 2) * 8).dp, ((minute / 5 % 5 - 2) * 8).dp).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (REST_CLOCK in items) {
                    Text(clock(now), fontSize = if (wide) 128.sp else 84.sp, fontWeight = FontWeight.Light)
                }
                if (REST_DATE in items) Text(dateText(now), style = MaterialTheme.typography.headlineSmall)
                if (REST_SENSORS in items) {
                    val chosen = monitor.restSensors
                    val sensors = devices.filter {
                        it.isSensor && (chosen == null || it.applianceId in chosen) &&
                            state.states[it.applianceId]?.temperature?.isNaN() == false
                    }
                    FlowRow(
                        Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        for (sensor in sensors) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    statusText(sensor, state.states[sensor.applianceId]),
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                                Text(
                                    // The room tells apart sensors with the same name.
                                    listOfNotNull(sensor.name, sensor.room).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.alpha(0.7f),
                                )
                            }
                        }
                    }
                }
                if (REST_POWER in items) {
                    val on = devices.count { it.hasPower && state.states[it.applianceId]?.power == true }
                    Text(
                        if (on == 0) str(R.string.rest_all_off) else plural(R.plurals.rest_on, on),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp).alpha(0.7f),
                    )
                }
                if (REST_TIMERS in items) {
                    val timers = state.timers.entries.filter { it.value > now }.sortedBy { it.value }
                    for ((id, at) in timers) {
                        val name = devices.find { it.applianceId == id }?.name ?: continue
                        Text(
                            str(R.string.rest_timer, name, countdown(at - now)),
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }
        }
    }
}
