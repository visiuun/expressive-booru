package dev.vis.booru.data

import kotlinx.serialization.Serializable
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** The four API families every booru we support speaks. */
@Serializable
enum class ApiType(val label: String) {
    GELBOORU("Gelbooru 0.2"),
    DANBOORU("Danbooru"),
    E621("e621"),
    MOEBOORU("Moebooru"),
    NOZOMI("Nozomi");

    /** Meta tag that sorts by score / randomly on this API family (Nozomi only knows "popular"). */
    val scoreSort get() = when (this) { GELBOORU -> "sort:score"; NOZOMI -> "order:popular"; else -> "order:score" }
    val randomSort get() = if (this == GELBOORU) "sort:random" else "order:random"

    /** e621 refuses autocomplete for fewer than 3 characters. */
    val minAutocomplete get() = if (this == E621) 3 else 1

    val userLabel get() = when (this) {
        GELBOORU -> "User ID"
        DANBOORU, E621, MOEBOORU, NOZOMI -> "Username"
    }
    val keyLabel get() = when (this) {
        MOEBOORU -> "Password hash"
        else -> "API key"
    }
}

@Serializable
enum class Rating(val short: String, val label: String) {
    GENERAL("G", "General"),
    SENSITIVE("S", "Sensitive"),
    QUESTIONABLE("Q", "Questionable"),
    EXPLICIT("E", "Explicit"),
    UNKNOWN("?", "Unknown");

    val nsfw get() = this == QUESTIONABLE || this == EXPLICIT
}

@Serializable
enum class TagCategory(val label: String) {
    ARTIST("Artist"),
    COPYRIGHT("Copyright"),
    CHARACTER("Character"),
    SPECIES("Species"),
    GENERAL("General"),
    META("Meta"),
    OTHER("Other"),
}

/** A booru the app can talk to. Presets ship with the app; users can add their own. */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val type: ApiType,
    /** Base URL the API lives on (no trailing slash). */
    val apiUrl: String,
    /** Base URL of the website for "open in browser"; defaults to [apiUrl]. */
    val webUrl: String = "",
    val user: String = "",
    val apiKey: String = "",
    /** Hint shown when the site needs an account before the API answers. */
    val needsAuth: Boolean = false,
) {
    val web: String get() = webUrl.ifBlank { apiUrl }
    val host: String get() = runCatching { java.net.URI(web).host }.getOrNull()?.removePrefix("www.") ?: web
    val hasAuth: Boolean get() = user.isNotBlank() && apiKey.isNotBlank()

    fun postPageUrl(id: Long): String = when (type) {
        ApiType.GELBOORU -> "$web/index.php?page=post&s=view&id=$id"
        ApiType.DANBOORU, ApiType.E621 -> "$web/posts/$id"
        ApiType.MOEBOORU -> "$web/post/show/$id"
        ApiType.NOZOMI -> "$web/post/$id.html"
    }

    /** Where to get an API key for this site, if we know. */
    val keyHelpUrl: String? get() = when {
        host.contains("rule34.xxx") -> "https://rule34.xxx/index.php?page=account&s=options"
        host.contains("gelbooru.com") -> "https://gelbooru.com/index.php?page=account&s=options"
        type == ApiType.DANBOORU -> "$web/profile"
        type == ApiType.E621 -> "$web/users/home"
        else -> null
    }
}

@Serializable
data class Post(
    val sourceId: String,
    val id: Long,
    val previewUrl: String? = null,
    val sampleUrl: String? = null,
    val fileUrl: String? = null,
    /** Playable video when it differs from [fileUrl] (e.g. e621's mp4 variant of a webm). */
    val videoUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val rating: Rating = Rating.UNKNOWN,
    val score: Int? = null,
    val favCount: Int? = null,
    val tags: List<String> = emptyList(),
    /** Tags split by category when the API tells us; null when it only gives a flat list. */
    val tagGroups: Map<TagCategory, List<String>>? = null,
    val source: String? = null,
    val md5: String? = null,
    val ext: String? = null,
    val fileSize: Long? = null,
    /** Epoch seconds. */
    val createdAt: Long? = null,
) {
    val key: String get() = "$sourceId:$id"

    val extension: String
        get() = (ext ?: fileUrl?.substringBefore('?')?.substringAfterLast('.', "") ?: "").lowercase()

    val isVideo: Boolean get() = extension in VIDEO_EXT
    val isGif: Boolean get() = extension == "gif"
    val isAnimated: Boolean get() = isVideo || isGif || "animated" in tags

    val aspect: Float get() = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.33f, 3f) else 1f

    val artists: List<String>
        get() = tagGroups?.get(TagCategory.ARTIST).orEmpty()
            .filterNot { it in IGNORED_ARTIST_TAGS }

    /** URL to show in the viewer for an image post. Animated GIFs always use the original. */
    fun viewerUrl(original: Boolean): String? = when {
        isGif -> fileUrl ?: sampleUrl
        original -> fileUrl ?: sampleUrl ?: previewUrl
        else -> sampleUrl ?: fileUrl ?: previewUrl
    }

    fun playableUrl(): String? = videoUrl ?: fileUrl

    companion object {
        val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v", "mkv")
        private val IGNORED_ARTIST_TAGS = setOf("artist_request", "unknown_artist", "anonymous_artist", "conditional_dnp", "sound_warning", "third-party_edit", "avoid_posting")
    }
}

@Serializable
data class Favorite(val post: Post, val addedAt: Long)

@Serializable
data class SavedSearch(val sourceId: String, val query: String, val time: Long = System.currentTimeMillis())

data class TagSuggestion(val name: String, val count: Int?, val category: TagCategory?)

class ApiException(message: String, val needsAuth: Boolean = false) : Exception(message)

/** The sites the app ships with. IDs are stable; settings refer to them. */
object Presets {
    val all: List<Source> = listOf(
        Source("rule34", "Rule34", ApiType.GELBOORU, "https://api.rule34.xxx", "https://rule34.xxx", needsAuth = true),
        Source("gelbooru", "Gelbooru", ApiType.GELBOORU, "https://gelbooru.com", needsAuth = true),
        Source("safebooru", "Safebooru", ApiType.GELBOORU, "https://safebooru.org"),
        Source("danbooru", "Danbooru", ApiType.DANBOORU, "https://danbooru.donmai.us"),
        Source("e621", "e621", ApiType.E621, "https://e621.net"),
        Source("e926", "e926", ApiType.E621, "https://e926.net"),
        Source("yandere", "yande.re", ApiType.MOEBOORU, "https://yande.re"),
        Source("konachan", "Konachan", ApiType.MOEBOORU, "https://konachan.com"),
        Source("xbooru", "Xbooru", ApiType.GELBOORU, "https://xbooru.com"),
        Source("tbib", "TBIB", ApiType.GELBOORU, "https://tbib.org"),
        Source("hypnohub", "HypnoHub", ApiType.GELBOORU, "https://hypnohub.net"),
        Source("nozomi", "Nozomi", ApiType.NOZOMI, "https://j.gold-usergeneratedcontent.net", "https://nozomi.la"),
    )
    const val DEFAULT_ID = "rule34"
}

fun relativeTime(epochSeconds: Long?): String {
    epochSeconds ?: return ""
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < TimeUnit.DAYS.toSeconds(30) -> "${diff / 86400}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000))
    }
}

fun formatDate(epochSeconds: Long?): String =
    epochSeconds?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it * 1000)) } ?: ""

fun humanSize(bytes: Long?): String {
    bytes ?: return ""
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble(); var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

fun compactCount(n: Int?): String = when {
    n == null -> ""
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1_000f)
    else -> n.toString()
}
