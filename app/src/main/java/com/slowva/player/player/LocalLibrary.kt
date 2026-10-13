package com.slowva.player.player

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.slowva.player.api.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Folders and files picked through the system picker. Tag reads are cached. */
object LocalLibrary {

    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val scanning = MutableStateFlow(false)

    private const val PREFS = "slowva-local"
    private val AUDIO_EXT = setOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma", "mka", "webm")

    fun isLocal(track: Track?) = track?.id?.startsWith("local:") == true

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sources(ctx: Context, key: String) =
        prefs(ctx).getStringSet(key, emptySet())!!.toMutableSet()

    fun addFolder(ctx: Context, uri: Uri) = remember(ctx, "folders", uri)
    fun addFiles(ctx: Context, uris: List<Uri>) = uris.forEach { remember(ctx, "files", it) }

    private fun remember(ctx: Context, key: String, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs(ctx).edit().putStringSet(key, sources(ctx, key).apply { add(uri.toString()) }).apply()
    }

    fun clear(ctx: Context) {
        (sources(ctx, "folders") + sources(ctx, "files")).forEach { s ->
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs(ctx).edit().clear().apply()
        File(ctx.filesDir, "local-index.json").delete()
        tracks.value = emptyList()
    }

    suspend fun scan(ctx: Context) = withContext(Dispatchers.IO) {
        scanning.value = true
        try {
            val found = LinkedHashMap<String, DocumentFile>()
            for (s in sources(ctx, "folders")) {
                DocumentFile.fromTreeUri(ctx, Uri.parse(s))?.let { walk(it, found, 0) }
            }
            for (s in sources(ctx, "files")) {
                DocumentFile.fromSingleUri(ctx, Uri.parse(s))?.takeIf { it.exists() }?.let { found[it.uri.toString()] = it }
            }
            val indexFile = File(ctx.filesDir, "local-index.json")
            val cache = runCatching { JSONObject(indexFile.readText()) }.getOrElse { JSONObject() }
            val fresh = JSONObject()
            val out = ArrayList<Track>(found.size)
            for ((key, doc) in found) {
                val stamp = doc.lastModified()
                val cached = cache.optJSONObject(key)
                val meta = if (cached != null && cached.optLong("mtime") == stamp) cached
                else readTags(ctx, doc, stamp) ?: continue
                fresh.put(key, meta)
                out += Track(
                    id = "local:" + meta.getString("id"),
                    uri = key,
                    name = meta.getString("title"),
                    artist = meta.optString("artist"),
                    album = meta.optString("album"),
                    durationMs = meta.optInt("duration"),
                    art = meta.optString("art").ifBlank { null },
                )
            }
            runCatching { indexFile.writeText(fresh.toString()) }
            tracks.value = out.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.name.lowercase() }))
        } finally {
            scanning.value = false
        }
    }

    private fun walk(dir: DocumentFile, out: MutableMap<String, DocumentFile>, depth: Int) {
        if (depth > 12 || out.size >= 5000) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) walk(f, out, depth + 1)
            else if (isAudio(f)) out[f.uri.toString()] = f
        }
    }

    private fun isAudio(f: DocumentFile): Boolean =
        f.type?.startsWith("audio/") == true ||
            f.name?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXT

    private fun readTags(ctx: Context, doc: DocumentFile, stamp: Long): JSONObject? {
        val id = sha1(doc.uri.toString())
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, doc.uri)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: doc.name?.substringBeforeLast('.') ?: "Unknown"
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "Unknown artist"
            val album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            var art = ""
            r.embeddedPicture?.let { bytes ->
                val dir = File(ctx.filesDir, "local-art").apply { mkdirs() }
                val f = File(dir, "$id.img")
                runCatching { f.writeBytes(bytes); art = Uri.fromFile(f).toString() }
            }
            JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album)
                .put("duration", duration).put("art", art).put("mtime", stamp)
        } catch (e: Exception) {
            Log.w("slowva-local", "skipping ${doc.name}: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}
