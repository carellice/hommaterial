package app.hommaterial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.str

/** Offers the update found on GitHub and follows its download. */
@Composable
fun UpdateDialog(state: UiState, vm: HomeViewModel) {
    val update = state.update ?: return
    val progress = state.updateProgress
    AlertDialog(
        // While downloading the dialog only closes through "Annulla".
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        title = { Text(str(R.string.update_title)) },
        text = {
            Column {
                val size = if (update.sizeBytes > 0) str(R.string.update_size, update.sizeBytes / 1_000_000.0) else ""
                Text(str(R.string.update_text, update.version, size, vm.installedVersion))
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (progress == null) TextButton(onClick = vm::installUpdate) { Text(str(R.string.update_now)) }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissUpdate) {
                Text(str(if (progress == null) R.string.update_later else R.string.cancel))
            }
        },
    )
}
