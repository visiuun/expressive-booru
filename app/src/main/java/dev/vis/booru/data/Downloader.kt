package dev.vis.booru.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
 * Name: "[artist] - <original filename>_<platform>.<ext>",.
 * Artist is "unknown" on sites whose API doesn't say which tag is the artist (Gelbooru-type sites),
 * unless the caller knows it (bulk downloads of a single-tag search pass that tag).
 */
object Downloader {

    fun fileName(post: Post, source: Source?, url: String, artistHint: String? = null): String {
        val platform = (source?.name ?: post.sourceId).lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "booru" }
        val original = java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8")
        val ext = original.substringAfterLast('.', "").ifBlank { post.extension.ifBlank { "jpg" } }
        val base = sanitize(original.substringBeforeLast('.', original)).ifBlank { post.md5 ?: post.id.toString() }
        val artist = post.artists.take(2).joinToString(", ") { sanitize(it) }
            .ifBlank { artistHint?.let(::sanitize).orEmpty() }
            .ifBlank { "unknown" }
        return "[$artist] - ${base}_$platform.$ext"
    }

    private fun sanitize(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80)

    /** Queues one file; returns its name. Throws if the post has nothing to download. */
    fun enqueue(context: Context, post: Post, artistHint: String? = null): String {
        val app = context.applicationContext
        val source = Graph.sources.get(post.sourceId)
        val url = post.fileUrl ?: post.videoUrl ?: post.sampleUrl ?: throw IllegalStateException("This post has no downloadable file")
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val name = fileName(post, source, url, artistHint)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Expressive Booru download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", Graph.api.userAgent)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Expressive Booru/$name")
        source?.let { req.addRequestHeader("Referer", it.web + "/") }
        dm.enqueue(req)
        Graph.downloadLog.add(post)
        return name
    }

    fun download(context: Context, post: Post) {
        val app = context.applicationContext
        try {
            val name = enqueue(app, post)
            Toast.makeText(app, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(app, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
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
