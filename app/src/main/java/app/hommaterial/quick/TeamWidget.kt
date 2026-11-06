package app.hommaterial.quick

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.hommaterial.MainActivity
import app.hommaterial.R
import app.hommaterial.data.Alexa
import app.hommaterial.data.FavoriteTeam
import app.hommaterial.data.Football
import app.hommaterial.data.LEAGUES
import app.hommaterial.data.Match
import app.hommaterial.data.TeamInfo
import app.hommaterial.str
import app.hommaterial.ui.matchWhen
import app.hommaterial.ui.smallLogo
import java.io.File
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val ACTION_REMINDER = "app.hommaterial.action.TEAM_REMINDER"
private const val CHANNEL = "team"
private const val OPEN_TEAM = "team"

/**
 * The next match of the favorite team kept on the phone: for the widget, which draws it in the
 * colors of the team as the card at the top of the home does, and for the reminder before it,
 * which the phone sets itself, with or without the home server.
 */
object TeamBoard {
    private val _changes = MutableStateFlow(0)
    val changes = _changes.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("team_board", Context.MODE_PRIVATE)
    private fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun team(context: Context): FavoriteTeam? =
        settings(context).getString("team", null)?.let { runCatching { FavoriteTeam.fromJson(JSONObject(it)) }.getOrNull() }

    /** The next match as kept, if it is of the team chosen now. */
    fun next(context: Context): Match? {
        val p = prefs(context)
        if (p.getString("teamId", null) != team(context)?.id) return null
        return p.getString("match", null)?.let { runCatching { Match.fromJson(JSONObject(it)) }.getOrNull() }
    }

    fun rank(context: Context): String? = prefs(context).getString("rank", null)

    /** Keeps what the app read of [team], draws the widget again and sets the reminder of the next match. */
    fun save(context: Context, team: FavoriteTeam, info: TeamInfo) {
        val app = context.applicationContext
        prefs(app).edit()
            .putString("teamId", team.id)
            .putString("match", info.next?.toJson()?.toString())
            .putString("rank", info.standing?.let { str(R.string.team_rank, it.rank, LEAGUES.find { l -> l.id == team.league }?.name.orEmpty()) })
            .apply()
        arm(app)
        // The logos come from the network: the widget draws once they are on the phone.
        Quick.scope.launch {
            listOfNotNull(team.logo, info.next?.home?.logo, info.next?.away?.logo).forEach { runCatching { logoFile(app, it) } }
            update(app)
        }
    }

    /** Reads the team again, for the widget; false when it cannot be read. */
    suspend fun refresh(context: Context): Boolean {
        val app = context.applicationContext
        val team = team(app) ?: run { update(app); return false }
        return runCatching { save(app, team, Football.team(Alexa.get(app).http, team)) }
            .onFailure { if (it is CancellationException) throw it }.isSuccess
    }

    fun update(context: Context) {
        val app = context.applicationContext
        _changes.update { it + 1 }
        Quick.scope.launch { runCatching { TeamWidget().updateAll(app) } }
    }

    // --- the logos, kept as files for the widget ---------------------------------------------------

    private fun logoPath(context: Context, url: String) = File(File(context.cacheDir, "team-logos").apply { mkdirs() }, "${url.hashCode()}.png")

