package app.hommaterial.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.hommaterial.HomeViewModel
import app.hommaterial.R
import app.hommaterial.data.AlexaList
import app.hommaterial.data.LIST_NOTION
import app.hommaterial.data.ListItem as AlexaItem
import app.hommaterial.data.NotionSource
import app.hommaterial.data.ListViews
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
    /** The lists as Alexa and Notion have them. */
    var source by mutableStateOf<List<AlexaList>?>(null)
        private set
    private val read = mutableStateMapOf<String, List<AlexaItem>>()
    /** The names given and the lists joined in the app, followed from the settings. */
    var views by mutableStateOf(ListViews())

    /** The lists as shown: renamed, those joined as one. */
    val lists: List<AlexaList>? get() = source?.let { views.apply(it, ::listName) }
    var loading by mutableStateOf(false)
        private set
    /** Whether the last reading of the lists failed: the reason was shown in the snackbar. */
    var failed by mutableStateOf(false)
        private set

    /** The items of a list shown: of all the lists joined in it, null until one was read. */
    fun itemsOf(listId: String): List<AlexaItem>? {
        val parts = ListViews.members(listId).map { read[it] }
        return if (parts.all { it == null }) null else parts.flatMap { it.orEmpty() }
    }

    /** The list an item of [listId] really is in; what is added goes to the first one. */
    private fun home(listId: String, item: AlexaItem? = null): String {
        val members = ListViews.members(listId)
        return members.firstOrNull { id -> item != null && read[id]?.any { it.id == item.id } == true } ?: members.first()
    }

    fun reload() {
        if (loading) return
        scope.launch {
            loading = true
            val found = vm.alexaLists()
            failed = found == null
            if (found != null) {
                source = found
                // Every list at once, so that swiping to the next one has nothing to wait for.
                found.map { list -> async { list.id to vm.alexaListItems(list.id) } }.awaitAll()
                    .forEach { (id, items) -> if (items != null) read[id] = items }
            }
            loading = false
        }
    }

    private suspend fun refresh(listId: String) {
        vm.alexaListItems(listId)?.let { read[listId] = it }
    }

    // A change is kept on the phone and shows at once; it goes to Alexa with the reading after it,
    // or, without internet, when the connection comes back. Notion gets it at once, or not at all.
    fun add(shownId: String, name: String) {
        val listId = home(shownId)
        if (NotionSource.ofList(listId) != null) notion(listId, null) { vm.addNotionItem(listId, name) }
        else changed(listId) { vm.addAlexaListItem(listId, name) }
    }

    fun toggle(shownId: String, item: AlexaItem) {
        val listId = home(shownId, item)
        if (NotionSource.ofList(listId) != null) {
            notion(listId, { list -> list.map { if (it.id == item.id) it.copy(completed = !item.completed) else it } }) {
                vm.setNotionItemDone(listId, item, !item.completed)
            }
        } else changed(listId) { vm.setAlexaListItemDone(listId, item, !item.completed) }
    }

    fun rename(shownId: String, item: AlexaItem, name: String) {
        if (name == item.name) return
        val listId = home(shownId, item)
        if (NotionSource.ofList(listId) != null) {
            notion(listId, { list -> list.map { if (it.id == item.id) it.copy(name = name) else it } }) {
                vm.renameNotionItem(listId, item, name)
            }
        } else changed(listId) { vm.renameAlexaListItem(listId, item, name) }
    }

    /** Deletes every item done of the list shown. */
    fun clearDone(shownId: String) {
        itemsOf(shownId).orEmpty().filter { it.completed }.forEach { delete(shownId, it) }
    }

    fun delete(shownId: String, item: AlexaItem) {
        val listId = home(shownId, item)
        if (NotionSource.ofList(listId) != null) notion(listId, { list -> list.filter { it.id != item.id } }) { vm.deleteNotionItem(listId, item) }
        else changed(listId) { vm.deleteAlexaListItem(listId, item) }
    }

    /** Shows the change at once with [shown], then reads the list again, as Notion has it, whether it went or not. */
    private fun notion(listId: String, shown: ((List<AlexaItem>) -> List<AlexaItem>)?, change: suspend () -> Boolean) {
        if (shown != null) read[listId]?.let { read[listId] = shown(it) }
        scope.launch {
            change()
            refresh(listId)
        }
    }

    private fun changed(listId: String, change: () -> Unit) {
        change()
        vm.keptListItems(listId)?.let { read[listId] = it }
        scope.launch { refresh(listId) }
    }

    /** How many items are still to do: counted from those read, or as Alexa last said. */
    fun active(list: AlexaList): Int? = itemsOf(list.id)?.count { !it.completed } ?: list.active
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
                    AppTonalButton(onClick = model::reload, modifier = Modifier.padding(top = 16.dp)) {
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
        // A few lists fit side by side; more are chosen from a sheet.
        if (all.size <= 3) {
            Segments(
                all.map { listName(it) to model.active(it) },
                position = { pager.currentPage + pager.currentPageOffsetFraction },
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                modifier = Modifier.wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            ListPicker(
                all.map { listName(it) to model.active(it) },
                selected = pager.currentPage,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                modifier = Modifier.wrapContentWidth().widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
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

/** The list shown, with its place among the others: a tap opens all of them, in a sheet, to choose one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListPicker(lists: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val (name, count) = lists[selected.coerceIn(0, lists.size - 1)]
    // An ivory key with a glass badge in the skeuomorphic look, a gray square with an accent one in Modern UI.
    val skeuo = LocalSkeuo.current
    val metro = LocalMetro.current
    val shape = when {
        metro -> RectangleShape
        skeuo -> RoundedCornerShape(14.dp)
        else -> CircleShape
    }
    val badge = when {
        metro -> RectangleShape
        skeuo -> RoundedCornerShape(10.dp)
        else -> CircleShape
    }
    Surface(
        onClick = { open = true },
        shape = shape,
        color = if (skeuo) Color.Transparent else colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().then(if (skeuo) Modifier.skeuoRaised(shape) else if (metro) Modifier.metroTilt() else Modifier),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 12.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).then(if (skeuo) Modifier.skeuoBadge(badge) else Modifier.clip(badge).background(colors.primary)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null, tint = colors.onPrimary)
            }
            AnimatedContent(name, label = "list", modifier = Modifier.weight(1f).padding(start = 12.dp)) { shown ->
                Text(shown, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (count != null && count > 0) CountBadge(count, selected = false)
            Text(
                str(R.string.lists_position, selected + 1, lists.size),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Icon(Icons.Outlined.UnfoldMore, contentDescription = str(R.string.lists_choose), tint = colors.onSurfaceVariant)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Text(str(R.string.lists_choose), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                itemsIndexed(lists) { index, (listName, listCount) ->
                    val chosen = index == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(CircleShape)
                            .background(if (chosen) colors.secondaryContainer else Color.Transparent)
                            .clickable {
                                onSelect(index)
                                open = false
                            }
                            .padding(horizontal = 16.dp)
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (chosen) Icons.Filled.CheckCircle else Icons.AutoMirrored.Outlined.List,
                            contentDescription = null,
                            tint = if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        )
                        Text(
                            listName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (chosen) colors.onSecondaryContainer else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                        )
                        if (listCount != null && listCount > 0) CountBadge(listCount, selected = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
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
    val skeuo = LocalSkeuo.current
    Surface(
        shape = if (skeuo) corners(12.dp) else CircleShape,
        color = if (skeuo) Color.Transparent else colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = modifier.fillMaxWidth().skeuoRaised(corners(12.dp), elevation = 1.dp),
    ) {
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
                        .then(if (skeuo) Modifier.skeuoBadge(corners(9.dp)) else Modifier.clip(CircleShape).background(colors.primary)),
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
    val items = model.itemsOf(list.id)
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
                DoneHeader(finished.size, showDone, Modifier.animateItem(), onClear = { model.clearDone(list.id) }) { showDone = !showDone }
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
        // In the skeuomorphic look the items are the lines of one sheet of a legal pad.
        val skeuo = LocalSkeuo.current
        Box(Modifier.animateItem().padding(bottom = if (skeuo) 0.dp else 2.dp)) {
            ItemRow(item, model, list, if (skeuo) RoundedCornerShape(if (index == 0) 4.dp else 0.dp) else shape)
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
            focusedContainerColor = LocalSkeuoMaterials.current?.field ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = LocalSkeuoMaterials.current?.field ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).skeuoInset(CircleShape),
    )
}

/** An item: the circle ticks it, a touch elsewhere edits it, the cross deletes it. Swiping sideways moves to the next list. */
@Composable
private fun ItemRow(item: AlexaItem, model: ListsModel, list: AlexaList, shape: RoundedCornerShape) {
    val colors = MaterialTheme.colorScheme
    val completed = item.completed
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        ItemEditDialog(item.name, onSave = { model.rename(list.id, item, it) }, onDismiss = { editing = false })
    }
    val pad = LocalSkeuoMaterials.current
    Surface(
        shape = shape,
        color = if (pad != null) pad.paper else colors.surfaceContainer,
        contentColor = colors.onSurface,
        modifier = Modifier.fillMaxWidth().clip(shape).legalPadLine().clickable { editing = true },
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A touch target of its own around the circle, so that only it ticks the item.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = str(R.string.list_toggle)) { model.toggle(list.id, item) },
                contentAlignment = Alignment.Center,
            ) {
                CheckCircle(completed)
            }
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                color = if (completed) colors.onSurfaceVariant else colors.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
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

/** The name of an item, to change. */
@Composable
private fun ItemEditDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf(name) }
    val ready = typed.isNotBlank()
    fun save() {
        if (!ready) return
        // A name stays on one line: the newlines typed in the bigger field become spaces.
        onSave(typed.replace(Regex("\\s*\\n\\s*"), " ").trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.list_item_edit)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(str(R.string.list_item_name)) },
                // Long names wrap and the field grows, so that all of the text shows.
                minLines = 2,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(enabled = ready, onClick = ::save) { Text(str(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(str(R.string.cancel)) } },
    )
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
private fun DoneHeader(count: Int, open: Boolean, modifier: Modifier, onClear: () -> Unit, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(str(R.string.list_clear_done_title)) },
            text = { Text(str(R.string.list_clear_done_text, count)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onClear() }) { Text(str(R.string.list_clear_done)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(str(R.string.cancel)) } },
        )
    }
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
        IconButton(
            onClick = { confirming = true },
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = str(R.string.list_clear_done))
        }
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

