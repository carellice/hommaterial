package app.hommaterial

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hommaterial.ui.LocalHaptics
import app.hommaterial.ui.ProvideHaptics
import app.hommaterial.ui.ReplyScreen
import app.hommaterial.ui.VoiceDialog
import app.hommaterial.ui.accentScheme
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How long the outcome of a command stays in sight before the panel goes away by itself. */
private const val DONE_MS = 2_200L

/** How long the panel waits for something to show after a command, before going away. */
private const val THINKING_MS = 20_000L

private enum class Phase { LISTENING, THINKING, DONE, FAILED }

/**
 * The digital assistant of the phone: its gesture opens a panel at the bottom of whatever is on
 * screen, already listening. The words appear as they are said, the colors below follow the voice,
 * and the outcome is told there before the panel goes away by itself.
 */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clear = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = clear, navigationBarStyle = clear)
        setContent {
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            // Without an Amazon account there is nothing to command: the app asks to sign in.
            if (!state.loggedIn) {
                LaunchedEffect(Unit) { openApp() }
                return@setContent
            }
            val dark = when (state.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = accentScheme(state.accent, dark, LocalContext.current)) {
                ProvideHaptics(state.haptics, state.hapticLevel) {
                    val haptic = LocalHaptics.current
                    LaunchedEffect(vm, haptic) { vm.haptics.collect(haptic) }
                    AssistPanel(state, vm, onOpenApp = ::openApp, onClosed = ::finish)
                }
            }
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    // A panel left behind would come back listening to nothing: it goes away with the screen.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}

@Composable
private fun AssistPanel(state: UiState, vm: HomeViewModel, onOpenApp: () -> Unit, onClosed: () -> Unit) {
    val context = LocalContext.current
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    // Gone once it has slid away.
    if (!shown.targetState && shown.isIdle) LaunchedEffect(Unit) { onClosed() }
    val close = { shown.targetState = false }
    BackHandler(onBack = close)

    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var heard by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var rms by remember { mutableFloatStateOf(0f) }

    fun onHeard(guesses: List<String>) {
        rms = 0f
        val said = guesses.firstOrNull { it.isNotBlank() }
        if (said == null) {
            phase = Phase.FAILED
            outcome = str(R.string.voice_nothing_heard)
            return
        }
        heard = said
        phase = Phase.THINKING
        vm.onSpeech(guesses)
    }

    fun onFailed(text: String) {
        rms = 0f
        phase = Phase.FAILED
        outcome = text
    }

    // Without the microphone, or a recognizer of its own, the one of the phone listens instead.
    val fallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onHeard(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty())
        } else {
            close()
        }
    }
    val listener = remember {
        Listener(
            context,
            onLevel = { rms = it },
            onPartial = { heard = it },
            onHeard = { onHeard(it) },
            onFailed = { onFailed(it) },
        )
    }
    DisposableEffect(listener) { onDispose { listener.destroy() } }
    val listen: () -> Unit = {
        heard = ""
        outcome = ""
        phase = Phase.LISTENING
        if (listener.available && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listener.start()
        } else {
            try {
                fallback.launch(recognizerIntent())
            } catch (e: ActivityNotFoundException) {
                onFailed(str(R.string.voice_unavailable))
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { listen() }
    LaunchedEffect(Unit) {
        if (listener.available && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            listen()
        }
    }

    // What the command did is told here, as the home would in its bar at the bottom.
    LaunchedEffect(vm) {
        vm.messages.collect {
            // Only what answers the command: a failed refresh meanwhile is not news here.
            if (phase == Phase.THINKING) {
                outcome = it
                phase = Phase.DONE
            }
        }
    }
    val asking = state.voice != null || state.reply != null
    LaunchedEffect(phase, outcome, asking) {
        when {
            asking -> {}
            phase == Phase.DONE -> {
                delay(DONE_MS)
                close()
            }
            phase == Phase.THINKING -> {
                delay(THINKING_MS)
                close()
            }
        }
    }
    // A choice between devices, or an answer of Alexa or of the assistant: once closed, so is the panel.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(asking) {
        if (asking) asked = true else if (asked && phase != Phase.DONE) close()
    }
    state.voice?.let { VoiceDialog(it, vm) }
    state.reply?.let { ReplyScreen(it, state.readAloud, vm) }

    val level by animateFloatAsState(
        // The recognizer gives about -2 dB in silence and 10 dB for a loud voice.
        if (phase == Phase.LISTENING) ((rms + 2f) / 12f).coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "level",
    )
    val visible by animateFloatAsState(if (shown.targetState) 1f else 0f, tween(300), label = "visible")

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f * visible))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        )
        Aura(phase, level, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).alpha(visible))
        AnimatedVisibility(
            shown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = 380f)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
        ) {
            Panel(
                phase = phase,
                heard = heard,
                outcome = outcome,
                prompt = str(if (state.voiceEcho != null) R.string.voice_prompt_alexa else R.string.voice_prompt),
                toAlexa = state.voiceEcho != null,
                level = level,
                onMic = { if (phase == Phase.LISTENING) listener.stop() else listen() },
                onOpenApp = onOpenApp,
                onClose = close,
            )
        }
    }
}

