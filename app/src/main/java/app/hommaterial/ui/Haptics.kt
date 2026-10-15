package app.hommaterial.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.roundToInt

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

    /** A tile held until its panel opens. */
    Hold,
}

/** How strong the vibrations are at first, in percent, and the weakest they can be made. */
const val HAPTIC_LEVEL_DEFAULT = 70
const val HAPTIC_LEVEL_MIN = 10

/** Plays a [Haptic]; does nothing where the vibrations are turned off. */
internal val LocalHaptics = staticCompositionLocalOf<(Haptic) -> Unit> { {} }

/**
 * Gives the screens below the vibrations of the app at [level] percent, unless [enabled] is false.
 * The ones Compose plays by itself, such as holding a tile, go the same way.
 */
@Composable
internal fun ProvideHaptics(enabled: Boolean, level: Int, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val vibrations = remember(context) { Vibrations(context) }
    val play = remember(vibrations, enabled, level) {
        { haptic: Haptic -> if (enabled) vibrations.play(haptic, level) }
    }
    val compose = remember(play) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = play(hapticFeedbackType.haptic())
        }
    }
    CompositionLocalProvider(LocalHaptics provides play, LocalHapticFeedback provides compose, content = content)
}

private fun HapticFeedbackType.haptic(): Haptic = when (this) {
    HapticFeedbackType.LongPress -> Haptic.Hold
    HapticFeedbackType.ToggleOn -> Haptic.On
    HapticFeedbackType.ToggleOff -> Haptic.Off
    HapticFeedbackType.Confirm -> Haptic.Confirm
    HapticFeedbackType.Reject -> Haptic.Reject
    HapticFeedbackType.TextHandleMove, HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentFrequentTick -> Haptic.Tick
    else -> Haptic.Key
}

/**
 * Drawn by the app rather than taken from the system: the touch feedback of Android is faint and
 * cannot be made stronger, these follow the level chosen in the settings.
 */
internal class Vibrations(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    fun play(haptic: Haptic, level: Int) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        val strength = level.coerceIn(0, 100) / 100f
        if (strength <= 0f) return
        vibrator.vibrate(haptic.effect(strength, vibrator.hasAmplitudeControl()))
    }
}

/** A buzz of [ms] milliseconds at [power] of the chosen level, [after] milliseconds of stillness. */
private class Pulse(val ms: Long, val power: Float, val after: Long = 0)

private fun Haptic.pulses(): List<Pulse> = when (this) {
    Haptic.On -> listOf(Pulse(26, 1f))
    Haptic.Off -> listOf(Pulse(18, 0.6f))
    Haptic.Confirm -> listOf(Pulse(18, 0.6f), Pulse(28, 1f, after = 70))
    Haptic.Reject -> listOf(Pulse(36, 1f), Pulse(36, 1f, after = 80))
    Haptic.Tick -> listOf(Pulse(8, 0.5f))
    Haptic.Key -> listOf(Pulse(14, 0.8f))
    Haptic.Hold -> listOf(Pulse(32, 0.9f))
}

private fun Haptic.effect(strength: Float, amplitude: Boolean): VibrationEffect {
    val timings = mutableListOf<Long>()
    val amplitudes = mutableListOf<Int>()
    for (pulse in pulses()) {
        timings += pulse.after
        amplitudes += 0
        // A stronger level also lasts a little longer: where the motor cannot change its force,
        // the length is all there is.
        val scale = if (amplitude) 0.8f + 0.5f * strength else 0.4f + 1.2f * strength
        timings += (pulse.ms * scale).roundToInt().coerceAtLeast(4).toLong()
        amplitudes += (255 * pulse.power * strength).roundToInt().coerceIn(1, 255)
    }
    return VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
}
