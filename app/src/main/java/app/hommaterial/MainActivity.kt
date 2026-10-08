package app.hommaterial

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.hommaterial.ui.HomeScreen
import app.hommaterial.ui.LoginScreen
import app.hommaterial.ui.OnboardingScreen
import app.hommaterial.ui.SettingsScreen
import app.hommaterial.ui.UpdateDialog
import kotlinx.coroutines.delay

private const val SETTINGS_SLIDE_MS = 320

class MainActivity : ComponentActivity() {
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            HommaterialTheme(dark) {
                // Every return to the app shows fresh states.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    vm.refresh()
                    vm.checkForUpdate(manual = false)
                }
                var settings by rememberSaveable { mutableStateOf(false) }
                // A new sign-in starts from the list, not from where the previous one left.
                if (!state.loggedIn) settings = false
                when {
                    !state.onboarded -> OnboardingScreen(onAccept = vm::acceptOnboarding)
                    state.loggedIn -> AnimatedContent(
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
                            SettingsScreen(state, vm, onBack = { settings = false })
                        } else {
                            HomeScreen(state, vm, onSettings = { settings = true })
                        }
                    }
                    else -> LoginScreen(state, vm)
                }
                UpdateDialog(state, vm)
            }
        }
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

@Composable
private fun HommaterialTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        // Painted behind every screen: what a sliding screen uncovers is the theme, not the window.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
