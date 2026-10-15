package app.hommaterial.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView

/** What the phone answers a touch with: each kind of action has its own short vibration. */
enum class Haptic {
    /** Something switched on. */
    On,

    /** Something switched off. */
    Off,

    /** Something done or set: a custom device that ran, a timer started, a tag written. */
    Confirm,

    /** Something refused: a command that did not go through, a wrong PIN. */
    Reject,

    /** A step along a slider, or a choice among several. */
    Tick,

    /** A key pressed: a remote, a command sent off. */
    Key,
}

/** Plays a [Haptic]; does nothing where the vibrations are turned off. */
internal val LocalHaptics = staticCompositionLocalOf<(Haptic) -> Unit> { {} }

/**
 * Gives the screens below the vibrations of the app, unless [enabled] is false: then also the ones
 * Compose plays by itself, such as holding a tile, stay quiet.
 */
@Composable
internal fun ProvideHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    val play = remember(view, enabled) {
        { haptic: Haptic -> if (enabled) view.performHapticFeedback(haptic.constant()) }
    }
    val compose = if (enabled) LocalHapticFeedback.current else Silent
    CompositionLocalProvider(LocalHaptics provides play, LocalHapticFeedback provides compose, content = content)
}

private object Silent : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

// The richer feedbacks of newer Android versions, and the closest older one elsewhere.
private fun Haptic.constant(): Int = when (this) {
    Haptic.On -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.Off -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
    Haptic.Confirm -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    Haptic.Reject -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    Haptic.Tick -> if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    Haptic.Key -> HapticFeedbackConstants.VIRTUAL_KEY
}
