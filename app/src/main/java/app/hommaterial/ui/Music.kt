package app.hommaterial.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.hommaterial.R
import app.hommaterial.data.SettingsSearch
import app.hommaterial.music.MusicLibrary
import app.hommaterial.music.MusicPlayer
import app.hommaterial.music.NowPlaying
import app.hommaterial.music.Track
import app.hommaterial.plural
import app.hommaterial.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The page of the music of the phone, once a folder is chosen in the settings. */
const val TAB_MUSIC = "music"

/** Whether the player covers the whole screen; the home draws it over everything. */
internal val musicExpanded = MutableStateFlow(false)

// The folder is read again the first time the page opens after the app starts; later with the button.
private var scannedThisRun = false

private const val ALBUM = "album:"
private const val ARTIST = "artist:"

private fun artistName(track: Track) = track.artist.ifEmpty { str(R.string.music_unknown_artist) }

/** How deep a place of the page is: an opened album, or a subfolder, is further in than its list. */
private fun depth(opened: String?, path: String) = (if (opened != null) 1 else 0) + if (path.isEmpty()) 0 else path.count { it == '/' } + 1

@Composable
fun MusicPage() {
    val context = LocalContext.current
    val tracks by MusicLibrary.tracks.collectAsState()
    val scanning by MusicLibrary.scanning.collectAsState()
    val now by MusicPlayer.now.collectAsState()
    LaunchedEffect(Unit) {
        MusicPlayer.attach(context)
        if (!scannedThisRun) {
            scannedThisRun = true
            MusicLibrary.refresh(context)
        }
    }
    var view by rememberSaveable { mutableIntStateOf(0) }
    // An album or artist opened from its list, by its key.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    // The folder shown on the page of the folders, "" for the chosen one.
    var path by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val searching = query.isNotBlank()
    BackHandler(enabled = searching) { query = "" }
    BackHandler(enabled = !searching && opened != null) { opened = null }
    BackHandler(enabled = !searching && opened == null && view == 3 && path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }

    val all = tracks.orEmpty()
    val play = { list: List<Track>, start: Int, shuffle: Boolean -> MusicPlayer.play(context, list, start, shuffle) }
    val bottom = PaddingValues(bottom = BOTTOM_BAR_SPACE)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SettingsSearchField(query, str(R.string.music_search_hint)) { query = it }
            }
            if (all.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (scanning || tracks == null) CircularProgressIndicator()
                    Text(
                        str(if (scanning || tracks == null) R.string.music_reading else R.string.music_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                return@Column
            }
            AnimatedContent(searching, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) }, label = "search") { shownSearch ->
                if (shownSearch) {
                    SearchResults(
                        all, query, now, bottom, play,
                        onArtist = { opened = ARTIST + it; view = 2; query = ""; focus.clearFocus() },
                        onAlbum = { opened = ALBUM + it; view = 1; query = ""; focus.clearFocus() },
                        onFolder = { path = it; opened = null; view = 3; query = ""; focus.clearFocus() },
                    )
                    return@AnimatedContent
                }
                Column {
                    val views = listOf(R.string.music_songs, R.string.music_albums, R.string.music_artists, R.string.music_folders)
                    PrimaryTabRow(selectedTabIndex = view) {
                        views.forEachIndexed { i, label ->
                            Tab(selected = view == i, onClick = { view = i; opened = null }, text = { Text(str(label), maxLines = 1) })
                        }
                    }
                    AnimatedContent(
                        Triple(view, opened, path),
                        transitionSpec = {
                            // Another tab comes from its side; going in comes from the right, coming back from the left.
                            val forward = if (targetState.first != initialState.first) {
                                targetState.first > initialState.first
                            } else {
                                depth(targetState.second, targetState.third) >= depth(initialState.second, initialState.third)
                            }
                            val sign = if (forward) 1 else -1
                            val motion = spring<IntOffset>(stiffness = Spring.StiffnessMediumLow)
                            (slideInHorizontally(motion) { sign * it / 4 } + fadeIn())
                                .togetherWith(slideOutHorizontally(motion) { -sign * it / 4 } + fadeOut())
                        },
                        label = "music view",
                    ) { (shownView, shownOpened, shownPath) ->
                        if (shownOpened != null) {
                            val list = if (shownOpened.startsWith(ALBUM)) {
                                all.filter { it.albumKey == shownOpened.removePrefix(ALBUM) }
                                    .sortedWith(compareBy({ it.disc }, { it.number }, { it.title.lowercase() }))
                            } else {
                                all.filter { artistName(it) == shownOpened.removePrefix(ARTIST) }
                            }
                            val title = if (shownOpened.startsWith(ALBUM)) list.firstOrNull()?.album.orEmpty() else shownOpened.removePrefix(ARTIST)
                            Tracks(list, now, bottom, header = { Header(title, list, onBack = { opened = null }, play) }, play)
                            return@AnimatedContent
                        }
                        when (shownView) {
                            0 -> {
                                val sorted = all.sortedBy { it.title.lowercase() }
                                Tracks(sorted, now, bottom, header = { PlayButtons(sorted, play) }, play)
                            }
                            1 -> Groups(
                                all.groupBy { it.albumKey }.entries.sortedBy { it.value.first().album.lowercase() },
                                Icons.Outlined.Album,
                                bottom,
                                name = { it.first().album },
                                detail = { "${artistName(it.first())} · ${plural(R.plurals.music_tracks, it.size)}" },
                            ) { opened = ALBUM + it }
                            2 -> Groups(
                                all.groupBy { artistName(it) }.entries.sortedBy { it.key.lowercase() },
                                Icons.Outlined.Person,
                                bottom,
                                name = { artistName(it.first()) },
                                detail = { plural(R.plurals.music_tracks, it.size) },
                            ) { opened = ARTIST + it }
                            else -> Folders(all, shownPath, now, bottom, onOpen = { path = it }, play)
                        }
                    }
                }
            }
        }
    }
}

