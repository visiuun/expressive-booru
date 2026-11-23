package dev.vis.booru.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vis.booru.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A paged list of posts for one (source, query). Feeds live in [FeedRegistry] rather than in a
 * ViewModel so the grid and the full-screen viewer page through the very same list.
 *
 * The [Everything.ID] source pages through every usable site at once and interleaves them.
 */
class Feed(val key: String, val sourceId: String, val query: String) {
    var posts by mutableStateOf<List<Post>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var endReached by mutableStateOf(false); private set
    var pagesLoaded by mutableIntStateOf(0); private set

    private var job: Job? = null
    private var generation = 0
    // md5 when known, so the same image cross-posted to several sites only shows once.
    private val seen = HashSet<String>()
    private val finishedSources = HashSet<String>()

    val isEverything get() = sourceId == Everything.ID
    val source: Source? get() = Graph.sources.resolve(sourceId)

    fun loadMore() {
        if (loading || endReached) return
        if (source == null) { error = ApiException("This site was removed."); return }
        loading = true
        error = null
        val page = pagesLoaded
        val gen = generation
        job = scope.launch {
            try {
                val batch = if (isEverything) fetchEverything(page) else Graph.api.posts(source!!, Everything.translate(query, source!!.type), page)
                if (gen != generation) return@launch
                val fresh = batch.filter { seen.add(it.md5 ?: it.key) }
                posts = posts + fresh
                pagesLoaded = page + 1
                // Some sites keep returning the last page forever; stop when nothing new arrives.
                if (batch.isEmpty() || fresh.isEmpty()) endReached = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e
            } finally {
                // A refresh may have started a newer load; only the current one clears the flags.
                if (gen == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    /** One page from every site that still has results, merged round-robin. Fails only if all fail. */
    private suspend fun fetchEverything(page: Int): List<Post> = coroutineScope {
        val sites = Everything.sites().filter { it.id !in finishedSources }
        if (sites.isEmpty()) return@coroutineScope emptyList()
        val results = sites.map { s ->
            async { s to runCatching { Graph.api.posts(s, Everything.translate(query, s.type), page, limit = Everything.PER_SITE) } }
        }.awaitAll()
        val ok = results.mapNotNull { (s, r) -> r.getOrNull()?.let { s to it } }
        if (ok.isEmpty()) throw results.first().second.exceptionOrNull() ?: ApiException("No site answered.")
        ok.filter { it.second.isEmpty() }.forEach { finishedSources += it.first.id }
        results.filter { it.second.isFailure }.forEach { finishedSources += it.first.id }
        val lists = ok.map { it.second }
        buildList { for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { l -> l.getOrNull(i)?.let(::add) } }
    }

    fun refresh() {
        generation++
        job?.cancel()
        loading = false
        refreshing = true
        posts = emptyList()
        seen.clear()
        finishedSources.clear()
        pagesLoaded = 0
        endReached = false
        error = null
        loadMore()
    }

    fun retry() { error = null; loadMore() }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

/** The "Everything" pseudo-site: every booru in your list that can answer without extra setup. */
object Everything {
    const val ID = "all"
    const val PER_SITE = 15

    val source = Source(ID, "Everything", ApiType.GELBOORU, apiUrl = "", webUrl = "")

    /** Sites that need a key are included once you've added one. */
    fun sites(): List<Source> = Graph.sources.items.value.filter { !it.needsAuth || it.hasAuth }

    /** Site to ask for tag suggestions: Danbooru's are the best categorised. */
    fun autocompleteSite(): Source? = sites().let { s -> s.firstOrNull { it.type == ApiType.DANBOORU } ?: s.firstOrNull() }

    /** Rewrites sort meta-tags between the Gelbooru (sort:) and Danbooru/e621/Moebooru (order:) dialects. */
    fun translate(query: String, type: ApiType): String = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { t ->
        when {
            type == ApiType.GELBOORU && t.startsWith("order:") -> "sort:" + t.removePrefix("order:")
            type != ApiType.GELBOORU && t.startsWith("sort:") -> "order:" + t.removePrefix("sort:").substringBefore(':')
            else -> t
        }
    }
}

object FeedRegistry {
    private const val MAX = 16
    private val feeds = object : LinkedHashMap<String, Feed>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Feed>) = size > MAX
    }

    fun key(sourceId: String, query: String) = "$sourceId|${query.trim()}"

    fun get(sourceId: String, query: String): Feed = get(key(sourceId, query))

    /** Rebuilds a feed from its key, so a viewer restored after process death still works. */
    fun get(key: String): Feed = feeds.getOrPut(key) {
        Feed(key, key.substringBefore('|'), key.substringAfter('|', ""))
    }

    /** Drop every cached feed for a source (its keys or URL changed), and the merged feeds too. */
    fun invalidate(sourceId: String) {
        feeds.keys.filter { it.startsWith("$sourceId|") || it.startsWith("${Everything.ID}|") }.forEach { feeds.remove(it) }
    }
}
