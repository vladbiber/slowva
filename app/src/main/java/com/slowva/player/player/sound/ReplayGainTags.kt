package com.slowva.player.player.sound

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

data class RgTags(val trackGain: Float?, val albumGain: Float?, val trackPeak: Float?, val albumPeak: Float?) {
    val any: Boolean get() = trackGain != null || albumGain != null
}

/**
 * ReplayGain from ID3v2 TXXX frames (MP3), FLAC Vorbis comments, and Ogg Vorbis / Opus
 * comment headers (Opus R128 gains are moved to the -18 LUFS reference). MP4 is not read.
 */
object ReplayGainTags {
    private const val OGG_SCAN = 128 * 1024

    fun open(context: Context, uri: String): InputStream? = runCatching {
        if (uri.startsWith(ASSET)) context.assets.open(uri.removePrefix(ASSET))
        else context.contentResolver.openInputStream(Uri.parse(uri))
    }.getOrNull()

    fun read(context: Context, uri: String): RgTags? {
        val input = open(context, uri) ?: return null
        return runCatching {
            DataInputStream(input.buffered(64 * 1024)).use { s ->
                s.mark(OGG_SCAN + 16)
                val head = ByteArray(4)
                s.readFully(head)
                when {
                    head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(s, head[3].toInt())
                    String(head, Charsets.US_ASCII) == "fLaC" -> flac(s)
                    String(head, Charsets.US_ASCII) == "OggS" -> { s.reset(); ogg(s) }
                    else -> null
                }
            }
        }.getOrNull()?.takeIf { it.any }
    }

    private fun id3(s: DataInputStream, major: Int): RgTags? {
        if (major !in 3..4) return null
        s.readUnsignedByte()
        val flags = s.readUnsignedByte()
        val size = syncsafe(s.readInt())
        val map = HashMap<String, String>()
        var left = size
        if (flags and 0x40 != 0) {
            val ext = s.readInt().let { if (major == 4) syncsafe(it) else it }
            val skip = if (major == 4) ext - 4 else ext
            s.skipFully(skip); left -= 4 + skip
        }
        while (left > 10) {
            val id = ByteArray(4).also { s.readFully(it) }
            if (id[0].toInt() == 0) break
            val raw = s.readInt()
            val len = if (major == 4) syncsafe(raw) else raw
            s.readUnsignedShort()
            left -= 10
            if (len < 0 || len > left) break
            if (String(id, Charsets.US_ASCII) == "TXXX" && len < 4096) {
                val body = ByteArray(len).also { s.readFully(it) }
                txxx(body)?.let { (k, v) -> map[k] = v }
            } else s.skipFully(len)
            left -= len
        }
        return fromMap(map)
    }

    private fun txxx(body: ByteArray): Pair<String, String>? {
        if (body.isEmpty()) return null
        val cs = when (body[0].toInt()) {
            0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8
        }
        val wide = body[0].toInt() == 1 || body[0].toInt() == 2
        var split = -1
        var i = 1
        while (i < body.size) {
            if (wide) {
                if (i + 1 < body.size && body[i].toInt() == 0 && body[i + 1].toInt() == 0 && (i - 1) % 2 == 0) { split = i; break }
                i += 2
            } else {
                if (body[i].toInt() == 0) { split = i; break }
                i++
            }
        }
        if (split < 0) return null
        val key = String(body, 1, split - 1, cs).trim()
        val vStart = split + if (wide) 2 else 1
        val value = String(body, vStart, body.size - vStart, cs).trim('\u0000', ' ')
        return key.uppercase() to value
    }

    private fun flac(s: DataInputStream): RgTags? {
        repeat(64) {
            val h = s.readInt()
            val last = h ushr 31 == 1
            val type = (h ushr 24) and 0x7f
            val len = h and 0xffffff
            if (type == 4 && len < 1 shl 20) {
                val body = ByteArray(len).also { s.readFully(it) }
                return fromMap(vorbisComments(body, 0))
            }
            s.skipFully(len)
            if (last) return null
        }
        return null
    }

    private fun ogg(s: DataInputStream): RgTags? {
        val buf = ByteArray(OGG_SCAN)
        var n = 0
        while (n < buf.size) { val r = s.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
        val vorbis = indexOf(buf, n, byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        if (vorbis >= 0) return fromMap(vorbisComments(buf.copyOf(n), vorbis + 7))
        val opus = indexOf(buf, n, "OpusTags".toByteArray(Charsets.US_ASCII))
        if (opus < 0) return null
        val map = vorbisComments(buf.copyOf(n), opus + 8)
        val tg = map["R128_TRACK_GAIN"]?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
        val ag = map["R128_ALBUM_GAIN"]?.trim()?.toIntOrNull()?.let { it / 256f + 5f }
        return fromMap(map)?.let { it.copy(trackGain = it.trackGain ?: tg, albumGain = it.albumGain ?: ag) }
            ?: RgTags(tg, ag, null, null)
    }

    /** Vorbis comment list at [at]: vendor, count, then "KEY=value" entries, all little-endian lengths. */
    private fun vorbisComments(b: ByteArray, at: Int): Map<String, String> {
        val map = HashMap<String, String>()
        var p = at
        fun le(): Int {
            if (p + 4 > b.size) throw EOFException()
            val v = (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
                ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
            p += 4
            return v
        }
        runCatching {
            val vendor = le(); if (vendor < 0 || vendor > b.size) return map
            p += vendor
            val count = le().coerceAtMost(1000)
            repeat(count) {
                val len = le()
                if (len < 0 || p + len > b.size) return map
                val entry = String(b, p, len, Charsets.UTF_8)
                p += len
                val eq = entry.indexOf('=')
                if (eq > 0) map[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1)
            }
        }
        return map
    }

    private fun fromMap(m: Map<String, String>): RgTags? {
        val tags = RgTags(
            trackGain = gain(m["REPLAYGAIN_TRACK_GAIN"]),
            albumGain = gain(m["REPLAYGAIN_ALBUM_GAIN"]),
            trackPeak = peak(m["REPLAYGAIN_TRACK_PEAK"]),
            albumPeak = peak(m["REPLAYGAIN_ALBUM_PEAK"]),
        )
        return tags.takeIf { it.any || it.trackPeak != null }
    }

    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    private fun gain(v: String?): Float? =
        v?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }?.takeIf { it in -60f..30f }

    private fun peak(v: String?): Float? =
        v?.let { NUMBER.find(it)?.value?.replace(',', '.')?.toFloatOrNull() }?.takeIf { it > 0f && it < 10f }

    private fun syncsafe(v: Int) = (v and 0x7f) or ((v shr 8 and 0x7f) shl 7) or ((v shr 16 and 0x7f) shl 14) or ((v shr 24 and 0x7f) shl 21)

    private fun DataInputStream.skipFully(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val k = skip(left)
            if (k <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= k
        }
    }

    private fun indexOf(b: ByteArray, n: Int, pat: ByteArray): Int {
        outer@ for (i in 0..n - pat.size) {
            for (j in pat.indices) if (b[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private const val ASSET = "file:///android_asset/"
}
