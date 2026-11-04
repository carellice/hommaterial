package app.hommaterial.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.ServerSync
import app.hommaterial.quick.clock
import app.hommaterial.str

/**
 * The section of the settings about the home server: off, the form that links it; on, how the
 * sync stands, a sync now and the way back to the phone alone.
 */
@Composable
internal fun ServerSettings(state: UiState, vm: HomeViewModel) {
    val config by ServerSync.store.config.collectAsStateWithLifecycle()
    val sync by ServerSync.status.collectAsStateWithLifecycle()
    var linking by rememberSaveable { mutableStateOf(false) }
    var unlinking by rememberSaveable { mutableStateOf(false) }
    // Signed in: the form is done with, and the choice of the data comes next.
    LaunchedEffect(config) { if (config != null) linking = false }
    if (linking || state.serverBusy && config == null) ServerLinkDialog(state, vm, onDismiss = { linking = false })
    if (state.serverChoice) ServerChoiceDialog(vm)
    if (unlinking) ServerUnlinkDialog(sync.pending, vm, onDismiss = { unlinking = false })
    val linked = config
    if (linked == null) {
        Setting(title = str(R.string.server_link), text = str(R.string.server_off), onClick = { linking = true })
        return
    }
    val host = linked.url.substringAfter("://")
    Setting(title = str(R.string.server_on, linked.username, host))
    val status = when {
        sync.error != null -> sync.error
        sync.pending > 0 -> str(R.string.server_pending, sync.pending)
        sync.syncedAt != null -> str(R.string.server_synced, clock(sync.syncedAt!!))
        else -> null
    }
    Setting(title = str(R.string.server_sync_now), text = status, onClick = vm::syncServer)
    Setting(title = str(R.string.server_unlink), onClick = { unlinking = true })
}

/** The address of the server and the account: one already there, or a new one with the family code. */
@Composable
internal fun ServerLinkDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(ServerSync.store.lastUrl) }
    var username by rememberSaveable { mutableStateOf(ServerSync.store.lastUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var invite by rememberSaveable { mutableStateOf("") }
    var register by rememberSaveable { mutableStateOf(false) }
    val busy = state.serverBusy
    val ready = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty() && !busy
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(str(R.string.server_link)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str(R.string.server_link_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(str(R.string.server_address)) },
                    placeholder = { Text("casaos.tail1234.ts.net") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = if (url.startsWith("http://", ignoreCase = true)) {
                        { Text(str(R.string.server_http_warning)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text(str(R.string.server_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(str(R.string.server_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (register) {
                    OutlinedTextField(
                        value = invite,
                        onValueChange = { invite = it.trim() },
                        label = { Text(str(R.string.server_invite)) },
                        supportingText = { Text(str(R.string.server_invite_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { register = !register }, enabled = !busy) {
                    Text(str(if (register) R.string.server_have_account else R.string.server_new_account))
                }
                state.serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(str(R.string.server_connecting))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.linkServer(url, username, password, register, invite) }, enabled = ready) {
                Text(str(R.string.server_connect))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(str(R.string.cancel)) } },
    )
}

/** Linked, with data on both sides: whose to keep. */
@Composable
internal fun ServerChoiceDialog(vm: HomeViewModel) {
    AlertDialog(
        onDismissRequest = vm::cancelServerLink,
        title = { Text(str(R.string.server_choose_title)) },
        text = { Text(str(R.string.server_choose_text)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.chooseServerData(phone = false) }) { Text(str(R.string.server_use_server)) }
                TextButton(onClick = { vm.chooseServerData(phone = true) }) { Text(str(R.string.server_use_phone)) }
                TextButton(onClick = vm::cancelServerLink) { Text(str(R.string.cancel)) }
            }
        },
    )
}

/** Back to the phone alone: with the copy of the server, or with what the phone had before. */
@Composable
private fun ServerUnlinkDialog(pending: Int, vm: HomeViewModel, onDismiss: () -> Unit) {
    val text = remember(pending) {
        str(R.string.server_unlink_text) + if (pending > 0) "\n\n" + str(R.string.server_unlink_pending, pending) else ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.server_unlink_title)) },
        text = { Text(text) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.unlinkServer(restore = false); onDismiss() }) { Text(str(R.string.server_keep)) }
                TextButton(onClick = { vm.unlinkServer(restore = true); onDismiss() }) { Text(str(R.string.server_restore)) }
                TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) }
            }
        },
        modifier = Modifier.padding(0.dp),
    )
}
