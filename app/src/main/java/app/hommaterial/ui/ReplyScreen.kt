package app.hommaterial.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.VoiceReply
import app.hommaterial.data.AgentAction
import app.hommaterial.quick.Quick
import app.hommaterial.str
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * What was asked of Alexa or of the assistant and the answer, full screen, to read at leisure or to
 * have read aloud; at once when [readAloud]. What the assistant would do waits here for a yes.
 * The question is a bubble, as in a chat; while the answer comes a ring of colors turns.
 */
@Composable
fun ReplyScreen(reply: VoiceReply, readAloud: Boolean, vm: HomeViewModel) {
    val onDismiss = vm::dismissReply
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf(false) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            // The answers of Alexa are in the language of her account, Italian here.
            val locale = Locale.ITALIAN
            tts.language = locale
            // The most natural voice installed for it, network ones included: they sound the most human.
            tts.voices
                ?.filter { it.locale.language == locale.language && "notInstalled" !in it.features }
                ?.maxByOrNull { it.quality }
                ?.let { tts.voice = it }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { speaking = true }
                override fun onDone(utteranceId: String?) { speaking = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { speaking = false }
            })
            ready = true
        }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    val text = reply.reply.takeIf { !reply.waiting }
    // Read as soon as both the answer and the voice are there.
    LaunchedEffect(reply.id, text, ready) {
        if (text != null && ready && readAloud) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }
    val colors = MaterialTheme.colorScheme
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.85f, 1.1f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = colors.surface) {
            Box(Modifier.fillMaxSize()) {
                // A soft light of the colors of the app at the top, breathing while the answer comes.
                Canvas(Modifier.fillMaxSize()) {
                    val scale = if (reply.waiting) glow else 1f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(colors.primary.copy(alpha = 0.22f), colors.tertiary.copy(alpha = 0.08f), Color.Transparent),
                            center = Offset(size.width / 2, 0f),
                            radius = size.width * scale,
                        ),
                        radius = size.width * scale,
                        center = Offset(size.width / 2, 0f),
                    )
                }
                Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = colors.secondaryContainer) {
                            Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (reply.fromAlexa) Icons.Filled.Speaker else Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                                Text(
                                    if (reply.fromAlexa) "Alexa" else str(R.string.ai_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = str(R.string.close)) }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Said(reply)
                        AnimatedContent(
                            reply.waiting,
                            transitionSpec = { (fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 6 }) togetherWith fadeOut(tween(200)) },
                            label = "answer",
                        ) { waiting ->
                            if (waiting) {
                                Thinking(
                                    if (reply.fromAlexa) Icons.Filled.Speaker else Icons.Filled.AutoAwesome,
                                    str(if (reply.fromAlexa) R.string.alexa_waiting else R.string.ai_thinking),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text ?: str(R.string.alexa_no_reply),
                                        style = if (text != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                                        color = if (text != null) colors.onSurface else colors.onSurfaceVariant,
                                    )
                                    reply.actions.forEachIndexed { i, action -> ActionRow(action, i) }
                                }
                            }
                        }
                    }
                    if (reply.actions.isNotEmpty() && !reply.waiting) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            AppTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(str(R.string.cancel)) }
                            Spacer(Modifier.width(12.dp))
                            AppButton(onClick = vm::confirmAgent, modifier = Modifier.weight(1f)) { Text(str(R.string.ai_confirm)) }
                        }
                    }
                    if (text != null) {
                        AppButton(
                            onClick = {
                                if (speaking) {
                                    tts.stop()
                                    speaking = false
                                } else {
                                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
                                }
                            },
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            if (speaking) Equalizer() else Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                            Text(str(if (speaking) R.string.alexa_stop_reading else R.string.alexa_read), Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** What was said, as a bubble on the right, popping in. */
@Composable
private fun Said(reply: VoiceReply) {
    val pop = remember(reply.id) { Animatable(0f) }
    LaunchedEffect(reply.id) { pop.animateTo(1f, spring(dampingRatio = 0.7f)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp).graphicsLayer {
                alpha = pop.value.coerceIn(0f, 1f)
                translationY = (1 - pop.value) * 16.dp.toPx()
                scaleX = 0.92f + 0.08f * pop.value
                scaleY = 0.92f + 0.08f * pop.value
            },
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(str(R.string.reply_you), style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.7f))
                Text(reply.said, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** While the answer comes: a ring of colors turning around a breathing core, and dots that blink. */
@Composable
private fun Thinking(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "thinking")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "turn")
    val breathe by motion.animateFloat(1f, 0.9f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val dots by motion.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                rotate(turn) {
                    drawCircle(
                        Brush.sweepGradient(listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary.copy(alpha = 0.2f), colors.primary)),
                        radius = size.minDimension / 2 - 3.dp.toPx(),
                        style = Stroke(6.dp.toPx()),
                    )
                }
            }
            Box(
                Modifier
                    .size(88.dp)
                    .scale(breathe)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = 0.75f), colors.primary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = colors.onPrimary, modifier = Modifier.size(40.dp))
            }
        }
        Row(Modifier.padding(top = 28.dp)) {
            Text(label.removeSuffix("…"), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            for (i in 0 until 3) {
                Text(
                    ".",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.alpha(if (dots.toInt() >= i) 1f else 0.2f),
                )
            }
        }
    }
}

/** What the assistant would do, one line each, coming in one after the other. */
@Composable
private fun ActionRow(action: AgentAction, index: Int) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200L + index * 70L)
        appear.animateTo(1f, tween(400))
    }
    Surface(
        shape = corners(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            alpha = appear.value
            translationY = (1 - appear.value) * 12.dp.toPx()
        },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.TaskAlt, null, tint = MaterialTheme.colorScheme.primary)
            Text(agentLabel(action), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** Bars that rise and fall while the answer is read aloud. */
@Composable
private fun Equalizer() {
    val motion = rememberInfiniteTransition(label = "eq")
    Row(Modifier.size(width = 24.dp, height = 18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 4) {
            val level by motion.animateFloat(
                0.3f, 1f, infiniteRepeatable(tween(450 + i * 90, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i",
            )
            Box(Modifier.width(3.dp).fillMaxHeight(level).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary))
        }
    }
}

/** What an action of the assistant does, as a sentence, for the list to confirm. */
fun agentLabel(a: AgentAction): String = when (a.kind) {
    "power" -> str(if (a.on) R.string.voice_on else R.string.voice_off, a.name)
    "run" -> str(R.string.schedule_run, a.name)
    "later" -> str(if (a.on) R.string.ai_later_on else R.string.ai_later_off, a.minutes, a.name)
    "automation" -> str(
        R.string.ai_automation,
        conditionText(a.sensorName, a.trigger, a.threshold),
        str(if (a.key.startsWith(Quick.macroKey(""))) R.string.schedule_run else if (a.on) R.string.voice_on else R.string.voice_off, a.name),
    )
    "list" -> str(R.string.ai_list, a.list, a.items.joinToString(", "))
    else -> str(R.string.ai_event, a.title, a.date.ifEmpty { a.start.replace('T', ' ') })
}
