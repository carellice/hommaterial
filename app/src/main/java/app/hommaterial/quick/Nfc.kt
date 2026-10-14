package app.hommaterial.quick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.annotation.StringRes
import app.hommaterial.R

// hommaterial://nfc/<kind>/<id>[/on|off]: what a tag written by the app holds.
private const val SCHEME = "hommaterial"
private const val HOST = "nfc"
internal const val NFC_DEVICE = "device"
internal const val NFC_GROUP = "group"
internal const val NFC_MACRO = "macro"
internal const val NFC_ON = "on"
internal const val NFC_OFF = "off"

/** What a tag asks for: a device or group switched [power] (toggled without it), or a custom device run. */
internal fun nfcUri(kind: String, id: String, power: Boolean? = null): Uri = Uri.Builder()
    .scheme(SCHEME)
    .authority(HOST)
    .appendPath(kind)
    .appendPath(id)
    .apply { power?.let { appendPath(if (it) NFC_ON else NFC_OFF) } }
    .build()

/** Writes [uri] on [tag], with the record that makes Android open this app for it, and says how it went. */
internal fun writeTag(tag: Tag, uri: Uri, packageName: String): NfcResult {
    val message = NdefMessage(NdefRecord.createUri(uri), NdefRecord.createApplicationRecord(packageName))
    return try {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                when {
                    !it.isWritable -> NfcResult(false, R.string.nfc_read_only)
                    it.maxSize < message.byteArrayLength -> NfcResult(false, R.string.nfc_too_small)
                    else -> {
                        it.writeNdefMessage(message)
                        NfcResult(true, R.string.nfc_written)
                    }
                }
            }
        } else {
            // A blank tag first has to be given the format that holds a message.
            val blank = NdefFormatable.get(tag) ?: return NfcResult(false, R.string.nfc_unsupported)
            blank.use {
                it.connect()
                it.format(message)
            }
            NfcResult(true, R.string.nfc_written)
        }
    } catch (e: Exception) {
        // The tag moved away halfway, or did not take the message.
        NfcResult(false, R.string.nfc_failed)
    }
}

internal class NfcResult(val written: Boolean, @StringRes val message: Int)

/**
 * Opened by Android when the phone touches a tag the app wrote, even with the app closed: passes
 * what the tag asks for to the same receiver as the shortcuts, and never shows itself.
 */
class NfcActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val segments = intent.data?.takeIf { it.scheme == SCHEME && it.host == HOST }?.pathSegments.orEmpty()
        val kind = segments.getOrNull(0)
        val id = segments.getOrNull(1)
        if (id != null) {
            val toggle = Intent(this, ToggleReceiver::class.java)
            when (kind) {
                NFC_DEVICE -> toggle.putExtra(EXTRA_APPLIANCE_ID, id)
                NFC_GROUP -> toggle.putExtra(EXTRA_GROUP_ID, id)
                NFC_MACRO -> toggle.putExtra(EXTRA_MACRO_ID, id)
                else -> null
            }?.let {
                when (segments.getOrNull(2)) {
                    NFC_ON -> it.putExtra(EXTRA_POWER, true)
                    NFC_OFF -> it.putExtra(EXTRA_POWER, false)
                }
                sendBroadcast(it)
            }
        }
        finish()
    }
}
