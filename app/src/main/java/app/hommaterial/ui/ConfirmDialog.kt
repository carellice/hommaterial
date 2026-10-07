package app.hommaterial.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.hommaterial.R
import app.hommaterial.str

/** Asks before doing something that cannot be undone. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
fun SignOutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = str(R.string.sign_out_title),
        text = str(R.string.sign_out_text),
        confirm = str(R.string.sign_out),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
