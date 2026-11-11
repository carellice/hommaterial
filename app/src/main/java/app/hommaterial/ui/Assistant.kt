package app.hommaterial.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import app.hommaterial.data.AgentAction
import app.hommaterial.voice.completeCommand
import app.hommaterial.voice.Completion
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.Teach
import app.hommaterial.UiState
import app.hommaterial.data.ASSISTANT_MODELS
import app.hommaterial.data.AssistantKey
import app.hommaterial.data.Remote
import app.hommaterial.data.RemoteKind
import app.hommaterial.plural
import app.hommaterial.quick.Quick
import app.hommaterial.str

private const val SIGN_UP_URL = "https://dash.cloudflare.com/sign-up"
private const val WORKERS_AI_URL = "https://dash.cloudflare.com/?to=/:account/ai/workers-ai"
private val ACCOUNT_ID = Regex("[0-9a-fA-F]{32}")

/** Every key of a remote the assistant may press, with the name the app shows for it. */
internal fun assistantKeys(remote: Remote, state: UiState): List<AssistantKey> {
    val keys = standardCommands(remote).map { AssistantKey(it, false, commandName(remote.id, it, false, state)) }.toMutableList()
    if (remote.kind == RemoteKind.TV) keys += AssistantKey("SetChannel", false, "SetChannel")
    state.lgTvs.find { it.id == remote.id }?.let { tv ->
        keys += LG_KEYS.map { (key, _) -> "key:$key" }.map { AssistantKey(it, false, lgLabel(it, tv.apps, tv.inputs)) }
        keys += tv.inputs.map { (id, name) -> AssistantKey("input:$id", false, name) }
        keys += tv.apps.map { (id, name) -> AssistantKey("app:$id", false, name) }
    }
    keys += state.remoteButtons[remote.id].orEmpty().map { AssistantKey(it, true, commandName(remote.id, it, true, state)) }
    return keys.distinctBy { it.command }
}

/** The keys of everything the assistant can be taught about, to tell what it was never asked about. */
internal fun UiState.assistantKeys(): Set<String> =
    placed.filter { it.applianceId !in hidden }.map { it.applianceId }.toSet() +
        groups.map { "group:${it.id}" } + macros.map { Quick.macroKey(it.id) } + shownRemotes.map { Quick.remoteKey(it.id) }

/** Asks the speech recognizer for a sentence and gives back the best guess. */
@Composable
private fun rememberDictation(onHeard: (String) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onHeard)
        }
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, str(R.string.voice_language))
            .putExtra(RecognizerIntent.EXTRA_PROMPT, str(R.string.assistant_answer))
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}

