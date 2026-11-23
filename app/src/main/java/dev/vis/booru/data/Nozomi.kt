package dev.vis.booru.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * nozomi.la has no JSON search API. Search results are binary ".nozomi" files of big-endian
 * uint32 post IDs (read in pages with HTTP Range), each post is its own JSON file, and media
 * lives on CDN hosts addressed by the file's hash.
 */
internal class Nozomi(private val api: BooruApi) {
    private val base = "https://j.${BooruApi.NOZOMI_CDN}"

    private val sizeCache = HashMap<String, Long>()
    // Where each page of a filtered search starts in its driving ID list: query → [page0, page1, …].
    private val cursors = object : LinkedHashMap<String, MutableList<Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<Int>>) = size > 32
    }
    private val suggestCache = HashMap<Char, List<Pair<String, Int>>>()

    private data class Query(val include: List<String>, val exclude: List<String>, val popular: Boolean, val id: Long?)

    private fun parse(q: String): Query {
        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        var popular = false
        var id: Long? = null
        q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { t ->
            val low = t.lowercase()
            when {
                low.startsWith("id:") -> id = low.removePrefix("id:").toLongOrNull()
                low in POPULAR_TOKENS -> popular = true
                low.startsWith("order:") || low.startsWith("sort:") || low.startsWith("rating:") -> Unit
                low.startsWith("-") -> exclude += low.drop(1)
                else -> include += low
            }
        }
        return Query(include, exclude, popular, id)
    }

    private fun tagUrl(tag: String?, popular: Boolean): String = when {
        tag == null && popular -> "$base/index-Popular.nozomi"
        tag == null -> "$base/index.nozomi"
        popular -> "$base/nozomi/popular/${enc(tag)}-Popular.nozomi"
        else -> "$base/nozomi/${enc(tag)}.nozomi"
    }

    suspend fun posts(source: Source, query: String, page: Int, limit: Int): List<Post> {
        val q = parse(query)
        if (q.id != null) return if (page == 0) listOfNotNull(post(source, q.id.toInt())) else emptyList()
        if (q.include.size <= 1 && q.exclude.isEmpty()) {
            return fetchAll(source, ranged(tagUrl(q.include.firstOrNull(), q.popular), page * limit, limit))
        }
        // Several tags: walk the smallest list (or the popular one) and check the rest against each post's tags.
        val driverTag = if (q.popular) q.include.firstOrNull() else q.include.minByOrNull { size(tagUrl(it, false)) }
        val driver = tagUrl(driverTag, q.popular)
        val others = q.include - listOfNotNull(driverTag).toSet()
        val key = "${q.popular}|${q.include.sorted()}|${q.exclude.sorted()}"
        val starts = synchronized(cursors) { cursors.getOrPut(key) { mutableListOf(0) } }
        var pos = starts.getOrNull(page) ?: return emptyList()
        val out = mutableListOf<Post>()
        var scanned = 0
        while (out.size < limit && scanned < MAX_SCAN) {
            val batch = ranged(driver, pos, SCAN_BATCH)
            if (batch.isEmpty()) break
            val posts = fetchAll(source, batch).associateBy { it.id.toInt() }
            for (id in batch) {
                pos++; scanned++
                val p = posts[id] ?: continue
                val tags = p.tags.toHashSet()
                if (others.all { it in tags } && q.exclude.none { it in tags }) out += p
                if (out.size == limit) break
            }
        }
        synchronized(cursors) { if (starts.size == page + 1) starts += pos }
        return out
    }

    private suspend fun fetchAll(source: Source, ids: List<Int>): List<Post> = coroutineScope {
        ids.map { id -> async { runCatching { post(source, id) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    /** Byte size of an ID list (4 bytes per post), from a HEAD request. */
    private suspend fun size(url: String): Long {
        synchronized(sizeCache) { sizeCache[url] }?.let { return it }
        val n = withContext(Dispatchers.IO) {
            api.client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: Long.MAX_VALUE else 0L
            }
        }
        synchronized(sizeCache) { sizeCache[url] = n }
        return n
    }

    /** One page of IDs straight from the server with a Range request. */
    private suspend fun ranged(url: String, offset: Int, count: Int): List<Int> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Range", "bytes=${offset * 4}-${(offset + count) * 4 - 1}").build()
        api.client.newCall(req).execute().use { r ->
            when (r.code) {
                416, 404 -> emptyList()
                206 -> decode(r.body!!.bytes()).toList()
                200 -> decode(r.body!!.bytes()).drop(offset).take(count) // server ignored the range
                else -> throw IOException("Nozomi returned HTTP ${r.code}")
            }
        }
    }

    private fun decode(bytes: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(bytes) // big-endian by default
        return IntArray(bytes.size / 4) { buf.getInt(it * 4) }
    }

    private suspend fun post(source: Source, id: Int): Post? {
        val o = AppJson.parseToJsonElement(api.get(source, "$base/post/${path(id.toString())}.json")) as? JsonObject ?: return null
        val media = (o["imageurls"] as? JsonArray)?.firstOrNull() as? JsonObject ?: o
        val hash = media.str("dataid") ?: return null
        val type = media.str("type") ?: "jpg"
        val isVideo = media.str("is_video").let { !it.isNullOrBlank() && it != "0" && it != "false" }
        val file = when {
            isVideo -> "https://v.${BooruApi.NOZOMI_CDN}/${path(hash)}.$type"
            type == "gif" -> "https://g.${BooruApi.NOZOMI_CDN}/${path(hash)}.gif"
            else -> "https://w.${BooruApi.NOZOMI_CDN}/${path(hash)}.webp"
        }
        val groups = linkedMapOf<TagCategory, MutableList<String>>()
        for (field in listOf("artist", "copyright", "character", "general")) {
            (o[field] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEach { t ->
                val name = t.str("tag") ?: return@forEach
                val cat = when (t.str("tagtype") ?: field) {
                    "artist" -> TagCategory.ARTIST; "copyright" -> TagCategory.COPYRIGHT
                    "character" -> TagCategory.CHARACTER; "metadata" -> TagCategory.META
                    else -> TagCategory.GENERAL
                }
                groups.getOrPut(cat) { mutableListOf() } += name
            }
        }
        val sorted = groups.toSortedMap(compareBy { it.ordinal })
        return Post(
            sourceId = source.id, id = id.toLong(),
            previewUrl = "https://qtn.${BooruApi.NOZOMI_CDN}/${path(hash)}.$type.webp",
            sampleUrl = if (isVideo) null else file, fileUrl = file,
            width = (media["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            height = (media["height"] as? JsonPrimitive)?.intOrNull ?: 0,
            rating = Rating.UNKNOWN,
            tags = sorted.values.flatten(),
            tagGroups = sorted.ifEmpty { null },
            ext = if (isVideo) type else if (type == "gif") "gif" else "webp",
            createdAt = o.str("date")?.let { parseDate(it) },
        )
    }

    /** Tag suggestions from the per-letter tag list the site itself uses (tag → post count). */
    suspend fun autocomplete(term: String): List<TagSuggestion> {
        val t = term.lowercase()
        val letter = t.first().let { if (it in 'a'..'z') it else '0' }
        val all = synchronized(suggestCache) { suggestCache[letter] } ?: run {
            val obj = AppJson.parseToJsonElement(
                withContext(Dispatchers.IO) {
                    api.client.newCall(Request.Builder().url("$base/search-$letter.json").build()).execute().use { it.body!!.string() }
                },
            ) as? JsonObject ?: return emptyList()
            obj.entries.map { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0) }
                .sortedByDescending { it.second }
                .also { list -> synchronized(suggestCache) { suggestCache[letter] = list } }
        }
        return all.asSequence().filter { it.first.startsWith(t) }.take(12).map { TagSuggestion(it.first, it.second, null) }.toList()
    }

    private fun parseDate(s: String): Long? = runCatching {
        // "2026-11-23 09:52:13-06" → ISO offset
        OffsetDateTime.parse(s.replace(' ', 'T').let { if (Regex("[+-]\\d{2}$").containsMatchIn(it)) "$it:00" else it }, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toEpochSecond()
    }.getOrNull()

    companion object {
        private const val SCAN_BATCH = 60
        private const val MAX_SCAN = 600
        private val POPULAR_TOKENS = setOf("order:popular", "sort:popular", "order:score", "sort:score", "sort:score:desc")

        /** "…abc12" → "2/c1/…abc12", the CDN's directory layout. */
        fun path(hash: String): String = if (hash.length < 3) hash else "${hash.last()}/${hash.substring(hash.length - 3, hash.length - 1)}/$hash"

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
