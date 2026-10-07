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
import app.hommaterial.UiState

/** Offers the update found on GitHub and follows its download. */
@Composable
fun UpdateDialog(state: UiState, vm: HomeViewModel) {
    val update = state.update ?: return
    val progress = state.updateProgress
    AlertDialog(
        // While downloading the dialog only closes through "Annulla".
        onDismissRequest = { if (progress == null) vm.dismissUpdate() },
        title = { Text("Aggiornamento disponibile") },
        text = {
            Column {
                val size = if (update.sizeBytes > 0) " (%.1f MB)".format(update.sizeBytes / 1_000_000.0) else ""
                Text("Hommaterial ${update.version}$size. Hai la versione ${vm.installedVersion}.")
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (progress == null) TextButton(onClick = vm::installUpdate) { Text("Aggiorna") }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissUpdate) { Text(if (progress == null) "Più tardi" else "Annulla") }
        },
    )
}
