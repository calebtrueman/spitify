package com.localfy.app.data.lyrics

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/**
 * Reads lyrics embedded in audio files without loading the whole file:
 *  - MP3: ID3v2.3/2.4 USLT (plain) and SYLT (synced, ms timestamps)
 *  - FLAC / Ogg Vorbis / Opus: Vorbis comments LYRICS, UNSYNCEDLYRICS, SYNCEDLYRICS
 *  - M4A / MP4 / ALAC: iTunes ©lyr atom
 * Returns raw text; LRC-formatted text (common in USLT/LYRICS) is detected as synced by the caller.
 * (Desktop port: same parser, reading a [File] instead of a content Uri.)
 */
class TagLyricsReader {

    fun read(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { ch ->
                val magic = ch.readAt(0, 12) ?: return@use null
                when {
                    magic.startsWith("ID3") -> readId3(ch)
                    magic.startsWith("fLaC") -> readFlac(ch)
                    magic.startsWith("OggS") -> readOgg(ch)
                    String(magic, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> readMp4(ch)
                    else -> null
                }
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ---------- ID3v2 ----------

    private fun readId3(ch: FileChannel): String? {
        val h = ch.readAt(0, 10) ?: return null
        val major = h[3].toInt()
        if (major !in 3..4) return null
        val flags = h[5].toInt()
        val tagEnd = 10L + synchsafe(h, 6)
        var pos = 10L
        if (flags and 0x40 != 0) {
            val ext = ch.readAt(pos, 4) ?: return null
            pos += if (major == 4) synchsafe(ext, 0) else (be32(ext, 0) + 4)
        }
        var plain: String? = null
        while (pos + 10 <= tagEnd) {
            val fh = ch.readAt(pos, 10) ?: break
            if (fh[0].toInt() == 0) break
            val id = String(fh, 0, 4, Charsets.ISO_8859_1)
            val size = if (major == 4) synchsafe(fh, 4) else be32(fh, 4)
            if (size <= 0 || pos + 10 + size > tagEnd) break
            when (id) {
                "SYLT" -> ch.readAt(pos + 10, size.toInt())?.let(::parseSylt)?.let { return it }
                "USLT" -> if (plain == null) plain = ch.readAt(pos + 10, size.toInt())?.let(::parseUslt)
                // foobar2000, ffmpeg and others store lyrics as user text frames.
                "TXXX" -> if (plain == null && size < 2_000_000) plain = ch.readAt(pos + 10, size.toInt())?.let(::parseTxxxLyrics)
            }
            pos += 10 + size
        }
        return plain
    }

    private fun parseUslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val enc = b[0].toInt()
        var i = 4 // encoding + 3-byte language
        i = skipTerminated(b, i, enc)
        return decode(b, i, b.size, enc)
    }

    private fun parseTxxxLyrics(b: ByteArray): String? {
        if (b.size < 3) return null
        val enc = b[0].toInt()
        val descEnd = terminatorIndex(b, 1, enc)
        val desc = decode(b, 1, descEnd, enc)?.uppercase() ?: return null
        if (desc !in setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS", "SYNCEDLYRICS", "USLT")) return null
        return decode(b, descEnd + terminatorLength(enc), b.size, enc)
    }

    /** Converts binary SYLT (ms timestamps only) into LRC text. */
    private fun parseSylt(b: ByteArray): String? {
        if (b.size < 7) return null
        val enc = b[0].toInt()
        val format = b[4].toInt()
        if (format != 2) return null
        var i = skipTerminated(b, 6, enc)
        val sb = StringBuilder()
        while (i < b.size) {
            val end = terminatorIndex(b, i, enc)
            val text = decode(b, i, end, enc).orEmpty()
            i = end + terminatorLength(enc)
            if (i + 4 > b.size) break
            val ms = be32(b, i)
            i += 4
            sb.append('[').append("%02d:%02d.%02d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10)).append(']')
                .append(text.trim('\n', '\r')).append('\n')
        }
        return sb.toString().ifBlank { null }
    }

    private fun terminatorLength(enc: Int) = if (enc == 1 || enc == 2) 2 else 1

    private fun terminatorIndex(b: ByteArray, from: Int, enc: Int): Int {
        var i = from
        if (terminatorLength(enc) == 2) {
            while (i + 1 < b.size && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
            return minOf(i, b.size)
        }
        while (i < b.size && b[i].toInt() != 0) i++
        return i
    }

    private fun skipTerminated(b: ByteArray, from: Int, enc: Int) = terminatorIndex(b, from, enc) + terminatorLength(enc)

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String? {
        if (from >= to || from >= b.size) return null
        val cs: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(b, from, minOf(to, b.size) - from, cs).trimEnd('\u0000')
    }

    // ---------- FLAC ----------

    private fun readFlac(ch: FileChannel): String? {
        var pos = 4L
        while (true) {
            val h = ch.readAt(pos, 4) ?: return null
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) return ch.readAt(pos + 4, len)?.let { vorbisLyrics(it, 0) }
            if (last) return null
            pos += 4 + len
        }
    }

    // ---------- Ogg (Vorbis / Opus) ----------

    private fun readOgg(ch: FileChannel): String? {
        // Reassemble the second packet (the comment header), which can span several pages.
        var pos = 0L
        var packetIndex = 0
        val packet = java.io.ByteArrayOutputStream()
        while (pos < minOf(ch.size(), 4L * 1024 * 1024)) {
            val hdr = ch.readAt(pos, 27) ?: return null
            if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
            val segCount = hdr[26].toInt() and 0xFF
            val segs = ch.readAt(pos + 27, segCount) ?: return null
            var dataPos = pos + 27 + segCount
            for (s in segs) {
                val len = s.toInt() and 0xFF
                if (packetIndex == 1) ch.readAt(dataPos, len)?.let { packet.write(it) }
                dataPos += len
                if (len < 255) {
                    if (packetIndex == 1) {
                        val p = packet.toByteArray()
                        return when {
                            p.size > 8 && String(p, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> vorbisLyrics(p, 8)
                            p.size > 7 && String(p, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> vorbisLyrics(p, 7)
                            else -> null
                        }
                    }
                    packetIndex++
                }
            }
            pos = dataPos
        }
        return null
    }

    private fun vorbisLyrics(b: ByteArray, start: Int): String? {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(start)
        if (buf.remaining() < 8) return null
        val vendor = buf.int
        if (vendor < 0 || vendor > buf.remaining()) return null
        buf.position(buf.position() + vendor)
        val count = buf.int
        var synced: String? = null
        var plain: String? = null
        repeat(count) {
            if (buf.remaining() < 4) return@repeat
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return synced ?: plain
            val bytes = ByteArray(len).also { buf.get(it) }
            val entry = String(bytes, Charsets.UTF_8)
            val key = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "")
            when (key) {
                "SYNCEDLYRICS" -> synced = value
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (plain == null) plain = value
            }
        }
        return synced ?: plain
    }

    // ---------- MP4 ----------

    private fun readMp4(ch: FileChannel): String? {
        val moov = findAtom(ch, 0, ch.size(), "moov") ?: return null
        val udta = findAtom(ch, moov.first, moov.second, "udta") ?: return null
        val meta = findAtom(ch, udta.first, udta.second, "meta") ?: return null
        val ilst = findAtom(ch, meta.first + 4, meta.second, "ilst") ?: return null // meta is a full box
        val lyr = findAtom(ch, ilst.first, ilst.second, "©lyr") ?: return null
        val data = findAtom(ch, lyr.first, lyr.second, "data") ?: return null
        val len = (data.second - data.first - 8).toInt()
        if (len <= 0 || len > 2_000_000) return null
        return ch.readAt(data.first + 8, len)?.let { String(it, Charsets.UTF_8) }
    }

    /** Returns (contentStart, end) of the first child atom named [type] within [start, end). */
    private fun findAtom(ch: FileChannel, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            val h = ch.readAt(pos, 8) ?: return null
            var size = be32(h, 0)
            var header = 8L
            val name = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) {
                val big = ch.readAt(pos + 8, 8) ?: return null
                size = ByteBuffer.wrap(big).long
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header) return null
            if (name == type) return (pos + header) to minOf(pos + size, end)
            pos += size
        }
        return null
    }

    // ---------- helpers ----------

    private fun FileChannel.readAt(pos: Long, len: Int): ByteArray? {
        if (len < 0 || pos < 0 || pos + len > size()) return null
        val buf = ByteBuffer.allocate(len)
        var read = 0
        while (read < len) {
            val n = read(buf, pos + read)
            if (n <= 0) break
            read += n
        }
        return if (read == len) buf.array() else null
    }

    private fun ByteArray.startsWith(s: String) = size >= s.length && String(this, 0, s.length, Charsets.ISO_8859_1) == s

    private fun synchsafe(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0x7F) shl 21) or ((b[o + 1].toLong() and 0x7F) shl 14) or
            ((b[o + 2].toLong() and 0x7F) shl 7) or (b[o + 3].toLong() and 0x7F)

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