/** The room the bar of what plays takes above the bar of the pages. */
internal val MUSIC_BAR_SPACE = 80.dp

/**
 * What plays, over the bar of the pages of every page of the home, until the music is stopped in
 * the player. Only once a music folder is chosen: [enabled].
 */
@Composable
internal fun MusicNowBar(enabled: Boolean, visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by MusicPlayer.now.collectAsState()
    // Music left playing by an earlier visit shows at once.
    LaunchedEffect(enabled) { if (enabled) MusicPlayer.attach(context) }
    AnimatedVisibility(
        visible = visible && now.queued,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it * 2 } + fadeIn(),
        exit = slideOutVertically { it * 2 } + fadeOut(),
        modifier = modifier,
    ) {
        NowBar(now)
    }
}

@Composable
private fun PlayButtons(list: List<Track>, play: (List<Track>, Int, Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { play(list, 0, false) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Text(str(R.string.music_play), Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = { play(list, 0, true) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Shuffle, contentDescription = null)
            Text(str(R.string.music_shuffle), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Header(title: String, list: List<Track>, onBack: () -> Unit, play: (List<Track>, Int, Boolean) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = str(R.string.music_back)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(plural(R.plurals.music_tracks, list.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PlayButtons(list, play)
    }
}

@Composable
private fun Tracks(
    list: List<Track>,
    now: NowPlaying,
    bottom: PaddingValues,
    header: @Composable () -> Unit,
    play: (List<Track>, Int, Boolean) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
        item { header() }
        itemsIndexed(list, key = { _, t -> t.uri }) { i, track ->
            TrackRow(track, track.uri == now.uri, now.playing, Modifier.animateItem()) { play(list, i, false) }
        }
    }
}

@Composable
private fun TrackRow(track: Track, current: Boolean, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, label = "track")
    ListItem(
        headlineContent = { Text(track.title, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${artistName(track)} · ${track.album}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            Crossfade(current, label = "track icon") { isCurrent ->
                if (isCurrent) {
                    Equalizer(playing, MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = if (track.duration > 0) ({ Text(duration(track.duration)) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}

/** Three bars that dance while the music plays, and rest when it is paused. */
@Composable
private fun Equalizer(playing: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    Row(Modifier.size(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 3) {
            val dance by transition.animateFloat(
                0.25f, 1f, infiniteRepeatable(tween(380 + i * 130), RepeatMode.Reverse), label = "bar $i",
            )
            val rest by animateFloatAsState(if (playing) 1f else 0f, label = "rest $i")
            val height = 0.3f + (dance - 0.3f).coerceAtLeast(0f) * rest
            Box(Modifier.width(5.dp).fillMaxHeight(height).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Groups(
    groups: List<Map.Entry<String, List<Track>>>,
    icon: ImageVector,
    bottom: PaddingValues,
    name: (List<Track>) -> String,
    detail: (List<Track>) -> String,
    onOpen: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
        groupItems(groups, icon, { _, list -> name(list) }, detail, onOpen)
    }
}

private fun LazyListScope.groupItems(
    groups: List<Map.Entry<String, List<Track>>>,
    icon: ImageVector,
    name: (key: String, List<Track>) -> String,
    detail: (List<Track>) -> String,
    onOpen: (String) -> Unit,
) {
    items(groups, key = { icon.name + it.key }) { (key, list) ->
        ListItem(
            headlineContent = { Text(name(key, list), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(detail(list), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.animateItem().clickable { onOpen(key) },
        )
    }
}

@Composable
private fun Folders(
    all: List<Track>,
    path: String,
    now: NowPlaying,
    bottom: PaddingValues,
    onOpen: (String) -> Unit,
    play: (List<Track>, Int, Boolean) -> Unit,
) {
    val prefix = if (path.isEmpty()) "" else "$path/"
    val inside = all.filter { it.folder == path || it.folder.startsWith(prefix) }
        .sortedWith(compareBy({ it.folder.lowercase() }, { it.disc }, { it.number }, { it.title.lowercase() }))
    val here = inside.filter { it.folder == path }
    val subfolders = inside.filter { it.folder != path }
        .groupBy { prefix + it.folder.removePrefix(prefix).substringBefore('/') }
        .entries.sortedBy { it.key.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
        item {
            Column {
                if (path.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp)) {
                        IconButton(onClick = { onOpen(path.substringBeforeLast('/', "")) }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = str(R.string.music_back))
                        }
                        Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                PlayButtons(inside, play)
            }
        }
        groupItems(subfolders, Icons.Outlined.Folder, { key, _ -> key.substringAfterLast('/') }, { plural(R.plurals.music_tracks, it.size) }, onOpen)
        itemsIndexed(here, key = { _, t -> t.uri }) { i, track ->
            TrackRow(track, track.uri == now.uri, now.playing, Modifier.animateItem()) { play(here, i, false) }
        }
    }
}

/** What the search finds among the artists, albums, folders and songs, each under its heading. */
@Composable
private fun SearchResults(
    all: List<Track>,
    query: String,
    now: NowPlaying,
    bottom: PaddingValues,
    play: (List<Track>, Int, Boolean) -> Unit,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    onFolder: (String) -> Unit,
) {
    val artists = all.groupBy { artistName(it) }.entries.filter { SettingsSearch.matches(query, it.key) }.sortedBy { it.key.lowercase() }
    val albums = all.groupBy { it.albumKey }.entries.filter { SettingsSearch.matches(query, it.value.first().album) }
        .sortedBy { it.value.first().album.lowercase() }
    // Every folder, also those that hold only other folders, by its own name.
    val folders = all.flatMap { t ->
        val parts = t.folder.split('/').filter { it.isNotEmpty() }
        parts.indices.map { parts.take(it + 1).joinToString("/") to t }
    }.groupBy({ it.first }, { it.second }).entries
        .filter { SettingsSearch.matches(query, it.key.substringAfterLast('/')) }.sortedBy { it.key.lowercase() }
    val songs = all.filter { SettingsSearch.matches(query, it.title) }.sortedBy { it.title.lowercase() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = bottom) {
        fun heading(label: Int) = item(key = "heading $label") {
            Text(
                str(label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.animateItem().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        if (artists.isNotEmpty()) {
            heading(R.string.music_artists)
            groupItems(artists, Icons.Outlined.Person, { key, _ -> key }, { plural(R.plurals.music_tracks, it.size) }, onArtist)
        }
        if (albums.isNotEmpty()) {
            heading(R.string.music_albums)
            groupItems(albums, Icons.Outlined.Album, { _, list -> list.first().album }, { "${artistName(it.first())} · ${plural(R.plurals.music_tracks, it.size)}" }, onAlbum)
        }
        if (folders.isNotEmpty()) {
            heading(R.string.music_folders)
            groupItems(folders, Icons.Outlined.Folder, { key, _ -> key.substringAfterLast('/') }, { plural(R.plurals.music_tracks, it.size) }, onFolder)
        }
        if (songs.isNotEmpty()) {
            heading(R.string.music_songs)
            itemsIndexed(songs, key = { _, t -> "song " + t.uri }) { i, track ->
                TrackRow(track, track.uri == now.uri, now.playing, Modifier.animateItem()) { play(songs, i, false) }
            }
        }
        if (artists.isEmpty() && albums.isEmpty() && folders.isEmpty() && songs.isEmpty()) {
            item(key = "none") {
                Text(
                    str(R.string.music_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

/** The picture of the track, decoded once per track. */
@Composable
private fun rememberArtwork(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }

@Composable
private fun Artwork(now: NowPlaying, corner: Dp, modifier: Modifier = Modifier) {
    val picture = rememberArtwork(now.artwork)
    Crossfade(picture to now.uri, modifier.clip(RoundedCornerShape(corner)), label = "artwork") { (image, _) ->
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxSize(0.4f),
                )
            }
        }
    }
}

/** Where the track is, and how long it is, read again twice a second while it plays. */
@Composable
private fun rememberProgress(now: NowPlaying): Pair<Long, Long> {
    var position by remember { mutableLongStateOf(MusicPlayer.position()) }
    var length by remember { mutableLongStateOf(MusicPlayer.duration()) }
    LaunchedEffect(now.uri, now.playing) {
        while (true) {
            position = MusicPlayer.position()
            length = MusicPlayer.duration()
            if (!now.playing) break
            delay(500)
        }
    }
    return position to length
}

@Composable
private fun PlayPauseIcon(playing: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        playing,
        transitionSpec = { (scaleIn(spring(dampingRatio = 0.6f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
        label = "play pause",
    ) {
        Icon(
            if (it) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = str(if (it) R.string.music_pause else R.string.music_play),
            modifier = modifier,
        )
    }
}

/** What plays now, over the bottom bar, with its controls; a touch opens it on the whole screen. */
@Composable
private fun NowBar(now: NowPlaying) {
    val context = LocalContext.current
    val (position, length) = rememberProgress(now)
    val progress by animateFloatAsState(if (length > 0) position.toFloat() / length else 0f, label = "progress")
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable { musicExpanded.value = true },
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(now, 14.dp, Modifier.size(48.dp))
                AnimatedContent(
                    now.title to now.artist,
                    transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                    label = "now title",
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                ) { (title, artist) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (artist.isNotEmpty()) {
                            Text(artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { MusicPlayer.previous(context) }) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = str(R.string.music_previous))
                }
                IconButton(onClick = { MusicPlayer.toggle(context) }) { PlayPauseIcon(now.playing) }
                IconButton(onClick = { MusicPlayer.next(context) }) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = str(R.string.music_next))
                }
            }
            // How far the track has gone, as a thin line along the bottom.
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp).height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}

/** The player on the whole screen, as music apps have it: a drag down or back closes it. */
@Composable
internal fun MusicFullScreen() {
    val open by musicExpanded.collectAsState()
    val now by MusicPlayer.now.collectAsState()
    LaunchedEffect(now.queued) { if (!now.queued) musicExpanded.value = false }
    AnimatedVisibility(
        visible = open && now.queued,
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium)) { it } + fadeOut(),
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val close = { musicExpanded.value = false }
        BackHandler(onBack = close)
        val drag = remember { Animatable(0f) }
        val colors = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .draggable(
                    rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                    Orientation.Vertical,
                    onDragStopped = { speed ->
                        if (drag.value > 280f || speed > 2500f) {
                            close()
                            drag.snapTo(0f)
                        } else {
                            drag.animateTo(0f, spring(dampingRatio = 0.7f))
                        }
                    },
                )
                .background(Brush.verticalGradient(listOf(colors.secondaryContainer, colors.surface, colors.surface))),
        ) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = str(R.string.music_close))
                    }
                    Text(
                        str(R.string.music_now_playing),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    // Stopped, the queue is emptied and the bar of the music leaves every page.
                    IconButton(onClick = { MusicPlayer.stop(context) }) {
                        Icon(Icons.Outlined.Stop, contentDescription = str(R.string.music_stop))
                    }
                }
                Spacer(Modifier.weight(1f))
                // The cover breathes out while the music plays and draws back when it is paused.
                val scale by animateFloatAsState(
                    if (now.playing) 1f else 0.86f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "cover",
                )
                val lift by animateDpAsState(if (now.playing) 24.dp else 6.dp, label = "cover shadow")
                Artwork(
                    now,
                    28.dp,
                    Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .shadow(lift, RoundedCornerShape(28.dp)),
                )
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    now.uri,
                    transitionSpec = {
                        (slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it / 3 } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut())
                    },
                    label = "full title",
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(now.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.basicMarquee())
                        Text(
                            listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                val (position, length) = rememberProgress(now)
                // While the thumb is dragged it shows where it is, not where the music is.
                var seeking by remember { mutableStateOf<Float?>(null) }
                var dragged by remember { mutableFloatStateOf(0f) }
                Slider(
                    value = seeking ?: if (length > 0) position.toFloat() / length else 0f,
                    onValueChange = { seeking = it; dragged = it },
                    onValueChangeFinished = {
                        MusicPlayer.seekTo(context, (dragged * length).toLong())
                        seeking = null
                    },
                    enabled = length > 0,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(duration(seeking?.let { (it * length).toLong() } ?: position), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(duration(length), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { MusicPlayer.setShuffle(context, !now.shuffle) }) {
                        val tint by animateColorAsState(if (now.shuffle) colors.primary else colors.onSurfaceVariant, label = "shuffle")
                        Icon(Icons.Outlined.Shuffle, contentDescription = str(R.string.music_shuffle), tint = tint)
                    }
                    IconButton(onClick = { MusicPlayer.previous(context) }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = str(R.string.music_previous), modifier = Modifier.size(40.dp))
                    }
                    // A circle while paused, a softer square while playing.
                    val corner by animateDpAsState(if (now.playing) 28.dp else 44.dp, spring(dampingRatio = 0.6f), label = "play shape")
                    Surface(
                        shape = RoundedCornerShape(corner),
                        color = colors.primary,
                        contentColor = colors.onPrimary,
                        modifier = Modifier.size(88.dp).clip(RoundedCornerShape(corner)).clickable { MusicPlayer.toggle(context) },
                    ) {
                        Box(contentAlignment = Alignment.Center) { PlayPauseIcon(now.playing, Modifier.size(44.dp)) }
                    }
                    IconButton(onClick = { MusicPlayer.next(context) }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = str(R.string.music_next), modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { MusicPlayer.cycleRepeat(context) }) {
                        val tint by animateColorAsState(if (now.repeat != Player.REPEAT_MODE_OFF) colors.primary else colors.onSurfaceVariant, label = "repeat")
                        Icon(
                            if (now.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            contentDescription = str(R.string.music_repeat),
                            tint = tint,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The folder of the music, with its subfolders, chosen with the file picker of the phone. */
@Composable
internal fun MusicSettings() {
    val context = LocalContext.current
    val folder by MusicLibrary.folder.collectAsState()
    val tracks by MusicLibrary.tracks.collectAsState()
    val scanning by MusicLibrary.scanning.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) MusicLibrary.choose(context, uri)
    }
    // "primary:Music/Rock" is shown as "Music/Rock".
    val name = folder?.let { runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringAfter(':') }.getOrNull() }
    Setting(
        title = str(R.string.music_folder),
        text = when {
            folder == null -> str(R.string.music_folder_none)
            name.isNullOrEmpty() -> str(R.string.music_folder_root)
            else -> name
        },
        onClick = { pick.launch(folder?.let(Uri::parse)) },
    )
    if (folder != null) {
        Setting(
            title = str(R.string.music_rescan),
            text = if (scanning) str(R.string.music_reading) else plural(R.plurals.music_tracks, tracks.orEmpty().size),
            onClick = if (scanning) null else ({ MusicLibrary.refresh(context) }),
        )
        Setting(title = str(R.string.music_folder_remove), onClick = { MusicLibrary.choose(context, null) })
    }
}
