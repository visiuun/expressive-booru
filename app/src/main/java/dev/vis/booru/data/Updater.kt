package dev.vis.booru.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import dev.vis.booru.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val pageUrl: String)

/**
 * Self-update from GitHub Releases. The latest release must carry a signed APK asset;
 * Android only accepts it if it is signed with the same key as the installed app.
 */
object Updater {
    const val REPO = "visiuun/expressive-booru"
    const val REPO_URL = "https://github.com/$REPO"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "ExpressiveBooru/${BuildConfig.VERSION_NAME}")
    }

    /** Returns the latest release if it is newer than the running build, otherwise null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) throw IOException("GitHub returned HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.optString("tag_name").removePrefix("v").removePrefix("V")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return@withContext null
            if (!isNewer(version, currentVersion)) return@withContext null
            Release(
                version = version,
                notes = plainText(json.optString("body")),
                apkUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
                pageUrl = json.optString("html_url", REPO_URL),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Release notes are Markdown; show them as plain text with bullets. */
    private fun plainText(md: String): String = md.lines()
        .map { line ->
            line.trim()
                .replace(Regex("""^#{1,6}\s*"""), "")
                .replace(Regex("""^[-*+]\s+"""), "• ")
                .replace(Regex("""\*\*|__|`"""), "")
                .replace(Regex("""(?<![\w*])\*(?!\s)([^*]+?)\*(?!\w)"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Compares dotted versions numerically: 1.10.0 > 1.9.2. Suffixes like "-beta" are ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "expressive-booru-${release.version}.apk")
        val conn = open(release.apkUrl)
        try {
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the APK to the system installer, which shows its own confirmation screen. */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** Receives installer status; brings up the system confirmation dialog when it is needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

data class UpdateUi(
    val checking: Boolean = false,
    val release: Release? = null,
    /** Download progress 0..1 while an update is being fetched. */
    val progress: Float? = null,
    val message: String? = null,
    val dismissed: Boolean = false,
)

/** App-wide update state: checked at launch (at most every 6 hours), installable from the dialog or Settings. */
object UpdateManager {
    private const val PREFS = "updates"
    private const val LAST_CHECK = "last_check"
    private const val INTERVAL_MS = 6 * 3600_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(UpdateUi())
    val state: StateFlow<UpdateUi> = _state.asStateFlow()

    fun checkIfDue(context: Context, enabled: Boolean) {
        if (!enabled) return
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong(LAST_CHECK, 0L) > INTERVAL_MS) check(context, manual = false)
    }

    fun check(context: Context, manual: Boolean = true) {
        if (_state.value.checking || _state.value.progress != null) return
        val app = context.applicationContext
        scope.launch {
            _state.update { it.copy(checking = true, message = null, dismissed = false) }
            try {
                val r = Updater.check()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply()
                _state.update {
                    it.copy(
                        checking = false, release = r,
                        message = if (r == null) "You're on the latest version (${Updater.currentVersion})" else "Version ${r.version} is available",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, message = if (manual) "Couldn't check for updates" else null) }
            }
        }
    }

    fun install(context: Context) {
        val r = _state.value.release ?: return
        val app = context.applicationContext
        if (!Updater.canInstall(app)) {
            _state.update { it.copy(message = "Allow installing apps from Expressive Booru, then tap Update again") }
            Updater.openInstallPermissionSettings(app)
            return
        }
        scope.launch {
            _state.update { it.copy(progress = 0f, message = null) }
            try {
                val apk = Updater.download(app, r) { p -> _state.update { it.copy(progress = p) } }
                Updater.install(app, apk)
                _state.update { it.copy(progress = null, message = "Installing ${r.version}…") }
            } catch (e: Exception) {
                _state.update { it.copy(progress = null, message = "Update failed: ${e.message ?: "unknown error"}") }
            }
        }
    }

    fun dismiss() = _state.update { it.copy(dismissed = true) }
}
