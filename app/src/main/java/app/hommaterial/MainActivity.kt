package app.hommaterial

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hommaterial.ui.HomeScreen
import app.hommaterial.ui.LoginScreen
import app.hommaterial.ui.OnboardingScreen
import app.hommaterial.ui.SettingsScreen
import app.hommaterial.ui.UpdateDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HommaterialTheme {
                val vm: HomeViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
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
                    state.loggedIn && settings -> SettingsScreen(state, vm, onBack = { settings = false })
                    state.loggedIn -> HomeScreen(state, vm, onSettings = { settings = true })
                    else -> LoginScreen(state, vm)
                }
                UpdateDialog(state, vm)
            }
        }
    }
}

@Composable
private fun HommaterialTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
