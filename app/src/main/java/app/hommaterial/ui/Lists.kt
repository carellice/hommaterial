package app.hommaterial.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.data.AlexaList
import app.hommaterial.data.LIST_NOTION
import app.hommaterial.data.ListItem as AlexaItem
import app.hommaterial.data.NotionSource
import app.hommaterial.str
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

/** What a list is called: the two of Alexa by their name in the app, the others by the one the user gave them. */
internal fun listName(list: AlexaList): String = when {
    list.type == "TO_DO" && list.name.isBlank() -> str(R.string.list_todo)
    list.type == "SHOPPING_LIST" && list.name.isBlank() -> str(R.string.list_shopping)
    list.name.isBlank() -> list.type
    else -> list.name
}

/**
 * The lists of Alexa and their items, kept while the app is open so that moving between the pages
 * shows them at once: read from Alexa when the page first opens, on a pull and after every change.
 */
@Stable
internal class ListsModel(private val vm: HomeViewModel, private val scope: CoroutineScope) {
    var lists by mutableStateOf<List<AlexaList>?>(null)
        private set
    val items = mutableStateMapOf<String, List<AlexaItem>>()
    var loading by mutableStateOf(false)
        private set
    /** Whether the last reading of the lists failed: the reason was shown in the snackbar. */
    var failed by mutableStateOf(false)
        private set

    fun reload() {
        if (loading) return
        scope.launch {
            loading = true
            val found = vm.alexaLists()
            failed = found == null
            if (found != null) {
                lists = found
                // Every list at once, so that swiping to the next one has nothing to wait for.
                found.map { list -> async { list.id to vm.alexaListItems(list.id) } }.awaitAll()
                    .forEach { (id, read) -> if (read != null) items[id] = read }
            }
            loading = false
        }
    }

    private suspend fun refresh(listId: String) {
        vm.alexaListItems(listId)?.let { items[listId] = it }
    }

    // A change is kept on the phone and shows at once; it goes to Alexa with the reading after it,
    // or, without internet, when the connection comes back. Notion gets it at once, or not at all.
    fun add(listId: String, name: String) =
        if (NotionSource.ofList(listId) != null) notion(listId, null) { vm.addNotionItem(listId, name) }
        else changed(listId) { vm.addAlexaListItem(listId, name) }

    fun toggle(listId: String, item: AlexaItem) =
        if (NotionSource.ofList(listId) != null) {
            notion(listId, { list -> list.map { if (it.id == item.id) it.copy(completed = !item.completed) else it } }) {
                vm.setNotionItemDone(listId, item, !item.completed)
            }
        } else changed(listId) { vm.setAlexaListItemDone(listId, item, !item.completed) }

    fun delete(listId: String, item: AlexaItem) =
        if (NotionSource.ofList(listId) != null) notion(listId, { list -> list.filter { it.id != item.id } }) { vm.deleteNotionItem(listId, item) }
        else changed(listId) { vm.deleteAlexaListItem(listId, item) }

    /** Shows the change at once with [shown], then reads the list again, as Notion has it, whether it went or not. */
    private fun notion(listId: String, shown: ((List<AlexaItem>) -> List<AlexaItem>)?, change: suspend () -> Boolean) {
        if (shown != null) items[listId]?.let { items[listId] = shown(it) }
        scope.launch {
            change()
            refresh(listId)
        }
    }

    private fun changed(listId: String, change: () -> Unit) {
        change()
        vm.keptListItems(listId)?.let { items[listId] = it }
        scope.launch { refresh(listId) }
    }

    /** How many items are still to do: counted from those read, or as Alexa last said. */
    fun active(list: AlexaList): Int? = items[list.id]?.count { !it.completed } ?: list.active
}

@Composable
internal fun rememberListsModel(vm: HomeViewModel): ListsModel {
    val scope = rememberCoroutineScope()
    return remember(vm) { ListsModel(vm, scope) }
}

