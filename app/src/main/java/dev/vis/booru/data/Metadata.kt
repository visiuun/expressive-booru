package dev.vis.booru.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** What gets written into a downloaded image. */
data class ImageMeta(
    val tags: List<String>,
    val artists: List<String>,
    val postUrl: String?,
)

/**
 * Embeds a post's tags into JPEG, PNG and WebP files, without re-encoding the image:
 *  - EXIF: ImageDescription (tags), Artist, and the Windows XPKeywords / XPAuthor / XPComment fields,
 *    which Windows shows as "Tags", "Authors" and "Comments".
 *  - XMP: dc:subject (tags), dc:creator (artists) and dc:source (post link), read by most photo apps.
 * Any EXIF orientation already in the file is kept; other formats are returned as null (left untouched).
 */
object MetadataWriter {

    fun write(file: ByteArray, meta: ImageMeta): ByteArray? = runCatching {
        when {
            isJpeg(file) -> jpeg(file, meta)
            isPng(file) -> png(file, meta)
            isWebp(file) -> webp(file, meta)
            else -> null
        }
    }.getOrNull()

    private fun isJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
    private fun isPng(b: ByteArray) = b.size > 8 && b.copyOfRange(0, 8).contentEquals(PNG_SIG)
    private fun isWebp(b: ByteArray) = b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"

    // ---------------- JPEG ----------------

