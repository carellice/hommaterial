package app.hommaterial.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.hommaterial.R
import app.hommaterial.music.MusicLibrary
import app.hommaterial.music.MusicPlayer
import app.hommaterial.music.NowPlaying
import app.hommaterial.music.Track
import app.hommaterial.plural
import app.hommaterial.str

/** The page of the music of the phone, once a folder is chosen in the settings. */
const val TAB_MUSIC = "music"

// The folder is read again the first time the page opens after the app starts; later with the button.
private var scannedThisRun = false

private const val ALBUM = "album:"
private const val ARTIST = "artist:"

private fun artistName(track: Track) = track.artist.ifEmpty { str(R.string.music_unknown_artist) }

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
    BackHandler(enabled = opened != null) { opened = null }
    BackHandler(enabled = opened == null && view == 3 && path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }

    val all = tracks.orEmpty()
    val play = { list: List<Track>, start: Int, shuffle: Boolean -> MusicPlayer.play(context, list, start, shuffle) }
    val bottom = PaddingValues(bottom = BOTTOM_BAR_SPACE + if (now.queued) 80.dp else 0.dp)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val views = listOf(R.string.music_songs, R.string.music_albums, R.string.music_artists, R.string.music_folders)
            PrimaryTabRow(selectedTabIndex = view) {
                views.forEachIndexed { i, label ->
                    Tab(selected = view == i, onClick = { view = i; opened = null }, text = { Text(str(label), maxLines = 1) })
                }
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
            val key = opened
            if (key != null) {
                val list = if (key.startsWith(ALBUM)) {
                    all.filter { it.albumKey == key.removePrefix(ALBUM) }.sortedWith(compareBy({ it.disc }, { it.number }, { it.title.lowercase() }))
                } else {
                    all.filter { artistName(it) == key.removePrefix(ARTIST) }
                }
                val title = if (key.startsWith(ALBUM)) list.firstOrNull()?.album.orEmpty() else key.removePrefix(ARTIST)
                Tracks(list, now, bottom, header = { Header(title, list, onBack = { opened = null }, play) }, play)
                return@Column
            }
            when (view) {
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
                else -> Folders(all, path, now, bottom, onOpen = { path = it }, play)
            }
        }
        AnimatedVisibility(
            visible = now.queued,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_SPACE),
        ) {
            NowBar(now)
        }
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
        itemsIndexed(list, key = { _, t -> t.uri }) { i, track -> TrackRow(track, track.uri == now.uri) { play(list, i, false) } }
    }
}

@Composable
private fun TrackRow(track: Track, current: Boolean, onClick: () -> Unit) {
    val color = if (current) MaterialTheme.colorScheme.primary else Color.Unspecified
    ListItem(
        headlineContent = { Text(track.title, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${artistName(track)} · ${track.album}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = if (current) color else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = if (track.duration > 0) ({ Text(duration(track.duration)) }) else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
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
        items(groups, key = { it.key }) { (key, list) ->
            ListItem(
                headlineContent = { Text(name(list), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(detail(list), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(key) },
            )
        }
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
        items(subfolders, key = { "folder:" + it.key }) { (folder, list) ->
            ListItem(
                headlineContent = { Text(folder.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(plural(R.plurals.music_tracks, list.size)) },
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onOpen(folder) },
            )
        }
        itemsIndexed(here, key = { _, t -> t.uri }) { i, track -> TrackRow(track, track.uri == now.uri) { play(here, i, false) } }
    }
}

/** What plays now, over the bottom bar, with its controls. */
@Composable
private fun NowBar(now: NowPlaying) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now.artist.isNotEmpty()) {
                    Text(now.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconToggleButton(checked = now.shuffle, onCheckedChange = { MusicPlayer.setShuffle(context, it) }) {
                Icon(
                    Icons.Outlined.Shuffle,
                    contentDescription = str(R.string.music_shuffle),
                    tint = if (now.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            IconButton(onClick = { MusicPlayer.previous(context) }) {
                Icon(Icons.Outlined.SkipPrevious, contentDescription = str(R.string.music_previous))
            }
            IconButton(onClick = { MusicPlayer.toggle(context) }) {
                Icon(
                    if (now.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = str(if (now.playing) R.string.music_pause else R.string.music_play),
                )
            }
            IconButton(onClick = { MusicPlayer.next(context) }) {
                Icon(Icons.Outlined.SkipNext, contentDescription = str(R.string.music_next))
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
