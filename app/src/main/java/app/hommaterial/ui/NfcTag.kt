package app.hommaterial.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.hommaterial.R
import app.hommaterial.quick.NfcResult
import app.hommaterial.quick.nfcUri
import app.hommaterial.quick.writeTag
import app.hommaterial.str

// Every kind of tag there is: which one comes along is not known in advance.
private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V

/** Whether the phone has NFC at all: without it there is no tag to write. */
@Composable
internal fun rememberHasNfc(): Boolean {
    val context = LocalContext.current
    return remember(context) { NfcAdapter.getDefaultAdapter(context) != null }
}

/**
 * Writes a tag for [name], [id] of a [kind]. With [canSwitch] it asks first whether the tag
 * switches on, off or either; then it waits for the tag.
 */
@Composable
internal fun NfcTagDialog(kind: String, id: String, name: String, canSwitch: Boolean, onDismiss: () -> Unit) {
    // Empty while the direction is still to choose; a custom device only runs.
    var uri by remember { mutableStateOf(if (canSwitch) null else nfcUri(kind, id)) }
    val chosen = uri
    if (chosen == null) {
        val choices = listOf(R.string.nfc_toggle, R.string.nfc_on, R.string.nfc_off).map { str(it) to null }
        ListDialog(str(R.string.nfc_what), choices, onDismiss = onDismiss) { i ->
            uri = nfcUri(kind, id, if (i == 0) null else i == 1)
        }
    } else {
        TagWaitDialog(chosen, name, onDismiss)
    }
}

/** Waits for a tag and writes [uri] on it; meanwhile the phone reads tags for nothing else. */
@Composable
private fun TagWaitDialog(uri: Uri, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    var result by remember { mutableStateOf<NfcResult?>(null) }
    var enabled by remember { mutableStateOf(adapter?.isEnabled == true) }
    // NFC turned on in the settings counts as soon as the user comes back.
    LifecycleResumeEffect(adapter) {
        enabled = adapter?.isEnabled == true
        onPauseOrDispose { }
    }
    val written = result?.written == true
    DisposableEffect(activity, adapter, enabled, written) {
        if (activity == null || adapter == null || !enabled || written) return@DisposableEffect onDispose { }
        val main = Handler(Looper.getMainLooper())
        adapter.enableReaderMode(
            activity,
            { tag ->
                val outcome = writeTag(tag, uri, context.packageName)
                main.post { result = outcome }
            },
            READER_FLAGS,
            null,
        )
        onDispose { adapter.disableReaderMode(activity) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.nfc_title)) },
        text = {
            Column {
                when {
                    !enabled -> Text(str(R.string.nfc_disabled))
                    written -> {
                        Text(str(R.string.nfc_written, name))
                        Text(
                            str(R.string.nfc_note),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    else -> {
                        Text(str(R.string.nfc_hold))
                        LinearProgressIndicator(Modifier.padding(top = 16.dp))
                        result?.let {
                            Text(
                                str(it.message),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                !enabled -> TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }) { Text(str(R.string.nfc_settings)) }
                written -> TextButton(onClick = onDismiss) { Text(str(R.string.nfc_done)) }
                else -> Unit
            }
        },
        dismissButton = if (written) null else {
            { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