    private fun jpeg(b: ByteArray, meta: ImageMeta): ByteArray {
        val out = ByteArrayOutputStream(b.size + 8192)
        out.write(b, 0, 2) // SOI
        var pos = 2
        val kept = mutableListOf<IntRange>()
        var orientation: Int? = null
        var app0: IntRange? = null
        while (pos + 4 <= b.size) {
            if (b[pos] != 0xFF.toByte()) break
            val marker = b[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // start of scan / end of image: the rest is copied as is
            val len = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val seg = pos until pos + 2 + len
            val body = pos + 4
            when {
                marker == 0xE0 && app0 == null -> app0 = seg
                marker == 0xE1 && startsWith(b, body, EXIF_HEADER) -> orientation = readOrientation(b, body + EXIF_HEADER.size, len - 2 - EXIF_HEADER.size)
                marker == 0xE1 && startsWith(b, body, XMP_HEADER) -> Unit // replaced by ours
                else -> kept += seg
            }
            pos += 2 + len
        }
        app0?.let { out.write(b, it.first, it.last - it.first + 1) }
        segment(out, 0xE1, EXIF_HEADER + tiff(meta, orientation, maxBytes = 60_000))
        segment(out, 0xE1, XMP_HEADER + xmp(meta, maxBytes = 65_000).toByteArray(Charsets.UTF_8))
        kept.forEach { out.write(b, it.first, it.last - it.first + 1) }
        out.write(b, pos, b.size - pos)
        return out.toByteArray()
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, payload: ByteArray) {
        require(payload.size + 2 <= 0xFFFF)
        out.write(0xFF); out.write(marker)
        out.write((payload.size + 2) shr 8); out.write((payload.size + 2) and 0xFF)
        out.write(payload)
    }

    private fun startsWith(b: ByteArray, at: Int, prefix: ByteArray) =
        at + prefix.size <= b.size && (prefix.indices).all { b[at + it] == prefix[it] }

    /** Reads IFD0 orientation (tag 0x0112) from an existing TIFF block, if there is one. */
    private fun readOrientation(b: ByteArray, start: Int, len: Int): Int? = runCatching {
        val buf = ByteBuffer.wrap(b, start, len).slice()
        buf.order(if (b[start] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val ifd = buf.getInt(4)
        val count = buf.getShort(ifd).toInt() and 0xFFFF
        (0 until count).map { ifd + 2 + it * 12 }.firstOrNull { (buf.getShort(it).toInt() and 0xFFFF) == 0x0112 }
            ?.let { buf.getShort(it + 8).toInt() and 0xFFFF }
    }.getOrNull()

    // ---------------- PNG ----------------

    private fun png(b: ByteArray, meta: ImageMeta): ByteArray {
        val out = ByteArrayOutputStream(b.size + 8192)
        out.write(PNG_SIG)
        var pos = 8
        var wroteMeta = false
        while (pos + 8 <= b.size) {
            val len = ByteBuffer.wrap(b, pos, 4).int
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            val total = 12 + len
            val isOurs = type == "eXIf" || (type == "iTXt" && startsWith(b, pos + 8, XMP_KEYWORD))
            if (!isOurs) out.write(b, pos, total)
            if (type == "IHDR" && !wroteMeta) {
                chunk(out, "eXIf", tiff(meta, null, maxBytes = 1_000_000))
                chunk(out, "iTXt", XMP_KEYWORD + byteArrayOf(0, 0, 0, 0, 0) + xmp(meta, maxBytes = 1_000_000).toByteArray(Charsets.UTF_8))
                wroteMeta = true
            }
            pos += total
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t); out.write(data)
        val crc = CRC32().apply { update(t); update(data) }.value
        out.write(ByteBuffer.allocate(4).putInt(crc.toInt()).array())
    }

    // ---------------- WebP ----------------

    private fun webp(b: ByteArray, meta: ImageMeta): ByteArray {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        var pos = 12
        while (pos + 8 <= b.size) {
            val type = String(b, pos, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 0 || pos + 8 + len > b.size) break
            chunks += type to b.copyOfRange(pos + 8, pos + 8 + len)
            pos += 8 + len + (len and 1)
        }
        val body = chunks.filter { it.first != "EXIF" && it.first != "XMP " }.toMutableList()
        val vp8x = body.indexOfFirst { it.first == "VP8X" }
        if (vp8x >= 0) {
            val d = body[vp8x].second.copyOf()
            d[0] = (d[0].toInt() or 0x08 or 0x04).toByte() // EXIF and XMP present
            body[vp8x] = "VP8X" to d
        } else {
            val (w, h, alpha) = webpCanvas(body) ?: return b
            val d = ByteArray(10)
            d[0] = (0x08 or 0x04 or (if (alpha) 0x10 else 0)).toByte()
            put24(d, 4, w - 1); put24(d, 7, h - 1)
            body.add(0, "VP8X" to d)
        }
        body += "EXIF" to tiff(meta, null, maxBytes = 1_000_000)
        body += "XMP " to xmp(meta, maxBytes = 1_000_000).toByteArray(Charsets.UTF_8)

        val payload = ByteArrayOutputStream()
        payload.write("WEBP".toByteArray(Charsets.US_ASCII))
        for ((type, data) in body) {
            payload.write(type.toByteArray(Charsets.US_ASCII))
            payload.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            payload.write(data)
            if (data.size and 1 == 1) payload.write(0)
        }
        val p = payload.toByteArray()
        val out = ByteArrayOutputStream(p.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(p.size).array())
        out.write(p)
        return out.toByteArray()
    }

    /** Canvas size and alpha of a simple (VP8 / VP8L) WebP, needed to build its VP8X header. */
    private fun webpCanvas(chunks: List<Pair<String, ByteArray>>): Triple<Int, Int, Boolean>? {
        val alphaChunk = chunks.any { it.first == "ALPH" }
        chunks.firstOrNull { it.first == "VP8L" }?.second?.let { d ->
            if (d.size < 5 || d[0] != 0x2F.toByte()) return null
            val v = (d[1].toInt() and 0xFF) or ((d[2].toInt() and 0xFF) shl 8) or ((d[3].toInt() and 0xFF) shl 16) or ((d[4].toInt() and 0xFF) shl 24)
            return Triple((v and 0x3FFF) + 1, ((v ushr 14) and 0x3FFF) + 1, (v ushr 28) and 1 == 1)
        }
        chunks.firstOrNull { it.first == "VP8 " }?.second?.let { d ->
            if (d.size < 10) return null
            val w = ((d[6].toInt() and 0xFF) or ((d[7].toInt() and 0xFF) shl 8)) and 0x3FFF
            val h = ((d[8].toInt() and 0xFF) or ((d[9].toInt() and 0xFF) shl 8)) and 0x3FFF
            return Triple(w, h, alphaChunk)
        }
        return null
    }

    private fun put24(d: ByteArray, at: Int, v: Int) {
        d[at] = (v and 0xFF).toByte(); d[at + 1] = ((v shr 8) and 0xFF).toByte(); d[at + 2] = ((v shr 16) and 0xFF).toByte()
    }

    // ---------------- EXIF (TIFF) ----------------

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** A little-endian TIFF block holding IFD0 with the description, artist and Windows XP fields. */
    fun tiff(meta: ImageMeta, orientation: Int?, maxBytes: Int): ByteArray {
        var tags = meta.tags
        while (true) {
            val block = buildTiff(tags, meta, orientation)
            if (block.size <= maxBytes || tags.isEmpty()) return block
            tags = tags.dropLast(maxOf(1, tags.size / 5))
        }
    }

    private fun buildTiff(tags: List<String>, meta: ImageMeta, orientation: Int?): ByteArray {
        fun ascii(s: String) = s.toByteArray(Charsets.UTF_8) + 0
        fun ucs2(s: String) = s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val entries = mutableListOf<Entry>()
        if (tags.isNotEmpty()) entries += Entry(0x010E, 2, ascii(tags.joinToString(" ")).size, ascii(tags.joinToString(" ")))
        orientation?.let { entries += Entry(0x0112, 3, 1, byteArrayOf(it.toByte(), (it shr 8).toByte())) }
        if (meta.artists.isNotEmpty()) entries += Entry(0x013B, 2, ascii(meta.artists.joinToString(", ")).size, ascii(meta.artists.joinToString(", ")))
        meta.postUrl?.let { entries += Entry(0x9C9C, 1, ucs2(it).size, ucs2(it)) }
        if (meta.artists.isNotEmpty()) entries += Entry(0x9C9D, 1, ucs2(meta.artists.joinToString(";")).size, ucs2(meta.artists.joinToString(";")))
        if (tags.isNotEmpty()) entries += Entry(0x9C9E, 1, ucs2(tags.joinToString(";")).size, ucs2(tags.joinToString(";")))
        entries.sortBy { it.tag }

        val ifdSize = 2 + entries.size * 12 + 4
        val dataStart = 8 + ifdSize
        val data = ByteArrayOutputStream()
        val ifd = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(entries.size.toShort())
        for (e in entries) {
            ifd.putShort(e.tag.toShort()); ifd.putShort(e.type.toShort()); ifd.putInt(e.count)
            if (e.data.size <= 4) {
                ifd.put(e.data); repeat(4 - e.data.size) { ifd.put(0) }
            } else {
                ifd.putInt(dataStart + data.size())
                data.write(e.data)
                if (data.size() and 1 == 1) data.write(0) // keep offsets word-aligned
            }
        }
        ifd.putInt(0) // no next IFD
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0))
        out.write(ifd.array())
        out.write(data.toByteArray())
        return out.toByteArray()
    }

    // ---------------- XMP ----------------

    fun xmp(meta: ImageMeta, maxBytes: Int): String {
        var tags = meta.tags
        while (true) {
            val packet = buildXmp(tags, meta)
            if (packet.toByteArray(Charsets.UTF_8).size <= maxBytes || tags.isEmpty()) return packet
            tags = tags.dropLast(maxOf(1, tags.size / 5))
        }
    }

    private fun buildXmp(tags: List<String>, meta: ImageMeta): String = buildString {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">")
        append("<xmp:CreatorTool>Expressive Booru</xmp:CreatorTool>")
        if (tags.isNotEmpty()) append("<dc:subject><rdf:Bag>").append(tags.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" }).append("</rdf:Bag></dc:subject>")
        if (meta.artists.isNotEmpty()) append("<dc:creator><rdf:Seq>").append(meta.artists.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" }).append("</rdf:Seq></dc:creator>")
        meta.postUrl?.let { append("<dc:source>${esc(it)}</dc:source>") }
        append("</rdf:Description></rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>")
    }

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val EXIF_HEADER = "Exif".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0)
    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + 0
    private val XMP_KEYWORD = "XML:com.adobe.xmp".toByteArray(Charsets.US_ASCII)

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
