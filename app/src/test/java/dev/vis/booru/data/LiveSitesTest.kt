package dev.vis.booru.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import org.junit.Assert.assertTrue

/**
 * Talks to the real sites, so it only runs when asked for:
 *   LIVE_SITES=1 ./gradlew :app:testReleaseUnitTest --tests '*LiveSitesTest*'
 * Rule34 / Gelbooru need keys: RULE34_USER, RULE34_KEY, GELBOORU_USER, GELBOORU_KEY.
 */
class LiveSitesTest {
    private val api = BooruApi(Files.createTempDirectory("booru-test").toFile())

    private fun keyed(s: Source, user: String, key: String): Source? {
        val u = System.getenv(user).orEmpty(); val k = System.getenv(key).orEmpty()
        return if (u.isBlank() || k.isBlank()) null else s.copy(user = u, apiKey = k)
    }

    private fun sites(): List<Source> = Presets.all.mapNotNull { s ->
        when (s.id) {
            "rule34" -> keyed(s, "RULE34_USER", "RULE34_KEY")
            "gelbooru" -> keyed(s, "GELBOORU_USER", "GELBOORU_KEY")
            else -> s
        }
    }

    @Test
    fun everySiteFindsArtists() = runBlocking {
        assumeTrue("set LIVE_SITES=1 to run against the real sites", System.getenv("LIVE_SITES") == "1")
        val failures = mutableListOf<String>()
        for (site in sites()) {
            val line = runCatching {
                // Konachan's newest uploads are untagged ("tagme"), so look at well-tagged ones there.
                val query = if (site.id == "konachan") "order:score" else ""
                val posts = api.withTagTypes(site, api.posts(site, query, 0, limit = 12))
                val withArtist = posts.filter { it.artists.isNotEmpty() }
                val sample = withArtist.firstOrNull() ?: posts.firstOrNull()
                val name = sample?.let { Downloader.fileName(it, site, it.fileUrl ?: it.sampleUrl ?: "") }
                if (posts.isEmpty() || withArtist.isEmpty()) failures += site.name
                "%-10s %2d posts, %2d with artist  e.g. %s".format(site.name, posts.size, withArtist.size, name)
            }.getOrElse { e -> failures += site.name; "%-10s FAILED: %s".format(site.name, e.message) }
            println(line)
        }
        val skipped = Presets.all.map { it.name } - sites().map { it.name }.toSet()
        if (skipped.isNotEmpty()) println("skipped (no API key in env): ${skipped.joinToString()}")
        assertTrue("No artists found on: $failures", failures.isEmpty())
    }

    @Test
    fun nozomiSearchAndSuggestions() = runBlocking {
        assumeTrue(System.getenv("LIVE_SITES") == "1")
        val nozomi = Presets.all.first { it.id == "nozomi" }
        val single = api.posts(nozomi, "hatsune_miku", 0, limit = 5)
        val multi = api.posts(nozomi, "hatsune_miku 1girl -monochrome", 0, limit = 5)
        val popular = api.posts(nozomi, "order:popular", 0, limit = 3)
        val suggest = api.autocomplete(nozomi, "hatsu")
        println("nozomi single=${single.size} multi=${multi.size} popular=${popular.size} suggest=${suggest.take(3).map { it.name }}")
        println("nozomi sample: ${single.firstOrNull()?.let { "${it.fileUrl} tags=${it.tags.size} artists=${it.artists}" }}")
        assertTrue(single.isNotEmpty() && multi.isNotEmpty() && popular.isNotEmpty() && suggest.isNotEmpty())
        assertTrue(multi.all { "hatsune_miku" in it.tags && "1girl" in it.tags && "monochrome" !in it.tags })
    }
}
