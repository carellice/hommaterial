package app.hommaterial.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.FavoriteTeam
import app.hommaterial.data.Football
import app.hommaterial.data.LEAGUES
import app.hommaterial.data.League
import app.hommaterial.data.Match
import app.hommaterial.data.TableRow
import app.hommaterial.quick.clock
import app.hommaterial.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// --- logos ---------------------------------------------------------------------------------------

private val logos = LruCache<String, ImageBitmap>(96)

/** A smaller copy of an ESPN logo: the originals are 500 pixels wide. */
internal fun smallLogo(url: String, px: Int = 96): String =
    if (url.startsWith("https://a.espncdn.com/i/")) {
        "https://a.espncdn.com/combiner/i?img=${url.removePrefix("https://a.espncdn.com")}&h=$px&w=$px"
    } else {
        url
    }

/** The logo at [url], kept in memory once read; nothing while it comes. */
@Composable
private fun rememberLogo(url: String): ImageBitmap? = produceState(logos.get(url), url) {
    if (value != null || url.isEmpty()) return@produceState
    value = withContext(Dispatchers.IO) {
        runCatching { URL(smallLogo(url)).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap() }.getOrNull()
    }?.also { logos.put(url, it) }
}.value

/** A logo on a light disc, readable whatever the color of the team behind it. */
@Composable
internal fun BadgeLogo(url: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.94f))
            .padding(size / 7),
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.Black) { TeamLogo(url, size) }
    }
}

@Composable
internal fun TeamLogo(url: String, size: Dp, modifier: Modifier = Modifier) {
    val logo = rememberLogo(url)
    Crossfade(logo, animationSpec = tween(250), label = "logo", modifier = modifier.size(size)) { shown ->
        if (shown != null) {
            Image(shown, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().padding(size / 8).clip(CircleShape).background(LocalContentColor.current.copy(alpha = 0.12f)))
        }
    }
}

// --- colors and words ---------------------------------------------------------------------------

private val WIN = Color(0xFF2E9D50)
private val DRAW = Color(0xFFE0A526)
private val LOSS = Color(0xFFD64545)

/** The color of the team, or of the app when ESPN gives none. */
@Composable
private fun teamColor(team: FavoriteTeam): Color =
    team.color?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: MaterialTheme.colorScheme.primary

/** The colors of the band of the team: its color, darker at the far end, with text readable on it. */
@Composable
private fun teamBrush(team: FavoriteTeam): Pair<Brush, Color> {
    val base = teamColor(team)
    val brush = Brush.linearGradient(listOf(lerp(base, Color.White, 0.08f), lerp(base, Color.Black, 0.45f)))
    return brush to if (base.luminance() > 0.55f) Color(0xFF15171A) else Color.White
}

private fun resultColor(result: Char?) = when (result) {
    'W' -> WIN
    'D' -> DRAW
    'L' -> LOSS
    else -> Color.Gray
}

private fun resultLetter(result: Char?) = when (result) {
    'W' -> str(R.string.team_result_w)
    'D' -> str(R.string.team_result_d)
    'L' -> str(R.string.team_result_l)
    else -> "–"
}

private fun leagueName(id: String) = LEAGUES.find { it.id == id }?.name.orEmpty()

/** The zones of the table in the language of the app; the names of the cups stay as they are. */
private fun zoneLabel(zone: String): String {
    val lower = zone.lowercase()
    return when {
        "relegat" in lower -> str(R.string.team_zone_relegation)
        "playoff" in lower || "play-off" in lower -> str(R.string.team_zone_playoff)
        "promot" in lower -> str(R.string.team_zone_promotion)
        else -> zone
    }
}

private fun zoneColor(row: TableRow): Color? =
    row.zoneColor?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/** When a match is: today, tomorrow or its day, and its hour once fixed. */
internal fun matchWhen(match: Match): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(match.at).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    val dayText = when (day) {
        today -> str(R.string.calendar_today)
        today.plusDays(1) -> str(R.string.calendar_tomorrow)
        else -> dateText(match.at).replaceFirstChar { it.uppercase() }
    }
    return if (match.timeValid) "$dayText · ${clock(match.at)}" else "$dayText · ${str(R.string.team_time_tbd)}"
}

/** A value going from 0 to 1 once, after [delayMs], when it first shows. */
@Composable
private fun rememberEntrance(key: Any, delayMs: Long = 0): Float {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay(delayMs)
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    return progress.value
}

// --- at the top of the home ---------------------------------------------------------------------

