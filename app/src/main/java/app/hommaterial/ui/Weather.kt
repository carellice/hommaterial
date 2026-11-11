package app.hommaterial.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.WeatherPlace
import app.hommaterial.str
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What a WMO weather code is called, its icon by day and by night, and the colors of the sky. The web app: ui/weather.js. */
private data class Sky(val name: Int, val icon: ImageVector, val top: Color, val bottom: Color)

private fun sky(code: Int, day: Boolean = true): Sky {
    val sun = Sky(R.string.weather_clear, Icons.Filled.WbSunny, Color(0xFF5AA9F5), Color(0xFF2560C4))
    val night = Sky(R.string.weather_clear, Icons.Filled.NightsStay, Color(0xFF2C3A6B), Color(0xFF0D1330))
    val cloud = Sky(R.string.weather_cloudy, Icons.Filled.Cloud, Color(0xFF8796AB), Color(0xFF4B586C))
    val rain = Sky(R.string.weather_rain, Icons.Filled.Umbrella, Color(0xFF5D7491), Color(0xFF2A3A52))
    return when {
        code == 0 -> if (day) sun else night
        code == 1 -> (if (day) sun else night).copy(name = R.string.weather_mostly_clear)
        code == 2 -> (if (day) cloud.copy(top = Color(0xFF6E9BD0), bottom = Color(0xFF34558A)) else night)
            .copy(name = R.string.weather_partly, icon = Icons.Filled.WbCloudy)
        code == 3 -> cloud
        code == 45 || code == 48 -> cloud.copy(name = R.string.weather_fog, icon = Icons.Filled.Dehaze)
        code in 51..57 -> rain.copy(name = R.string.weather_drizzle, icon = Icons.Filled.Grain)
        code in 61..67 -> rain
        code in 71..77 || code == 85 || code == 86 -> Sky(R.string.weather_snow, Icons.Filled.AcUnit, Color(0xFFA9C2DC), Color(0xFF5F7F9F))
        code in 80..82 -> rain.copy(name = R.string.weather_showers)
        code >= 95 -> Sky(R.string.weather_storm, Icons.Filled.FlashOn, Color(0xFF5B4F86), Color(0xFF221B3D))
        else -> cloud
    }
}

private fun degrees(t: Double) = "${t.roundToInt()}°"

/** The weather now and of the next days at the top of the home, in the colors of the sky. */
@Composable
internal fun WeatherHomeCard(state: UiState, vm: HomeViewModel) {
    val place = state.weather ?: return
    LaunchedEffect(place) { vm.loadWeather() }
    val w = state.weatherInfo
    val look = w?.let { sky(it.code, it.day) } ?: sky(3)
    val float by rememberInfiniteTransition(label = "weather").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float",
    )
    val metro = LocalMetro.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .then(if (metro) Modifier.metroTilt() else Modifier.lookGlass(corners(if (LocalSkeuo.current) 16.dp else 24.dp)))
            .clip(corners(if (LocalSkeuo.current) 16.dp else 24.dp))
            // A live tile is one flat color; elsewhere the sky fades from top to bottom.
            .background(if (metro) Brush.linearGradient(listOf(look.bottom, look.bottom)) else Brush.linearGradient(listOf(look.top, look.bottom))),
    ) {
        // The icon of the sky, large and faint, behind everything.
        Icon(
            look.icon, null, tint = Color.White,
            modifier = Modifier.size(170.dp).align(Alignment.CenterEnd).graphicsLayer { translationX = 30.dp.toPx() }.alpha(0.12f),
        )
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        place.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    if (w != null) Text(str(look.name), style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.85f))
                }
                when {
                    w == null && !state.weatherFailed -> LinearProgressIndicator(
                        color = Color.White, trackColor = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth().clip(CircleShape),
                    )
                    w == null -> Text(
                        str(R.string.weather_unavailable), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp).alpha(0.85f),
                    )
                    else -> {
                        val today = w.days.firstOrNull()
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(look.icon, null, Modifier.size(56.dp).graphicsLayer { translationY = -4.dp.toPx() * float })
                            Text(
                                degrees(w.temperature),
                                style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                if (today != null) Text("${degrees(today.max)} / ${degrees(today.min)}", style = MaterialTheme.typography.labelLarge)
                                Text(str(R.string.weather_feels, degrees(w.feels)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.85f))
                                Text(
                                    (today?.rain?.let { str(R.string.weather_rain_chance, it) } ?: str(R.string.weather_humidity, w.humidity)) +
                                        " · " + str(R.string.weather_wind, w.wind.roundToInt()),
                                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.85f),
                                )
                            }
                        }
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            w.days.drop(1).take(4).forEachIndexed { i, d ->
                                val appear = rememberAppear(d.date, 120L + i * 60L)
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .graphicsLayer {
                                            alpha = appear
                                            translationY = (1 - appear) * 16.dp.toPx()
                                        }
                                        .clip(corners(16.dp))
                                        .background(Color.White.copy(alpha = if (LocalMetro.current) 0.18f else 0.14f))
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val day = runCatching { LocalDate.parse(d.date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }.getOrDefault("")
                                    Text(day, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.85f))
                                    Icon(sky(d.code).icon, null, Modifier.padding(vertical = 4.dp).size(24.dp))
                                    Text(
                                        "${degrees(d.max)} ${degrees(d.min)}",
                                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 0 to 1 once, after [delayMs], when it first shows. */
@Composable
private fun rememberAppear(key: Any, delayMs: Long): Float {
    val progress = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs)
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    return progress.value
}

/** The section of the settings: whether the home shows the weather, and of which place. */
@Composable
internal fun WeatherSettings(state: UiState, vm: HomeViewModel) {
    var picking by remember { mutableStateOf(false) }
    Setting(
        title = str(R.string.weather_home),
        onClick = { if (state.weather == null) picking = true else vm.setWeather(null) },
        trailing = { AppSwitch(checked = state.weather != null, onCheckedChange = { if (it) picking = true else vm.setWeather(null) }) },
    )
    state.weather?.let { Setting(title = str(R.string.weather_place), text = it.name, onClick = { picking = true }) }
    if (picking) PlacePicker(vm, onPick = { vm.setWeather(it); picking = false }, onDismiss = { picking = false })
}

/** A town searched by name, among those of Open-Meteo. */
@Composable
private fun PlacePicker(vm: HomeViewModel, onPick: (WeatherPlace) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<WeatherPlace>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            found = null
            return@LaunchedEffect
        }
        searching = true
        delay(350)
        found = vm.weatherPlaces(query.trim()) ?: emptyList()
        searching = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.weather_place_none)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(str(R.string.weather_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().height(280.dp).padding(top = 8.dp)) {
                    when {
                        searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        found?.isEmpty() == true -> Text(str(R.string.weather_no_results), Modifier.padding(top = 16.dp))
                        else -> LazyColumn {
                            items(found.orEmpty(), key = { "${it.lat},${it.lon}" }) { place ->
                                Column(Modifier.fillMaxWidth().clip(corners(12.dp)).clickable { onPick(place) }.padding(12.dp)) {
                                    Text(place.name, style = MaterialTheme.typography.bodyLarge)
                                    if (place.detail.isNotEmpty()) {
                                        Text(place.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
