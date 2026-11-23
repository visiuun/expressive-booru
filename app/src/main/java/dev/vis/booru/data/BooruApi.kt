package dev.vis.booru.data

import dev.vis.booru.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cache
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

/** Talks to every supported booru API and maps the responses to [Post]. */
class BooruApi(cacheDir: File) {

    val userAgent = "ExpressiveBooru/${BuildConfig.VERSION_NAME} (by anonymous)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .cache(Cache(cacheDir.resolve("http"), 32L * 1024 * 1024))
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder()
            if (req.header("User-Agent") == null) b.header("User-Agent", userAgent)
            // Nozomi's image hosts only answer requests that come from nozomi.la.
            if (req.header("Referer") == null && req.url.host.endsWith(NOZOMI_CDN)) b.header("Referer", "https://nozomi.la/")
            chain.proceed(b.build())
        }
        .build()

    suspend fun posts(source: Source, tags: String, page: Int, limit: Int = PAGE_SIZE): List<Post> {
        val q = enc(tags.trim())
        val url = when (source.type) {
            ApiType.GELBOORU -> "${source.apiUrl}/index.php?page=dapi&s=post&q=index&json=1&limit=$limit&pid=$page&tags=$q&fields=tag_info" + gelAuth(source)
            ApiType.DANBOORU -> "${source.apiUrl}/posts.json?limit=$limit&page=${page + 1}&tags=$q" + danAuth(source)
            ApiType.E621 -> "${source.apiUrl}/posts.json?limit=$limit&page=${page + 1}&tags=$q"
            ApiType.MOEBOORU -> "${source.apiUrl}/post.json?api_version=2&include_tags=1&limit=$limit&page=${page + 1}&tags=$q" + moeAuth(source)
            ApiType.NOZOMI -> return nozomi.posts(source, tags, page, limit)
        }
        val body = get(source, url)
        return when (source.type) {
            ApiType.GELBOORU -> parseGelbooru(source, body)
            ApiType.DANBOORU -> parseDanbooru(source, body)
            ApiType.E621 -> parseE621(source, body)
            ApiType.MOEBOORU -> parseMoebooru(source, body)
            ApiType.NOZOMI -> emptyList()
        }
    }

    private val nozomi = Nozomi(this)

    suspend fun autocomplete(source: Source, term: String): List<TagSuggestion> {
        val t = term.trim()
        if (t.length < source.type.minAutocomplete) return emptyList()
        val q = enc(t)
        val url = when (source.type) {
            ApiType.GELBOORU -> when {
                source.host.contains("gelbooru.com") -> "${source.apiUrl}/index.php?page=autocomplete2&term=$q&type=tag_query&limit=12"
                source.host.contains("rule34.xxx") -> "https://ac.rule34.xxx/autocomplete.php?q=$q"
                else -> "${source.apiUrl}/autocomplete.php?q=$q"
            }
            ApiType.DANBOORU -> "${source.apiUrl}/autocomplete.json?search%5Bquery%5D=$q&search%5Btype%5D=tag_query&limit=12"
            ApiType.E621 -> "${source.apiUrl}/tags/autocomplete.json?search%5Bname_matches%5D=$q&expiry=7"
            ApiType.MOEBOORU -> "${source.apiUrl}/tag.json?name=$q*&order=count&limit=12"
            ApiType.NOZOMI -> return nozomi.autocomplete(t)
        }
        val root = runCatching { AppJson.parseToJsonElement(get(source, url)) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            when (source.type) {
                ApiType.GELBOORU -> {
                    val value = o.str("value") ?: return@mapNotNull null
                    val count = o.int("post_count") ?: o.str("label")?.let { COUNT_IN_LABEL.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                    TagSuggestion(value, count, gelCategory(o.str("type") ?: o.str("category")))
                }
                ApiType.DANBOORU -> {
                    if (o.str("type")?.startsWith("tag") == false) return@mapNotNull null
                    TagSuggestion(o.str("value") ?: return@mapNotNull null, o.int("post_count"), danCategory(o.int("category")))
                }
                ApiType.E621 -> TagSuggestion(o.str("name") ?: return@mapNotNull null, o.int("post_count"), e621Category(o.int("category")))
                ApiType.MOEBOORU -> TagSuggestion(o.str("name") ?: return@mapNotNull null, o.int("count"), moeCategory(o.int("type")))
                ApiType.NOZOMI -> null
            }
        }
    }

    internal suspend fun get(source: Source, url: String): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Accept", "application/json")
        if (source.type == ApiType.E621) {
            val who = source.user.ifBlank { "anonymous" }
            b.header("User-Agent", "ExpressiveBooru/${BuildConfig.VERSION_NAME} (by $who on e621)")
            if (source.hasAuth) b.header("Authorization", Credentials.basic(source.user, source.apiKey))
        }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 -> throw ApiException(
                    errorMessage(body) ?: if (source.hasAuth) "${source.name} rejected your API key (HTTP ${r.code})." else "${source.name} needs an API key (HTTP ${r.code}).",
                    needsAuth = true,
                )
                r.code == 429 -> throw ApiException("${source.name} is rate limiting requests. Wait a moment and try again.")
                !r.isSuccessful -> throw ApiException(errorMessage(body) ?: "${source.name} returned HTTP ${r.code}.")
            }
            body
        }
    }

    /** Pull a human message out of the many error shapes boorus return. */
    private fun errorMessage(body: String): String? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("<")) return XML_REASON.find(t)?.groupValues?.get(1)
        return runCatching {
            when (val e = AppJson.parseToJsonElement(t)) {
                is JsonPrimitive -> e.contentOrNull
                is JsonObject -> e.str("message") ?: e.str("reason") ?: e.str("error")
                else -> null
            }
        }.getOrNull()
    }

    private fun parseRoot(body: String): JsonElement? {
        val t = body.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("<")) {
            val reason = XML_REASON.find(t)?.groupValues?.get(1)
            if (reason != null) throw ApiException(reason)
            return null
        }
        val e = try { AppJson.parseToJsonElement(t) } catch (ex: Exception) { throw ApiException("Unexpected response: ${t.take(120)}") }
        if (e is JsonPrimitive) {
            val msg = e.contentOrNull.orEmpty()
            throw ApiException(msg.ifBlank { "Unexpected response" }, needsAuth = msg.contains("auth", ignoreCase = true) || msg.contains("api key", ignoreCase = true))
        }
        if (e is JsonObject && (e["success"] as? JsonPrimitive)?.contentOrNull == "false") {
            throw ApiException(e.str("message") ?: e.str("reason") ?: "Request failed")
        }
        return e
    }

    private fun parseGelbooru(src: Source, body: String): List<Post> {
        val arr = when (val root = parseRoot(body)) {
            is JsonArray -> root
            is JsonObject -> when (val p = root["post"]) {
                is JsonArray -> p
                is JsonObject -> JsonArray(listOf(p))
                else -> JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.long("id") ?: return@mapNotNull null
            val dir = o.str("directory")
            val image = o.str("image")
            val stem = image?.substringBeforeLast('.')
            fun built(path: String) = if (dir != null && image != null) "${src.apiUrl}/$path" else null
            val file = absolute(src, o.str("file_url")) ?: built("images/$dir/$image")
            val hasSample = o.bool("sample")
            val sample = absolute(src, o.str("sample_url"))?.takeIf { hasSample != false } ?: (if (hasSample == true) built("samples/$dir/sample_$stem.jpg") else null)
            val preview = absolute(src, o.str("preview_url")) ?: built("thumbnails/$dir/thumbnail_$stem.jpg")
            if (file == null && preview == null) return@mapNotNull null
            val tags = splitTags(o.str("tags"))
            val groups = gelTagInfo(o["tag_info"])?.let { groupTags(tags, it) }
            Post(
                sourceId = src.id, id = id,
                previewUrl = preview, sampleUrl = sample, fileUrl = file,
                width = o.int("width") ?: 0, height = o.int("height") ?: 0,
                rating = gelRating(o.str("rating")),
                score = o.int("score"),
                tags = tags,
                tagGroups = groups,
                source = o.str("source"),
                md5 = o.str("md5") ?: o.str("hash"),
                ext = image?.substringAfterLast('.', "")?.ifBlank { null } ?: file?.substringBefore('?')?.substringAfterLast('.'),
                createdAt = o.str("created_at")?.let(::parseGelDate) ?: o.long("change"),
            )
        }
    }

    private fun gelAuth(s: Source) = if (s.hasAuth) "&user_id=${enc(s.user)}&api_key=${enc(s.apiKey)}" else ""

    private fun gelRating(r: String?) = when (r?.lowercase()) {
        "general", "g", "safe", "s" -> Rating.GENERAL
        "sensitive" -> Rating.SENSITIVE
        "questionable", "q" -> Rating.QUESTIONABLE
        "explicit", "e" -> Rating.EXPLICIT
        else -> Rating.UNKNOWN
    }

    private fun gelCategory(t: String?) = when (t?.lowercase()) {
        "artist", "1" -> TagCategory.ARTIST
        "character", "4" -> TagCategory.CHARACTER
        "copyright", "3" -> TagCategory.COPYRIGHT
        "metadata", "meta", "5" -> TagCategory.META
        "general", "tag", "0" -> TagCategory.GENERAL
        null, "" -> null
        else -> TagCategory.OTHER
    }

    /** Rule34, Safebooru and Xbooru send `tag_info` ({"tag", "type"}) when asked with `fields=tag_info`. */
    private fun gelTagInfo(el: JsonElement?): Map<String, TagCategory>? {
        val arr = el as? JsonArray ?: return null
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { e ->
            val name = e.str("tag") ?: e.str("name") ?: return@mapNotNull null
            name to (gelCategory(e.str("type")) ?: TagCategory.GENERAL)
        }.toMap().ifEmpty { null }
    }

    internal fun groupTags(tags: List<String>, types: Map<String, TagCategory>): Map<TagCategory, List<String>> =
        tags.groupBy { types[it] ?: types[it.lowercase()] ?: TagCategory.GENERAL }.toSortedMap(compareBy { it.ordinal })

    // Tag types looked up from a site, keyed "sourceId:tag". Shared across searches; LRU-bounded.
    private val tagTypeCache = object : LinkedHashMap<String, TagCategory>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TagCategory>) = size > 30_000
    }

    /**
     * Fills in tag categories (so the artist is known) for posts that arrived as flat tag lists:
     *  - Gelbooru.com: its tag API, 100 names per request.
     *  - e621 / e926: `tags.json`, 100 names per request.
     *  - Other Gelbooru-type sites without `tag_info` (TBIB, HypnoHub…): the post page's tag list.
     * Known tags come from the cache. Posts that already have categories are returned unchanged.
     */
    suspend fun withTagTypes(source: Source, posts: List<Post>): List<Post> {
        val typed = siteTagTypes(source, posts)
        // Safebooru, TBIB and other Danbooru mirrors often lack (or mistype) artist tags; Danbooru knows them by md5.
        return if (source.type == ApiType.GELBOORU) runCatching { artistsFromDanbooru(typed) }.getOrDefault(typed) else typed
    }

    private suspend fun siteTagTypes(source: Source, posts: List<Post>): List<Post> {
        val flat = posts.filter { it.tagGroups == null }
        if (flat.isEmpty()) return posts
        return when {
            source.type == ApiType.GELBOORU && source.host.contains("gelbooru.com") -> {
                val types = lookupTypes(source, flat) { chunk ->
                    val url = "${source.apiUrl}/index.php?page=dapi&s=tag&q=index&json=1&limit=100&names=${enc(chunk.joinToString(" "))}" + gelAuth(source)
                    val root = AppJson.parseToJsonElement(get(source, url))
                    val entries = when (root) {
                        is JsonObject -> (root["tag"] as? JsonArray) ?: (root["tag"] as? JsonObject)?.let { JsonArray(listOf(it)) }
                        is JsonArray -> root
                        else -> null
                    }
                    entries.orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { e ->
                        e.str("name")?.lowercase()?.let { it to (gelCategory(e.str("type")) ?: TagCategory.GENERAL) }
                    }
                }
                posts.map { p -> if (p.tagGroups != null) p else p.copy(tagGroups = groupTags(p.tags, types)) }
            }
            source.type == ApiType.E621 -> {
                val types = lookupTypes(source, flat) { chunk ->
                    val url = "${source.apiUrl}/tags.json?limit=320&search%5Bname%5D=${enc(chunk.joinToString(","))}"
                    (AppJson.parseToJsonElement(get(source, url)) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { e ->
                        e.str("name")?.lowercase()?.let { it to (e621Category(e.int("category")) ?: TagCategory.GENERAL) }
                    }
                }
                posts.map { p -> if (p.tagGroups != null) p else p.copy(tagGroups = groupTags(p.tags, types)) }
            }
            source.type == ApiType.GELBOORU -> posts.map { p ->
                if (p.tagGroups != null) p else runCatching { pageTagTypes(source, p) }.getOrNull()?.let { p.copy(tagGroups = groupTags(p.tags, it)) } ?: p
            }
            else -> posts
        }
    }

    private val danbooruArtists = object : LinkedHashMap<String, Triple<List<String>, List<String>, List<String>>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<List<String>, List<String>, List<String>>>) = size > 5_000
    }

    /** Fills artist / character / copyright groups from Danbooru for posts with an md5 but no artist, 40 per request. */
    private suspend fun artistsFromDanbooru(posts: List<Post>): List<Post> {
        val danbooru = Presets.all.first { it.type == ApiType.DANBOORU }
        val need = posts.filter { it.artists.isEmpty() && it.md5?.matches(MD5) == true }.map { it.md5!!.lowercase() }.distinct()
        val missing = synchronized(danbooruArtists) { need.filter { it !in danbooruArtists } }
        for (chunk in missing.chunked(40)) {
            val url = "${danbooru.apiUrl}/posts.json?limit=100&only=md5,tag_string_artist,tag_string_character,tag_string_copyright&tags=md5:${chunk.joinToString(",")}"
            val arr = AppJson.parseToJsonElement(get(danbooru, url)) as? JsonArray ?: continue
            synchronized(danbooruArtists) {
                arr.mapNotNull { it as? JsonObject }.forEach { o ->
                    val md5 = o.str("md5")?.lowercase() ?: return@forEach
                    danbooruArtists[md5] = Triple(splitTags(o.str("tag_string_artist")), splitTags(o.str("tag_string_character")), splitTags(o.str("tag_string_copyright")))
                }
                chunk.forEach { if (it !in danbooruArtists) danbooruArtists[it] = Triple(emptyList(), emptyList(), emptyList()) }
            }
        }
        return posts.map { p ->
            val hit = p.md5?.lowercase()?.let { synchronized(danbooruArtists) { danbooruArtists[it] } }
            if (hit == null || p.artists.isNotEmpty() || hit.first.isEmpty()) p else {
                val (artists, characters, copyrights) = hit
                val known = (artists + characters + copyrights).toSet()
                val rest = (p.tagGroups ?: mapOf(TagCategory.GENERAL to p.tags)).mapValues { (_, v) -> v.filterNot { it in known } }
                val merged = rest + mapOf(TagCategory.ARTIST to artists, TagCategory.CHARACTER to characters, TagCategory.COPYRIGHT to copyrights)
                p.copy(tagGroups = merged.filterValues { it.isNotEmpty() }.toSortedMap(compareBy { it.ordinal }))
            }
        }
    }

    /** Cached batch lookup: fetches only unknown tags, 100 per call; tags the site doesn't return count as general. */
    private suspend fun lookupTypes(
        source: Source,
        posts: List<Post>,
        fetch: suspend (List<String>) -> List<Pair<String, TagCategory>>,
    ): Map<String, TagCategory> {
        fun key(t: String) = "${source.id}:$t"
        val wanted = posts.flatMap { it.tags }.map { it.lowercase() }.toSet()
        val missing = synchronized(tagTypeCache) { wanted.filter { key(it) !in tagTypeCache } }
        for (chunk in missing.chunked(100)) {
            val found = runCatching { fetch(chunk) }.getOrNull() ?: continue
            synchronized(tagTypeCache) {
                found.forEach { (name, cat) -> tagTypeCache[key(name)] = cat }
                chunk.forEach { if (key(it) !in tagTypeCache) tagTypeCache[key(it)] = TagCategory.GENERAL }
            }
        }
        return synchronized(tagTypeCache) { wanted.associateWith { tagTypeCache[key(it)] ?: TagCategory.GENERAL } }
    }

    /** Gelbooru post pages list every tag as `<li class="tag-type-artist tag">… tags=name">`. */
    private suspend fun pageTagTypes(source: Source, post: Post): Map<String, TagCategory>? {
        val html = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("${source.web}/index.php?page=post&s=view&id=${post.id}").build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } ?: return null
        val types = HTML_TAG_ITEM.findAll(html).mapNotNull { m ->
            val name = HTML_TAG_LINK.find(m.groupValues[2])?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@mapNotNull null
            name.lowercase() to (gelCategory(m.groupValues[1]) ?: TagCategory.GENERAL)
        }.toMap()
        return types.ifEmpty { null }
    }

    private fun parseGelDate(s: String): Long? =
        runCatching { SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(s)!!.time / 1000 }.getOrNull()

    private fun parseDanbooru(src: Source, body: String): List<Post> {
        val arr = parseRoot(body) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.long("id") ?: return@mapNotNull null
            val variants = (o.obj("media_asset")?.get("variants") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            fun variant(type: String) = variants.firstOrNull { it.str("type") == type }?.str("url")
            val file = o.str("file_url")
            val preview = variant("360x360") ?: o.str("preview_file_url") ?: variant("180x180")
            if (file == null && preview == null) return@mapNotNull null
            val groups = linkedMapOf(
                TagCategory.ARTIST to splitTags(o.str("tag_string_artist")),
                TagCategory.COPYRIGHT to splitTags(o.str("tag_string_copyright")),
                TagCategory.CHARACTER to splitTags(o.str("tag_string_character")),
                TagCategory.GENERAL to splitTags(o.str("tag_string_general")),
                TagCategory.META to splitTags(o.str("tag_string_meta")),
            ).filterValues { it.isNotEmpty() }
            Post(
                sourceId = src.id, id = id,
                previewUrl = preview, sampleUrl = o.str("large_file_url"), fileUrl = file,
                width = o.int("image_width") ?: 0, height = o.int("image_height") ?: 0,
                rating = when (o.str("rating")) { "g" -> Rating.GENERAL; "s" -> Rating.SENSITIVE; "q" -> Rating.QUESTIONABLE; "e" -> Rating.EXPLICIT; else -> Rating.UNKNOWN },
                score = o.int("score"), favCount = o.int("fav_count"),
                tags = splitTags(o.str("tag_string")),
                tagGroups = groups.ifEmpty { null },
                source = o.str("source"), md5 = o.str("md5"), ext = o.str("file_ext"),
                fileSize = o.long("file_size"),
                createdAt = o.str("created_at")?.let(::parseIso),
            )
        }
    }

    private fun danAuth(s: Source) = if (s.hasAuth) "&login=${enc(s.user)}&api_key=${enc(s.apiKey)}" else ""

    private fun danCategory(c: Int?) = when (c) {
        0 -> TagCategory.GENERAL; 1 -> TagCategory.ARTIST; 3 -> TagCategory.COPYRIGHT
        4 -> TagCategory.CHARACTER; 5 -> TagCategory.META; null -> null; else -> TagCategory.OTHER
    }

    private fun parseE621(src: Source, body: String): List<Post> {
        val arr = when (val root = parseRoot(body)) {
            is JsonArray -> root
            is JsonObject -> root["posts"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.long("id") ?: return@mapNotNull null
            val files = o.obj("files")
            val file: String?; val preview: String?; val sample: String?; var video: String? = null
            val width: Int; val height: Int; val ext: String?; val md5: String?; val size: Long?
            if (files != null) {
                val meta = files.obj("meta")
                val original = files.obj("original")
                file = original?.str("url")
                preview = files.obj("preview")?.let { it.str("jpg") ?: it.str("webp") }
                sample = files.obj("sample")?.takeIf { meta?.bool("has_sample") != false }?.let { it.str("jpg") ?: it.str("webp") }
                width = original?.int("width") ?: 0; height = original?.int("height") ?: 0
                ext = meta?.str("ext"); md5 = meta?.str("md5"); size = meta?.long("size")
                // Prefer the H.264 mp4 variant: plays on every device, unlike some VP9 webms.
                files.obj("video")?.let { v -> video = v.obj("variants")?.obj("mp4")?.str("url") ?: v.obj("original")?.str("url") }
            } else {
                val f = o.obj("file")
                file = f?.str("url")
                preview = o.obj("preview")?.str("url")
                sample = o.obj("sample")?.takeIf { it.bool("has") != false }?.str("url")
                width = f?.int("width") ?: 0; height = f?.int("height") ?: 0
                ext = f?.str("ext"); md5 = f?.str("md5"); size = f?.long("size")
                o.obj("sample")?.obj("alternates")?.let { alt ->
                    video = alt.obj("original")?.let { (it["urls"] as? JsonArray)?.firstOrNull { u -> (u as? JsonPrimitive)?.contentOrNull?.endsWith(".mp4") == true } }
                        ?.let { (it as JsonPrimitive).contentOrNull }
                }
            }
            // e621 hides file URLs on some posts when logged out; nothing to show then.
            if (file == null && preview == null) return@mapNotNull null

            val tagsEl = o["tags"]
            val groups: Map<TagCategory, List<String>>?
            val flat: List<String>
            when (tagsEl) {
                is JsonObject -> {
                    groups = linkedMapOf<TagCategory, List<String>>().apply {
                        for ((k, v) in tagsEl) {
                            val list = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                            if (list.isEmpty()) continue
                            val cat = when (k) {
                                "artist" -> TagCategory.ARTIST; "copyright" -> TagCategory.COPYRIGHT
                                "character" -> TagCategory.CHARACTER; "species" -> TagCategory.SPECIES
                                "general" -> TagCategory.GENERAL; "meta" -> TagCategory.META
                                else -> TagCategory.OTHER
                            }
                            put(cat, getOrElse(cat) { emptyList() } + list)
                        }
                    }.toSortedMap(compareBy { it.ordinal })
                    flat = groups.values.flatten()
                }
                is JsonArray -> { groups = null; flat = tagsEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
                is JsonPrimitive -> { groups = null; flat = splitTags(tagsEl.contentOrNull) }
                else -> { groups = null; flat = emptyList() }
            }
            val stats = o.obj("stats")
            val score = (stats?.obj("score") ?: o.obj("score"))?.int("total")
            val sources = (o["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            Post(
                sourceId = src.id, id = id,
                previewUrl = preview, sampleUrl = sample, fileUrl = file, videoUrl = video,
                width = width, height = height,
                rating = when (o.str("rating")) { "s" -> Rating.GENERAL; "q" -> Rating.QUESTIONABLE; "e" -> Rating.EXPLICIT; else -> Rating.UNKNOWN },
                score = score, favCount = stats?.int("fav_count") ?: o.int("fav_count"),
                tags = flat, tagGroups = groups?.ifEmpty { null },
                source = sources?.firstOrNull(), md5 = md5, ext = ext, fileSize = size,
                createdAt = o.str("created_at")?.let(::parseIso),
            )
        }
    }

    private fun e621Category(c: Int?) = when (c) {
        0 -> TagCategory.GENERAL; 1 -> TagCategory.ARTIST; 3 -> TagCategory.COPYRIGHT; 4 -> TagCategory.CHARACTER
        5 -> TagCategory.SPECIES; 7 -> TagCategory.META; null -> null; else -> TagCategory.OTHER
    }

    private fun parseMoebooru(src: Source, body: String): List<Post> {
        // API v2 wraps posts as {"posts": [...], "tags": {"name": "artist", ...}}; v1 is a bare array.
        val root = parseRoot(body)
        val arr = (root as? JsonArray) ?: ((root as? JsonObject)?.get("posts") as? JsonArray) ?: return emptyList()
        val types: Map<String, TagCategory> = ((root as? JsonObject)?.get("tags") as? JsonObject)
            ?.mapValues { (_, v) -> moeTypeName((v as? JsonPrimitive)?.contentOrNull) }.orEmpty()
        return arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val id = o.long("id") ?: return@mapNotNull null
            val file = absolute(src, o.str("file_url"))
            val preview = absolute(src, o.str("preview_url"))
            if (file == null && preview == null) return@mapNotNull null
            Post(
                sourceId = src.id, id = id,
                previewUrl = preview,
                sampleUrl = absolute(src, o.str("sample_url")) ?: absolute(src, o.str("jpeg_url")),
                fileUrl = file,
                width = o.int("width") ?: 0, height = o.int("height") ?: 0,
                rating = when (o.str("rating")) { "s" -> Rating.GENERAL; "q" -> Rating.QUESTIONABLE; "e" -> Rating.EXPLICIT; else -> Rating.UNKNOWN },
                score = o.int("score"),
                tags = splitTags(o.str("tags")),
                tagGroups = splitTags(o.str("tags")).takeIf { types.isNotEmpty() }?.let { groupTags(it, types) },
                source = o.str("source"), md5 = o.str("md5"), ext = o.str("file_ext"),
                fileSize = o.long("file_size"), createdAt = o.long("created_at"),
            )
        }
    }

    private fun moeAuth(s: Source) = if (s.hasAuth) "&login=${enc(s.user)}&password_hash=${enc(s.apiKey)}" else ""

    private fun moeTypeName(t: String?) = when (t) {
        "artist" -> TagCategory.ARTIST; "copyright" -> TagCategory.COPYRIGHT; "character" -> TagCategory.CHARACTER
        "general" -> TagCategory.GENERAL; "faults", "meta" -> TagCategory.META; else -> TagCategory.OTHER
    }

    private fun moeCategory(c: Int?) = when (c) {
        0 -> TagCategory.GENERAL; 1 -> TagCategory.ARTIST; 3 -> TagCategory.COPYRIGHT
        4 -> TagCategory.CHARACTER; 6 -> TagCategory.META; null -> null; else -> TagCategory.OTHER
    }

    private fun absolute(src: Source, url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> src.apiUrl + url
        else -> url
    }

    private fun splitTags(s: String?): List<String> = s?.split(' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun parseIso(s: String): Long? = runCatching { OffsetDateTime.parse(s).toEpochSecond() }.getOrNull()

    companion object {
        const val PAGE_SIZE = 40
        const val NOZOMI_CDN = "gold-usergeneratedcontent.net"
        private val MD5 = Regex("[0-9a-fA-F]{32}")
        private val XML_REASON = Regex("reason=\"([^\"]*)\"")
        private val HTML_TAG_ITEM = Regex("<li class=\"tag-type-([a-z]+)[^\"]*\">(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
        private val HTML_TAG_LINK = Regex("[?&;]tags=([^\"&]+)\"")
        private val COUNT_IN_LABEL = Regex("\\((\\d+)\\)\\s*$")
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

// JSON field accessors tolerant of the type drift between boorus ("1" vs 1 vs true, null vs missing).
internal fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.long(k: String): Long? = str(k)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
internal fun JsonObject.int(k: String): Int? = long(k)?.toInt()
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.bool(k: String): Boolean? = when (str(k)?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

fun friendlyError(t: Throwable): String = when (t) {
    is ApiException -> t.message ?: "Request failed"
    is java.net.UnknownHostException -> "No connection. Check your internet and try again."
    is java.net.SocketTimeoutException -> "The site took too long to answer."
    is IOException -> t.message ?: "Network error"
    else -> t.message ?: t.javaClass.simpleName
}
