package app.hommaterial.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.UiState
import app.hommaterial.data.NoteBlock
import app.hommaterial.data.NotionNote
import app.hommaterial.data.NotionSource
import app.hommaterial.str
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The page of the bottom bar with the notes of Notion, there once some are chosen in the settings. */
const val TAB_NOTES = "notes"

private const val NOTION_INTEGRATIONS = "https://www.notion.so/profile/integrations"

/** The notes of Notion, kept while the app is open: read when the page first opens, on a pull and on the button. */
@Stable
internal class NotesModel(private val vm: HomeViewModel, private val scope: CoroutineScope) {
    var notes by mutableStateOf<List<NotionNote>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun reload() {
        if (loading) return
        scope.launch {
            loading = true
            val found = vm.notionNotes()
            failed = found == null
            if (found != null) notes = found
            loading = false
        }
    }
}

@Composable
internal fun rememberNotesModel(vm: HomeViewModel): NotesModel {
    val scope = rememberCoroutineScope()
    return remember(vm) { NotesModel(vm, scope) }
}

/** The notes, the latest changed first; one opens on a tap, to be read. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesPage(model: NotesModel, state: UiState, vm: HomeViewModel) {
    LaunchedEffect(state.notionNotes) { model.reload() }
    var open by remember { mutableStateOf<NotionNote?>(null) }
    open?.let { NoteDialog(it, state.assistant.configured, vm) { open = null } }
    val notes = model.notes
    Column(Modifier.fillMaxSize()) {
        OfflineBanner()
        PullToRefreshBox(isRefreshing = model.loading, onRefresh = model::reload, modifier = Modifier.fillMaxSize()) {
            when {
                notes == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (model.failed && !model.loading) {
                        AppTonalButton(onClick = model::reload) { Text(str(R.string.lists_retry)) }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                notes.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState(str(R.string.notes_none), str(R.string.notes_none_text))
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 720.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + BOTTOM_BAR_SPACE),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = corners(20.dp),
                            modifier = Modifier.fillMaxWidth().clip(corners(20.dp)).clickable { open = note }.animateItem(),
                        ) {
                            ListItem(
                                leadingContent = {
                                    Box(
                                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                },
                                headlineContent = { Text(note.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (note.edited > 0) ({ Text(str(R.string.notes_edited, edited(note.edited))) }) else null,
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun edited(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

/**
 * One note, read from Notion as it is now: a touch on a piece of text changes it, a touch on a box
 * ticks it, the button at the end adds text. Each change goes to Notion at once and the note is read again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDialog(note: NotionNote, ai: Boolean, vm: HomeViewModel, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var blocks by remember(note.id) { mutableStateOf<List<NoteBlock>?>(null) }
    var failed by remember(note.id) { mutableStateOf(false) }
    suspend fun read() {
        val read = vm.notionNote(note.id)
        failed = read == null
        blocks = read
    }
    LaunchedEffect(note.id) { read() }
    // The block whose text is being changed; adding is a new paragraph at the end.
    var editing by remember { mutableStateOf<NoteBlock?>(null) }
    var adding by remember { mutableStateOf(false) }
    editing?.let { block ->
        NoteTextDialog(str(R.string.notes_block_edit), block.text, hint = str(R.string.notes_block_hint), onDismiss = { editing = null }) { text ->
            scope.launch { if (vm.editNoteBlock(block, text)) read() }
        }
    }
    if (adding) {
        NoteTextDialog(str(R.string.notes_add), "", hint = null, onDismiss = { adding = false }) { text ->
            if (text.isNotBlank()) scope.launch { if (vm.addToNote(note.id, text)) read() }
        }
    }
    fun tick(block: NoteBlock, checked: Boolean) {
        blocks = blocks?.map { if (it.id == block.id) it.copy(checked = checked) else it }
        scope.launch { if (!vm.tickNoteBlock(block, checked)) read() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenSurface {
            Column {
                AppTopBar(
                    title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = str(R.string.close)) }
                    },
                    actions = {
                        val text = blocks?.joinToString("\n") { it.text }.orEmpty()
                        if (ai && text.isNotBlank()) {
                            IconButton(onClick = { vm.summarize(note.title, text) }) {
                                Icon(Icons.Outlined.Summarize, contentDescription = str(R.string.ai_summary))
                            }
                        }
                        if (note.url.isNotBlank()) {
                            IconButton(onClick = { uri.openUri(note.url) }) {
                                Icon(Icons.Outlined.OpenInNew, contentDescription = str(R.string.notes_open_notion))
                            }
                        }
                    },
                )
                val shown = blocks
                when {
                    shown == null && !failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    shown == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(str(R.string.notes_failed)) }
                    shown.isEmpty() -> Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(str(R.string.notes_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppTonalButton(onClick = { adding = true }, modifier = Modifier.padding(top = 16.dp)) { Text(str(R.string.notes_add)) }
                    }
                    else -> SelectionContainer {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth().widthIn(max = 720.dp)
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            var number = 0
                            for (block in shown) {
                                number = if (block.kind == "number") number + 1 else 0
                                NoteBlockView(
                                    block, number,
                                    onEdit = if (block.editable) ({ editing = block }) else null,
                                    onTick = if (block.editable) ({ tick(block, it) }) else null,
                                )
                            }
                            // Notes kept from an older version have no ids: they change once read again.
                            if (shown.all { it.id.isNotEmpty() }) {
                                AppTonalButton(onClick = { adding = true }, modifier = Modifier.padding(top = 12.dp)) { Text(str(R.string.notes_add)) }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A piece of text to change, or to add when [text] is empty; [hint] says what an empty text does. */