/**
 * The page of the lists of Alexa: a segmented control to choose the list, and under it the list
 * itself, a page each, to swipe between. The items still to do come first, then those done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListsPage(model: ListsModel, bottomSpace: Int) {
    LaunchedEffect(Unit) { if (model.lists == null) model.reload() }
    val all = model.lists
    if (all == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (model.failed && !model.loading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        str(R.string.lists_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = model::reload, modifier = Modifier.padding(top = 16.dp)) {
                        Text(str(R.string.lists_retry))
                    }
                }
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }
    if (all.isEmpty()) {
        EmptyState(str(R.string.lists_none), null)
        return
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val start = all.indexOfFirst { it.id == chosen }.takeIf { it >= 0 }
        ?: all.indexOfFirst { it.type == "TO_DO" }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { all.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager, all) {
        snapshotFlow { pager.settledPage }.collect { chosen = all.getOrNull(it)?.id }
    }

    Column(Modifier.fillMaxSize()) {
        OfflineBanner()
        Segments(
            all.map { listName(it) to model.active(it) },
            position = { pager.currentPage + pager.currentPageOffsetFraction },
            onSelect = { scope.launch { pager.animateScrollToPage(it) } },
            modifier = Modifier.wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PullToRefreshBox(
            isRefreshing = model.loading,
            onRefresh = model::reload,
            modifier = Modifier.fillMaxSize(),
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { all[it].id }, beyondViewportPageCount = 1) { page ->
                ListContent(model, all[page], bottomSpace)
            }
        }
    }
}

/**
 * A segmented control with a pill that slides under the chosen segment, following the finger
 * while the lists are swiped. [position] is the page shown, with the fraction of the swipe.
 */
