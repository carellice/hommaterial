package app.hommaterial.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.VoiceReply
import app.hommaterial.R
import app.hommaterial.data.AgentAction
import app.hommaterial.quick.Quick
import app.hommaterial.str
import java.util.Locale

/**
 * What was asked of Alexa or of the assistant and the answer, full screen, to read at leisure or to
 * have read aloud; at once when [readAloud]. What the assistant would do waits here for a yes.
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (reply.fromAlexa) str(R.string.voice_to_alexa, reply.said) else "“${reply.said}”",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = str(R.string.close)) }
                }
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when {
                        reply.waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(
                                str(if (reply.fromAlexa) R.string.alexa_waiting else R.string.ai_thinking),
                                Modifier.padding(start = 16.dp),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        text == null -> Text(str(R.string.alexa_no_reply), style = MaterialTheme.typography.titleLarge)
                        else -> Text(text, style = MaterialTheme.typography.headlineMedium)
                    }
                    for (action in reply.actions) {
                        Text("• " + agentLabel(action), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (reply.actions.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(str(R.string.cancel)) }
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = vm::confirmAgent, modifier = Modifier.weight(1f)) { Text(str(R.string.ai_confirm)) }
                    }
                }
                if (text != null) {
                    Button(
                        onClick = {
                            if (speaking) {
                                tts.stop()
                                speaking = false
                            } else {
                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
                            }
                        },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (speaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Text(str(if (speaking) R.string.alexa_stop_reading else R.string.alexa_read), Modifier.padding(start = 8.dp))
                    }
                }
            }
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