@Composable
private fun NoteTextDialog(title: String, text: String, hint: String?, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var typed by remember { mutableStateOf(text) }
    fun save() {
        onSave(typed.trim())
        onDismiss()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.notes_block_text)) },
                supportingText = hint?.let { { Text(it) } },
                minLines = 3,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { AppTextButton(onClick = ::save, enabled = typed.trim() != text.trim()) { Text(str(R.string.save)) } },
        dismissButton = { AppTextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}

@Composable
private fun NoteBlockView(block: NoteBlock, number: Int, onEdit: (() -> Unit)?, onTick: ((Boolean) -> Unit)?) {
    val indent = Modifier.padding(start = (block.depth * 20).dp)
        .then(if (onEdit != null) Modifier.clip(corners(8.dp)).clickable(onClickLabel = str(R.string.notes_block_edit), onClick = onEdit) else Modifier)
    val type = MaterialTheme.typography
    when (block.kind) {
        "h1" -> Text(block.text, style = type.headlineSmall, fontWeight = FontWeight.Medium, modifier = indent.padding(top = 12.dp))
        "h2" -> Text(block.text, style = type.titleLarge, modifier = indent.padding(top = 10.dp))
        "h3" -> Text(block.text, style = type.titleMedium, modifier = indent.padding(top = 8.dp))
        "divider" -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
        "bullet", "number" -> Row(indent) {
            Text(if (block.kind == "bullet") "•" else "$number.", style = type.bodyLarge, modifier = Modifier.width(24.dp))
            Text(block.text, style = type.bodyLarge)
        }
        "todo" -> Row(indent, verticalAlignment = Alignment.Top) {
            Icon(
                if (block.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (block.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
                    .then(if (onTick != null) Modifier.clip(pill()).clickable { onTick(!block.checked) } else Modifier),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                block.text,
                style = type.bodyLarge,
                textDecoration = if (block.checked) TextDecoration.LineThrough else null,
                color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        "quote" -> Row(indent) {
            Box(Modifier.width(3.dp).height(24.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(12.dp))
            Text(block.text, style = type.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        "code" -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = corners(12.dp), modifier = indent.fillMaxWidth()) {
            Text(block.text, style = type.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
        }
        else -> if (block.text.isNotBlank()) Text(block.text, style = type.bodyLarge, modifier = indent)
    }
}

/**
 * Notion in the settings: the guide to make the integration and give its secret; once linked, the
 * pages and databases shown as lists and as notes on this device.
 */
@Composable
internal fun NotionSettings(state: UiState, vm: HomeViewModel) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    if (!state.notionLinked) {
        var token by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(str(R.string.notion_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Step(R.string.notion_step1_title, R.string.notion_step1_text) { uri.openUri(NOTION_INTEGRATIONS) }
            Step(R.string.notion_step2_title, R.string.notion_step2_text, onOpen = null)
            Step(R.string.notion_step3_title, R.string.notion_step3_text, onOpen = null)
            OutlinedTextField(
                token, { token = it.trim(); error = null },
                label = { Text(str(R.string.notion_secret)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            AppTonalButton(
                onClick = {
                    busy = true
                    scope.launch {
                        error = vm.connectNotion(token)
                        busy = false
                    }
                },
                enabled = token.isNotBlank() && !busy,
            ) { Text(str(R.string.notion_link)) }
            Spacer(Modifier.height(4.dp))
        }
        return
    }
    var picking by remember { mutableStateOf<Boolean?>(null) }
    picking?.let { lists ->
        SourcesDialog(
            title = str(if (lists) R.string.notion_lists else R.string.notion_notes),
            chosen = if (lists) state.notionLists else state.notionNotes,
            vm = vm,
            onDone = { if (lists) vm.setNotionLists(it) else vm.setNotionNotes(it); picking = null },
            onDismiss = { picking = null },
        )
    }
    fun names(sources: List<NotionSource>) = sources.joinToString(", ") { it.name }.ifEmpty { str(R.string.notion_none_chosen) }
    Setting(title = str(R.string.notion_lists), text = names(state.notionLists), onClick = { picking = true })
    Setting(title = str(R.string.notion_notes), text = names(state.notionNotes), onClick = { picking = false })
    Setting(
        title = str(R.string.notion_unlink),
        text = if (state.notionWorkspace.isNotBlank()) str(R.string.notion_linked, state.notionWorkspace) else null,
        onClick = vm::disconnectNotion,
    )
}

/** The pages and databases shared with the integration, ticked when chosen. */
@Composable
private fun SourcesDialog(
    title: String,
    chosen: List<NotionSource>,
    vm: HomeViewModel,
    onDone: (List<NotionSource>) -> Unit,
    onDismiss: () -> Unit,
) {
    var sources by remember { mutableStateOf<List<NotionSource>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(chosen.map { it.id }.toSet()) }
    LaunchedEffect(Unit) {
        val found = vm.notionSources()
        failed = found == null
        sources = found
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val all = sources
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    all == null && !failed -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    all == null -> Text(str(R.string.notes_failed))
                    all.isEmpty() -> Text(str(R.string.notion_none_shared))
                    else -> for (source in all) {
                        val on = source.id in ticked
                        ListItem(
                            headlineContent = { Text(source.name) },
                            supportingContent = {
                                Text(str(if (source.isDatabase) R.string.notion_database else R.string.notion_page))
                            },
                            trailingContent = { Checkbox(checked = on, onCheckedChange = { ticked = if (it) ticked + source.id else ticked - source.id }) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { ticked = if (on) ticked - source.id else ticked + source.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // What could not be read again is kept as it was chosen.
                    val all = sources ?: chosen
                    onDone(all.filter { it.id in ticked } + chosen.filter { old -> old.id in ticked && all.none { it.id == old.id } })
                },
            ) { Text(str(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
}
