package app.hommaterial.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val REPO = "carellice/hommaterial"
private const val LATEST_RELEASE = "https://api.github.com/repos/$REPO/releases/latest"
// Anything else in a release answer is not an APK published by release.command.
private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"

data class Update(val version: String, val apkUrl: String, val sizeBytes: Long)

/** Finds, downloads and hands over to Android the releases published on GitHub. */
class Updater(private val context: Context, private val http: OkHttpClient) {
    private val dir = File(context.cacheDir, "updates")

    val installedVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The latest release, or null when the installed version is already that one. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Hommaterial/$installedVersion")
            .build()
        http.newCall(request).execute().use { response ->
            when {
                // No release published yet.
                response.code == 404 -> return@withContext null
                response.code == 403 || response.code == 429 ->
                    throw Exception("Troppe richieste a GitHub, riprova più tardi")
                !response.isSuccessful -> throw Exception("GitHub ha risposto con errore ${response.code}")
            }
            val release = JSONObject(response.body?.string().orEmpty())
            val version = release.getString("tag_name").removePrefix("v")
            if (!isNewer(version, installedVersion)) return@withContext null
            val apk = release.getJSONArray("assets").mapObjects { it }.firstOrNull {
                it.getString("name").endsWith(".apk") &&
                    it.getString("browser_download_url").startsWith(DOWNLOAD_PREFIX)
            } ?: throw Exception("La versione $version non contiene un APK")
            Update(version, apk.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    /** Downloads the APK, reporting progress from 0 to 1, and checks it before returning it. */
    suspend fun download(update: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "Hommaterial-${update.version}.apk")
        http.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download non riuscito (errore ${response.code})")
            val body = response.body ?: throw Exception("Download non riuscito")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        verify(file)
        file
    }

    // Android refuses an APK signed with another key anyway; this says so before the installer opens.
    @Suppress("DEPRECATION")
    private fun verify(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: fail(file, "Il file scaricato non è un APK valido")
        if (archive.packageName != context.packageName) fail(file, "Il file scaricato non è Hommaterial")
        // Some Android versions do not report the signatures of an APK that is not installed.
        val downloaded = signers(archive) ?: return
        val installed = signers(pm.getPackageInfo(context.packageName, flags)) ?: return
        if (downloaded != installed) {
            fail(file, "L'aggiornamento è firmato con una chiave diversa da quella dell'app installata")
        }
    }

    private fun fail(file: File, message: String): Nothing {
        file.delete()
        throw Exception(message)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures?.map { it.toCharsString() }?.toSet()?.takeIf { it.isNotEmpty() }
    }

    /** Opens Android's installer, which asks the user for confirmation. */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Compares dotted versions number by number, so that 1.10 comes after 1.9. */
internal fun isNewer(candidate: String, current: String): Boolean {
    val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
