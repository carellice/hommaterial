package app.hommaterial.quick

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.content.IntentCompat
import app.hommaterial.R
import app.hommaterial.data.Cache
import java.security.MessageDigest

// hommaterial://nfc/<kind>/<id>[/on|off]: what a tag written by the app holds.
private const val SCHEME = "hommaterial"
private const val HOST = "nfc"
internal const val NFC_DEVICE = "device"
internal const val NFC_GROUP = "group"
internal const val NFC_MACRO = "macro"
internal const val NFC_ON = "on"
internal const val NFC_OFF = "off"

// Marks a short code in place of the id: Alexa ids run to hundreds of characters, too many for small tags.
private const val CODE = "~"

/** The short code a tag holds for [id]: 40 bits of its hash, plenty to tell one home's devices apart. */
internal fun nfcCode(id: String): String =
    MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(5).joinToString("") { "%02x".format(it) }

/** What a tag asks for: a device or group switched [power] (toggled without it), or a custom device run. */
internal fun nfcUri(kind: String, id: String, power: Boolean? = null): Uri = Uri.Builder()
    .scheme(SCHEME)
    .authority(HOST)
    .appendPath(kind)
    .appendPath(CODE + nfcCode(id))
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
 * Set while the app waits to write a tag. Android may still hand a tag the app already wrote to
 * [NfcActivity] instead of the reader: then it is overwritten, not run.
 */
@Volatile
internal var pendingWrite: ((Tag) -> Unit)? = null

/**
 * Opened by Android when the phone touches a tag the app wrote, even with the app closed: passes
 * what the tag asks for to the same receiver as the shortcuts, and never shows itself.
 */
class NfcActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingWrite?.let { write ->
            IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)?.let { tag -> Thread { write(tag) }.start() }
            finish()
            return
        }
        val segments = intent.data?.takeIf { it.scheme == SCHEME && it.host == HOST }?.pathSegments.orEmpty()
        val kind = segments.getOrNull(0)
        val id = segments.getOrNull(1)?.let { written ->
            // Tags written before the short codes hold the whole id.
            if (!written.startsWith(CODE)) return@let written
            val cache = Cache(this)
            val ids = when (kind) {
                NFC_DEVICE -> cache.devices().map { it.applianceId }
                NFC_GROUP -> cache.groups().map { it.id }
                NFC_MACRO -> cache.macros().map { it.id }
                else -> emptyList()
            }
            ids.find { nfcCode(it) == written.removePrefix(CODE) }
        }
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