/** The guided set-up: an account, its id, a token, then a request to try them. */
@Composable
internal fun AssistantSetupDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val assistant = state.assistant
    var step by rememberSaveable { mutableIntStateOf(0) }
    var account by rememberSaveable { mutableStateOf(assistant.accountId) }
    var token by rememberSaveable { mutableStateOf("") }
    val links = LocalUriHandler.current
    val steps = 4
    val validAccount = account.trim().matches(ACCOUNT_ID)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Column {
                Text(
                    str(R.string.assistant_step, step + 1, steps),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    str(
                        listOf(
                            R.string.assistant_step1_title,
                            R.string.assistant_step2_title,
                            R.string.assistant_step3_title,
                            R.string.assistant_step4_title,
                        )[step],
                    ),
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LinearProgressIndicator(
                    progress = { (step + 1f) / steps },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                )
                when (step) {
                    0 -> {
                        Text(str(R.string.assistant_step1_text))
                        AppTonalButton(onClick = { links.openUri(SIGN_UP_URL) }, modifier = Modifier.padding(top = 16.dp)) {
                            Text(str(R.string.assistant_open_signup))
                        }
                    }
                    1 -> {
                        Text(str(R.string.assistant_step2_text))
                        AppTonalButton(onClick = { links.openUri(WORKERS_AI_URL) }, modifier = Modifier.padding(vertical = 16.dp)) {
                            Text(str(R.string.assistant_open_dashboard))
                        }
                        OutlinedTextField(
                            value = account,
                            onValueChange = { account = it.trim() },
                            label = { Text(str(R.string.assistant_account_id)) },
                            singleLine = true,
                            isError = account.isNotEmpty() && !validAccount,
                            supportingText = if (account.isNotEmpty() && !validAccount) {
                                { Text(str(R.string.assistant_bad_account)) }
                            } else {
                                null
                            },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    2 -> {
                        Text(str(R.string.assistant_step3_text))
                        AppTonalButton(onClick = { links.openUri(WORKERS_AI_URL) }, modifier = Modifier.padding(vertical = 16.dp)) {
                            Text(str(R.string.assistant_open_dashboard))
                        }
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it.trim() },
                            label = { Text(str(R.string.assistant_token)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        Text(str(R.string.assistant_step4_text))
                        if (assistant.testing) {
                            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(str(R.string.assistant_testing), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                        assistant.setupError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step < steps - 1) {
                val ready = when (step) {
                    1 -> validAccount
                    2 -> token.isNotBlank()
                    else -> true
                }
                TextButton(onClick = { step++ }, enabled = ready) { Text(str(R.string.assistant_next)) }
            } else {
                TextButton(onClick = { vm.connectAssistant(account, token) }, enabled = !assistant.testing) {
                    Text(str(R.string.assistant_test))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 0) onDismiss() else step-- }) {
                Text(str(if (step == 0) R.string.cancel else R.string.back))
            }
        },
    )
}

/** The questions of the assistant about what it does not understand, one at a time. */
@Composable
internal fun AssistantTeachDialog(teach: Teach, vm: HomeViewModel) {
    var answer by remember(teach.index) { mutableStateOf("") }
    val dictate = rememberDictation(onHeard = { answer = if (answer.isBlank()) it else "$answer $it" }, onUnavailable = vm::speechUnavailable)
    val question = teach.questions.getOrNull(teach.index)
    val done = !teach.loading && teach.error == null && question == null
    AlertDialog(
        onDismissRequest = vm::closeTeaching,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                when {
                    done -> str(R.string.assistant_teach_done_title)
                    question != null -> str(R.string.assistant_teach_question, teach.index + 1, teach.questions.size)
                    else -> str(R.string.assistant_teach)
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    teach.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(str(R.string.assistant_teach_loading), modifier = Modifier.padding(start = 16.dp))
                    }
                    teach.error != null -> Text(teach.error, color = MaterialTheme.colorScheme.error)
                    question != null -> {
                        LinearProgressIndicator(
                            progress = { teach.index.toFloat() / teach.questions.size },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        )
                        Text(question.target.name, style = MaterialTheme.typography.titleMedium)
                        Text(question.question, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            label = { Text(str(R.string.assistant_answer)) },
                            minLines = 2,
                            maxLines = 5,
                            trailingIcon = {
                                IconButton(onClick = dictate) {
                                    Icon(Icons.Outlined.Mic, contentDescription = str(R.string.assistant_dictate))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    teach.questions.isEmpty() -> Text(str(R.string.assistant_teach_nothing))
                    else -> Text(plural(R.plurals.assistant_learnt, teach.learnt))
                }
            }
        },
        confirmButton = {
            when {
                question != null -> TextButton(onClick = { vm.answerQuestion(answer) }, enabled = answer.isNotBlank()) {
                    Text(str(R.string.assistant_next))
                }
                teach.loading -> {}
                else -> TextButton(onClick = vm::closeTeaching) { Text(str(R.string.close)) }
            }
        },
        dismissButton = {
            when {
                question != null -> TextButton(onClick = { vm.answerQuestion(null) }) { Text(str(R.string.assistant_skip)) }
                teach.loading -> TextButton(onClick = vm::closeTeaching) { Text(str(R.string.cancel)) }
            }
        },
    )
}

/** What the assistant was taught, to correct or forget: the home in general and each answer. */
@Composable
internal fun AssistantNotesDialog(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    var house by rememberSaveable { mutableStateOf(state.assistant.house) }
    val notes = remember { mutableStateListOf(*state.assistant.notes.toTypedArray()) }
    fun nameOf(key: String): String = when {
        key.startsWith("group:") -> state.groups.find { "group:${it.id}" == key }?.name
        else -> state.devices.find { it.applianceId == key }?.name
            ?: state.macros.find { Quick.macroKey(it.id) == key }?.name
            ?: state.remotes.find { Quick.remoteKey(it.id) == key }?.name
    } ?: str(R.string.macro_gone)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.assistant_notes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = house,
                    onValueChange = { house = it },
                    label = { Text(str(R.string.assistant_house)) },
                    supportingText = { Text(str(R.string.assistant_house_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (notes.isEmpty()) {
                    Text(str(R.string.assistant_notes_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                notes.forEachIndexed { index, note ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = corners(16.dp)) {
                        Column(Modifier.padding(start = 12.dp, bottom = 12.dp, end = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(nameOf(note.target), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { notes.removeAt(index) }) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = str(R.string.assistant_note_delete),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (note.question.isNotBlank()) {
                                Text(
                                    note.question,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                                )
                            }
                            OutlinedTextField(
                                value = note.answer,
                                onValueChange = { notes[index] = note.copy(answer = it) },
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveAssistantNotes(house, notes.filter { it.answer.isNotBlank() })
                onDismiss()
            }) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** The section of the settings: the guided set-up, then the model, the teaching and the notes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantSettings(state: UiState, vm: HomeViewModel) {
    val assistant = state.assistant
    var setup by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var unlinking by rememberSaveable { mutableStateOf(false) }
    // Once linked the guide is done with, and the assistant goes on to its questions by itself.
    LaunchedEffect(assistant.configured) { if (assistant.configured) setup = false }
    if (setup) AssistantSetupDialog(state, vm, onDismiss = { setup = false })
    if (notes) AssistantNotesDialog(state, vm, onDismiss = { notes = false })
    assistant.teach?.let { AssistantTeachDialog(it, vm) }
    if (unlinking) {
        ConfirmDialog(
            title = str(R.string.assistant_unlink_title),
            text = str(R.string.assistant_unlink_text),
            confirm = str(R.string.assistant_unlink),
            onConfirm = vm::disconnectAssistant,
            onDismiss = { unlinking = false },
        )
    }
    var webKey by rememberSaveable { mutableStateOf(false) }
    if (webKey) WebSearchKeyDialog(vm, onDismiss = { webKey = false })
    Setting(
        title = str(R.string.web_search),
        text = str(if (state.webSearch) R.string.web_search_on else R.string.web_search_off),
        onClick = { webKey = true },
    )
    if (!assistant.configured) {
        Setting(title = str(R.string.assistant_setup), text = str(R.string.assistant_setup_text), onClick = { setup = true })
        return
    }
    Setting(title = str(R.string.assistant_linked), text = str(R.string.assistant_linked_text, assistant.accountId.take(6)))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(str(R.string.assistant_model), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = listOf(R.string.assistant_model_big, R.string.assistant_model_small)
            ASSISTANT_MODELS.forEachIndexed { i, model ->
                AppFilterChip(
                    selected = assistant.model == model,
                    onClick = { vm.setAssistantModel(model) },
                    label = { Text(str(labels[i])) },
                )
            }
        }
    }
    val fresh = state.assistantKeys().any { it !in assistant.taught }
    Setting(
        title = str(R.string.assistant_teach),
        text = str(if (fresh && assistant.taught.isNotEmpty()) R.string.assistant_teach_new else R.string.assistant_teach_text),
        onClick = vm::startTeaching,
    )
    Setting(
        title = str(R.string.assistant_notes),
        text = plural(R.plurals.assistant_notes_count, assistant.notes.size),
        onClick = { notes = true },
    )
    Setting(title = str(R.string.assistant_unlink), onClick = { unlinking = true })
}

/** The key of Tavily for the web search, made for free on app.tavily.com; empty switches the search off. */
@Composable
private fun WebSearchKeyDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.web_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(R.string.web_search_key_text))
                TextButton(onClick = { uri.openUri("https://app.tavily.com") }) { Text("app.tavily.com") }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(str(R.string.web_search_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.setWebSearchKey(key); onDismiss() }) { Text(str(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** The picture sent in the chat, small, read from the address the photo picker gave. */
@Composable
private fun ChatPicture(uri: String) {
    val context = LocalContext.current
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val parsed = android.net.Uri.parse(uri)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(parsed)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 640) sample *= 2
                context.contentResolver.openInputStream(parsed)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    picture?.let {
        androidx.compose.foundation.Image(
            it, contentDescription = null,
            modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 260.dp).clip(corners(12.dp)),
        )
    }
}

/** The pages a web search found: a tap opens one in the browser. */
@Composable
internal fun WebLinks(links: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(modifier) {
        Text(str(R.string.web_search_sources), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for ((title, url) in links) {
            TextButton(onClick = { runCatching { uri.openUri(url) } }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(android.net.Uri.parse(url).host.orEmpty().removePrefix("www."), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A command typed instead of said: it goes to [onSend] as if it had been heard. */
@Composable
internal fun TypeCommandDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ready = typed.isNotBlank()
    fun send() {
        if (!ready) return
        onSend(typed.trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.assistant_type)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.assistant_type_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = ready, onClick = ::send) { Text(str(R.string.assistant_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

/** A line of the chat: [mine] when typed by the user; an answer is [waiting] until it comes. */
internal data class ChatLine(
    val id: Long,
    val mine: Boolean,
    val text: String?,
    val waiting: Boolean = false,
    val fromAlexa: Boolean = false,
    val actions: List<AgentAction> = emptyList(),
    val links: List<Pair<String, String>> = emptyList(),
    val search: String = "",
    /** The picture sent with the line, as the address the photo picker gave. */
    val image: String? = null,
)

/** Whether the chat with the assistant is open: then answers are written there, not shown full screen. */
internal val chatOpen = mutableStateOf(false)

/** What was written in the chat, kept while the app is open. */
private val chatLines = mutableStateListOf<ChatLine>()

/**
 * A chat with the assistant: what is typed goes on as a voice command, and every answer, of the
 * assistant, of Alexa or of the app itself, comes back in writing, never aloud.
 */
@Composable
internal fun AssistantChat(vm: HomeViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    var typed by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val list = rememberLazyListState()
    // The names of the lists are read once, as the chat opens; the rest comes from the state.
    val lists by produceState(emptyList<String>()) { value = runCatching { vm.alexaLists() }.getOrNull().orEmpty().map { it.name } }
    val words = remember(state, lists) { vm.completionWords(lists) }
    // Completed only with the cursor at the end, where the user is writing.
    val suggestions = remember(typed, words) {
        if (typed.selection.collapsed && typed.selection.end == typed.text.length) completeCommand(typed.text, words) else emptyList()
    }
    // Tab on a keyboard walks the suggestions as they were when it was first pressed: the third is three Tabs away.
    var cycle by remember { mutableStateOf<Pair<List<Completion>, Int>?>(null) }
    val shown = cycle?.first ?: suggestions
    fun close() {
        vm.dismissReply()
        onDismiss()
    }
    fun send() {
        val text = typed.text.trim()
        if (text.isEmpty()) return
        typed = TextFieldValue()
        cycle = null
        chatLines += ChatLine(System.nanoTime(), mine = true, text = text)
        vm.onSpeech(listOf(text))
    }
    // A picture from the photo picker, with what is written so far: the assistant says what it is about.
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = typed.text.trim()
        typed = TextFieldValue()
        cycle = null
        chatLines += ChatLine(System.nanoTime(), mine = true, text = text.ifEmpty { null }, image = uri.toString())
        vm.askImage(uri, text)
    }
    // The answer of the assistant or of Alexa, written in place as it changes.
    LaunchedEffect(state.reply) {
        val reply = state.reply ?: return@LaunchedEffect
        val line = ChatLine(reply.id, mine = false, text = reply.reply, waiting = reply.waiting, fromAlexa = reply.fromAlexa, actions = reply.actions, links = reply.links, search = reply.search)
        val at = chatLines.indexOfFirst { it.id == reply.id }
        if (at >= 0) chatLines[at] = line else chatLines += line
    }
    // What the app says by itself: "Luce accesa", "Non ho capito…".
    LaunchedEffect(Unit) { vm.messages.collect { chatLines += ChatLine(System.nanoTime(), mine = false, text = it) } }
    LaunchedEffect(chatLines.size) { if (chatLines.isNotEmpty()) list.animateScrollToItem(chatLines.lastIndex) }
    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val skeuo = LocalSkeuoMaterials.current
        val metro = LocalMetro.current
        // The table of the skeuomorphic look under the chat, its bar of brushed metal; the header of a hub in Modern UI.
        Surface(Modifier.fillMaxSize().screenTexture(), color = screenColor()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                AppTopBar(
                    title = { Text(str(R.string.assistant_chat).let { if (metro) it.lowercase() else it }) },
                    navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.Outlined.Close, contentDescription = str(R.string.close)) } },
                    actions = {
                        if (chatLines.isNotEmpty()) {
                            IconButton(onClick = { chatLines.clear() }) {
                                Icon(Icons.Outlined.RestartAlt, contentDescription = str(R.string.assistant_chat_clear))
                            }
                        }
                    },
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (chatLines.isEmpty()) {
                        item {
                            Text(
                                str(R.string.assistant_chat_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            )
                        }
                    }
                    itemsIndexed(chatLines, key = { _, line -> line.id }) { _, line -> ChatBubble(line, state.reply?.id == line.id, vm) }
                }
                if (shown.isNotEmpty()) {
                    val label = str(R.string.assistant_suggestions)
                    LazyRow(
                        Modifier.fillMaxWidth().semantics { contentDescription = label },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.label }) { i, suggestion ->
                            val chosen = cycle?.second == i
                            if (skeuo != null || metro) {
                                AppFilterChip(
                                    selected = chosen,
                                    onClick = {
                                        cycle = null
                                        typed = TextFieldValue(suggestion.text, TextRange(suggestion.text.length))
                                    },
                                    label = { Text(suggestion.label, maxLines = 1) },
                                    modifier = Modifier.animateItem(),
                                )
                                return@itemsIndexed
                            }
                            AssistChip(
                                onClick = {
                                    cycle = null
                                    typed = TextFieldValue(suggestion.text, TextRange(suggestion.text.length))
                                },
                                label = { Text(suggestion.label, maxLines = 1) },
                                colors = if (chosen) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors()
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = str(R.string.ai_image_send))
                    }
                    OutlinedTextField(
                        value = typed,
                        onValueChange = {
                            if (it.text != typed.text) cycle = null
                            typed = it
                        },
                        placeholder = { Text(str(R.string.assistant_type_hint)) },
                        shape = corners(28.dp),
                        // A white field cut into the table in the skeuomorphic look.
                        colors = if (skeuo != null) {
                            OutlinedTextFieldDefaults.colors(focusedContainerColor = skeuo.field, unfocusedContainerColor = skeuo.field)
                        } else {
                            OutlinedTextFieldDefaults.colors()
                        },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                            if (e.key != Key.Tab || shown.isEmpty()) return@onPreviewKeyEvent false
                            if (e.type == KeyEventType.KeyDown) {
                                // Shift+Tab goes back one.
                                val step = if (e.isShiftPressed) shown.size - 1 else 1
                                val i = cycle?.second?.let { (it + step) % shown.size } ?: if (e.isShiftPressed) shown.size - 1 else 0
                                cycle = shown to i
                                typed = TextFieldValue(shown[i].text, TextRange(shown[i].text.length))
                            }
                            true
                        },
                    )
                    Spacer(Modifier.size(8.dp))
                    FilledIconButton(
                        onClick = ::send,
                        enabled = typed.text.isNotBlank(),
                        colors = if (skeuo != null) {
                            IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = Color.White)
                        } else {
                            IconButtonDefaults.filledIconButtonColors()
                        },
                        modifier = if (skeuo != null) Modifier.skeuoRaised(CircleShape, lit = typed.text.isNotBlank()) else Modifier,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = str(R.string.assistant_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(line: ChatLine, current: Boolean, vm: HomeViewModel) {
    val colors = MaterialTheme.colorScheme
    val skeuo = LocalSkeuo.current
    val metro = LocalMetro.current
    // Lit glass for what I wrote and ivory for the answers in the skeuomorphic look; flat squares in Modern UI.
    val shape = when {
        metro -> RectangleShape
        skeuo -> RoundedCornerShape(16.dp)
        else -> RoundedCornerShape(20.dp, 20.dp, if (line.mine) 4.dp else 20.dp, if (line.mine) 20.dp else 4.dp)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = when {
                skeuo -> Color.Transparent
                metro && line.mine -> colors.primary
                line.mine -> colors.primaryContainer
                else -> colors.surfaceContainerHigh
            },
            contentColor = when {
                (skeuo || metro) && line.mine -> Color.White
                line.mine -> colors.onPrimaryContainer
                else -> colors.onSurface
            },
            shape = shape,
            modifier = Modifier.widthIn(max = 320.dp).then(if (skeuo) Modifier.skeuoRaised(shape, lit = line.mine) else Modifier),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                line.image?.let { ChatPicture(it) }
                if (line.mine && line.text == null) return@Column
                if (line.waiting) {
                    Text(
                        str(if (line.fromAlexa) R.string.alexa_waiting else R.string.ai_thinking),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(line.text ?: str(R.string.alexa_no_reply), style = MaterialTheme.typography.bodyLarge)
                    if (line.links.isNotEmpty()) WebLinks(line.links, Modifier.padding(top = 8.dp))
                    if (current && line.search.isNotEmpty()) {
                        OutlinedButton(onClick = { vm.searchFor(line.search) }, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.TravelExplore, contentDescription = null)
                            Text(str(R.string.ai_image_search), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                // What the assistant would do waits for a yes, as on the full screen answer.
                if (!line.waiting && line.actions.isNotEmpty()) {
                    for (action in line.actions) Text("• " + agentLabel(action), style = MaterialTheme.typography.bodyMedium)
                    if (current) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val at = chatLines.indexOfFirst { it.id == line.id }
                                if (at >= 0) chatLines[at] = line.copy(actions = emptyList())
                                vm.dismissReply()
                            }) { Text(str(R.string.cancel)) }
                            AppButton(onClick = vm::confirmAgent) { Text(str(R.string.ai_confirm)) }
                        }
                    }
                }
            }
        }
    }
}
