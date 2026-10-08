package app.hommaterial.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

// Between one sentence and the next the recognizer needs a moment to let go of the microphone.
private const val RESTART_MS = 300L
// After a real failure, such as a recognizer that is busy or gone, there is no point in insisting:
// each one in a row waits twice as long, up to a minute.
private const val RETRY_MS = 3_000L
private const val RETRY_MAX_MS = 60_000L

/**
 * Keeps the speech recognizer of the device listening, one sentence after another, and passes on
 * what it hears. Android has no wake word for apps: this is the closest to one, and it lasts only
 * while the app is on the screen. To be used from the main thread.
 */
class WakeListener(
    private val context: Context,
    private val language: String,
    private val onHeard: (List<String>) -> Unit,
) : RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    // The recognizer that works on the device is preferred: what is said at home stays there.
    private var local = onDevice(context)
    private var failures = 0

    fun start() {
        if (recognizer != null) return
        recognizer = create()?.also { it.setRecognitionListener(this) }
        listen()
    }

    private fun create(): SpeechRecognizer? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && local ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
        else -> null
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        recognizer?.startListening(intent)
    }

    override fun onResults(results: Bundle) {
        failures = 0
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.let(onHeard)
        handler.postDelayed(::listen, RESTART_MS)
    }

    override fun onError(error: Int) {
        // The recognizer on the device may not have the language: the ordinary one takes over.
        val noLanguage = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        if (local && noLanguage) {
            local = false
            stop()
            start()
            return
        }
        // Silence is not a failure: it is what a recognizer left listening hears most of the time.
        val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        failures = if (quiet) 0 else failures + 1
        val wait = if (quiet) RESTART_MS else (RETRY_MS shl (failures - 1).coerceAtMost(5)).coerceAtMost(RETRY_MAX_MS)
        handler.postDelayed(::listen, wait)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        fun available(context: Context): Boolean = onDevice(context) || SpeechRecognizer.isRecognitionAvailable(context)

        private fun onDevice(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }
}
