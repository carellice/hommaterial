package app.hommaterial.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import java.util.Locale
import kotlinx.coroutines.delay

/** The current time, read again every [periodMs] for as long as something shows it. */
@Composable
fun rememberNow(periodMs: Long = 1_000): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs)
    }
}

/** The day as "giovedì 8 ottobre", in the order and words of the language in use. */
fun dateText(at: Long): String =
    DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), at).toString()
