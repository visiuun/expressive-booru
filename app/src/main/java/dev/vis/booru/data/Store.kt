package dev.vis.booru.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App accent. [WALLPAPER] follows Material You on Android 12+, the rest are fixed seeds. */
enum class Accent(val label: String, val seed: Long) {
    WALLPAPER("Wallpaper", 0xFF6B4FA8),
    GREEN("Green", 0xFF2E9E5B),
    LILAC("Lilac", 0xFF6B4FA8),
    PINK("Pink", 0xFFE0457B),
    RED("Red", 0xFFD83A3A),
    ORANGE("Orange", 0xFFF08A24),
    BLUE("Blue", 0xFF3F7FD9),
    TEAL("Teal", 0xFF1E9C9C),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val oled: Boolean = false,
    val accent: Accent = Accent.WALLPAPER,
    val gridColumns: Int = 2,
    val staggered: Boolean = true,
    val hdThumbs: Boolean = false,
    val blurNsfw: Boolean = false,
    val showRating: Boolean = true,
    val ratings: Set<Rating> = Rating.entries.toSet(),
    val blacklist: String = "",
    val originalInViewer: Boolean = false,
    val autoplay: Boolean = true,
    val muted: Boolean = false,
    val activeSourceId: String = Presets.DEFAULT_ID,
    val saveHistory: Boolean = true,
) {
    val blacklistTags: Set<String> by lazy {
        blacklist.split(Regex("[\\s,]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Client-side filter: rating allowlist + blacklisted tags. Applied to every feed. */
    fun allows(p: Post): Boolean {
        if (p.rating != Rating.UNKNOWN && p.rating !in ratings) return false
        if (blacklistTags.isEmpty()) return true
        return p.tags.none { it.lowercase() in blacklistTags }
    }
}

class SettingsRepo(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val oled = booleanPreferencesKey("oled")
        val accent = stringPreferencesKey("accent")
        val columns = intPreferencesKey("columns")
        val staggered = booleanPreferencesKey("staggered")
        val hdThumbs = booleanPreferencesKey("hd_thumbs")
        val blur = booleanPreferencesKey("blur_nsfw")
        val showRating = booleanPreferencesKey("show_rating")
        val ratings = stringPreferencesKey("ratings")
        val blacklist = stringPreferencesKey("blacklist")
        val original = booleanPreferencesKey("original")
        val autoplay = booleanPreferencesKey("autoplay")
        val muted = booleanPreferencesKey("muted")
        val active = stringPreferencesKey("active_source")
        val history = booleanPreferencesKey("save_history")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            oled = p[K.oled] ?: false,
            accent = p[K.accent]?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: Accent.WALLPAPER,
            gridColumns = p[K.columns] ?: 2,
            staggered = p[K.staggered] ?: true,
            hdThumbs = p[K.hdThumbs] ?: false,
            blurNsfw = p[K.blur] ?: false,
            showRating = p[K.showRating] ?: true,
            ratings = parseRatings(p[K.ratings]),
            blacklist = p[K.blacklist] ?: "",
            originalInViewer = p[K.original] ?: false,
            autoplay = p[K.autoplay] ?: true,
            muted = p[K.muted] ?: false,
            activeSourceId = p[K.active] ?: Presets.DEFAULT_ID,
            saveHistory = p[K.history] ?: true,
        )
    }

    suspend fun setTheme(m: ThemeMode) = context.dataStore.edit { it[K.theme] = m.name }
    suspend fun setOled(v: Boolean) = context.dataStore.edit { it[K.oled] = v }
    suspend fun setAccent(v: Accent) = context.dataStore.edit { it[K.accent] = v.name }
    suspend fun setColumns(v: Int) = context.dataStore.edit { it[K.columns] = v.coerceIn(1, 5) }
    suspend fun setStaggered(v: Boolean) = context.dataStore.edit { it[K.staggered] = v }
    suspend fun setHdThumbs(v: Boolean) = context.dataStore.edit { it[K.hdThumbs] = v }
    suspend fun setBlur(v: Boolean) = context.dataStore.edit { it[K.blur] = v }
    suspend fun setShowRating(v: Boolean) = context.dataStore.edit { it[K.showRating] = v }
    /** Toggles one rating against the stored set, so quick successive taps never overwrite each other. */
    suspend fun toggleRating(r: Rating) = context.dataStore.edit { p ->
        val cur = parseRatings(p[K.ratings])
        p[K.ratings] = (if (r in cur) cur - r else cur + r).joinToString(",") { it.name }
    }

    private fun parseRatings(v: String?): Set<Rating> =
        v?.split(',')?.mapNotNull { runCatching { Rating.valueOf(it) }.getOrNull() }?.toSet() ?: Rating.entries.toSet()
    suspend fun setBlacklist(v: String) = context.dataStore.edit { it[K.blacklist] = v }
    suspend fun setOriginal(v: Boolean) = context.dataStore.edit { it[K.original] = v }
    suspend fun setAutoplay(v: Boolean) = context.dataStore.edit { it[K.autoplay] = v }
    suspend fun setMuted(v: Boolean) = context.dataStore.edit { it[K.muted] = v }
    suspend fun setActive(id: String) = context.dataStore.edit { it[K.active] = id }
    suspend fun setSaveHistory(v: Boolean) = context.dataStore.edit { it[K.history] = v }

    suspend fun addToBlacklist(tag: String) = context.dataStore.edit { p ->
        val cur = AppSettings(blacklist = p[K.blacklist] ?: "").blacklistTags
        if (tag.lowercase() !in cur) p[K.blacklist] = ((p[K.blacklist] ?: "").trim() + " " + tag).trim()
    }
}

/**
 * Small JSON-file store with an in-memory StateFlow. Used for sources, favorites and searches:
 * lists that are read constantly and written rarely.
 */
open class JsonListStore<T>(
    private val file: File,
    private val serializer: kotlinx.serialization.KSerializer<T>,
    initial: () -> List<T> = { emptyList() },
) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val listSer = ListSerializer(serializer)
    private val _items = MutableStateFlow(load() ?: initial())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private fun load(): List<T>? = runCatching {
        if (!file.exists()) null else AppJson.decodeFromString(listSer, file.readText())
    }.getOrNull()

    fun update(transform: (List<T>) -> List<T>) {
        val next = transform(_items.value)
        _items.value = next
        io.launch {
            lock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(AppJson.encodeToString(listSer, _items.value))
                tmp.renameTo(file)
            }
        }
    }

    fun encode(list: List<T>): String = AppJson.encodeToString(listSer, list)
    fun decode(text: String): List<T> = AppJson.decodeFromString(listSer, text)
}