    private suspend fun logoFile(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        if (url.isEmpty()) return@withContext null
        val file = logoPath(context, url)
        if (!file.exists()) {
            URL(smallLogo(url, 128)).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        file
    }

    fun logo(context: Context, url: String): Bitmap? =
        logoPath(context, url).takeIf { url.isNotEmpty() && it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    // --- the reminder ------------------------------------------------------------------------------

    /** Sets the alarm before the next match, as many minutes before as chosen; none when off. */
    fun arm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java)
        val intent = pending(app)
        alarms.cancel(intent)
        val minutes = settings(app).getInt("teamReminder", 0)
        val match = next(app) ?: return
        if (minutes <= 0 || match.state != "pre" || !match.timeValid) return
        val at = match.at - minutes * 60_000L
        if (at <= System.currentTimeMillis()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, TeamReminderReceiver::class.java).setAction(ACTION_REMINDER),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun remind(context: Context) {
        val match = next(context) ?: return
        if (settings(context).getInt("teamReminder", 0) <= 0 || match.at <= System.currentTimeMillis()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, str(R.string.team_channel), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 7, Intent(context, MainActivity::class.java).putExtra(EXTRA_TAB, OPEN_TEAM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = str(R.string.team_reminder_text, clock(match.at), match.competition)
        manager.notify(
            "team${match.id}".hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${match.home.name} – ${match.away.name}")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    // --- the colors of the card --------------------------------------------------------------------

    /** The band of the team as a picture: its color, darker at the far end, with its logo large and faint. */
    fun band(context: Context, team: FavoriteTeam): Bitmap {
        val base = team.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: 0xFF3F51B5.toInt()
        val bitmap = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 800f, 400f,
            ColorUtils.blendARGB(base, android.graphics.Color.WHITE, 0.08f), ColorUtils.blendARGB(base, android.graphics.Color.BLACK, 0.45f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 800f, 400f, paint)
        logo(context, team.logo)?.let { logo ->
            val faint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 31 }
            canvas.drawBitmap(logo, Rect(0, 0, logo.width, logo.height), RectF(560f, 20f, 920f, 380f), faint)
        }
        return bitmap
    }

    /** A logo on a light disc, readable whatever the color behind it. */
    fun badge(context: Context, url: String): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0FFFFFF.toInt() })
        logo(context, url)?.let { logo ->
            val inset = size / 7f
            canvas.drawBitmap(logo, Rect(0, 0, logo.width, logo.height), RectF(inset, inset, size - inset, size - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return bitmap
    }

    fun onColor(team: FavoriteTeam): Color {
        val base = team.color?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: return Color.White
        return if (ColorUtils.calculateLuminance(base) > 0.55) Color(0xFF15171A) else Color.White
    }
}

/** The next match of the favorite team, as the card at the top of the home shows it. */
class TeamWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by TeamBoard.changes.collectAsState()
            val team = remember(version) { TeamBoard.team(context) }
            val next = remember(version) { TeamBoard.next(context) }
            val rank = remember(version) { TeamBoard.rank(context) }
            val open = actionStartActivity<MainActivity>(actionParametersOf(ActionParameters.Key<String>(EXTRA_TAB) to OPEN_TEAM))
            if (team == null) {
                Box(
                    GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(androidx.glance.GlanceTheme.colors.widgetBackground).padding(16.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(str(R.string.widget_team_none), style = TextStyle(color = androidx.glance.GlanceTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center))
                }
                return@provideContent
            }
            val band = remember(version) { TeamBoard.band(context, team) }
            val on = ColorProvider(TeamBoard.onColor(team))
            Box(
                GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(ImageProvider(band), ContentScale.Crop).clickable(open),
            ) {
                Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(R.drawable.ic_soccer), contentDescription = null, colorFilter = ColorFilter.tint(on), modifier = GlanceModifier.size(20.dp))
                        Text(
                            next?.let { str(if (it.state == "in") R.string.team_live else R.string.team_next) } ?: team.name,
                            maxLines = 1,
                            style = TextStyle(color = on, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.defaultWeight().padding(start = 8.dp),
                        )
                        if (rank != null) Text(rank, maxLines = 1, style = TextStyle(color = on, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                        Image(ImageProvider(R.drawable.ic_chevron), contentDescription = null, colorFilter = ColorFilter.tint(on), modifier = GlanceModifier.size(22.dp))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    if (next == null) {
                        Text(str(R.string.team_no_match), style = TextStyle(color = on, fontSize = 16.sp))
                    } else {
                        Versus(context, next, on)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Versus(context: Context, match: Match, on: ColorProvider) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SideColumn(context, match.home.name, match.home.logo, on, GlanceModifier.defaultWeight())
        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(match.competition, maxLines = 1, style = TextStyle(color = on, fontSize = 12.sp, textAlign = TextAlign.Center))
            if (match.state == "in") {
                Text(
                    "${match.home.score ?: 0} – ${match.away.score ?: 0}",
                    style = TextStyle(color = on, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                )
                Text(listOf(str(R.string.team_live), match.detail).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1, style = TextStyle(color = on, fontSize = 13.sp))
            } else {
                Text(
                    if (match.timeValid) clock(match.at) else "–",
                    style = TextStyle(color = on, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                )
                Text(matchWhen(match).substringBefore(" · "), maxLines = 1, style = TextStyle(color = on, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
            }
        }
        SideColumn(context, match.away.name, match.away.logo, on, GlanceModifier.defaultWeight())
    }
}

@Composable
private fun SideColumn(context: Context, name: String, logo: String, on: ColorProvider, modifier: GlanceModifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(ImageProvider(TeamBoard.badge(context, logo)), contentDescription = null, modifier = GlanceModifier.size(48.dp))
        Spacer(GlanceModifier.height(6.dp))
        Text(name, maxLines = 1, style = TextStyle(color = on, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
    }
}

class TeamWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TeamWidget()

    // Runs when the widget is placed and then about every half hour.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val app = context.applicationContext
        Quick.scope.launch { TeamBoard.refresh(app) }
    }
}

/** The reminder before a match of the team; after a restart of the phone, the alarm set again. */
class TeamReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) TeamBoard.arm(app) else TeamBoard.remind(app)
    }
}
