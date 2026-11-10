package app.hommaterial.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import app.hommaterial.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A song of the chosen folder; [folder] is where it sits under it ("" for the folder itself, "Rock/Queen"). */
data class Track(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    /** The album it belongs to: the same name by another artist, or in another folder, is another album. */
    val albumKey: String,
    val folder: String,
    val disc: Int,
    val number: Int,
    val duration: Long,
    val modified: Long,
)

/**
 * The music of the folder chosen in the settings, with its subfolders. What the files say of
 * themselves (title, artist, album) is read once and kept in a file: a new scan reads only the
 * files that changed since.
 */
object MusicLibrary {
    private const val PREFS = "music"
    private const val KEY_FOLDER = "folder"
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "mka")

    private val _folder = MutableStateFlow<String?>(null)

    /** The tree of the chosen folder, null until one is chosen. */
    val folder: StateFlow<String?> = _folder.asStateFlow()
    private val _tracks = MutableStateFlow<List<Track>?>(null)

    /** Null until read from the file; ordered by artist, album and track number. */
    val tracks: StateFlow<List<Track>?> = _tracks.asStateFlow()
    val scanning = MutableStateFlow(false)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads the folder again in the background, whatever page is left meanwhile. */
    fun refresh(context: Context) {
        scope.launch { scan(context.applicationContext) }
    }

    /** Like [setFolder], in the background. */
    fun choose(context: Context, tree: Uri?) {
        scope.launch { setFolder(context.applicationContext, tree) }
    }

    fun init(context: Context) {
        _folder.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
        Thread { load(context) }.start()
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "music.json")

    /** The tracks known, read from the file when they are not yet. */
    @Synchronized
    fun load(context: Context): List<Track> {
        _tracks.value?.let { return it }
        val list = runCatching {
            val array = JSONArray(cacheFile(context).readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Track(
                    o.getString("uri"), o.getString("title"), o.getString("artist"), o.getString("album"),
                    o.getString("albumKey"), o.getString("folder"), o.getInt("disc"), o.getInt("number"),
                    o.getLong("duration"), o.getLong("modified"),
                )
            }
        }.getOrDefault(emptyList())
        _tracks.value = list
        return list
    }

    private fun save(context: Context, list: List<Track>) {
        val array = JSONArray()
        for (t in list) {
            array.put(
                JSONObject()
                    .put("uri", t.uri).put("title", t.title).put("artist", t.artist).put("album", t.album)
                    .put("albumKey", t.albumKey).put("folder", t.folder).put("disc", t.disc).put("number", t.number)
                    .put("duration", t.duration).put("modified", t.modified),
            )
        }
        cacheFile(context).writeText(array.toString())
    }

    /** Chooses [tree] as the folder of the music, or none; the old one stops being readable by the app. */
    suspend fun setFolder(context: Context, tree: Uri?) {
        val resolver = context.contentResolver
        _folder.value?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (tree != null) resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FOLDER, tree?.toString()).apply()
        _folder.value = tree?.toString()
        _tracks.value = emptyList()
        withContext(Dispatchers.IO) { save(context, emptyList()) }
        if (tree != null) scan(context)
    }

    /** Reads the folder again: new files are added, removed ones go, changed ones are read again. */
    suspend fun scan(context: Context) = mutex.withLock {
        val tree = _folder.value?.let(Uri::parse) ?: return@withLock
        scanning.value = true
        try {
            withContext(Dispatchers.IO) {
                val known = load(context).associateBy { it.uri }
                val files = mutableListOf<Triple<Uri, String, Long>>()
                walk(context, tree, DocumentsContract.getTreeDocumentId(tree), "", files)
                val list = files.map { (uri, folder, modified) ->
                    known[uri.toString()]?.takeIf { it.modified == modified && it.folder == folder }
                        ?: read(context, uri, folder, modified)
                }.sortedWith(
                    compareBy<Track>({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.number }, { it.title.lowercase() }),
                )
                save(context, list)
                _tracks.value = list
            }
        } catch (e: Exception) {
            // A folder that is gone, or no longer allowed, leaves what was known as it was.
            Log.w("Music", "scan failed", e)
        } finally {
            scanning.value = false
        }
    }

    private fun walk(context: Context, tree: Uri, id: String, path: String, out: MutableList<Triple<Uri, String, Long>>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val dirs = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val child = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".")) dirs += child to name
                } else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                    out += Triple(DocumentsContract.buildDocumentUriUsingTree(tree, child), path, c.getLong(3))
                }
            }
        }
        for ((child, name) in dirs) walk(context, tree, child, if (path.isEmpty()) name else "$path/$name", out)
    }

    private fun read(context: Context, uri: Uri, folder: String, modified: Long): Track {
        val file = Uri.decode(uri.lastPathSegment.orEmpty()).substringAfterLast('/').substringBeforeLast('.')
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            val album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: folder.substringAfterLast('/')
            val owner = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: folder
            return Track(
                uri = uri.toString(),
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file,
                artist = artist,
                album = album,
                albumKey = "$album\u0000$owner",
                folder = folder,
                disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                number = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0,
                duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                modified = modified,
            )
        } catch (e: Exception) {
            // A file that cannot be read still plays, or is skipped by the player: it goes by its name.
            return Track(uri.toString(), file, "", folder.substringAfterLast('/'), "\u0000$folder", folder, 0, 0, 0, modified)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** What plays now, for the bar of the music page. */
data class NowPlaying(
    /** The address of the track that plays, to mark it in the lists. */
    val uri: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** The picture held in the file, when it has one. */
    val artwork: ByteArray? = null,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    /** One of [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] and [Player.REPEAT_MODE_ONE]. */
    val repeat: Int = Player.REPEAT_MODE_OFF,
    /** Whether something is in the queue at all. */
    val queued: Boolean = false,
    /** What plays after this one, in the order it will play. */
    val upcoming: List<QueueEntry> = emptyList(),
)

/** A song waiting in the queue: [index] is its place among the items of the player. */
data class QueueEntry(val index: Int, val title: String, val artist: String)

/** The music plays in [PlaybackService]; the app talks to it through a controller of its own. */
object MusicPlayer {
    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now.asStateFlow()
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    private fun update(player: Player) {
        val meta = player.mediaMetadata
        _now.value = NowPlaying(
            uri = player.currentMediaItem?.mediaId,
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = meta.albumTitle?.toString().orEmpty(),
            artwork = meta.artworkData,
            playing = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            queued = player.mediaItemCount > 0,
            upcoming = upcoming(player),
        )
    }

    /** The songs after the one that plays, in the order they will play, shuffled or not. */
    private fun upcoming(player: Player): List<QueueEntry> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = mutableListOf<QueueEntry>()
        var i = player.currentMediaItemIndex
        while (out.size < QUEUE_SHOWN) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) break
            val meta = player.getMediaItemAt(i).mediaMetadata
            out += QueueEntry(i, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty())
        }
        return out
    }

    private const val QUEUE_SHOWN = 300

    /** Runs [action] once connected to the service, which starts if it is not running. */
    private fun withController(context: Context, action: (MediaController) -> Unit) {
        controller?.takeIf { it.isConnected }?.let { return action(it) }
        waiting += action
        if (connecting != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@MusicPlayer.controller = null
                    _now.value = NowPlaying()
                }
            })
            .buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener waiting.clear()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = update(player)
            })
            update(c)
            waiting.toList().also { waiting.clear() }.forEach { it(c) }
        }, ContextCompat.getMainExecutor(app))
    }

    /** Keeps the bar of the page up to date with music left playing by an earlier visit. */
    fun attach(context: Context) = withController(context) {}

    /** Plays [tracks] from the one at [start]; mixed, from a random one, when [shuffle]. */
    fun play(context: Context, tracks: List<Track>, start: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val items = tracks.map(::item)
        withController(context) { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, if (shuffle && start == 0) tracks.indices.random() else start, 0)
            c.prepare()
            c.play()
        }
    }

    private fun item(t: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.uri)
            .setUri(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifEmpty { null }).setAlbumTitle(t.album).build(),
            )
            .build()

    /**
     * Puts [track] in the queue: right after the one that plays when [next], at its end otherwise.
     * With nothing in the queue it plays at once.
     */
    fun enqueue(context: Context, track: Track, next: Boolean) = withController(context) { c ->
        if (c.mediaItemCount == 0) {
            c.setMediaItems(listOf(item(track)))
            c.prepare()
            c.play()
        } else if (next) {
            // shortcut: in shuffle the player gives the new song a random place of its order; fine until shuffle needs "next" too.
            c.addMediaItem(c.currentMediaItemIndex + 1, item(track))
        } else {
            c.addMediaItem(item(track))
        }
    }

    /** Jumps to the song at [index] of the queue. */
    fun playAt(context: Context, index: Int) = withController(context) {
        it.seekToDefaultPosition(index)
        it.play()
    }

    fun remove(context: Context, index: Int) = withController(context) { it.removeMediaItem(index) }

    fun toggle(context: Context) = withController(context) { if (it.isPlaying) it.pause() else it.play() }

    fun pause(context: Context) = withController(context) { it.pause() }

    /** Stops and empties the queue: nothing is left to show. */
    fun stop(context: Context) = withController(context) {
        it.stop()
        it.clearMediaItems()
    }

    fun resume(context: Context) = withController(context) { it.play() }

    fun next(context: Context) = withController(context) { it.seekToNextMediaItem() }

    fun previous(context: Context) = withController(context) { it.seekToPrevious() }

    /** The song before, even when this one is past its start: for a swipe on the cover. */
    fun previousTrack(context: Context) = withController(context) { it.seekToPreviousMediaItem() }

    fun setShuffle(context: Context, on: Boolean) = withController(context) { it.shuffleModeEnabled = on }

    /** Off, then the whole queue again and again, then the same track. */
    fun cycleRepeat(context: Context) = withController(context) {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun seekTo(context: Context, ms: Long) = withController(context) { it.seekTo(ms) }

    /** Where the track is and how long it lasts, in milliseconds: read often, while the player is in sight. */
    fun position(): Long = controller?.currentPosition ?: 0

    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0
}

/** Plays in the background, with the controls of the notification, the lock screen and headphones. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // The address of a file is not always handed over with the item: its id is the address.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList())
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away stops the music only when it was not playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
