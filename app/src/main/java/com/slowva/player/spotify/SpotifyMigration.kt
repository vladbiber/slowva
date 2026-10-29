package com.slowva.player.spotify

import android.content.Context

/** Carries a slowva 2.x sign-in over to 3.0; credentials.json already sits at the same path. */
object SpotifyMigration {
    /** 2.x kept everything in this file, see auth/Auth.kt on the 2.x branch */
    const val V2_PREFS = "slowva-auth"
    const val DONE = "migrated_2x"

    /** The 3.0 values for a 2.x prefs map; empty when 2.x left nothing worth keeping. */
    fun fromV2(old: Map<String, *>): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        (old["api_client_id"] as? String)?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9]{32}")) }?.let { id ->
            out["client_id"] = id
            // the personal tokens only mean something with the Client ID they came from
            (old["api_refresh"] as? String)?.takeIf { it.isNotBlank() }?.let { out["api_refresh"] = it }
            val access = (old["api_access"] as? String)?.takeIf { it.isNotBlank() }
            val expires = old["api_expires_at"] as? Long
            if (access != null && expires != null && out.containsKey("api_refresh")) {
                out["api_access"] = access
                out["api_expires"] = expires
            }
        }
        // 2.x's own "refresh" is the desktop client's, the one librespot can sign in again with
        (old["refresh"] as? String)?.takeIf { it.isNotBlank() }?.let { out["desktop_refresh"] = it }
        return out
    }

    /** Runs once per install; leaves the 2.x file alone so a downgrade still finds it. */
    fun run(ctx: Context) {
        val prefs = ctx.getSharedPreferences(Spotify.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        val values = fromV2(ctx.getSharedPreferences(V2_PREFS, Context.MODE_PRIVATE).all)
        val e = prefs.edit().putBoolean(DONE, true)
        values.forEach { (k, v) ->
            if (prefs.contains(k)) return@forEach
            when (v) {
                is String -> e.putString(k, v)
                is Long -> e.putLong(k, v)
            }
        }
        e.commit()
    }
}