@Composable
private fun Segments(
    segments: List<Pair<String, Int?>>,
    position: () -> Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // Where each segment sits in the track, in pixels, once measured.
    val lefts = remember { mutableStateMapOf<Int, Int>() }
    val widths = remember { mutableStateMapOf<Int, Int>() }
    // A few lists share the width; more scroll sideways.
    val fits = segments.size <= 3
    Surface(shape = CircleShape, color = colors.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Box(
            (if (fits) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
                .padding(4.dp),
        ) {
            if (widths.size == segments.size) {
                Box(
                    Modifier
                        .offset {
                            val at = position().coerceIn(0f, (segments.size - 1).toFloat())
                            val from = at.toInt()
                            val to = (from + 1).coerceAtMost(segments.size - 1)
                            IntOffset(lerp(lefts[from]!!.toFloat(), lefts[to]!!.toFloat(), at - from).roundToInt(), 0)
                        }
                        .width(
                            with(density) {
                                val at = position().coerceIn(0f, (segments.size - 1).toFloat())
                                val from = at.toInt()
                                val to = (from + 1).coerceAtMost(segments.size - 1)
                                lerp(widths[from]!!.toFloat(), widths[to]!!.toFloat(), at - from).toDp()
                            },
                        )
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                )
            }
            Row(if (fits) Modifier.fillMaxWidth() else Modifier) {
                val shown = position().roundToInt()
                segments.forEachIndexed { index, (name, count) ->
                    val selected = index == shown
                    val content by animateColorAsState(
                        if (selected) colors.onPrimary else colors.onSurfaceVariant,
                        label = "segment",
                    )
                    Row(
                        // Sharing the width, a longer name takes more of it.
                        (if (fits) Modifier.weight(name.length + (if (count != null && count > 0) 4f else 0f) + 6f) else Modifier)
                            .height(44.dp)
                            .clip(CircleShape)
                            .clickable { onSelect(index) }
                            .onGloballyPositioned {
                                lefts[index] = it.positionInParent().x.roundToInt()
                                widths[index] = it.size.width
                            }
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                            color = content,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (count != null && count > 0) {
                            Box(
                                Modifier
                                    .padding(start = 8.dp)
                                    .heightIn(min = 20.dp)
                                    .widthIn(min = 20.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) colors.onPrimary.copy(alpha = 0.2f) else colors.secondaryContainer)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    count.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) colors.onPrimary else colors.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One list: how far along it is, the field to add to it, the items to do, then those done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListContent(model: ListsModel, list: AlexaList, bottomSpace: Int) {
    val items = model.items[list.id]
    var showDone by rememberSaveable(list.id) { mutableStateOf(false) }
    val todo = items.orEmpty().filter { !it.completed }
    val finished = items.orEmpty().filter { it.completed }

    LazyColumn(
        Modifier.fillMaxSize().wrapContentWidth().widthIn(max = 720.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp + bottomSpace.dp),
    ) {
        item(key = "header") {
            Header(listName(list), todo.size, finished.size, loaded = items != null)
        }
        item(key = "add") {
            AddField { model.add(list.id, it) }
            Spacer(Modifier.height(16.dp))
        }
        if (items != null && todo.isEmpty()) {
            item(key = "empty") {
                EmptyState(str(R.string.list_empty), str(R.string.list_empty_text), Modifier.animateItem())
            }
        }
        itemsGrouped(todo, model, list)
        if (finished.isNotEmpty()) {
            item(key = "done header") {
                DoneHeader(finished.size, showDone, Modifier.animateItem()) { showDone = !showDone }
            }
            if (showDone) itemsGrouped(finished, model, list)
        }
        item(key = "footnote") {
            Text(
                str(if (list.type == LIST_NOTION) R.string.lists_notion_text else R.string.lists_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp).animateItem(),
            )
        }
    }
}

/** The items as one card cut in rows: the first and the last rounded, those between nearly square. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsGrouped(
    shown: List<AlexaItem>,
    model: ListsModel,
    list: AlexaList,
) {
    items(shown, key = { it.id }) { item ->
        val index = shown.indexOf(item)
        val big = 20.dp
        val small = 6.dp
        val shape = RoundedCornerShape(
            topStart = if (index == 0) big else small,
            topEnd = if (index == 0) big else small,
            bottomStart = if (index == shown.lastIndex) big else small,
            bottomEnd = if (index == shown.lastIndex) big else small,
        )
        Box(Modifier.animateItem().padding(bottom = 2.dp)) {
            ItemRow(item, model, list, shape)
        }
    }
}

@Composable
private fun Header(name: String, todo: Int, done: Int, loaded: Boolean) {
    val total = todo + done
    val progress by animateFloatAsState(
        if (total == 0) 0f else done.toFloat() / total,
        spring(stiffness = Spring.StiffnessLow),
        label = "progress",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        if (loaded) {
            Text(
                str(R.string.list_summary, todo, done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    strokeCap = StrokeCap.Round,
                    gapSize = 4.dp,
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The field at the top of a list, with its button that lights up once there is something to add. */
@Composable
private fun AddField(onAdd: (String) -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val ready = typed.isNotBlank()
    fun add() {
        if (!ready) return
        onAdd(typed.trim())
        typed = ""
    }
    TextField(
        value = typed,
        onValueChange = { typed = it },
        placeholder = { Text(str(R.string.list_add_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
        trailingIcon = {
            AnimatedVisibility(visible = ready, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                FilledIconButton(onClick = ::add, modifier = Modifier.padding(end = 6.dp)) {
                    Icon(Icons.Outlined.Check, contentDescription = str(R.string.list_add))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** An item: a touch ticks it, the cross deletes it. Swiping sideways moves to the next list. */
@Composable
private fun ItemRow(item: AlexaItem, model: ListsModel, list: AlexaList, shape: RoundedCornerShape) {
    val colors = MaterialTheme.colorScheme
    val completed = item.completed
    Surface(
        shape = shape,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable { model.toggle(list.id, item) },
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckCircle(completed)
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (completed) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
            )
            // Changed here and not yet sent: it goes as soon as there is internet.
            if (item.waiting) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = str(R.string.offline_waiting),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { model.delete(list.id, item) },
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = str(R.string.list_delete))
            }
        }
    }
}

/** The round tick of an item, which fills with a little bounce when it is done. */
@Composable
private fun CheckCircle(checked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (checked) colors.primary else colors.primary.copy(alpha = 0f), label = "tick")
    val scale by animateFloatAsState(
        if (checked) 1f else 0.4f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tick scale",
    )
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, if (checked) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked || scale > 0.45f) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size((18 * scale).dp),
            )
        }
    }
}

@Composable
private fun DoneHeader(count: Int, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            str(R.string.list_done),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            " · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = str(if (open) R.string.list_done_hide else R.string.list_done_show, count),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(turn),
        )
    }
}

@Composable
internal fun EmptyState(title: String, text: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (text != null) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
