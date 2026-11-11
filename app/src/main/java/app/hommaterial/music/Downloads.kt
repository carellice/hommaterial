package app.hommaterial.music

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.hommaterial.R
import app.hommaterial.data.ServerSync
import app.hommaterial.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The songs of the home server kept on the phone, to play them without a network: each in a folder
 * of the app, at the same place it has on the server.
 */
object MusicDownloads {
    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())

    /** The addresses on the server of the songs that are on the phone too. */
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()
    private val _waiting = MutableStateFlow(0)

    /** How many songs are still to download. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()
    private val _progress = MutableStateFlow(0f)

    /** How far the downloads asked so far have gone, from 0 to 1, the song being downloaded counted by its bytes. */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** The songs asked since the downloads last ended, and the part of the current one already on the phone. */
    private var total = 0
    private var current = 0f
    private val jobs = mutableListOf<Job>()

    private fun showProgress() {
        _progress.value = if (total == 0) 0f else ((total - _waiting.value + current) / total).coerceIn(0f, 1f)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private fun dir(context: Context) = File(context.filesDir, "music-server")

    /** The place on the server of the song at [url], from its address. */
    private fun pathOf(url: String): String? = Uri.parse(url).getQueryParameter("path")?.takeIf { it.isNotBlank() }

    /** Where the song at [url] is kept on the phone; null for an address that is not of a song of the server. */
    fun fileFor(context: Context, url: String): File? {
        val path = pathOf(url) ?: return null
        val root = dir(context).canonicalFile
        val file = File(root, path).canonicalFile
        return file.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    /** The file of the song at [url] when it is on the phone. */
    fun local(context: Context, url: String): File? = fileFor(context, url)?.takeIf { it.isFile }

    fun init(context: Context) {
        scope.launch { recount(context.applicationContext) }
    }

    private fun recount(context: Context) {
        val root = dir(context)
        val paths = root.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        _downloaded.value = paths.map { ServerSync.musicUrl(it) }.toSet()
    }

    /** Downloads, one after the other, the songs of [tracks] that are not yet on the phone. */
    fun download(context: Context, tracks: List<Track>) {
        val app = context.applicationContext
        val missing = tracks.filter { it.uri.startsWith("http") && it.uri !in _downloaded.value }
        if (missing.isEmpty()) return
        synchronized(jobs) {
            total += missing.size
            _waiting.value += missing.size
            showProgress()
        }
        tell(app, str(R.string.music_downloading, missing.size))
        val job = scope.launch {
            var failed = 0
            mutex.withLock {
                for (track in missing) {
                    var part: File? = null
                    try {
                        val file = fileFor(app, track.uri) ?: throw IOException("no path")
                        file.parentFile?.mkdirs()
                        part = File(file.path + ".part")
                        open(track.uri).let { connection ->
                            try {
                                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                                val length = connection.contentLengthLong
                                connection.inputStream.use { input ->
                                    part.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        var read = 0L
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            output.write(buffer, 0, n)
                                            read += n
                                            if (length > 0) {
                                                current = read.toFloat() / length
                                                showProgress()
                                            }
                                        }
                                    }
                                }
                            } finally {
                                connection.disconnect()
                            }
                        }
                        if (!part.renameTo(file)) throw IOException("rename")
                        _downloaded.value = _downloaded.value + track.uri
                    } catch (e: CancellationException) {
                        part?.delete()
                        throw e
                    } catch (e: Exception) {
                        part?.delete()
                        failed++
                    } finally {
                        synchronized(jobs) {
                            _waiting.value = (_waiting.value - 1).coerceAtLeast(0)
                            current = 0f
                            if (_waiting.value == 0) total = 0
                            showProgress()
                        }
                    }
                }
            }
            val done = missing.size - failed
            tell(app, if (failed == 0) str(R.string.music_download_done, done) else str(R.string.music_download_failed, failed))
        }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }

    /** Stops the downloads in progress and those waiting; the songs already downloaded stay on the phone. */
    fun stop(context: Context) {
        val running = synchronized(jobs) { jobs.toList() }
        if (running.isEmpty()) return
        running.forEach { it.cancel() }
        scope.launch {
            running.joinAll()
            synchronized(jobs) {
                // shortcut: a download asked while stopping is stopped too, which is what a user tapping stop expects.
                _waiting.value = 0
                total = 0
                current = 0f
                showProgress()
            }
            tell(context.applicationContext, str(R.string.music_download_stopped))
        }
    }

    /** Takes the songs of [tracks] off the phone: they stay on the server and play again by streaming. */
    fun remove(context: Context, tracks: List<Track>) {
        val app = context.applicationContext
        val kept = tracks.filter { it.uri in _downloaded.value }
        if (kept.isEmpty()) return
        scope.launch {
            for (track in kept) fileFor(app, track.uri)?.delete()
            _downloaded.value = _downloaded.value - kept.map { it.uri }.toSet()
            tell(app, str(R.string.music_removed, kept.size))
        }
    }

    /** The bytes at [url] of the home server, with the session of the account; null when it does not answer. */
    fun fetch(url: String): ByteArray? = runCatching {
        val connection = open(url)
        try {
            if (connection.responseCode in 200..299) connection.inputStream.use { it.readBytes() } else null
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 60_000
        ServerSync.sessionToken()?.let { setRequestProperty("X-Session", it) }
    }

    /** The room the songs take on the phone, in bytes. */
    fun size(context: Context): Long = dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll(context: Context) {
        scope.launch {
            mutex.withLock {
                dir(context.applicationContext).deleteRecursively()
                _downloaded.value = emptySet()
            }
        }
    }

    private fun tell(context: Context, text: String) =
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
}