/** The next match of the favorite team at the top of the home, in its colors: a tap opens the team. */
@Composable
internal fun TeamHomeCard(state: UiState, vm: HomeViewModel, onOpen: () -> Unit) {
    val team = state.team ?: return
    LaunchedEffect(team) { vm.loadTeam() }
    val info = state.teamInfo
    val (brush, on) = teamBrush(team)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(brush)
            .clickable(onClick = onOpen),
    ) {
        // The logo of the team, large and faint, behind everything.
        TeamLogo(team.logo, 150.dp, Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = 40.dp.toPx() }.alpha(0.12f))
        CompositionLocalProvider(LocalContentColor provides on) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SportsSoccer, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        info?.next?.let { if (it.state == "in") str(R.string.team_live) else str(R.string.team_next) } ?: team.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    info?.standing?.let {
                        Text(str(R.string.team_rank, it.rank, leagueName(team.league)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.85f))
                    }
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
                val next = info?.next
                when {
                    info == null && !state.teamFailed -> LinearProgressIndicator(
                        color = on, trackColor = on.copy(alpha = 0.2f),
                        modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth().clip(CircleShape),
                    )
                    next == null -> Text(
                        str(if (info == null) R.string.team_unavailable else R.string.team_no_match),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp).alpha(0.85f),
                    )
                    else -> Versus(next, logo = 52.dp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

/** The two teams face to face, sliding in from their sides, with the time or the score between them. */
@Composable
private fun Versus(match: Match, logo: Dp, modifier: Modifier = Modifier) {
    val enter = rememberEntrance(match.id)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SideColumn(match.home.name, match.home.logo, logo, Modifier.weight(1f).graphicsLayer {
            translationX = (1 - enter) * -48.dp.toPx()
            alpha = enter.coerceIn(0f, 1f)
        })
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(match.competition, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (match.state == "in") {
                Text(
                    "${match.home.score ?: 0} – ${match.away.score ?: 0}",
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
                LiveBadge(match.detail)
            } else {
                Text(
                    if (match.timeValid) clock(match.at) else "–",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.scale(0.85f + 0.15f * enter),
                )
                Text(matchWhen(match).substringBefore(" · "), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
        SideColumn(match.away.name, match.away.logo, logo, Modifier.weight(1f).graphicsLayer {
            translationX = (1 - enter) * 48.dp.toPx()
            alpha = enter.coerceIn(0f, 1f)
        })
    }
}

@Composable
private fun SideColumn(name: String, logo: String, size: Dp, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BadgeLogo(logo, size)
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** A red dot that breathes, with the minute of the match being played. */
@Composable
private fun LiveBadge(detail: String) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(Color(0xFFFF3B30)))
        Text(
            listOf(str(R.string.team_live), detail).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

// --- the page of the team -----------------------------------------------------------------------

/** Everything about the favorite team: its place, its record, its matches and the whole table. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TeamScreen(state: UiState, vm: HomeViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val team = state.team ?: return
    LaunchedEffect(team) { vm.loadTeam() }
    val info = state.teamInfo
    val (brush, on) = teamBrush(team)
    val enter = rememberEntrance(team.id)
    // Rows animate in the first time they show, not every time they scroll back into sight.
    val seen = remember(team.id) { mutableSetOf<String>() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().background(brush)) {
                    TeamLogo(
                        team.logo, 260.dp,
                        Modifier.align(Alignment.CenterEnd).graphicsLayer {
                            translationX = 70.dp.toPx()
                            rotationZ = -12f + 12f * enter
                        }.alpha(0.1f),
                    )
                    CompositionLocalProvider(LocalContentColor provides on) {
                        Column(Modifier.statusBarsPadding().padding(bottom = 28.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back))
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { vm.loadTeam(force = true) }) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = str(R.string.refresh_now))
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                BadgeLogo(team.logo, 96.dp, Modifier.scale(0.6f + 0.4f * enter).alpha(enter.coerceIn(0f, 1f)))
                                Text(
                                    team.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp).graphicsLayer { translationY = (1 - enter) * 24.dp.toPx() },
                                )
                                val standing = info?.standing
                                Text(
                                    if (standing != null) {
                                        "${str(R.string.team_rank, standing.rank, leagueName(team.league))} · ${str(R.string.team_points, standing.points)}"
                                    } else {
                                        leagueName(team.league)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.alpha(0.85f * enter.coerceIn(0f, 1f)),
                                )
                            }
                        }
                    }
                }
            }
            if (info == null) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        if (state.teamFailed) {
                            Text(str(R.string.team_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                return@LazyColumn
            }
            info.standing?.let { standing ->
                item(key = "record") { Record(standing) }
            }
            info.next?.let { next ->
                item(key = "next") {
                    Block(str(if (next.state == "in") R.string.team_live else R.string.team_next)) {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(16.dp)) {
                                Versus(next, logo = 56.dp)
                                if (next.venue.isNotEmpty()) {
                                    Text(
                                        next.venue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (info.recent.isNotEmpty()) {
                item(key = "form") {
                    Block(str(R.string.team_form)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            // Oldest on the left, as a form guide reads.
                            info.recent.reversed().forEachIndexed { i, match ->
                                val result = Football.outcome(match, team.id)
                                val pop = rememberEntrance("form${match.id}", 120L + i * 70L)
                                Box(
                                    Modifier.size(36.dp).scale(pop).clip(CircleShape).background(resultColor(result)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(resultLetter(result), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        for (match in info.recent) MatchLine(match, team.id)
                    }
                }
            }
            if (info.upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    Block(str(R.string.team_upcoming)) { for (match in info.upcoming) MatchLine(match, team.id) }
                }
            }
            if (info.table.isNotEmpty()) {
                item(key = "tableHead") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                        Text(str(R.string.team_table), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        TableLine(
                            "#", null, "", str(R.string.team_col_played), str(R.string.team_col_diff), str(R.string.team_col_points),
                            header = true,
                        )
                    }
                }
                itemsIndexed(info.table, key = { _, row -> "row${row.id}" }) { index, row ->
                    val first = seen.add(row.id)
                    val appear = if (first) rememberEntrance(row.id, 40L * index.coerceAtMost(20)) else 1f
                    val mine = row.id == team.id
                    Box(
                        Modifier
                            .padding(horizontal = 16.dp)
                            .graphicsLayer {
                                alpha = appear.coerceIn(0f, 1f)
                                translationY = (1 - appear) * 16.dp.toPx()
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (mine) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                    ) {
                        CompositionLocalProvider(
                            LocalContentColor provides if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ) {
                            TableLine(
                                row.rank.toString(), row, row.name, row.played.toString(),
                                (row.goalsFor - row.goalsAgainst).let { if (it > 0) "+$it" else it.toString() },
                                row.points.toString(), bold = mine,
                            )
                        }
                    }
                }
                item(key = "zones") {
                    val zones = info.table.filter { it.zone != null }.distinctBy { it.zone }
                    FlowRow(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (row in zones) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(zoneColor(row) ?: Color.Gray))
                                Text(
                                    zoneLabel(row.zone!!),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            item(key = "source") {
                Text(
                    str(R.string.team_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

/** Won, drawn and lost: counted up, with a bar that fills in their proportions. */
@Composable
private fun Record(row: TableRow) {
    val enter = rememberEntrance("record${row.id}", 150)
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    @Composable
    fun count(value: Int) = animateIntAsState(if (started) value else 0, tween(900, easing = FastOutSlowInEasing), label = "count").value

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp).graphicsLayer {
            translationY = (1 - enter) * 24.dp.toPx()
            alpha = enter.coerceIn(0f, 1f)
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                for ((label, value, color) in listOf(
                    Triple(R.string.team_wins, row.wins, WIN),
                    Triple(R.string.team_draws, row.draws, DRAW),
                    Triple(R.string.team_losses, row.losses, LOSS),
                )) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            count(value).toString(),
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = color,
                        )
                        Text(str(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val played = (row.wins + row.draws + row.losses).coerceAtLeast(1)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth(enter.coerceIn(0.001f, 1f)).fillMaxHeight()) {
                    if (row.wins > 0) Box(Modifier.weight(row.wins.toFloat() / played).fillMaxHeight().background(WIN))
                    if (row.draws > 0) Box(Modifier.weight(row.draws.toFloat() / played).fillMaxHeight().background(DRAW))
                    if (row.losses > 0) Box(Modifier.weight(row.losses.toFloat() / played).fillMaxHeight().background(LOSS))
                }
            }
            Row(Modifier.padding(top = 16.dp)) {
                for ((label, value) in listOf(
                    R.string.team_played to row.played,
                    R.string.team_goals_for to row.goalsFor,
                    R.string.team_goals_against to row.goalsAgainst,
                )) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(count(value).toString(), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                        Text(str(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A match in a line: when, the two teams and, once played, the score with how it went. */
@Composable
private fun MatchLine(match: Match, teamId: String) {
    val result = Football.outcome(match, teamId)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(92.dp)) {
            Text(
                android.text.format.DateFormat.format(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM"), match.at).toString(),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(match.competition, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(match.home.name, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TeamLogo(match.home.logo, 22.dp, Modifier.padding(horizontal = 6.dp))
        Box(
            Modifier.width(54.dp).clip(RoundedCornerShape(8.dp))
                .background(if (result != null) resultColor(result).copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (match.state == "pre") (if (match.timeValid) clock(match.at) else "–") else "${match.home.score ?: "-"} – ${match.away.score ?: "-"}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = if (result != null) resultColor(result) else LocalContentColor.current,
            )
        }
        TeamLogo(match.away.logo, 22.dp, Modifier.padding(horizontal = 6.dp))
        Text(match.away.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableLine(rank: String, row: TableRow?, name: String, played: String, diff: String, points: String, header: Boolean = false, bold: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val weight = if (bold) FontWeight.Bold else null
    val faint = if (header) Modifier.alpha(0.7f) else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The zone the place falls in, as a stripe.
        Box(Modifier.width(3.dp).height(20.dp).clip(CircleShape).background(row?.let { zoneColor(it) } ?: Color.Transparent))
        Text(rank, style = style, fontWeight = weight, textAlign = TextAlign.Center, modifier = faint.width(32.dp))
        if (row != null) TeamLogo(row.logo, 22.dp) else Spacer(Modifier.width(22.dp))
        Text(name, style = style, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = faint.padding(start = 10.dp).weight(1f))
        Text(played, style = style, textAlign = TextAlign.Center, modifier = faint.width(36.dp))
        Text(diff, style = style, textAlign = TextAlign.Center, modifier = faint.width(44.dp))
        Text(points, style = style, fontWeight = if (header) null else FontWeight.Bold, textAlign = TextAlign.Center, modifier = faint.width(40.dp))
    }
}

// --- the settings -------------------------------------------------------------------------------

/** The section of the settings: the team chosen, to change or remove, or the choice of one. */
@Composable
internal fun TeamSettings(state: UiState, vm: HomeViewModel) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        TeamPicker(vm, state.team, onPick = { vm.setTeam(it); picking = false }, onDismiss = { picking = false })
    }
    val team = state.team
    if (team == null) {
        Setting(title = str(R.string.team_choose), text = str(R.string.team_none), onClick = { picking = true })
        return
    }
    Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(team.logo, 40.dp)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(team.name, style = MaterialTheme.typography.bodyLarge)
            Text(leagueName(team.league), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(str(R.string.team_change), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Setting(title = str(R.string.team_remove), onClick = { vm.setTeam(null) })
}

/** The teams of each league as a grid of their logos. */
@Composable
private fun TeamPicker(vm: HomeViewModel, current: FavoriteTeam?, onPick: (FavoriteTeam) -> Unit, onDismiss: () -> Unit) {
    val leagues by produceState<List<Pair<League, List<FavoriteTeam>>>?>(null) { value = vm.footballTeams() ?: emptyList() }
    var league by remember { mutableStateOf(current?.league ?: LEAGUES.first().id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.team_choose)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in LEAGUES) FilterChip(selected = league == l.id, onClick = { league = l.id }, label = { Text(l.name) })
                }
                val teams = leagues?.find { it.first.id == league }?.second
                Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                    when {
                        leagues == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(str(R.string.team_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                        }
                        teams.isNullOrEmpty() -> Text(str(R.string.team_unavailable), textAlign = TextAlign.Center)
                        else -> LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp)) {
                            items(teams, key = { it.id }) { team ->
                                val chosen = team.id == current?.id
                                Column(
                                    Modifier
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable { onPick(team) }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TeamLogo(team.logo, 44.dp)
                                    Text(
                                        team.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
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

// --- the rest screen of the monitor -------------------------------------------------------------

/** The next match in one line on the rest screen: the two logos, the teams and when. */
@Composable
internal fun RestTeam(state: UiState, vm: HomeViewModel) {
    val team = state.team ?: return
    LaunchedEffect(team) { vm.loadTeam() }
    val next = state.teamInfo?.next ?: return
    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        BadgeLogo(next.home.logo, 32.dp)
        Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${next.home.name} – ${next.away.name}", style = MaterialTheme.typography.titleLarge)
            Text(
                if (next.state == "in") "${next.home.score ?: 0} – ${next.away.score ?: 0} · ${next.detail}" else matchWhen(next),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                modifier = Modifier.alpha(0.7f),
            )
        }
        BadgeLogo(next.away.logo, 32.dp)
    }
}
