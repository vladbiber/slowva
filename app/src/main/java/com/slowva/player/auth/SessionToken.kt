package com.slowva.player.auth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Web API tokens minted through the librespot session, the way official
 * clients get theirs — same trick spotc uses.
 *
 * It matters because the PKCE flow signs in as Spotify's *desktop* client id,
 * which every librespot-based tool on earth shares: its Web API quota is
 * permanently exhausted and calls come back 429. A session token is tied to the
 * account instead, so the rate limit is ours alone.
 *
 * Needs credentials cached by librespot, so it only works after the first
 * successful local playback sign-in.
 */
object SessionToken {

    private const val SCOPES =
        "user-read-playback-state,user-modify-playback-state," +
            "user-read-currently-playing,playlist-read-private," +
            "playlist-read-collaborative,user-library-read"

    fun credentialsFile(ctx: Context) = File(ctx.cacheDir, "librespot/credentials.json")

    fun available(ctx: Context): Boolean {
        val file = credentialsFile(ctx)
        return file.isFile && file.length() > 16L
    }

    /** Why the last mint failed, for the diagnostics card. */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun mint(ctx: Context): Pair<String, Long>? = mintDetailed(ctx).getOrNull()

    /** Returns the token and its lifetime in seconds, or the helper's complaint. */
    suspend fun mintDetailed(ctx: Context): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        fun fail(why: String): Result<Pair<String, Long>> {
            lastError = why
            Log.w(TAG, why)
            return Result.failure(IllegalStateException(why))
        }
        if (!available(ctx)) return@withContext fail("no librespot credentials cached yet")
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libslowvatoken.so")
        if (!bin.exists()) return@withContext fail("token helper missing from the APK")
        bin.setExecutable(true, false)
        val cache = File(ctx.cacheDir, "librespot").absolutePath
        val process = try {
            ProcessBuilder(bin.absolutePath, cache, SCOPES)
                .directory(ctx.cacheDir)
                .start()
        } catch (e: Exception) {
            return@withContext fail("token helper will not start: ${e.message}")
        }
        try {
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroy()
                return@withContext fail("token helper timed out")
            }
            if (process.exitValue() != 0 || out.isBlank()) {
                return@withContext fail("token helper exit ${process.exitValue()}: ${err.take(200)}")
            }
            val parts = out.split(" ")
            val token = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?: return@withContext fail("token helper printed nothing usable")
            lastError = null
            Result.success(token to (parts.getOrNull(1)?.toLongOrNull() ?: 3600L))
        } catch (e: Exception) {
            fail("token helper: ${e.message}")
        } finally {
            process.destroy()
        }
    }

    private const val TAG = "slowva"
}