/** What is left to do in the lists on the rest screen of the monitor: the first lists with something in them. */
@Composable
internal fun RestLists(model: ListsModel) {
    LaunchedEffect(Unit) { if (model.lists == null) model.reload() }
    val shown = model.lists.orEmpty()
        .map { list -> list to model.itemsOf(list.id).orEmpty().filter { !it.completed } }
        .filter { it.second.isNotEmpty() }
        .take(REST_LISTS_SHOWN)
    if (shown.isEmpty()) return
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        for ((list, items) in shown) {
            Column {
                Text(listName(list), style = MaterialTheme.typography.titleMedium, modifier = Modifier.alpha(0.7f))
                for (item in items.take(REST_ITEMS_SHOWN)) {
                    Text("· ${item.name}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (items.size > REST_ITEMS_SHOWN) {
                    Text("+${items.size - REST_ITEMS_SHOWN}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.6f))
                }
            }
        }
    }
}

private const val REST_LISTS_SHOWN = 3
private const val REST_ITEMS_SHOWN = 4

/**
 * The names the lists have in the app and the lists joined into one: Alexa and Notion keep theirs.
 * A tap renames a list, the button joins a few, a joined list can be parted again.
 */
@Composable
internal fun ListsEditDialog(model: ListsModel, views: ListViews, onChange: (ListViews) -> Unit, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<AlexaList?>(null) }
    var merging by remember { mutableStateOf(false) }
    val shown = model.lists.orEmpty()
    val source = model.source.orEmpty()
    renaming?.let { list ->
        var name by remember(list.id) { mutableStateOf(views.names[list.id] ?: listName(list)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(str(R.string.list_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(str(R.string.list_rename_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { onChange(views.rename(list.id, name)); renaming = null }) { Text(str(R.string.save)) }
            },
            dismissButton = {
                Row {
                    if (list.id in views.names) {
                        TextButton(onClick = { onChange(views.rename(list.id, "")); renaming = null }) { Text(str(R.string.list_name_reset)) }
                    }
                    TextButton(onClick = { renaming = null }) { Text(str(R.string.cancel)) }
                }
            },
        )
    }
    if (merging) {
        var chosen by remember { mutableStateOf(listOf<String>()) }
        AlertDialog(
            onDismissRequest = { merging = false },
            title = { Text(str(R.string.list_merge)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(str(R.string.list_merge_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                    for (list in source) {
                        val checked = list.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clip(corners(12.dp))
                                .clickable { chosen = if (checked) chosen - list.id else chosen + list.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Text(listName(list), modifier = Modifier.weight(1f))
                            // The first chosen takes what is added.
                            if (chosen.firstOrNull() == list.id) Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = chosen.size >= 2, onClick = { onChange(views.merge(chosen)); merging = false }) { Text(str(R.string.list_merge)) }
            },
            dismissButton = { TextButton(onClick = { merging = false }) { Text(str(R.string.cancel)) } },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(str(R.string.lists_edit)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(str(R.string.lists_edit_text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                val ids = shown.map { it.id }
                for ((index, list) in shown.withIndex()) {
                    Row(
                        Modifier.fillMaxWidth().clip(corners(12.dp)).clickable { renaming = list }.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(listName(list), style = MaterialTheme.typography.bodyLarge)
                            if (list.type == ListViews.MERGED && list.id in views.names) {
                                Text(
                                    ListViews.members(list.id).mapNotNull { id -> source.find { it.id == id } }.joinToString(" + ") { listName(it) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (list.type == ListViews.MERGED) {
                            IconButton(onClick = { onChange(views.split(list.id)) }) {
                                Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = str(R.string.list_split))
                            }
                        }
                        // The order of the pages of the lists, the first one shown first.
                        IconButton(enabled = index > 0, onClick = { onChange(views.move(ids, index, index - 1)) }) {
                            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = str(R.string.list_move_up))
                        }
                        IconButton(enabled = index < shown.lastIndex, onClick = { onChange(views.move(ids, index, index + 1)) }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = str(R.string.list_move_down))
                        }
                        Icon(Icons.Outlined.Edit, contentDescription = str(R.string.list_rename), modifier = Modifier.padding(12.dp).size(20.dp))
                    }
                }
                if (source.size >= 2) {
                    AppTonalButton(onClick = { merging = true }, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(str(R.string.list_merge), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(str(R.string.close)) } },
    )
}