class SourcesRepo(context: Context) : JsonListStore<Source>(
    File(context.filesDir, "sources.json"), Source.serializer(), { Presets.all },
) {
    fun get(id: String): Source? = items.value.firstOrNull { it.id == id }

    /** Like [get], but also knows the Everything pseudo-site. */
    fun resolve(id: String): Source? = if (id == Everything.ID) Everything.source else get(id)

    fun upsert(s: Source) = update { list ->
        if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun move(id: String, delta: Int) = update { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Put back any shipped preset the user deleted, keeping their own entries and keys. */
    fun restorePresets() = update { list ->
        list + Presets.all.filter { p -> list.none { it.id == p.id } }
    }
}

class FavoritesRepo(context: Context) : JsonListStore<Favorite>(
    File(context.filesDir, "favorites.json"), Favorite.serializer(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val keys: StateFlow<Set<String>> = items.map { l -> l.mapTo(HashSet()) { it.post.key } }
        .stateIn(scope, SharingStarted.Eagerly, items.value.mapTo(HashSet()) { it.post.key })

    fun isFavorite(p: Post) = p.key in keys.value

    fun toggle(p: Post): Boolean {
        val now = !isFavorite(p)
        update { list -> if (now) listOf(Favorite(p, System.currentTimeMillis())) + list else list.filterNot { it.post.key == p.key } }
        return now
    }

    /** Merge an imported list; existing entries win so nothing local gets overwritten. */
    fun merge(incoming: List<Favorite>): Int {
        var added = 0
        update { list ->
            val have = list.mapTo(HashSet()) { it.post.key }
            val fresh = incoming.filter { it.post.key !in have }.also { added = it.size }
            (list + fresh).sortedByDescending { it.addedAt }
        }
        return added
    }
}

class SearchesRepo(context: Context) {
    val history = JsonListStore(File(context.filesDir, "history.json"), SavedSearch.serializer())
    val saved = JsonListStore(File(context.filesDir, "saved.json"), SavedSearch.serializer())

    fun record(sourceId: String, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        history.update { list -> (listOf(SavedSearch(sourceId, q)) + list.filterNot { it.sourceId == sourceId && it.query == q }).take(100) }
    }

    fun isSaved(sourceId: String, query: String) = saved.items.value.any { it.sourceId == sourceId && it.query == query.trim() }

    fun toggleSaved(sourceId: String, query: String): Boolean {
        val q = query.trim()
        val now = !isSaved(sourceId, q)
        saved.update { list -> if (now) listOf(SavedSearch(sourceId, q)) + list else list.filterNot { it.sourceId == sourceId && it.query == q } }
        return now
    }
}

/** Backup file contents. API keys are never exported. */
@Serializable
data class Backup(
    val version: Int = 1,
    val favorites: List<Favorite> = emptyList(),
    val saved: List<SavedSearch> = emptyList(),
    val customSources: List<Source> = emptyList(),
)

object BackupIo {
    suspend fun export(favorites: FavoritesRepo, searches: SearchesRepo, sources: SourcesRepo): String = withContext(Dispatchers.Default) {
        val presetIds = Presets.all.map { it.id }.toSet()
        AppJson.encodeToString(
            Backup.serializer(),
            Backup(
                favorites = favorites.items.value,
                saved = searches.saved.items.value,
                customSources = sources.items.value.filter { it.id !in presetIds }.map { it.copy(user = "", apiKey = "") },
            ),
        )
    }

    /** Returns the number of favorites added. */
    suspend fun import(text: String, favorites: FavoritesRepo, searches: SearchesRepo, sources: SourcesRepo): Int = withContext(Dispatchers.Default) {
        val b = AppJson.decodeFromString(Backup.serializer(), text)
        b.customSources.forEach { s -> if (sources.get(s.id) == null) sources.upsert(s) }
        searches.saved.update { list -> list + b.saved.filter { s -> list.none { it.sourceId == s.sourceId && it.query == s.query } } }
        favorites.merge(b.favorites)
    }
}

/** Posts downloaded with this app, so "Download all" can skip what you already have. */
class DownloadLog(context: Context) {
    private val store = JsonListStore(File(context.filesDir, "downloaded.json"), String.serializer())
    private val keys = HashSet<String>(store.items.value)

    @Synchronized fun has(p: Post) = p.key in keys

    @Synchronized fun add(p: Post) {
        if (keys.add(p.key)) store.update { it + p.key }
    }
}
