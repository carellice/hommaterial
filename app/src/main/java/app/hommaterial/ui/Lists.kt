package app.hommaterial.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.data.AlexaList
import app.hommaterial.data.ListItem as AlexaItem
import app.hommaterial.str
import kotlinx.coroutines.launch

/** What a list is called: the two of Alexa by their name in the app, the others by the one the user gave them. */
internal fun listName(list: AlexaList): String = when {
    list.type == "TO_DO" && list.name.isBlank() -> str(R.string.list_todo)
    list.type == "SHOPPING_LIST" && list.name.isBlank() -> str(R.string.list_shopping)
    list.name.isBlank() -> list.type
    else -> list.name
}

/**
 * The lists of Alexa, as in its app and on the Echo devices: one at a time, the items still to do
 * first, then those done. Asked to Alexa as the screen opens and after every change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListsDialog(vm: HomeViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<AlexaList>?>(null) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<AlexaItem>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var typed by rememberSaveable { mutableStateOf("") }
    var showDone by rememberSaveable { mutableStateOf(false) }
    // Items being ticked, shown at once as they will be, and those being deleted, gone at once.
    var pending by remember { mutableStateOf(emptySet<String>()) }
    var removing by remember { mutableStateOf(emptySet<String>()) }

    suspend fun reload() {
        loading = true
        val found = vm.alexaLists()
        if (found != null) {
            lists = found
            val current = found.find { it.id == chosen } ?: found.find { it.type == "TO_DO" } ?: found.firstOrNull()
            chosen = current?.id
            items = current?.let { vm.alexaListItems(it.id) } ?: emptyList()
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun choose(list: AlexaList) {
        if (list.id == chosen) return
        chosen = list.id
        items = null
        scope.launch {
            loading = true
            items = vm.alexaListItems(list.id) ?: emptyList()
            loading = false
        }
    }

    fun add() {
        val listId = chosen ?: return
        val name = typed.trim()
        if (name.isEmpty()) return
        typed = ""
        scope.launch {
            if (vm.addAlexaListItem(listId, name)) items = vm.alexaListItems(listId) ?: items
        }
    }

    fun change(item: AlexaItem, delete: Boolean) {
        val listId = chosen ?: return
        if (delete) removing = removing + item.id else pending = pending + item.id
        scope.launch {
            if (delete) vm.deleteAlexaListItem(listId, item) else vm.setAlexaListItemDone(listId, item, !item.completed)
            items = vm.alexaListItems(listId) ?: items
            pending = pending - item.id
            removing = removing - item.id
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(
                    title = { Text(str(R.string.s_lists)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = str(R.string.back))
                        }
                    },
                    actions = {
                        if (loading) {
                            CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = { scope.launch { reload() } }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = str(R.string.refresh))
                            }
                        }
                    },
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .wrapContentWidth()
                        .widthIn(max = 640.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        str(R.string.lists_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val all = lists
                    if (all != null && all.isEmpty()) {
                        Text(str(R.string.lists_none), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!all.isNullOrEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (list in all) {
                                val count = list.active?.let { " · $it" }.orEmpty()
                                FilterChip(
                                    selected = list.id == chosen,
                                    onClick = { choose(list) },
                                    label = { Text(listName(list) + count) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { typed = it },
                                label = { Text(str(R.string.list_add_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { add() }),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = ::add, enabled = typed.isNotBlank() && chosen != null) {
                                Icon(Icons.Outlined.Add, contentDescription = str(R.string.list_add))
                            }
                        }
                    }
                    val shown = items
                    if (shown != null) {
                        val kept = shown.filter { it.id !in removing }
                        val todo = kept.filter { !it.completed }
                        val done = kept.filter { it.completed }
                        SectionCard { Column(Modifier.fillMaxWidth()) {
                            if (todo.isEmpty()) {
                                Text(
                                    str(R.string.list_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            for (item in todo) ItemRow(item, item.id in pending, ::change)
                        } }
                        if (done.isNotEmpty()) {
                            TextButton(onClick = { showDone = !showDone }) {
                                Text(str(if (showDone) R.string.list_done_hide else R.string.list_done_show, done.size))
                            }
                            if (showDone) {
                                SectionCard { Column(Modifier.fillMaxWidth()) { for (item in done) ItemRow(item, item.id in pending, ::change) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: AlexaItem, busy: Boolean, change: (AlexaItem, Boolean) -> Unit) {
    // What it will be once Alexa has taken the change.
    val completed = if (busy) !item.completed else item.completed
    ListItem(
        headlineContent = {
            Text(
                item.name,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingContent = {
            Checkbox(checked = completed, onCheckedChange = { change(item, false) }, enabled = !busy)
        },
        trailingContent = {
            IconButton(onClick = { change(item, true) }, enabled = !busy) {
                Icon(Icons.Outlined.Close, contentDescription = str(R.string.list_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !busy) { change(item, false) },
    )
}
