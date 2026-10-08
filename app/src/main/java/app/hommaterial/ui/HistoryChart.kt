package app.hommaterial.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.hommaterial.R
import app.hommaterial.data.Reading
import app.hommaterial.quick.clock
import app.hommaterial.str
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

private const val HOUR_MS = 60 * 60_000L
// Readings further apart than this are not joined: the line would invent what happened in between.
private const val MAX_JOIN_MS = 2 * HOUR_MS
// How far apart the lines of the scale can be, in degrees or in percent.
private val SCALE_STEPS = listOf(0.5f, 1f, 2f, 5f, 10f, 20f)
private val SPANS = listOf(R.string.span_day to 24 * HOUR_MS, R.string.span_week to 7 * 24 * HOUR_MS)

/** Temperature and humidity of a sensor over the last day or week. */
@Composable
fun HistoryCharts(readings: List<Reading>) {
    var span by remember { mutableStateOf(SPANS.first()) }
    val now = remember(readings) { System.currentTimeMillis() }
    val shown = readings.filter { now - it.at <= span.second }

    Column(Modifier.padding(top = 20.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in SPANS) {
                FilterChip(selected = span == choice, onClick = { span = choice }, label = { Text(str(choice.first)) })
            }
        }
        if (shown.size < 2) {
            Text(
                str(R.string.history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            return@Column
        }
        val start = now - span.second
        val week = span.second > 24 * HOUR_MS
        Chart(
            title = str(R.string.temperature),
            points = shown.map { it.at to it.temperature.toFloat() },
            start = start,
            end = now,
            week = week,
            color = MaterialTheme.colorScheme.primary,
            unit = "°",
            decimals = 1,
        )
        val humidity = shown.mapNotNull { r -> r.humidity?.let { r.at to it.toFloat() } }
        if (humidity.size >= 2) {
            Chart(
                title = str(R.string.humidity),
                points = humidity,
                start = start,
                end = now,
                week = week,
                color = MaterialTheme.colorScheme.tertiary,
                unit = "%",
                decimals = 0,
            )
        }
        Text(
            str(R.string.chart_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * One series over time, with its scale on the left and the hours, or the days of a [week], at
 * the bottom. A finger held on it reads the value under it.
 */
@Composable
private fun Chart(
    title: String,
    points: List<Pair<Long, Float>>,
    start: Long,
    end: Long,
    week: Boolean,
    color: Color,
    unit: String,
    decimals: Int,
) {
    val lowest = points.minOf { it.second }
    val highest = points.maxOf { it.second }
    val average = points.map { it.second }.average().toFloat()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val hollow = MaterialTheme.colorScheme.surfaceContainerLow
    val labels = MaterialTheme.typography.labelSmall.copy(color = faint)
    val measurer = rememberTextMeasurer()
    // The unit is added afterwards: a percent sign would be read as part of the format.
    fun value(v: Float) = "%.${decimals}f".format(v) + unit
    // The reading under the finger, while one is held on the chart.
    var picked by remember(points) { mutableStateOf<Pair<Long, Float>?>(null) }
    // Known once drawn: the scale on the left takes the room its widest value needs.
    var plotWidth by remember { mutableFloatStateOf(1f) }

    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        val reading = picked
        if (reading != null) {
            Text(
                str(R.string.chart_reading, value(reading.second), moment(reading.first, week)),
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        } else {
            Text(
                str(R.string.min_avg_max, value(lowest), value(average), value(highest)),
                style = MaterialTheme.typography.labelMedium,
                color = faint,
            )
        }
    }

    // Round steps, few enough to leave three or four lines to read the values against.
    val step = SCALE_STEPS.firstOrNull { (highest - lowest) / it <= 3f } ?: SCALE_STEPS.last()
    val bottom = floor(lowest / step) * step
    val top = max(ceil(highest / step) * step, bottom + 2 * step)
    val levels = generateSequence(bottom) { it + step }.takeWhile { it <= top + step / 100 }.toList()
    fun level(v: Float) = if (step < 1f) value(v) else "%.0f".format(v) + unit

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .padding(top = 8.dp)
            .pointerInput(points, start, end) {
                awaitEachGesture {
                    val gutter = size.width - plotWidth
                    fun pick(x: Float) {
                        val at = start + ((x - gutter) / plotWidth * (end - start)).toLong()
                        picked = points.minByOrNull { abs(it.first - at) }
                    }
                    // Left unconsumed: the panel around the chart keeps scrolling.
                    pick(awaitFirstDown(requireUnconsumed = false).position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { pick(it.position.x) }
                    } while (event.changes.any { it.pressed })
                    picked = null
                }
            },
    ) {
        val scale = levels.map { measurer.measure(level(it), labels) }
        val gutter = scale.maxOf { it.size.width } + 8.dp.toPx()
        val hours = measurer.measure("00", labels).size.height + 6.dp.toPx()
        val width = size.width - gutter
        val height = size.height - hours
        plotWidth = width
        fun x(at: Long) = gutter + (at - start).toFloat() / (end - start) * width
        fun y(v: Float) = height - (v - bottom) / (top - bottom) * height
        fun place(point: Pair<Long, Float>) = Offset(x(point.first), y(point.second))

        levels.forEachIndexed { i, v ->
            drawLine(grid, Offset(gutter, y(v)), Offset(size.width, y(v)))
            val text = scale[i]
            val down = (y(v) - text.size.height / 2f).coerceIn(0f, height - text.size.height)
            drawText(text, topLeft = Offset(gutter - 8.dp.toPx() - text.size.width, down))
        }
        for (tick in ticks(start, end, week)) {
            drawLine(grid.copy(alpha = 0.5f), Offset(x(tick), 0f), Offset(x(tick), height))
            val text = measurer.measure(tickLabel(tick, week), labels)
            val left = (x(tick) - text.size.width / 2f).coerceIn(gutter, size.width - text.size.width)
            drawText(text, topLeft = Offset(left, height + 6.dp.toPx()))
        }

        // Readings far apart are not joined: each stretch is a line with its own shade under it.
        val stroke = 2.dp.toPx()
        val stretches = mutableListOf(mutableListOf(points.first()))
        for ((previous, current) in points.zipWithNext()) {
            if (current.first - previous.first > MAX_JOIN_MS) stretches += mutableListOf<Pair<Long, Float>>()
            stretches.last() += current
        }
        val fade = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), endY = height)
        for (stretch in stretches) {
            if (stretch.size == 1) {
                drawCircle(color, radius = stroke, center = place(stretch.single()))
                continue
            }
            val line = Path().apply {
                stretch.forEachIndexed { i, point ->
                    val (px, py) = place(point)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            val shade = Path().apply {
                addPath(line)
                lineTo(x(stretch.last().first), height)
                lineTo(x(stretch.first().first), height)
                close()
            }
            drawPath(shade, fade)
            drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // The latest reading stands out, unless the finger is pointing at another one.
        val center = place(picked ?: points.last())
        if (picked != null) drawLine(color.copy(alpha = 0.6f), Offset(center.x, 0f), Offset(center.x, height))
        drawCircle(color, radius = 5.dp.toPx(), center = center)
        drawCircle(hollow, radius = 2.5.dp.toPx(), center = center)
    }
}

/** Where the vertical lines go: every six hours of the clock, or at the midnights of a week. */
private fun ticks(start: Long, end: Long, week: Boolean): List<Long> {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = start
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.HOUR_OF_DAY, if (week) 0 else get(Calendar.HOUR_OF_DAY) / 6 * 6)
    }
    val ticks = mutableListOf<Long>()
    while (calendar.timeInMillis <= end) {
        if (calendar.timeInMillis >= start) ticks += calendar.timeInMillis
        if (week) calendar.add(Calendar.DAY_OF_YEAR, 1) else calendar.add(Calendar.HOUR_OF_DAY, 6)
    }
    return ticks
}

private fun tickLabel(at: Long, week: Boolean): String =
    if (week) DateFormat.format("EEE", at).toString() else clock(at)

/** When a reading was taken: the time alone within a day, with its day over a week. */
private fun moment(at: Long, week: Boolean): String =
    if (week) "${DateFormat.format("EEE d", at)}, ${clock(at)}" else clock(at)
