package dev.vis.booru.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import android.widget.Toast
import dev.vis.booru.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Saves originals through the system DownloadManager into Downloads/Expressive Booru.
 * Name: "[artist] - <md5>_<platform>.<ext>", or just "<md5>_<platform>.<ext>"
 * when the site can't tell which tag is the artist (TBIB, HypnoHub). A single-tag bulk download passes
 * its tag as the artist for those.
 */
object Downloader {

    fun fileName(post: Post, source: Source?, url: String, artistHint: String? = null): String {
        val platform = (source?.name ?: post.sourceId).lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "booru" }
        val original = java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
        val ext = original.substringAfterLast('.', "").ifBlank { post.extension.ifBlank { "jpg" } }
        // The file's hash keeps names short and stable; some sites (yande.re, Konachan) put every tag in the URL's filename.
        val base = post.md5?.takeIf { it.isNotBlank() }?.let(::sanitize)
            ?: sanitize(original.substringBeforeLast('.', original)).ifBlank { post.id.toString() }
        val artist = post.artists.take(2).joinToString(", ") { sanitize(it) }
            .ifBlank { artistHint?.let(::sanitize).orEmpty() }
        val name = "${base}_$platform.$ext"
        return if (artist.isBlank()) name else "[$artist] - $name"
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)

    /** Queues one file; returns its name. Throws if the post has nothing to download. */
    fun enqueue(context: Context, post: Post, artistHint: String? = null): String {
        val app = context.applicationContext
        val source = Graph.sources.get(post.sourceId)
        val url = post.fileUrl ?: post.videoUrl ?: post.sampleUrl ?: throw IllegalStateException("This post has no downloadable file")
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val name = fileName(post, source, url, artistHint)
        // Lands in the app's folder first so the tags can be written in, then moves to Downloads (see SavedDownloads).
        val incoming = SavedDownloads.incomingDir(app) ?: throw IllegalStateException("Storage isn't available")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Expressive Booru download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", Graph.api.userAgent)
            .setDestinationUri(Uri.fromFile(File(incoming, "${System.nanoTime()}_$name")))
        source?.let { req.addRequestHeader("Referer", it.web + "/") }
        val id = dm.enqueue(req)
        val artists = post.artists.ifEmpty { listOfNotNull(artistHint) }
        SavedDownloads.remember(app, id, PendingDownload(name, post.tags, artists, source?.postPageUrl(post.id)))
        Graph.downloadLog.add(post)
        return name
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Set by the activity: asks for the notification (Android 13+) or storage (Android 8–9) permission. */
    var askPermissions: (() -> Unit)? = null
    private var asked = false

    fun requestPermissionsOnce() {
        if (!asked) { asked = true; askPermissions?.invoke() }
    }

    /** Looks up the post's tag types first where the site only gives flat tags, so the artist is in the name. */
    fun download(context: Context, post: Post) {
        val app = context.applicationContext
        requestPermissionsOnce()
        scope.launch {
            val typed = Graph.sources.get(post.sourceId)
                ?.let { s -> runCatching { Graph.api.withTagTypes(s, listOf(post)).first() }.getOrNull() } ?: post
            try {
                val name = enqueue(app, typed)
                Toast.makeText(app, "Downloading $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * "Download all" for a search: walks every page of the query and queues each post. Runs in its own
 * scope so leaving the screen doesn't stop it. Skips posts hidden by the rating filter or blacklist,
 * and posts already downloaded with this app.
 */
object BulkDownloader {
    const val MAX_POSTS = 3000

    data class Progress(
        val sourceId: String,
        val query: String,
        val queued: Int = 0,
        val skipped: Int = 0,
        val pages: Int = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val _state = MutableStateFlow<Progress?>(null)
    val state: StateFlow<Progress?> = _state.asStateFlow()

    val running: Boolean get() = job?.isActive == true

    /** A single plain tag (no meta tags, no negation) is taken to be the artist being downloaded. */
    fun artistHint(query: String): String? =
        query.trim().split(Regex("\\s+")).singleOrNull()?.takeIf { it.isNotBlank() && ':' !in it && !it.startsWith("-") && !it.startsWith("~") }

    fun start(context: Context, source: Source, query: String) {
        if (running) return
        val app = context.applicationContext
        Downloader.requestPermissionsOnce()
        val hint = artistHint(query)
        _state.value = Progress(source.id, query)
        job = scope.launch {
            val settings = Graph.settings.settings.first()
            var p = Progress(source.id, query)
            var seen = 0
            try {
                var page = 0
                while (seen < MAX_POSTS) {
                    val batch = fetchWithRetry(source, query, page)
                        .let { b -> runCatching { Graph.api.withTagTypes(source, b) }.getOrDefault(b) }
                    if (batch.isEmpty()) break
                    for (post in batch) {
                        if (seen >= MAX_POSTS) break
                        seen++
                        if (!settings.allows(post) || Graph.downloadLog.has(post)) {
                            p = p.copy(skipped = p.skipped + 1)
                            continue
                        }
                        p = try {
                            Downloader.enqueue(app, post, hint)
                            p.copy(queued = p.queued + 1)
                        } catch (e: IllegalStateException) {
                            p.copy(skipped = p.skipped + 1)
                        }
                    }
                    page++
                    p = p.copy(pages = page)
                    _state.value = p
                    delay(350) // stay polite to the site's API
                }
                p = p.copy(done = true)
            } catch (e: CancellationException) {
                p = p.copy(done = true, error = "Stopped")
                _state.value = p
                throw e
            } catch (e: Exception) {
                p = p.copy(done = true, error = friendlyError(e))
            }
            _state.value = p
            Toast.makeText(
                app,
                if (p.error != null && p.error != "Stopped") "Download all stopped: ${p.error} (${p.queued} queued)"
                else "Queued ${p.queued} download${if (p.queued == 1) "" else "s"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private suspend fun fetchWithRetry(source: Source, query: String, page: Int): List<Post> {
        repeat(2) { attempt ->
            try {
                return Graph.api.posts(source, query, page, limit = 100)
            } catch (e: ApiException) {
                if (e.needsAuth || attempt == 1) throw e
                delay(3000)
            } catch (e: java.io.IOException) {
                if (attempt == 1) throw e
                delay(3000)
            }
        }
        return emptyList()
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!running) _state.value = null
    }
}
