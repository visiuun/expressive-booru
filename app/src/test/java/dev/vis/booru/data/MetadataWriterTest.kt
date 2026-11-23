package dev.vis.booru.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataWriterTest {
    private val meta = ImageMeta(
        tags = listOf("hatsune_miku", "1girl", "long_hair", "smile", "café_<&>", "日本語"),
        artists = listOf("happoubi_jin"),
        postUrl = "https://danbooru.donmai.us/posts/123",
    )

    @Test
    fun unsupportedFormatsAreLeftAlone() {
        assertNull(MetadataWriter.write("GIF89a....".toByteArray(), meta))
        assertNull(MetadataWriter.write(ByteArray(0), meta))
    }

    @Test
    fun tiffHasWindowsTagsInUcs2() {
        val t = MetadataWriter.tiff(meta, orientation = 6, maxBytes = 100_000)
        assertArrayEquals(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0), t.copyOf(4))
        val keywords = "hatsune_miku;1girl;long_hair;smile;café_<&>;日本語".toByteArray(Charsets.UTF_16LE)
        assertTrue(t.toList().windowed(keywords.size).any { it == keywords.toList() })
    }

    @Test
    fun oversizedTagListsAreTrimmedToFit() {
        val many = meta.copy(tags = (1..20_000).map { "tag_number_$it" })
        assertTrue(MetadataWriter.tiff(many, null, maxBytes = 60_000).size <= 60_000)
        assertTrue(MetadataWriter.xmp(many, maxBytes = 65_000).toByteArray().size <= 65_000)
    }

    /** Tags every file in META_IN into META_OUT; a Python/Pillow script checks the results. */
    @Test
    fun tagsSampleFiles() {
        val inDir = System.getenv("META_IN")?.let(::File) ?: return
        val outDir = File(System.getenv("META_OUT")!!).apply { mkdirs() }
        for (f in inDir.listFiles()!!.sortedBy { it.name }) {
            val tagged = MetadataWriter.write(f.readBytes(), meta)
            assertNotNull("${f.name} should be taggable", tagged)
            File(outDir, f.name).writeBytes(tagged!!)
            println("tagged ${f.name}: ${f.length()} -> ${tagged.size} bytes")
        }
    }
}
