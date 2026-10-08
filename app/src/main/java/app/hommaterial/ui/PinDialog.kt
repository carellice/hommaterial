package app.hommaterial.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.hommaterial.R
import app.hommaterial.str

private const val PIN_MIN = 4
private const val PIN_MAX = 8

/**
 * Asks for a PIN, to check it or to choose a new one. [onSubmit] says whether the digits were
 * taken; when they were not, the dialog stays open and says the PIN is wrong.
 */
@Composable
fun PinDialog(title: String, hint: String? = null, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val submit = {
        if (typed.length >= PIN_MIN && !onSubmit(typed)) {
            wrong = true
            typed = ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { text ->
                    typed = text.filter { it.isDigit() }.take(PIN_MAX)
                    wrong = false
                },
                label = { Text(str(R.string.pin_label)) },
                supportingText = (if (wrong) str(R.string.pin_wrong) else hint)?.let { { Text(it) } },
                isError = wrong,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = typed.length >= PIN_MIN) { Text(str(R.string.pin_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
