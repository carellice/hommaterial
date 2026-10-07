package app.hommaterial.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.hommaterial.data.Reading

private const val HOUR_MS = 60 * 60_000L
// Readings further apart than this are not joined: the line would invent what happened in between.
private const val MAX_JOIN_MS = 2 * HOUR_MS
private val SPANS = listOf("24 ore" to 24 * HOUR_MS, "7 giorni" to 7 * 24 * HOUR_MS)

/** Temperature and humidity of a sensor over the last day or week. */
@Composable
fun HistoryCharts(readings: List<Reading>) {
    var span by remember { mutableStateOf(SPANS.first()) }
    val now = remember(readings) { System.currentTimeMillis() }
    val shown = readings.filter { now - it.at <= span.second }

    Column(Modifier.padding(top = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in SPANS) {
                FilterChip(selected = span == choice, onClick = { span = choice }, label = { Text(choice.first) })
            }
        }
        if (shown.size < 2) {
            Text(
                "Ancora pochi dati: la cronologia si riempie ogni volta che l'app o il widget aggiornano gli stati.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        val start = now - span.second
        Chart(
            title = "Temperatura",
            points = shown.map { it.at to it.temperature.toFloat() },
            start = start,
            end = now,
            color = MaterialTheme.colorScheme.primary,
            format = { "%.1f°".format(it) },
        )
        val humidity = shown.mapNotNull { r -> r.humidity?.let { r.at to it.toFloat() } }
        if (humidity.size >= 2) {
            Chart(
                title = "Umidità",
                points = humidity,
                start = start,
                end = now,
                color = MaterialTheme.colorScheme.tertiary,
                format = { "%.0f%%".format(it) },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = MaterialTheme.typography.labelSmall
            Text("${span.first} fa", style = labels, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("adesso", style = labels, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Chart(
    title: String,
    points: List<Pair<Long, Float>>,
    start: Long,
    end: Long,
    color: Color,
    format: (Float) -> String,
) {
    val lowest = points.minOf { it.second }
    val highest = points.maxOf { it.second }
    val grid = MaterialTheme.colorScheme.outlineVariant

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            "min ${format(lowest)} · max ${format(highest)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp)) {
        // A flat series still gets some height to sit in the middle of.
        val margin = ((highest - lowest) * 0.1f).coerceAtLeast(0.5f)
        val bottom = lowest - margin
        val range = highest + margin - bottom
        fun place(point: Pair<Long, Float>) = Offset(
            x = (point.first - start).toFloat() / (end - start) * size.width,
            y = size.height - (point.second - bottom) / range * size.height,
        )

        drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f))
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        val stroke = 2.dp.toPx()
        for ((previous, current) in points.zipWithNext()) {
            if (current.first - previous.first <= MAX_JOIN_MS) {
                drawLine(color, place(previous), place(current), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
        for (point in points) drawCircle(color, radius = stroke, center = place(point))
    }
}
