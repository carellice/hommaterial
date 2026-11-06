package app.hommaterial.ui

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.hommaterial.AlexaReply
import app.hommaterial.R
import app.hommaterial.str
import java.util.Locale

/** What was said to Alexa and her answer, full screen, to read at leisure or to have read aloud. */
@Composable
fun AlexaReplyScreen(reply: AlexaReply, onDismiss: () -> Unit) {
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        str(R.string.voice_to_alexa, reply.said),
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
                            Text(str(R.string.alexa_waiting), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        reply.reply == null -> Text(str(R.string.alexa_no_reply), style = MaterialTheme.typography.titleLarge)
                        else -> Text(reply.reply, style = MaterialTheme.typography.headlineMedium)
                    }
                }
                val text = reply.reply
                if (text != null) {
                    Button(
                        onClick = {
                            if (speaking) {
                                tts.stop()
                                speaking = false
                            } else {
                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alexa")
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
