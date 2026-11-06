package app.hommaterial.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.WakeWord
import app.hommaterial.str
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the microphone open while the screen is in front and calls [onWake] when the wake word is
 * said. It closes before calling, so that the speech recognizer has the microphone to itself, and
 * opens again when the screen comes back.
 */
@Composable
fun WakeListener(model: WakeWord.Model, sensitivity: Int, onWake: () -> Unit) {
    val context = LocalContext.current
    val wake by rememberUpdatedState(onWake)
    LifecycleResumeEffect(model, sensitivity) {
        val main = Handler(Looper.getMainLooper())
        val detector = WakeWord.Detector(model, sensitivity)
        val fired = AtomicBoolean(false)
        var microphone: WakeWord.Microphone? = null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            microphone = runCatching {
                WakeWord.Microphone { frames ->
                    if (detector.feed(frames) && fired.compareAndSet(false, true)) {
                        main.post {
                            microphone?.close()
                            wake()
                        }
                    }
                }
            }.getOrNull()
        }
        onPauseOrDispose { microphone?.close() }
    }
}

/** Asks for the microphone when needed, then does [then]. */
@Composable
private fun rememberMicrophoneRequest(then: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val after by rememberUpdatedState(then)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) after() }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            after()
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** The settings of the wake word, under those of the voice. */
@Composable
fun WakeSettings(state: UiState, vm: HomeViewModel) {
    var teaching by remember { mutableStateOf(false) }
    val teach = rememberMicrophoneRequest { teaching = true }
    val enable = rememberMicrophoneRequest { vm.setWakeWord(true) }
    val taught = state.wakeModel != null
    if (teaching) WakeTeachDialog(state, vm, onDismiss = { teaching = false })
    Toggle(R.string.wake_word, R.string.wake_word_text, state.wakeWord && taught) { on ->
        when {
            !on -> vm.setWakeWord(false)
            taught -> enable()
            else -> teach()
        }
    }
    Setting(
        title = str(R.string.wake_teach),
        text = str(if (taught) R.string.wake_teach_again else R.string.wake_teach_text),
        onClick = teach,
    )
    if (taught) {
        Chips(
            R.string.wake_sensitivity,
            listOf(0 to str(R.string.wake_sensitivity_low), 1 to str(R.string.wake_sensitivity_normal), 2 to str(R.string.wake_sensitivity_high)),
            state.wakeSensitivity,
            vm::setWakeSensitivity,
        )
    }
}

/** One recording: from a little before the voice starts to half a second of quiet after it. */
private class Recorder {
    private val before = ArrayDeque<WakeWord.Frame>()
    private val taken = mutableListOf<WakeWord.Frame>()
    private var quiet = 0

    fun feed(frame: WakeWord.Frame): List<WakeWord.Frame>? {
        val loud = frame.rel > WakeWord.SPEECH_DB
        if (taken.isEmpty()) {
            before.addLast(frame)
            if (before.size > 30) before.removeFirst()
            if (loud) taken += before
            return null
        }
        taken += frame
        quiet = if (loud) 0 else quiet + 1
        if (quiet < 50 && taken.size < 400) return null
        return taken.toList().also { taken.clear(); before.clear(); quiet = 0 }
    }
}

/**
 * Teaches the wake word: the user says it [WakeWord.SAMPLES] times, each recording is checked, a
 * sample unlike the others is asked again, and then the word can be tried out at once.
 */
@Composable
private fun WakeTeachDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val samples = remember { mutableStateListOf<List<WakeWord.Frame>?>().apply { repeat(WakeWord.SAMPLES) { add(null) } } }
    var message by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var detector by remember { mutableStateOf<WakeWord.Detector?>(null) }
    var heard by remember { mutableIntStateOf(0) }
    val frames = remember { Channel<List<WakeWord.Frame>>(Channel.UNLIMITED) }
    var broken by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val microphone = runCatching { WakeWord.Microphone { frames.trySend(it) } }.getOrNull()
        if (microphone == null) broken = true
        onDispose { microphone?.close() }
    }
    LaunchedEffect(Unit) {
        val recorder = Recorder()
        for (batch in frames) {
            batch.lastOrNull()?.let { level = (it.rel / 40).toFloat().coerceIn(0f, 1f) }
            detector?.let { if (it.feed(batch)) heard++ }
            if (detector != null) continue
            for (frame in batch) {
                val recording = recorder.feed(frame) ?: continue
                val index = samples.indexOf(null)
                if (index < 0) break
                val trimmed = WakeWord.trim(recording)
                if (trimmed == null) {
                    message = str(R.string.wake_unclear)
                    continue
                }
                samples[index] = trimmed
                message = null
                if (samples.any { it == null }) continue
                when (val taught = WakeWord.train(samples.map { it!! })) {
                    is WakeWord.Taught.Again -> {
                        samples[taught.index] = null
                        message = str(R.string.wake_different, taught.index + 1)
                    }
                    is WakeWord.Taught.Done -> {
                        vm.setWakeModel(taught.model)
                        detector = WakeWord.Detector(taught.model, state.wakeSensitivity)
                    }
                }
            }
        }
    }

    val testing = detector != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.wake_teach)) },
        text = {
            Column {
                when {
                    broken -> Text(str(R.string.wake_no_microphone))
                    testing -> {
                        Text(str(R.string.wake_try))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (heard == 0) str(R.string.wake_try_waiting) else str(R.string.wake_try_heard, heard),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (heard == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> {
                        Text(str(R.string.wake_say))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            str(R.string.wake_sample, samples.count { it != null } + 1, WakeWord.SAMPLES),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (!broken) {
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (testing) TextButton(onClick = onDismiss) { Text(str(R.string.wake_done)) }
        },
        dismissButton = {
            if (!testing) TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
        },
    )
}
