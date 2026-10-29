package com.slowva.player.spotify

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs the librespot helpers shipped as lib*.so in nativeLibraryDir; their stderr goes to [log]. */
object SpotifyNative {
    const val DECODE = "libslowvadecode.so"
    const val TOKEN = "libslowvatoken.so"
    private const val IPV4 = "libslowvaipv4.so"
    private const val LOG_MAX = 256 * 1024L

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "librespot").apply { mkdirs() }
    fun credentials(ctx: Context) = File(cacheDir(ctx), "credentials.json")
    fun hasCredentials(ctx: Context) = credentials(ctx).let { it.isFile && it.length() > 16 }
    fun log(ctx: Context) = File(ctx.cacheDir, "spotify.log")

    /** Starts [bin] with [args]; stdout is a pipe unless [output] is given. */
    fun start(ctx: Context, bin: String, args: List<String>, env: Map<String, String> = emptyMap(), output: File? = null): Process {
        val dir = ctx.applicationInfo.nativeLibraryDir
        val exe = File(dir, bin)
        if (!exe.isFile) throw IOException("$bin is missing from the app")
        trim(ctx)
        return ProcessBuilder(listOf(exe.absolutePath) + args).apply {
            // some routers advertise IPv6 routes that go nowhere
            File(dir, IPV4).takeIf { it.isFile }?.let { environment()["LD_PRELOAD"] = it.absolutePath }
            // librespot downloads into temp_dir(), which is /data/local/tmp on Android unless TMPDIR says otherwise
            environment()["TMPDIR"] = File(ctx.cacheDir, "tmp").apply { mkdirs() }.absolutePath
            environment().putAll(env)
            directory(ctx.cacheDir)
            redirectError(ProcessBuilder.Redirect.appendTo(log(ctx)))
            if (output != null) redirectOutput(output)
        }.start()
    }

    /** A line of our own in the log, so a run can be told from the next. */
    fun note(ctx: Context, text: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching { log(ctx).appendText("[$time] slowva: $text\n") }
    }

    fun tail(ctx: Context, lines: Int = 40): String =
        runCatching { log(ctx).readLines().takeLast(lines).joinToString("\n") }.getOrNull()?.ifBlank { null } ?: "(empty)"

    fun clearLog(ctx: Context) { log(ctx).delete() }

    private fun trim(ctx: Context) {
        val f = log(ctx)
        if (f.length() < LOG_MAX) return
        runCatching { val keep = f.readLines().takeLast(400); f.writeText(keep.joinToString("\n", postfix = "\n")) }
    }

    /** What a libslowvadecode exit code means. */
    fun decodeError(code: Int): String = when (code) {
        3 -> "Not signed in to Spotify"
        4 -> "Not a playable Spotify track"
        5 -> "Couldn't connect to Spotify"
        6 -> "This song isn't available on Spotify"
        7 -> "Spotify stopped sending the song"
        else -> "Spotify decoder failed (exit $code)"
    }

    /** What a libslowvatoken exit code means. */
    fun tokenError(code: Int): String = when (code) {
        3 -> "Not signed in to Spotify"
        4 -> "Spotify refused the sign-in or couldn't be reached"
        5 -> "Spotify gave no Web API token"
        else -> "Spotify token helper failed (exit $code)"
    }
}
