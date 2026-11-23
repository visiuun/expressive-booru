package dev.vis.booru.data

import android.content.Context
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
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

/** Talks to every supported booru API and maps the responses to [Post]. */
class BooruApi(context: Context) {

    val userAgent = "ExpressiveBooru/${BuildConfig.VERSION_NAME} (by anonymous)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .cache(Cache(context.cacheDir.resolve("http"), 32L * 1024 * 1024))
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", userAgent).build() else req)
        }
        .build()

    suspend fun posts(source: Source, tags: String, page: Int, limit: Int = PAGE_SIZE): List<Post> {
        val q = enc(tags.trim())
        val url = when (source.type) {
            ApiType.GELBOORU -> "${source.apiUrl}/index.php?page=dapi&s=post&q=index&json=1&limit=$limit&pid=$page&tags=$q" + gelAuth(source)
            ApiType.DANBOORU -> "${source.apiUrl}/posts.json?limit=$limit&page=${page + 1}&tags=$q" + danAuth(source)
            ApiType.E621 -> "${source.apiUrl}/posts.json?limit=$limit&page=${page + 1}&tags=$q"
            ApiType.MOEBOORU -> "${source.apiUrl}/post.json?limit=$limit&page=${page + 1}&tags=$q" + moeAuth(source)
        }
        val body = get(source, url)
        return when (source.type) {
            ApiType.GELBOORU -> parseGelbooru(source, body)
            ApiType.DANBOORU -> parseDanbooru(source, body)
            ApiType.E621 -> parseE621(source, body)
            ApiType.MOEBOORU -> parseMoebooru(source, body)
        }
    }

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
            }
        }
    }

    private suspend fun get(source: Source, url: String): String = withContext(Dispatchers.IO) {
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
            Post(
                sourceId = src.id, id = id,
                previewUrl = preview, sampleUrl = sample, fileUrl = file,
                width = o.int("width") ?: 0, height = o.int("height") ?: 0,
                rating = gelRating(o.str("rating")),
                score = o.int("score"),
                tags = splitTags(o.str("tags")),
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
        "artist" -> TagCategory.ARTIST
        "character" -> TagCategory.CHARACTER
        "copyright" -> TagCategory.COPYRIGHT
        "metadata", "meta" -> TagCategory.META
        "general", "tag" -> TagCategory.GENERAL
        null, "" -> null
        else -> TagCategory.OTHER
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
        val arr = parseRoot(body) as? JsonArray ?: return emptyList()
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
                source = o.str("source"), md5 = o.str("md5"), ext = o.str("file_ext"),
                fileSize = o.long("file_size"), createdAt = o.long("created_at"),
            )
        }
    }

    private fun moeAuth(s: Source) = if (s.hasAuth) "&login=${enc(s.user)}&password_hash=${enc(s.apiKey)}" else ""

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
        private val XML_REASON = Regex("reason=\"([^\"]*)\"")
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