/** The card at the bottom: what is said, what came of it, and the microphone. */
@Composable
private fun Panel(
    phase: Phase,
    heard: String,
    outcome: String,
    prompt: String,
    toAlexa: Boolean,
    level: Float,
    onMic: () -> Unit,
    onOpenApp: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "panel")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(if (phase == Phase.THINKING) 1200 else 4000, easing = LinearEasing)), label = "turn")
    // The ring of colors turns while something happens and rests once it is over.
    val ring by animateFloatAsState(if (phase == Phase.LISTENING || phase == Phase.THINKING) 1f else 0.25f, tween(500), label = "ring")
    val shape = RoundedCornerShape(32.dp)
    val ringColors = listOf(colors.primary, colors.tertiary, colors.secondary, colors.primary.copy(alpha = 0.3f), colors.primary)
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                rotate(turn) {
                    drawCircle(Brush.sweepGradient(ringColors), radius = size.maxDimension, alpha = ring)
                }
            }
            .padding(2.dp),
    ) {
        Surface(shape = RoundedCornerShape(30.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (toAlexa) Icons.Filled.Speaker else Icons.Filled.AutoAwesome,
                        null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (toAlexa) "Alexa" else str(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenApp) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = str(R.string.assist_open_app))
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = str(R.string.close)) }
                }
                Words(phase, heard, outcome, prompt, Modifier.padding(end = 8.dp, top = 4.dp, bottom = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wave(phase, level, Modifier.weight(1f).height(40.dp))
                    Spacer(Modifier.width(16.dp))
                    Mic(phase, level, onMic)
                }
            }
        }
    }
}

