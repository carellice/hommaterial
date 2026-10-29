package app.hommaterial.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.hommaterial.ChatLine
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.Teach
import app.hommaterial.UiState
import app.hommaterial.data.ASSISTANT_MODELS
import app.hommaterial.data.AssistantKey
import app.hommaterial.data.AssistantStep
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

/** What a step does, as a line of the chat. */
internal fun assistantStepLabel(step: AssistantStep): String = when (step) {
    is AssistantStep.Power -> str(if (step.on) R.string.voice_on else R.string.voice_off, step.device.name)
    is AssistantStep.Brightness -> str(R.string.assistant_step_brightness, step.device.name, step.percent)
    is AssistantStep.Color -> str(R.string.assistant_step_color, step.device.name, str(step.choice.label))
    is AssistantStep.GroupPower -> str(if (step.on) R.string.voice_on else R.string.voice_off, step.group.name)
    is AssistantStep.Run -> str(R.string.schedule_run, step.macro.name)
    is AssistantStep.Press -> {
        val key = if (step.key.command == "SetChannel") "${commandLabel(step.key.command)} ${step.parameter}" else step.key.label
        if (step.times > 1) {
            str(R.string.assistant_step_press_times, key, step.times, step.remote.name)
        } else {
            str(R.string.assistant_step_press, key, step.remote.name)
        }
    }
    is AssistantStep.Ac -> if (step.on) {
        str(R.string.assistant_step_ac, step.remote.name, step.setting.temperature)
    } else {
        str(R.string.voice_off, step.remote.name)
    }
    is AssistantStep.Wait -> plural(R.plurals.macro_wait, step.seconds)
}

/** The button of the top bar that opens the assistant, next to the microphone. */
@Composable
internal fun AssistantButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = str(R.string.assistant_button))
    }
}

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
            .putExtra(RecognizerIntent.EXTRA_PROMPT, str(R.string.assistant_hint))
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}

/** The conversation with the assistant, over the whole screen: what was asked, what it did. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantScreen(state: UiState, vm: HomeViewModel, onDismiss: () -> Unit) {
    val assistant = state.assistant
    var typed by rememberSaveable { mutableStateOf("") }
    fun send(text: String) {
        if (text.isBlank() || assistant.thinking) return
        vm.askAssistant(text.trim())
        typed = ""
    }
    val dictate = rememberDictation(onHeard = ::send, onUnavailable = vm::speechUnavailable)
    DetailsContainer(onDismiss, fullScreen = true) {
        // The field stays above the keyboard.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str(R.string.assistant_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (assistant.chat.isNotEmpty()) {
                    IconButton(onClick = vm::clearAssistant) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = str(R.string.assistant_clear))
                    }
                }
            }
            val list = rememberLazyListState()
            LaunchedEffect(assistant.chat.size, assistant.pending, assistant.thinking) {
                val last = assistant.chat.size + 1
                list.animateScrollToItem(last)
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (assistant.chat.isEmpty()) {
                    item {
                        Column(Modifier.padding(8.dp)) {
                            Text(str(R.string.assistant_empty), style = MaterialTheme.typography.bodyLarge)
                            FlowRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (example in listOf(R.string.assistant_example1, R.string.assistant_example2, R.string.assistant_example3)) {
                                    AssistChip(onClick = { send(str(example)) }, label = { Text(str(example)) })
                                }
                            }
                        }
                    }
                }
                itemsIndexed(assistant.chat) { _, line -> Bubble(line) }
                item {
                    val pending = assistant.pending
                    when {
                        pending != null -> Row(
                            Modifier.padding(start = 8.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = { vm.confirmAssistant(true) }) { Text(str(R.string.assistant_run)) }
                            OutlinedButton(onClick = { vm.confirmAssistant(false) }) { Text(str(R.string.cancel)) }
                        }
                        assistant.thinking -> Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                str(R.string.assistant_thinking),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        else -> Spacer(Modifier.size(1.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text(str(R.string.assistant_hint)) },
                    maxLines = 4,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(typed) }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = dictate, enabled = !assistant.thinking) {
                    Icon(Icons.Outlined.Mic, contentDescription = str(R.string.assistant_dictate))
                }
                IconButton(onClick = { send(typed) }, enabled = typed.isNotBlank() && !assistant.thinking) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = str(R.string.assistant_send))
                }
            }
        }
    }
}

@Composable
private fun Bubble(line: ChatLine) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        line.user -> colors.primaryContainer
        line.failed -> colors.errorContainer
        else -> colors.surfaceContainerHigh
    }
    val foreground = when {
        line.user -> colors.onPrimaryContainer
        line.failed -> colors.onErrorContainer
        else -> colors.onSurface
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (line.user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .padding(start = if (line.user) 48.dp else 0.dp, end = if (line.user) 0.dp else 48.dp)
                .background(background, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (line.text.isNotEmpty()) Text(line.text, color = foreground, style = MaterialTheme.typography.bodyLarge)
            for (step in line.steps) {
                Text("• $step", color = foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
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
                        FilledTonalButton(onClick = { links.openUri(SIGN_UP_URL) }, modifier = Modifier.padding(top = 16.dp)) {
                            Text(str(R.string.assistant_open_signup))
                        }
                    }
                    1 -> {
                        Text(str(R.string.assistant_step2_text))
                        FilledTonalButton(onClick = { links.openUri(WORKERS_AI_URL) }, modifier = Modifier.padding(vertical = 16.dp)) {
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
                        FilledTonalButton(onClick = { links.openUri(WORKERS_AI_URL) }, modifier = Modifier.padding(vertical = 16.dp)) {
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
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
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
                FilterChip(
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
