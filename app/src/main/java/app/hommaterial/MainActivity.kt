package app.hommaterial

import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hommaterial.ui.HomeScreen
import app.hommaterial.ui.LoginScreen
import app.hommaterial.ui.OnboardingScreen
import app.hommaterial.ui.SettingsScreen
import app.hommaterial.ui.UpdateDialog

private const val SETTINGS_SLIDE_MS = 320

class MainActivity : ComponentActivity() {
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
}

@Composable
private fun HommaterialTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