/** What was said as it is said, then what came of it. */
@Composable
private fun Words(phase: Phase, heard: String, outcome: String, prompt: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().heightIn(min = 72.dp).animateContentSize(spring(stiffness = 500f))) {
        if (heard.isEmpty() && phase == Phase.LISTENING) {
            Text(str(R.string.assist_listening), style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
            Text(prompt, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        } else if (heard.isNotEmpty()) {
            Text(
                heard,
                style = MaterialTheme.typography.headlineSmall,
                color = if (phase == Phase.LISTENING) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        AnimatedContent(
            phase to outcome,
            transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 3 }) togetherWith fadeOut(tween(150)) },
            label = "outcome",
        ) { (now, text) ->
            when (now) {
                Phase.LISTENING -> Spacer(Modifier.height(0.dp))
                Phase.THINKING -> Dots(Modifier.padding(top = 10.dp))
                Phase.DONE, Phase.FAILED -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val done = now == Phase.DONE
                    Icon(
                        if (done) Icons.Outlined.TaskAlt else Icons.Outlined.ErrorOutline,
                        null,
                        tint = if (done) colors.primary else colors.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (done) colors.primary else colors.error,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/** Three dots that rise one after the other while the command is worked out. */
@Composable
private fun Dots(modifier: Modifier) {
    val motion = rememberInfiniteTransition(label = "dots")
    val t by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "t")
    Row(modifier.height(22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
        for (i in 0 until 3) {
            val wave = sin(((t - i * 0.15f) * 2 * PI).toFloat()).coerceAtLeast(0f)
            Box(
                Modifier
                    .padding(bottom = (wave * 8).dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colors[i]),
            )
        }
    }
}

/** Bars that follow the voice while listening, and breathe slowly while the command is worked out. */
@Composable
private fun Wave(phase: Phase, level: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "wave")
    val t by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "t")
    val active by animateFloatAsState(if (phase == Phase.LISTENING || phase == Phase.THINKING) 1f else 0f, tween(400), label = "active")
    val brush = Brush.horizontalGradient(listOf(colors.primary, colors.tertiary, colors.secondary))
    Canvas(modifier) {
        val bars = 24
        val gap = 4.dp.toPx()
        val width = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(2f)
        for (i in 0 until bars) {
            // Highest in the middle, as a voice is drawn.
            val middle = sin(PI * (i + 0.5) / bars).toFloat()
            val ripple = (sin(((t + i / bars.toFloat()) * 2 * PI).toFloat()) + 1) / 2
            val idle = 0.12f + 0.18f * ripple * active
            val h = size.height * (idle + (0.25f + 0.75f * ripple) * level * middle).coerceIn(0.08f, 1f)
            val x = i * (width + gap)
            drawRoundRect(
                brush,
                topLeft = Offset(x, (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2),
                alpha = 0.35f + 0.65f * active,
            )
        }
    }
}

/** The microphone: a halo grows with the voice; a tap stops listening, or listens again. */
@Composable
private fun Mic(phase: Phase, level: Float, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val listening = phase == Phase.LISTENING
    val motion = rememberInfiniteTransition(label = "mic")
    val breathe by motion.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val halo = if (listening) breathe + level * 0.45f else 1f
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(56.dp)
                .scale(halo)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = if (listening) 0.22f else 0f)),
        )
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (listening) colors.primary else colors.primaryContainer)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = str(if (listening) R.string.voice_button else R.string.assist_again),
                tint = if (listening) colors.onPrimary else colors.onPrimaryContainer,
            )
        }
    }
}

/** Soft lights of the colors of the app rising from the bottom of the screen, higher as the voice grows. */
@Composable
private fun Aura(phase: Phase, level: Float, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberInfiniteTransition(label = "aura")
    val drift by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "drift")
    val rest by animateFloatAsState(if (phase == Phase.DONE || phase == Phase.FAILED) 0.55f else 1f, tween(600), label = "rest")
    val lights = listOf(colors.primary, colors.tertiary, colors.secondary, colors.inversePrimary)
    Canvas(modifier) {
        lights.forEachIndexed { i, color ->
            val angle = (drift + i / lights.size.toFloat()) * 2 * PI
            val x = size.width * (0.5f + 0.38f * sin(angle).toFloat())
            val radius = size.width * (0.45f + 0.12f * (i % 2)) * (0.8f + 0.5f * level) * rest
            val center = Offset(x, size.height + radius * 0.25f - size.height * 0.15f * level)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent), center, radius),
                radius = radius,
                center = center,
            )
        }
    }
}

private fun recognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, str(R.string.voice_language))
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

/** The speech recognizer of the phone, heard from inside the panel: the words come as they are said. */
private class Listener(
    context: Context,
    onLevel: (Float) -> Unit,
    onPartial: (String) -> Unit,
    onHeard: (List<String>) -> Unit,
    onFailed: (String) -> Unit,
) {
    val available = SpeechRecognizer.isRecognitionAvailable(context)
    private val recognizer = if (available) SpeechRecognizer.createSpeechRecognizer(context) else null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onPartial)
            }
            override fun onResults(results: Bundle?) =
                onHeard(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
            override fun onError(error: Int) = onFailed(
                str(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_nothing_heard
                        else -> R.string.voice_unavailable
                    },
                ),
            )
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun start() = recognizer?.startListening(recognizerIntent())

    /** Stops listening: what was said so far is taken as said. */
    fun stop() = recognizer?.stopListening()

    fun destroy() = recognizer?.destroy()
}
