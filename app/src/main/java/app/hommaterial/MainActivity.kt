package app.hommaterial

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hommaterial.data.ServerSync
import app.hommaterial.quick.EXTRA_TAB
import app.hommaterial.ui.accentScheme
import app.hommaterial.ui.HomeScreen
import app.hommaterial.ui.LocalHaptics
import app.hommaterial.ui.LoginScreen
import app.hommaterial.ui.OnboardingScreen
import app.hommaterial.ui.ProvideHaptics
import app.hommaterial.ui.ReplyScreen
import app.hommaterial.ui.RestScreen
import app.hommaterial.ui.SettingsScreen
import app.hommaterial.ui.UpdateDialog
import app.hommaterial.ui.rememberNow
import app.hommaterial.ui.requestedTab
import java.util.Calendar
import kotlinx.coroutines.delay

private const val SETTINGS_SLIDE_MS = 320
private const val REST_FADE_MS = 600
private const val SERVER_SYNC_MS = 30_000L

class MainActivity : ComponentActivity() {
    private var fullscreen = false
    /** Bumped at every touch: a panel rests only after a while without any. */
    private var touches by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openTab(intent)
        enableEdgeToEdge()
        setContent {
            val vm: HomeViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            val dark = when (state.theme) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            // The icons of the system bars must stay readable on the theme chosen in the app.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // A panel on a wall stays lit, uses the whole screen and never comes back to the app:
            // it asks for the states by itself.
            val monitor = state.monitor.takeIf { it.enabled && state.loggedIn }
            LaunchedEffect(monitor?.keepOn) {
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (monitor?.keepOn == true) window.addFlags(flag) else window.clearFlags(flag)
            }
            LaunchedEffect(monitor?.fullscreen) {
                fullscreen = monitor?.fullscreen == true
                applyFullscreen()
            }
            if (monitor != null) {
                val lifecycle = LocalLifecycleOwner.current
                LaunchedEffect(monitor.refreshSeconds) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            delay(monitor.refreshSeconds * 1000L)
                            vm.poll()
                            vm.checkForUpdate(manual = false)
                        }
                    }
                }
            }
            // On the home server what changed elsewhere, on the web or on other phones, shows up here
            // within half a minute while the app is in sight.
            val server by ServerSync.store.config.collectAsStateWithLifecycle()
            if (server != null) {
                val lifecycle = LocalLifecycleOwner.current
                LaunchedEffect(server) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            runCatching { ServerSync.pull() }
                            delay(SERVER_SYNC_MS)
                        }
                    }
                }
            }
            var settings by rememberSaveable { mutableStateOf(false) }
            // A new sign-in starts from the list, not from where the previous one left.
            if (!state.loggedIn) settings = false
            var resting by remember { mutableStateOf(false) }
            val rests = monitor?.rest == true
            LaunchedEffect(touches, rests, monitor?.restMinutes) {
                resting = false
                if (monitor == null || !rests) return@LaunchedEffect
                delay(monitor.restMinutes * 60_000L)
                // Whoever wakes the panel finds the first page, not the settings someone left open.
                settings = false
                resting = true
            }
            val now by rememberNow(30_000)
            val night = monitor?.night == true && isNight(now, monitor.nightFrom, monitor.nightTo)
            LaunchedEffect(resting, night, monitor?.restBrightness) {
                val percent = monitor?.restBrightness ?: 0
                window.attributes = window.attributes.apply {
                    screenBrightness = when {
                        !resting -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        night -> 0.01f
                        percent == 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        else -> percent / 100f
                    }
                }
            }
            HommaterialTheme(dark, state.accent, haptics = state.haptics, hapticLevel = state.hapticLevel) {
                val haptic = LocalHaptics.current
                LaunchedEffect(vm, haptic) { vm.haptics.collect(haptic) }
                // Every return to the app shows fresh states.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    vm.refresh()
                    vm.checkForUpdate(manual = false)
                }
                Crossfade(resting && monitor != null, animationSpec = tween(REST_FADE_MS), label = "rest") { rest ->
                    if (rest) {
                        RestScreen(state, vm, night, onWake = { resting = false })
                    } else {
                        Screens(state, vm, settings) { settings = it }
                    }
                }
                UpdateDialog(state, vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTab(intent)
    }

    /** The page of the bottom bar a widget asked for, if any. */
    private fun openTab(intent: Intent?) {
        intent?.getStringExtra(EXTRA_TAB)?.let { requestedTab.value = it }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        touches++
    }

    // Dialogs and the keyboard bring the system bars back: they are hidden again on the way back.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) applyFullscreen()
    }

    private fun applyFullscreen() {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** The screen the app is on: the first-run notice, the sign-in, the home or its settings. */
@Composable
private fun Screens(state: UiState, vm: HomeViewModel, settings: Boolean, onSettings: (Boolean) -> Unit) {
    when {
        !state.onboarded -> OnboardingScreen(onAccept = vm::acceptOnboarding)
        state.loggedIn -> {
            AnimatedContent(
                targetState = settings,
                // The settings slide in over the list from the right and leave the same way.
                transitionSpec = {
                    val spec = tween<IntOffset>(SETTINGS_SLIDE_MS)
                    val fade = tween<Float>(SETTINGS_SLIDE_MS)
                    if (targetState) {
                        (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade))
                    } else {
                        (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spec) { it } + fadeOut(fade))
                    }
                },
                label = "settings",
            ) { shown ->
                if (shown) {
                    SettingsScreen(state, vm, onBack = { onSettings(false) })
                } else {
                    HomeScreen(state, vm, onSettings = { onSettings(true) })
                }
            }
            state.reply?.let { ReplyScreen(it, state.readAloud, vm) }
        }
        else -> LoginScreen(state, vm)
    }
}

/** Whether [at] falls in the hours from [from] to [to] o'clock, which usually cross midnight. */
private fun isNight(at: Long, from: Int, to: Int): Boolean {
    val hour = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.HOUR_OF_DAY)
    return if (from <= to) hour in from until to else hour >= from || hour < to
}

@Composable
private fun HommaterialTheme(dark: Boolean, accent: String, haptics: Boolean, hapticLevel: Int, content: @Composable () -> Unit) {
    val colors = accentScheme(accent, dark, LocalContext.current)
    MaterialTheme(colorScheme = colors) {
        // Painted behind every screen: what a sliding screen uncovers is the theme, not the window.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProvideHaptics(haptics, hapticLevel, content)
        }
    }
}
